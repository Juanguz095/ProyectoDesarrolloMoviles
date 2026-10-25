package com.auditlab.api

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID

/**
 * Comprueba las garantías que solo se sostienen bajo contención real: consumo único del código,
 * idempotencia del `eventId`, unicidad de códigos y bloqueo de escrituras tras la revocación.
 *
 * Las peticiones se lanzan en paralelo contra la API y la base PostgreSQL reales; no se simula
 * la concurrencia ni se serializan las llamadas.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrencyIntegrationTest : ApiTestSupport() {
    @BeforeAll
    fun setup() {
        Bootstrap.user(owner, "concurrency_admin", password, "admin")
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.revoke(admin: String, deviceId: String): HttpResponse =
        client.post("/v1/devices/$deviceId/revoke") { bearerAuth(admin) }

    private fun storedEvents(deviceId: String): Int = inspect { connection ->
        connection.prepareStatement("SELECT count(*) FROM auditlab.events WHERE device_id=?::uuid").use {
            it.setString(1, deviceId)
            it.executeQuery().use { row -> row.next(); row.getInt(1) }
        }
    }

    @Test
    fun `un mismo eventId entregado 16 veces en paralelo se acepta una sola vez`() = http { _ ->
        val admin = login("concurrency_admin")
        val device = enroll(admin)
        val token = device["deviceToken"].textValue()
        val payload = event(device, metadata = mapOf("appEventCode" to "agent_started"))

        val responses = coroutineScope { List(16) { async { sendEvents(token, payload) } }.awaitAll() }
        assertEquals(List(16) { HttpStatusCode.Accepted.value }, responses.map { it.status.value })

        val bodies = responses.map { mapper.readTree(it.bodyAsText()) }
        assertEquals(1, bodies.sumOf { it["accepted"].intValue() })
        assertEquals(15, bodies.sumOf { it["duplicates"].intValue() })
        // Un solo evento persistido: el duplicado no crea filas ni pierde la primera entrega.
        assertEquals(1, storedEvents(device["deviceId"].textValue()))
    }

    @Test
    fun `eventos distintos en paralelo no se confunden entre si`() = http { _ ->
        val admin = login("concurrency_admin")
        val device = enroll(admin)
        val token = device["deviceToken"].textValue()

        val responses = coroutineScope {
            List(20) { index ->
                async { sendEvents(token, event(device, metadata = mapOf("batteryPercent" to index * 5))) }
            }.awaitAll()
        }
        val summary = responses.map { response -> "${response.status.value}:${response.bodyAsText().take(80)}" }.joinToString()
        assertTrue(responses.all { it.status == HttpStatusCode.Accepted }, summary)
        assertEquals(20, responses.map { mapper.readTree(it.bodyAsText())["accepted"].intValue() }.sum())
        assertEquals(20, storedEvents(device["deviceId"].textValue()))
    }

    @Test
    fun `un codigo de inscripcion se consume una sola vez con 16 intentos simultaneos`() = http { _ ->
        val admin = login("concurrency_admin")
        val code = createCode(admin)["code"].textValue()

        val responses = coroutineScope {
            List(16) { async { enrollResponse(code, enrollmentBody()) } }.awaitAll()
        }
        assertEquals(1, responses.count { it.status == HttpStatusCode.Created })
        responses.filter { it.status != HttpStatusCode.Created }.forEach {
            problem(it, 401, "ENROLLMENT_CODE_INVALID")
        }
        inspect { connection ->
            connection.prepareStatement("""SELECT count(*) FROM auditlab.devices d
                JOIN auditlab.enrollment_codes e ON e.id=d.enrollment_code_id WHERE e.code_hash=?""").use {
                it.setString(1, sha256(code))
                it.executeQuery().use { row -> row.next(); assertEquals(1, row.getInt(1)) }
            }
        }
    }

    @Test
    fun `la creacion simultanea de codigos no repite ninguno`() = http { _ ->
        val admin = login("concurrency_admin")
        val codes = coroutineScope { List(12) { async { createCode(admin)["code"].textValue() } }.awaitAll() }
        assertEquals(12, codes.toSet().size)
        assertTrue(codes.all { it.length in 32..4096 })
    }

    @Test
    fun `la revocacion bloquea los lotes posteriores`() = http { _ ->
        val admin = login("concurrency_admin")
        val device = enroll(admin)
        val deviceId = device["deviceId"].textValue()
        val token = device["deviceToken"].textValue()

        assertEquals(HttpStatusCode.NoContent, revoke(admin, deviceId).status)
        problem(sendEvents(token, event(device)), 401, "DEVICE_REVOKED")
        assertEquals(0, storedEvents(deviceId))
    }

    @Test
    fun `ningun evento se persiste despues de confirmarse la revocacion`() = http { _ ->
        val admin = login("concurrency_admin")
        val device = enroll(admin)
        val deviceId = device["deviceId"].textValue()
        val token = device["deviceToken"].textValue()

        // Ocho entregas y la revocación compiten por el mismo dispositivo.
        val (responses, revocations) = coroutineScope {
            val sends = List(8) { async { sendEvents(token, event(device)) } }
            val revokeCall = async { revoke(admin, deviceId) }
            sends.awaitAll() to listOf(revokeCall.await())
        }
        assertEquals(HttpStatusCode.NoContent, revocations.single().status)
        // Cada entrega terminó aceptada o rechazada; ninguna quedó en estado indeterminado.
        assertTrue(responses.all { it.status == HttpStatusCode.Accepted || it.status == HttpStatusCode.Unauthorized })

        val revokedAt = inspect { connection ->
            connection.prepareStatement("SELECT revoked_at FROM auditlab.devices WHERE id=?::uuid").use {
                it.setString(1, deviceId)
                it.executeQuery().use { row -> row.next(); assertNotNull(row.getTimestamp(1)); row.getTimestamp(1).toInstant() }
            }
        }
        val afterRevocation = inspect { connection ->
            connection.prepareStatement("SELECT count(*) FROM auditlab.events WHERE device_id=?::uuid AND received_at > ?").use {
                it.setString(1, deviceId)
                it.setTimestamp(2, java.sql.Timestamp.from(revokedAt))
                it.executeQuery().use { row -> row.next(); row.getInt(1) }
            }
        }
        assertEquals(0, afterRevocation, "un evento se guardó después de la revocación confirmada")
        // Tras la revocación, ninguna credencial vuelve a escribir.
        problem(sendEvents(token, event(device)), 401, "DEVICE_REVOKED")
    }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun enrollmentBody(consentId: UUID = UUID.randomUUID(), acceptedAt: Instant = Instant.now().minusSeconds(1)): Map<String, Any?> =
        mapOf(
            "consentAcknowledged" to true,
            "consent" to mapOf(
                "consentId" to consentId.toString(),
                "policyVersion" to "policy-1",
                "acceptedAt" to acceptedAt.toString(),
            ),
        )
}
