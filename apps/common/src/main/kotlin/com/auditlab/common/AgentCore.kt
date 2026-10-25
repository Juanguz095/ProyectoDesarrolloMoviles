package com.auditlab.common

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Credencial que el agente guarda cifrada en el dispositivo (implementación Android con Keystore). */
data class StoredCredential(
    val deviceId: String,
    val deviceToken: String,
    val consent: ConsentContext,
)

interface CredentialStore {
    fun load(): StoredCredential?
    fun save(credential: StoredCredential)
    fun clear()
}

/**
 * Lógica del agente independiente de Android y de la UI.
 *
 * Reglas de P4.1/P4.3:
 * - sin consentimiento aceptado no se inscribe ni se generan eventos;
 * - la credencial solo se entrega a la API como cabecera, nunca en URLs ni registros;
 * - una revocación local borra la credencial antes de avisar al servidor, de modo que funcione sin red;
 * - si el servidor responde `DEVICE_REVOKED`, el agente pasa a estado revocado.
 */
class AgentCore(
    private val api: AuditApi,
    private val credentials: CredentialStore,
    private val clock: () -> Instant = Instant::now,
    private val policyVersion: String = "mvp-1",
    private val appVersion: String = "0.1.0-dev",
) {
    sealed interface State {
        data object ConsentPending : State
        data class Enrolled(val deviceId: String, val consent: ConsentContext) : State
        data class Revoked(val deviceId: String, val reason: RevokeReason) : State
    }

    enum class RevokeReason { LOCAL, REMOTE }

    enum class RevokeOutcome { NOTIFIED, LOCAL_ONLY }

    private var consent: ConsentContext? = null

    var state: State = credentials.load()?.let { State.Enrolled(it.deviceId, it.consent) } ?: State.ConsentPending
        private set

    val acceptedConsent: ConsentContext? get() = consent

    /** Registra la aceptación explícita. Sin llamarla, `enroll` falla. */
    fun acceptConsent(): ConsentContext {
        val accepted = ConsentContext(
            consentId = UUID.randomUUID().toString(),
            policyVersion = policyVersion,
            acceptedAt = clock().truncatedTo(ChronoUnit.SECONDS).toString(),
        )
        consent = accepted
        return accepted
    }

    fun declineConsent() {
        consent = null
        if (state is State.ConsentPending) return
        throw IllegalStateException("No se puede retirar el consentimiento desde este estado")
    }

    fun enroll(code: String): State.Enrolled {
        val accepted = consent ?: throw IllegalStateException("Debe aceptar el consentimiento antes de inscribirse")
        if (state is State.Enrolled) throw IllegalStateException("Este dispositivo ya está inscrito")
        val device = api.enroll(code.trim(), accepted, appVersion)
        credentials.save(StoredCredential(device.deviceId, device.deviceToken, device.consent))
        return State.Enrolled(device.deviceId, device.consent).also { state = it }
    }

    /**
     * Genera y envía un único evento propio y simulado. Reutiliza el consentimiento canonicalizado
     * que devolvió la inscripción.
     */
    fun sendSimulatedEvent(): IngestResult {
        val enrolled = state as? State.Enrolled
            ?: throw IllegalStateException("El dispositivo no está inscrito")
        val credential = credentials.load()
            ?: throw IllegalStateException("No hay credencial guardada")
        val event = EventDraft(
            eventId = UUID.randomUUID().toString(),
            timestamp = clock().truncatedTo(ChronoUnit.MICROS).toString(),
            category = "app_lifecycle",
            direction = "occurred",
            sourceDevice = enrolled.deviceId,
            consent = credential.consent,
            metadata = mapOf("appEventCode" to "agent_started", "networkState" to "online"),
        )
        return try {
            api.sendEvents(credential.deviceToken, listOf(event))
        } catch (error: ApiException) {
            if (error.isDeviceRevoked) revokeState(enrolled.deviceId, RevokeReason.REMOTE)
            throw error
        }
    }

    /**
     * Revoca el consentimiento en este dispositivo. Primero borra la credencial, así que ningún
     * evento nuevo puede salir aunque el aviso al servidor falle.
     */
    fun revokeLocally(): RevokeOutcome {
        val enrolled = state as? State.Enrolled ?: throw IllegalStateException("El dispositivo no está inscrito")
        val credential = credentials.load()
        credentials.clear()
        revokeState(enrolled.deviceId, RevokeReason.LOCAL)
        if (credential == null) return RevokeOutcome.LOCAL_ONLY
        return try {
            api.revokeConsent(credential.deviceToken, enrolled.deviceId)
            RevokeOutcome.NOTIFIED
        } catch (_: ApiException) {
            RevokeOutcome.LOCAL_ONLY
        }
    }

    private fun revokeState(deviceId: String, reason: RevokeReason) {
        credentials.clear()
        state = State.Revoked(deviceId, reason)
    }
}
