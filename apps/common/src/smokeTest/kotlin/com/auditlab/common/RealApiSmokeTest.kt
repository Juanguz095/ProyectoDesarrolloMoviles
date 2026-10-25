package com.auditlab.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Prueba de humo del **cliente real** contra una API Ktor en ejecución.
 *
 * No sustituye la ejecución en Android: aquí no existen Keystore ni la política de red del
 * dispositivo. Verifica que las peticiones que la app construye son aceptadas por el servidor real
 * y que las respuestas se interpretan como espera la UI.
 *
 * Se ejecuta con `:apps:common:smokeTest` y las variables `AUDITLAB_SMOKE_URL`, `_USER` y `_PASSWORD`.
 */
class RealApiSmokeTest {
    private val url = System.getenv("AUDITLAB_SMOKE_URL").orEmpty()
    private val user = System.getenv("AUDITLAB_SMOKE_USER").orEmpty()
    private val password = System.getenv("AUDITLAB_SMOKE_PASSWORD").orEmpty()

    private fun api() = ApiClient(url)

    private fun consent(): ConsentContext = ConsentContext(
        consentId = UUID.randomUUID().toString(),
        policyVersion = "p4-smoke",
        acceptedAt = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.SECONDS).toString(),
    )

    private fun draft(deviceId: String, consent: ConsentContext) = EventDraft(
        eventId = UUID.randomUUID().toString(),
        timestamp = Instant.now().truncatedTo(ChronoUnit.MICROS).toString(),
        category = "app_lifecycle",
        direction = "occurred",
        sourceDevice = deviceId,
        consent = consent,
        metadata = mapOf("appEventCode" to "agent_started"),
    )

    @Test
    fun `el centro inscribe, consulta y revoca contra la API real`() {
        assumeTrue(url.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty(), "Sin configuración de humo")
        val api = api()
        val control = ControlCore(api)
        val session = control.login(user, password)
        assertEquals("admin", session.role)
        assertTrue(control.isAdmin)

        val code = control.createEnrollmentCode()
        assertTrue(code.code.length >= 32)
        val device = api.enroll(code.code, consent(), "0.1.0-smoke")
        assertEquals("active", device.status)

        val first = draft(device.deviceId, device.consent)
        assertEquals(IngestResult(1, 0), api.sendEvents(device.deviceToken, listOf(first)))
        // Reintento idempotente: mismo eventId, mismo contenido.
        assertEquals(IngestResult(0, 1), api.sendEvents(device.deviceToken, listOf(first)))

        val devices = control.devices()
        assertTrue(devices.items.any { it.deviceId == device.deviceId && it.isActive })
        val events = control.events(device.deviceId)
        val stored = events.items.firstOrNull { it.eventId == first.eventId }
        assertNotNull(stored, "El evento enviado debe estar en la consulta del centro")
        assertTrue(stored!!.isSimulated)

        control.revoke(device.deviceId)
        val revoked = control.devices().items.first { it.deviceId == device.deviceId }
        assertEquals("revoked", revoked.status)
        val error = assertThrows(ApiException::class.java) { api.sendEvents(device.deviceToken, listOf(draft(device.deviceId, device.consent))) }
        assertEquals("DEVICE_REVOKED", error.code)
        control.logout()
    }

    @Test
    fun `el agente refleja la revocacion remota al contactar`() {
        assumeTrue(url.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty(), "Sin configuración de humo")
        val api = api()
        val control = ControlCore(api)
        control.login(user, password)
        val code = control.createEnrollmentCode()
        val device = api.enroll(code.code, consent(), "0.1.0-smoke")

        val store = InMemoryCredentialStore(StoredCredential(device.deviceId, device.deviceToken, device.consent))
        val agent = AgentCore(api, store)
        assertEquals(AgentCore.State.Enrolled(device.deviceId, device.consent), agent.state)
        val result = agent.sendSimulatedEvent()
        assertEquals(1, result.accepted)

        // Revocación remota desde el centro y nuevo contacto del agente.
        control.revoke(device.deviceId)
        val error = assertThrows(ApiException::class.java) { agent.sendSimulatedEvent() }
        assertTrue(error.isDeviceRevoked)
        assertEquals(AgentCore.State.Revoked(device.deviceId, AgentCore.RevokeReason.REMOTE), agent.state)
        assertEquals(null, store.load())
        control.logout()
    }

    @Test
    fun `la revocacion local del agente se refleja en el centro`() {
        assumeTrue(url.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty(), "Sin configuración de humo")
        val api = api()
        val control = ControlCore(api)
        control.login(user, password)
        val code = control.createEnrollmentCode()
        val device = api.enroll(code.code, consent(), "0.1.0-smoke")

        val store = InMemoryCredentialStore(StoredCredential(device.deviceId, device.deviceToken, device.consent))
        val agent = AgentCore(api, store)
        assertEquals(AgentCore.RevokeOutcome.NOTIFIED, agent.revokeLocally())
        assertEquals(AgentCore.State.Revoked(device.deviceId, AgentCore.RevokeReason.LOCAL), agent.state)
        val detail = control.devices().items.first { it.deviceId == device.deviceId }
        assertEquals("revoked", detail.status)
        control.logout()
    }
}
