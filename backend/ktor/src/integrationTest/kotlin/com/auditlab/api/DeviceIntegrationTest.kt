package com.auditlab.api

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeviceIntegrationTest : ApiTestSupport() {
    @BeforeAll
    fun setup() {
        Bootstrap.user(owner, "device_admin", password, "admin")
        Bootstrap.user(owner, "device_auditor", password, "auditor")
    }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun auditActions(): List<String> = inspect { connection ->
        connection.prepareStatement("SELECT action FROM auditlab.audit_log").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private fun enrollmentBody(consentId: UUID = UUID.randomUUID(), acknowledged: Boolean = true,
                               acceptedAt: Instant = Instant.now().minusSeconds(1)): Map<String, Any?> = mapOf(
        "consentAcknowledged" to acknowledged,
        "consent" to mapOf(
            "consentId" to consentId.toString(),
            "policyVersion" to "policy-1",
            "acceptedAt" to acceptedAt.toString(),
        ),
    )

    @Test
    fun `enrollment hashes the code and token and consumes the code once`() = http { _ ->
        val admin = login("device_admin")
        val created = createCode(admin)
        val code = created["code"].textValue()
        assertTrue(code.length in 32..4096)
        assertEquals(600, created["expiresIn"].intValue())
        assertNotNull(Instant.parse(created["expiresAt"].textValue()))

        inspect { connection ->
            connection.prepareStatement("SELECT code_hash FROM auditlab.enrollment_codes WHERE code_hash=?").use {
                it.setString(1, sha256(code))
                it.executeQuery().use { row -> assertTrue(row.next()) }
            }
        }

        val device = enrollWithCode(code)
        val token = device["deviceToken"].textValue()
        assertTrue(token.length in 32..4096)
        assertNotEquals(code, token)
        assertEquals("active", device["status"].textValue())
        assertEquals("policy-1", device["consentContext"]["policyVersion"].textValue())
        inspect { connection ->
            connection.prepareStatement("SELECT token_hash,status FROM auditlab.devices WHERE id=?").use {
                it.setObject(1, UUID.fromString(device["deviceId"].textValue()))
                it.executeQuery().use { row ->
                    assertTrue(row.next())
                    assertEquals(sha256(token), row.getString(1))
                    assertFalse(row.getString(1).contains(token))
                    assertEquals("active", row.getString(2))
                }
            }
            connection.prepareStatement("SELECT used_at,consumed_device_id FROM auditlab.enrollment_codes WHERE code_hash=?").use {
                it.setString(1, sha256(code))
                it.executeQuery().use { row ->
                    assertTrue(row.next())
                    assertNotNull(row.getTimestamp(1))
                    assertNotNull(row.getObject(2))
                }
            }
        }
        // The same code cannot enroll a second device.
        problem(enrollResponse(code, enrollmentBody()), 401, "ENROLLMENT_CODE_INVALID")
        assertTrue(auditActions().containsAll(listOf("enrollment_code_created", "device_enrolled")))
    }

    @Test
    fun `enrollment requires explicit consent and rejects reused consent identities`() = http { _ ->
        val admin = login("device_admin")
        val code = createCode(admin)["code"].textValue()
        problem(enrollResponse(code, mapOf("consentAcknowledged" to false, "consent" to enrollmentBody()["consent"])),
            400, "CONSENT_REQUIRED")
        problem(enrollResponse(code, mapOf("consentAcknowledged" to true)), 400, "INVALID_REQUEST")
        problem(enrollResponse(code, mapOf("consentAcknowledged" to true, "consent" to enrollmentBody()["consent"],
            "extra" to "x")), 400, "INVALID_REQUEST")
        problem(enrollResponse("too-short", enrollmentBody()), 401, "ENROLLMENT_CODE_INVALID")

        val consentId = UUID.randomUUID()
        enrollWithCode(code, consentId = consentId)
        val second = createCode(admin)["code"].textValue()
        problem(enrollResponse(second, enrollmentBody(consentId)), 409, "CONSENT_MISMATCH")
        // The rejected enrollment must not leave a device behind.
        inspect { connection ->
            connection.prepareStatement("SELECT count(*) FROM auditlab.consents WHERE id=?").use {
                it.setObject(1, consentId)
                it.executeQuery().use { row -> row.next(); assertEquals(1, row.getInt(1)) }
            }
        }
    }

    @Test
    fun `one enrollment code cannot be consumed twice concurrently`() = http { _ ->
        val admin = login("device_admin")
        val code = createCode(admin)["code"].textValue()
        val responses = coroutineScope {
            List(2) { async { enrollResponse(code, enrollmentBody()) } }.awaitAll()
        }
        assertEquals(listOf(201, 401), responses.map { it.status.value }.sorted())
        inspect { connection ->
            connection.prepareStatement("""SELECT count(*) FROM auditlab.devices d
                JOIN auditlab.enrollment_codes e ON e.id=d.enrollment_code_id WHERE e.code_hash=?""").use {
                it.setString(1, sha256(code))
                it.executeQuery().use { row -> row.next(); assertEquals(1, row.getInt(1)) }
            }
            connection.prepareStatement("SELECT used_at FROM auditlab.enrollment_codes WHERE code_hash=?").use {
                it.setString(1, sha256(code))
                it.executeQuery().use { row -> row.next(); assertNotNull(row.getTimestamp(1)) }
            }
        }
    }

    @Test
    fun `expired codes are rejected and remain unconsumed`() = http { _ ->
        val admin = login("device_admin")
        val code = createCode(admin)["code"].textValue()
        sql("UPDATE auditlab.enrollment_codes SET expires_at=created_at + interval '1 millisecond' WHERE code_hash=?",
            sha256(code))
        problem(enrollResponse(code, enrollmentBody()), 401, "ENROLLMENT_CODE_INVALID")
    }

    @Test
    fun `device list is paginated, filterable and scoped to user roles`() = http { _ ->
        val admin = login("device_admin")
        val auditor = login("device_auditor")
        val first = enroll(admin)
        val second = enroll(admin)
        val third = enroll(admin)

        val page = json(client.get("/v1/devices?limit=2") { bearerAuth(admin) })
        assertEquals(2, page["items"].size())
        assertNotNull(page["nextCursor"].textValue())
        val rest = json(client.get("/v1/devices?limit=2&cursor=${page["nextCursor"].textValue()}") { bearerAuth(admin) })
        assertTrue(rest["items"].size() >= 1)
        val seen = page["items"].map { it["deviceId"].textValue() } + rest["items"].map { it["deviceId"].textValue() }
        assertTrue(seen.containsAll(listOf(first, second, third).map { it["deviceId"].textValue() }))
        assertEquals(seen.size, seen.toSet().size)
        // A cursor is bound to its filters and actor.
        problem(client.get("/v1/devices?limit=2&status=revoked&cursor=${page["nextCursor"].textValue()}") { bearerAuth(admin) },
            400, "INVALID_CURSOR")
        problem(client.get("/v1/devices?limit=2&cursor=${page["nextCursor"].textValue()}") { bearerAuth(auditor) },
            400, "INVALID_CURSOR")

        val auditorPage = client.get("/v1/devices?limit=1") { bearerAuth(auditor) }
        assertEquals(HttpStatusCode.OK, auditorPage.status)

        val detail = json(client.get("/v1/devices/${second["deviceId"].textValue()}") { bearerAuth(auditor) })
        assertEquals(second["deviceId"].textValue(), detail["deviceId"].textValue())
        assertEquals("active", detail["status"].textValue())
        assertNull(detail["revokedAt"].textValue())
        assertNotNull(detail["enrolledAt"].textValue())
        assertEquals("0.1.0-test", detail["appVersion"].textValue())

        problem(client.get("/v1/devices?limit=0") { bearerAuth(admin) }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/devices?status=unknown") { bearerAuth(admin) }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/devices?limit=1&limit=2") { bearerAuth(admin) }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/devices/${UUID.randomUUID()}") { bearerAuth(admin) }, 404, "RESOURCE_NOT_FOUND")
        problem(client.get("/v1/devices/${UUID.randomUUID()}") { bearerAuth(first["deviceToken"].textValue()) },
            401, "UNAUTHENTICATED")
    }

    @Test
    fun `remote revocation is idempotent and blocks ingestion immediately`() = http { _ ->
        val admin = login("device_admin")
        val auditor = login("device_auditor")
        val device = enroll(admin)
        val deviceId = device["deviceId"].textValue()
        val token = device["deviceToken"].textValue()

        problem(client.post("/v1/devices/$deviceId/revoke") { bearerAuth(auditor) }, 403, "FORBIDDEN")
        val revoked = client.post("/v1/devices/$deviceId/revoke") { bearerAuth(admin) }
        assertEquals(HttpStatusCode.NoContent, revoked.status)
        assertEquals("", revoked.bodyAsText())

        val detail = json(client.get("/v1/devices/$deviceId") { bearerAuth(admin) })
        assertEquals("revoked", detail["status"].textValue())
        assertNotNull(detail["revokedAt"].textValue())
        val filtered = json(client.get("/v1/devices?status=revoked&limit=100") { bearerAuth(admin) })
        assertTrue(filtered["items"].any { it["deviceId"].textValue() == deviceId })

        problem(sendEvents(token, event(device)), 401, "DEVICE_REVOKED")
        problem(client.post("/v1/devices/$deviceId/consent-revoke") { bearerAuth(token) }, 401, "DEVICE_REVOKED")
        // Repeating a revocation is harmless.
        assertEquals(HttpStatusCode.NoContent, client.post("/v1/devices/$deviceId/revoke") { bearerAuth(admin) }.status)
        assertTrue(auditActions().contains("device_revoked"))
    }

    @Test
    fun `device consent revocation only affects its own identity`() = http { _ ->
        val admin = login("device_admin")
        val device = enroll(admin)
        val other = enroll(admin)
        val token = device["deviceToken"].textValue()
        problem(client.post("/v1/devices/${other["deviceId"].textValue()}/consent-revoke") { bearerAuth(token) },
            403, "DEVICE_SCOPE_MISMATCH")
        assertEquals(HttpStatusCode.NoContent,
            client.post("/v1/devices/${device["deviceId"].textValue()}/consent-revoke") { bearerAuth(token) }.status)
        inspect { connection ->
            connection.prepareStatement("SELECT revoked_at FROM auditlab.consents WHERE device_id=?").use {
                it.setObject(1, UUID.fromString(device["deviceId"].textValue()))
                it.executeQuery().use { row -> row.next(); assertNotNull(row.getTimestamp(1)) }
            }
        }
        problem(sendEvents(token, event(device)), 401, "DEVICE_REVOKED")
        val otherToken = other["deviceToken"].textValue()
        assertEquals(HttpStatusCode.Accepted, sendEvents(otherToken, event(other)).status)
    }

    @Test
    fun `only admins create enrollment codes and device credentials cannot use user routes`() = http { _ ->
        val auditor = login("device_auditor")
        problem(client.post("/v1/devices/enrollment-code") { bearerAuth(auditor) }, 403, "FORBIDDEN")
        val admin = login("device_admin")
        val device = enroll(admin)
        problem(client.get("/v1/events") { bearerAuth(device["deviceToken"].textValue()) }, 401, "UNAUTHENTICATED")
        problem(client.post("/v1/devices/enrollment-code?code=leak") { bearerAuth(admin) }, 400, "INVALID_REQUEST")
    }
}
