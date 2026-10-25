package com.auditlab.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class AgentCoreTest {
    private val consent = ConsentContext("c-1", "mvp-1", "2026-10-24T10:00:00Z")
    private val api = FakeApi()
    private val store = InMemoryCredentialStore()
    private val core = AgentCore(api, store, clock = { Instant.parse("2026-10-24T10:05:00Z") })

    @Test
    fun `sin consentimiento no se inscribe ni llama a la API`() {
        assertThrows(IllegalStateException::class.java) { core.enroll("x".repeat(40)) }
        assertTrue(api.calls.isEmpty())
        assertEquals(AgentCore.State.ConsentPending, core.state)
    }

    @Test
    fun `la inscripcion guarda la credencial y reutiliza el consentimiento del servidor`() {
        val accepted = core.acceptConsent()
        assertEquals("mvp-1", accepted.policyVersion)
        assertEquals("2026-10-24T10:05:00Z", accepted.acceptedAt)
        val state = core.enroll("  " + "x".repeat(40) + "  ")
        // El estado y la credencial usan el consentimiento canonicalizado que devolvió el servidor.
        assertEquals(AgentCore.State.Enrolled("d-1", consent), state)
        assertEquals("x".repeat(40), api.enrolledCode)
        assertEquals(accepted, api.enrolledConsent)
        assertEquals("d-1", store.load()?.deviceId)
        assertEquals("dev_token", store.load()?.deviceToken)
        assertEquals(consent, store.load()?.consent)
    }

    @Test
    fun `el evento simulado usa el consentimiento y la identidad inscritos`() {
        core.acceptConsent()
        core.enroll("x".repeat(40))
        api.sendResult = IngestResult(accepted = 1, duplicates = 0)
        val result = core.sendSimulatedEvent()
        assertEquals(1, result.accepted)
        val draft = api.sent.single()
        assertEquals("d-1", draft.sourceDevice)
        assertEquals(consent, draft.consent)
        assertEquals("app_lifecycle", draft.category)
        assertEquals("occurred", draft.direction)
        assertEquals("agent_started", draft.metadata["appEventCode"])
        assertEquals("dev_token", api.sentToken)
    }

    @Test
    fun `no se generan eventos sin inscripcion`() {
        core.acceptConsent()
        assertThrows(IllegalStateException::class.java) { core.sendSimulatedEvent() }
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun `una revocacion remota cambia el estado y borra la credencial`() {
        core.acceptConsent()
        core.enroll("x".repeat(40))
        api.sendError = ApiException(401, "DEVICE_REVOKED", "corr", "revocado")
        assertThrows(ApiException::class.java) { core.sendSimulatedEvent() }
        assertEquals(AgentCore.State.Revoked("d-1", AgentCore.RevokeReason.REMOTE), core.state)
        assertNull(store.load())
        // Un segundo intento ya no tiene credencial ni estado inscrito.
        assertThrows(IllegalStateException::class.java) { core.sendSimulatedEvent() }
    }

    @Test
    fun `la revocacion local impide eventos aunque el servidor no responda`() {
        core.acceptConsent()
        core.enroll("x".repeat(40))
        api.revokeError = ApiException(0, "NETWORK_ERROR", null, "sin red")
        val outcome = core.revokeLocally()
        assertEquals(AgentCore.RevokeOutcome.LOCAL_ONLY, outcome)
        assertEquals(AgentCore.State.Revoked("d-1", AgentCore.RevokeReason.LOCAL), core.state)
        assertNull(store.load())
        assertThrows(IllegalStateException::class.java) { core.sendSimulatedEvent() }
        assertEquals("d-1", api.revokedConsentDevice)
    }

    @Test
    fun `la revocacion local avisa al servidor cuando hay red`() {
        core.acceptConsent()
        core.enroll("x".repeat(40))
        assertEquals(AgentCore.RevokeOutcome.NOTIFIED, core.revokeLocally())
        assertEquals("dev_token", api.revokedConsentToken)
    }

    @Test
    fun `una credencial guardada se recupera al iniciar`() {
        val restored = AgentCore(api, InMemoryCredentialStore(StoredCredential("d-9", "tok", consent)))
        assertEquals(AgentCore.State.Enrolled("d-9", consent), restored.state)
        assertNotNull(restored.state)
    }

    private class FakeApi : AuditApi {
        val calls = mutableListOf<String>()
        var enrolledCode: String? = null
        var enrolledConsent: ConsentContext? = null
        var sent: List<EventDraft> = emptyList()
        var sentToken: String? = null
        var revokedConsentDevice: String? = null
        var revokedConsentToken: String? = null
        var sendResult = IngestResult(0, 0)
        var sendError: ApiException? = null
        var revokeError: ApiException? = null
        var canonicalConsent = ConsentContext("c-1", "mvp-1", "2026-10-24T10:00:00Z")

        override fun login(username: String, password: String): Session = throw UnsupportedOperationException()
        override fun refresh(refreshToken: String): Session = throw UnsupportedOperationException()
        override fun logout(accessToken: String) = throw UnsupportedOperationException()
        override fun createEnrollmentCode(accessToken: String): EnrollmentCode = throw UnsupportedOperationException()

        override fun enroll(code: String, consent: ConsentContext, appVersion: String): EnrolledDevice {
            calls += "enroll"
            enrolledCode = code
            enrolledConsent = consent
            // Simula la canonicalización del servidor: devuelve un contexto distinto del local.
            return EnrolledDevice("d-1", "dev_token", canonicalConsent, "2026-10-24T10:00:01Z")
        }

        override fun sendEvents(deviceToken: String, events: List<EventDraft>): IngestResult {
            calls += "send"
            sentToken = deviceToken
            sent = events
            sendError?.let { throw it }
            return sendResult
        }

        override fun listDevices(accessToken: String, status: String?, limit: Int): DevicePage =
            throw UnsupportedOperationException()

        override fun listEvents(accessToken: String, deviceId: String?, limit: Int): EventPage =
            throw UnsupportedOperationException()

        override fun revokeDevice(accessToken: String, deviceId: String) = throw UnsupportedOperationException()

        override fun revokeConsent(deviceToken: String, deviceId: String) {
            calls += "revokeConsent"
            revokedConsentToken = deviceToken
            revokedConsentDevice = deviceId
            revokeError?.let { throw it }
        }
    }
}
