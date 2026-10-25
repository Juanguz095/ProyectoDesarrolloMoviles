package com.auditlab.api

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventIntegrationTest : ApiTestSupport() {
    @BeforeAll
    fun setup() {
        Bootstrap.user(owner, "event_admin", password, "admin")
        Bootstrap.user(owner, "event_auditor", password, "auditor")
    }

    private fun countEvents(deviceId: String? = null): Int = inspect { connection ->
        val sql = if (deviceId == null) "SELECT count(*) FROM auditlab.events" else "SELECT count(*) FROM auditlab.events WHERE device_id=?"
        connection.prepareStatement(sql).use {
            if (deviceId != null) it.setObject(1, UUID.fromString(deviceId))
            it.executeQuery().use { row -> row.next(); row.getInt(1) }
        }
    }

    private fun auditRows(action: String): Int = inspect { connection ->
        connection.prepareStatement("SELECT count(*) FROM auditlab.audit_log WHERE action=?").use {
            it.setString(1, action)
            it.executeQuery().use { row -> row.next(); row.getInt(1) }
        }
    }

    @Test
    fun `batch is accepted, stored normalized and retried idempotently`() = http { _ ->
        val admin = login("event_admin")
        val device = enroll(admin)
        val first = event(device, metadata = mapOf("appEventCode" to "agent_started", "networkState" to "online",
            "batteryPercent" to 55))
        val second = event(device, category = "sync", direction = "outbound", timestamp = Instant.now().minusSeconds(1),
            metadata = mapOf("appEventCode" to "sync_succeeded"))
        val response = sendEvents(device["deviceToken"].textValue(), first, second)
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("no-store", response.headers["Cache-Control"])
        val result = json(response)
        assertEquals(2, result["accepted"].intValue())
        assertEquals(0, result["duplicates"].intValue())

        val retry = json(sendEvents(device["deviceToken"].textValue(), first, second))
        assertEquals(0, retry["accepted"].intValue())
        assertEquals(2, retry["duplicates"].intValue())
        assertEquals(2, countEvents(device["deviceId"].textValue()))

        inspect { connection ->
            connection.prepareStatement("SELECT payload_hash,is_simulated,received_at,metadata FROM auditlab.events WHERE device_id=? ORDER BY event_id").use {
                it.setObject(1, UUID.fromString(device["deviceId"].textValue()))
                it.executeQuery().use { rows ->
                    while (rows.next()) {
                        assertTrue(Regex("[0-9a-f]{64}").matches(rows.getString(1)))
                        assertTrue(rows.getBoolean(2))
                        assertNotNull(rows.getTimestamp(3))
                        assertNotNull(rows.getString(4))
                    }
                }
            }
        }
        val page = json(client.get("/v1/events?device=${device["deviceId"].textValue()}") { bearerAuth(admin) })
        assertEquals(2, page["items"].size())
        assertEquals("app_lifecycle", page["items"].first()["category"].textValue())
        assertEquals("sync", page["items"].last()["category"].textValue())
        val item = page["items"].first()
        assertEquals(device["deviceId"].textValue(), item["sourceDevice"].textValue())
        assertTrue(item["isSimulated"].booleanValue())
        assertNotNull(Instant.parse(item["receivedAt"].textValue()))
        assertTrue(auditRows("events_received") >= 2)

        val detail = json(client.get("/v1/devices/${device["deviceId"].textValue()}/events/${item["eventId"].textValue()}") {
            bearerAuth(admin)
        })
        assertEquals(item["eventId"].textValue(), detail["eventId"].textValue())
    }

    @Test
    fun `invalid batches and conflicts are rejected without partial writes`() = http { _ ->
        val admin = login("event_admin")
        val device = enroll(admin)
        val other = enroll(admin)
        val token = device["deviceToken"].textValue()

        // Unknown metadata key.
        problem(sendEvents(token, event(device, metadata = mapOf("smsBody" to "forbidden"))), 400, "INVALID_REQUEST")
        // Non-simulated event.
        val simulatedFalse = event(device).toMutableMap().also { it["isSimulated"] = false }
        problem(sendEvents(token, simulatedFalse), 400, "INVALID_REQUEST")
        // Scope mismatch.
        problem(sendEvents(token, event(other)), 403, "DEVICE_SCOPE_MISMATCH")
        // Consent from another device.
        problem(sendEvents(token, event(device, consent = consentOf(other))), 409, "CONSENT_MISMATCH")
        // Out of range values and clocks.
        problem(sendEvents(token, event(device, metadata = mapOf("batteryPercent" to 101))), 400, "INVALID_REQUEST")
        problem(sendEvents(token, event(device, metadata = mapOf("appEventCode" to "sms_received"))), 400, "INVALID_REQUEST")
        problem(sendEvents(token, event(device, category = "chats")), 400, "INVALID_REQUEST")
        problem(sendEvents(token, event(device, timestamp = Instant.now().plusSeconds(600))), 400, "INVALID_REQUEST")
        problem(sendEvents(token, event(device, timestamp = Instant.parse(device["enrolledAt"].textValue()).minusSeconds(5))),
            400, "INVALID_REQUEST")
        assertEquals(0, countEvents(device["deviceId"].textValue()))

        // One invalid member rejects the whole batch.
        val valid = event(device)
        problem(sendEvents(token, valid, event(device, metadata = mapOf("batteryPercent" to -1))), 400, "INVALID_REQUEST")
        assertEquals(0, countEvents(device["deviceId"].textValue()))

        // Reusing an eventId with different content conflicts and keeps the stored row.
        assertEquals(HttpStatusCode.Accepted, sendEvents(token, valid).status)
        val changed = valid.toMutableMap().also { it["direction"] = "inbound" }
        problem(sendEvents(token, changed), 409, "EVENT_ID_CONFLICT")
        assertEquals(1, countEvents(device["deviceId"].textValue()))

        // A retry plus a new event is counted per member.
        val added = event(device, category = "sync")
        val mixed = json(sendEvents(token, valid, added))
        assertEquals(1, mixed["accepted"].intValue())
        assertEquals(1, mixed["duplicates"].intValue())
        assertEquals(2, countEvents(device["deviceId"].textValue()))

        // Envelope limits.
        problem(sendEvents(token), 400, "INVALID_REQUEST")
        problem(sendEvents(token, *Array(101) { event(device) }), 400, "INVALID_REQUEST")
    }

    @Test
    fun `filters and cursors are stable and bound to actor and filters`() = http { _ ->
        val admin = login("event_admin")
        val auditor = login("event_auditor")
        val device = enroll(admin)
        val other = enroll(admin)
        val token = device["deviceToken"].textValue()
        val start = Instant.now().minusMillis(500)
        val events = (0 until 5).map { index ->
            event(device, category = if (index % 2 == 0) "app_lifecycle" else "sync",
                direction = if (index % 2 == 0) "occurred" else "inbound",
                timestamp = start.plusMillis(index * 100L),
                metadata = if (index % 2 == 0) mapOf("appEventCode" to "agent_started") else mapOf("appEventCode" to "sync_succeeded"))
        }
        assertEquals(HttpStatusCode.Accepted, sendEvents(token, *events.toTypedArray()).status)
        assertEquals(HttpStatusCode.Accepted, sendEvents(other["deviceToken"].textValue(), event(other, category = "sync")).status)

        val byCategory = json(client.get("/v1/events?category=sync&device=${device["deviceId"].textValue()}") { bearerAuth(admin) })
        assertEquals(2, byCategory["items"].size())
        assertTrue(byCategory["items"].all { it["category"].textValue() == "sync" })
        val byCode = json(client.get("/v1/events?q=sync_succeeded&device=${device["deviceId"].textValue()}") { bearerAuth(admin) })
        assertEquals(2, byCode["items"].size())
        val byWindow = json(client.get("/v1/events?from=${start.minusSeconds(1)}&to=${start.plusSeconds(1)}&device=${device["deviceId"].textValue()}") {
            bearerAuth(admin)
        })
        assertEquals(5, byWindow["items"].size())

        val first = json(client.get("/v1/events?limit=2&device=${device["deviceId"].textValue()}") { bearerAuth(admin) })
        assertEquals(2, first["items"].size())
        val cursor = first["nextCursor"].textValue()
        assertNotNull(cursor)
        val second = json(client.get("/v1/events?limit=2&device=${device["deviceId"].textValue()}&cursor=$cursor") { bearerAuth(admin) })
        assertEquals(2, second["items"].size())
        val third = json(client.get("/v1/events?limit=2&device=${device["deviceId"].textValue()}&cursor=${second["nextCursor"].textValue()}") {
            bearerAuth(admin)
        })
        assertEquals(1, third["items"].size())
        assertEquals(null, third["nextCursor"].textValue())
        val ids = first["items"].map { it["eventId"].textValue() } + second["items"].map { it["eventId"].textValue() } +
            third["items"].map { it["eventId"].textValue() }
        assertEquals(5, ids.toSet().size)

        problem(client.get("/v1/events?limit=2&device=${device["deviceId"].textValue()}&category=sync&cursor=$cursor") {
            bearerAuth(admin)
        }, 400, "INVALID_CURSOR")
        problem(client.get("/v1/events?limit=2&device=${device["deviceId"].textValue()}&cursor=$cursor") { bearerAuth(auditor) },
            400, "INVALID_CURSOR")
        problem(client.get("/v1/events?cursor=not-a-cursor") { bearerAuth(admin) }, 400, "INVALID_CURSOR")
        problem(client.get("/v1/events?from=${start}&to=${start}") { bearerAuth(admin) }, 400, "INVALID_RANGE")
        problem(client.get("/v1/events?category=sync&category=app_lifecycle") { bearerAuth(admin) }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/events?q=bad%20value") { bearerAuth(admin) }, 400, "INVALID_REQUEST")
    }

    @Test
    fun `csv export follows filters, quotes cells and refuses oversized sets`() = http { _ ->
        val admin = login("event_admin")
        val auditor = login("event_auditor")
        val device = enroll(admin)
        val token = device["deviceToken"].textValue()
        assertEquals(HttpStatusCode.Accepted, sendEvents(token,
            event(device, metadata = mapOf("appEventCode" to "agent_started", "batteryPercent" to 12)),
            event(device, category = "sync", direction = "inbound",
                metadata = mapOf("appEventCode" to "sync_failed"))).status)

        val response = client.get("/v1/events/export.csv?device=${device["deviceId"].textValue()}") { bearerAuth(auditor) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Type"].orEmpty().startsWith("text/csv"))
        assertTrue(response.headers["Content-Disposition"].orEmpty().contains("auditlab-events.csv"))
        val lines = response.bodyAsText().split("\r\n").filter { it.isNotEmpty() }
        assertEquals(3, lines.size)
        assertEquals("eventId,timestamp,category,isSimulated,direction,sourceDevice,consentId,policyVersion,metadata", lines[0])
        assertTrue(lines.drop(1).all { it.split(',').size >= 9 })
        assertTrue(lines.drop(1).any { it.contains("\"{\"\"appEventCode\"\"") })
        assertTrue(auditRows("report_exported") >= 1)

        // Force the export above the documented limit; the response must not contain a partial CSV.
        sql("""INSERT INTO auditlab.events(device_id,event_id,consent_id,occurred_at,received_at,category,direction,is_simulated,metadata,payload_hash)
            SELECT ?,gen_random_uuid(),?,now() - (g || ' seconds')::interval,now(),'sync','inbound',true,'{}',repeat('a',64)
            FROM generate_series(1,10001) g""",
            UUID.fromString(device["deviceId"].textValue()), UUID.fromString(device["consentContext"]["consentId"].textValue()))
        val tooLarge = client.get("/v1/events/export.csv?device=${device["deviceId"].textValue()}") { bearerAuth(admin) }
        val tooLargeBody = tooLarge.bodyAsText()
        assertEquals(HttpStatusCode.Conflict, tooLarge.status)
        assertEquals("no-store", tooLarge.headers["Cache-Control"])
        assertTrue(tooLarge.headers["Content-Type"].orEmpty().startsWith("application/problem+json"))
        assertEquals("EXPORT_LIMIT_EXCEEDED", mapper.readTree(tooLargeBody)["code"].textValue())
        assertFalse(tooLargeBody.contains("eventId,timestamp"))
        sql("DELETE FROM auditlab.events WHERE device_id=?", UUID.fromString(device["deviceId"].textValue()))
        inspect { connection ->
            connection.prepareStatement("SELECT result FROM auditlab.audit_log WHERE action='report_exported' AND result='denied'").use {
                it.executeQuery().use { rows -> assertTrue(rows.next()) }
            }
        }
    }

    @Test
    fun `audit log is filtered, paginated and read only by user roles`() = http { _ ->
        val admin = login("event_admin")
        val auditor = login("event_auditor")
        val device = enroll(admin)
        assertEquals(HttpStatusCode.Accepted, sendEvents(device["deviceToken"].textValue(), event(device)).status)
        client.get("/v1/events?device=${device["deviceId"].textValue()}") { bearerAuth(admin) }
        client.get("/v1/events?device=${device["deviceId"].textValue()}") { bearerAuth(admin) }

        val filtered = client.get("/v1/audit-log?action=events_queried&limit=1") { bearerAuth(auditor) }
        assertEquals(HttpStatusCode.OK, filtered.status)
        val page = json(filtered)
        assertEquals(1, page["items"].size())
        assertNotNull(page["nextCursor"].textValue())
        val entry = page["items"].first()
        assertEquals("events_queried", entry["action"].textValue())
        assertEquals("user", entry["actor"]["type"].textValue())
        assertNotNull(UUID.fromString(entry["actor"]["id"].textValue()))
        assertEquals("success", entry["result"].textValue())
        assertNotNull(UUID.fromString(entry["correlationId"].textValue()))
        assertNotNull(Instant.parse(entry["occurredAt"].textValue()))
        assertTrue(entry["resource"].isObject)
        assertTrue(entry["filters"].isObject)

        val byResult = json(client.get("/v1/audit-log?result=success&limit=100") { bearerAuth(admin) })
        assertTrue(byResult["items"].all { it["result"].textValue() == "success" })
        problem(client.get("/v1/audit-log?action=not_an_action") { bearerAuth(admin) }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/audit-log?result=unknown") { bearerAuth(admin) }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/audit-log?from=2026-01-02T00:00:00Z&to=2026-01-01T00:00:00Z") { bearerAuth(admin) },
            400, "INVALID_RANGE")
        problem(client.get("/v1/audit-log") { bearerAuth(device["deviceToken"].textValue()) }, 401, "UNAUTHENTICATED")
        problem(client.get("/v1/audit-log?actorId=not-a-uuid") { bearerAuth(admin) }, 400, "INVALID_REQUEST")
    }
}
