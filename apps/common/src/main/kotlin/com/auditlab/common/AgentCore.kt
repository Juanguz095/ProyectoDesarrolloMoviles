package com.auditlab.common

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class StoredCredential(val deviceId: String, val deviceToken: String, val consent: ConsentContext, val serverUrl: String? = null) {
    override fun toString() = "StoredCredential(deviceId=$deviceId, token=[redacted])"
}
interface CredentialStore {
    fun load(): StoredCredential?
    fun save(credential: StoredCredential)
    fun clear()
}

/** Persistent outbox and privacy state; one gate serializes producers, workers and withdrawal. */
class AgentCore(
    private val api: AuditApi,
    private val credentials: CredentialStore,
    private val clock: () -> Instant = Instant::now,
    private val policyVersion: String = "mvp-1",
    private val appVersion: String = "0.2.0",
    journal: AgentJournal? = null,
    private val gate: AgentGate = AgentGate(),
    private val visible: () -> Boolean = { true },
    private val serverUrl: String = "http://10.0.2.2:8081",
) {
    private val requireBoundCredential = journal != null
    sealed interface State {
        data object ConsentPending : State
        data class Enrolled(val deviceId: String, val consent: ConsentContext) : State
        data class Revoked(val deviceId: String, val reason: RevokeReason) : State
        data class Blocked(val message: String) : State
    }
    enum class RevokeReason { LOCAL, REMOTE }
    enum class RevokeOutcome { NOTIFIED, LOCAL_ONLY }
    private val journal: AgentJournal = journal ?: MemoryAgentJournal().also { memory ->
        runCatching { credentials.load() }.getOrNull()?.let { memory.activate(it, serverUrl) }
    }
    private var consent: ConsentContext? = null
    var state: State = restore()
        private set
    val acceptedConsent: ConsentContext? get() = consent
    val revocationPending: Boolean get() = synchronized(gate) { journal.registration()?.phase == AgentPhase.REVOKING }
    fun stats(): QueueStats = synchronized(gate) { journal.stats() }

    private fun restore(): State = try {
        val row = journal.registration()
        when (row?.phase) {
            AgentPhase.REVOKING, AgentPhase.REVOKED, AgentPhase.LOCAL_ONLY -> State.Revoked(row.deviceId, if (row.remoteRevocation) RevokeReason.REMOTE else RevokeReason.LOCAL)
            AgentPhase.REJECTED -> State.Blocked("Credencial rechazada. Retira la inscripción antes de volver a participar.")
            AgentPhase.ACTIVE -> {
                val c = credentials.load()
                if (gate.withdrawalRequested) State.Blocked("Se está retirando el consentimiento. No se envían eventos.")
                else if (c == null || c.deviceId != row.deviceId || c.consent != row.consent || row.serverUrl != serverUrl ||
                    (requireBoundCredential && c.serverUrl != serverUrl))
                    State.Blocked("La inscripción no coincide con el almacenamiento. Revoca y vuelve a inscribirte.")
                else State.Enrolled(c.deviceId, c.consent)
            }
            null -> if (credentials.load() == null && journal.stats() == QueueStats()) State.ConsentPending
                else State.Blocked("Inscripción anterior sin cola vinculada. Retírala y acepta de nuevo.")
        }
    } catch (_: Exception) { State.Blocked("No se pudo leer el almacenamiento. Las transmisiones están bloqueadas.") }

    fun refresh(): State = synchronized(gate) {
        (if (gate.storageBlocked) State.Blocked("No se pudo guardar la retirada. Reintenta; no se confirma persistencia.") else restore()).also { state = it }
    }
    fun requestWithdrawal() { gate.withdrawalRequested = true }
    fun acceptConsent(): ConsentContext = synchronized(gate) {
        check(state == State.ConsentPending) { "Retira la inscripción anterior antes de aceptar de nuevo." }
        ConsentContext(UUID.randomUUID().toString(), policyVersion, clock().truncatedTo(ChronoUnit.SECONDS).toString())
            .also { consent = it }
    }
    fun declineConsent() = synchronized(gate) {
        check(state == State.ConsentPending) { "No se puede retirar el consentimiento desde este estado" }
        consent = null
    }
    fun enroll(code: String): State.Enrolled = synchronized(gate) {
        check(state == State.ConsentPending && !gate.storageBlocked) { "Este dispositivo no puede inscribirse en su estado actual." }
        val accepted = consent ?: throw IllegalStateException("Debe aceptar el consentimiento antes de inscribirse")
        val device = api.enroll(code.trim(), accepted, appVersion)
        val sameConsent = runCatching {
            device.consent.consentId == accepted.consentId && device.consent.policyVersion == accepted.policyVersion &&
                Instant.parse(device.consent.acceptedAt) == Instant.parse(accepted.acceptedAt)
        }.getOrDefault(false)
        if (!sameConsent || device.status != "active" || device.deviceToken.isBlank())
            throw ApiException(0, "INVALID_RESPONSE", null, "La inscripción no confirmó el consentimiento solicitado.")
        val stored = StoredCredential(device.deviceId, device.deviceToken, device.consent, serverUrl)
        try {
            credentials.save(stored)
            journal.activate(stored, serverUrl)
        } catch (_: Exception) {
            gate.storageBlocked = true
            state = State.Blocked("No se pudo confirmar la inscripción local. Retírala antes de volver a participar.")
            throw StorageFailure()
        }
        State.Enrolled(device.deviceId, device.consent).also { state = it }
    }

    fun sendSimulatedEvent(metadata: Map<String, Any?> = mapOf("appEventCode" to "agent_started", "networkState" to "online")): IngestResult = captureAndSend { metadata }
    /** Capture only; the Android adapter deduplicates provider rows before calling this gate. */
    fun captureRealEvent(category: String, direction: String, metadata: Map<String, Any?>,
                         timestamp: Instant = clock(), eventId: String = UUID.randomUUID().toString()) = synchronized(gate) {
        val active = activeCredential()
        if (!visible()) throw MonitoringPaused()
        check(!timestamp.isBefore(Instant.parse(active.consent.acceptedAt))) { "Evento anterior al consentimiento" }
        check(!timestamp.isAfter(clock().plusSeconds(300))) { "Fecha futura no permitida" }
        val event = EventDraft(eventId, timestamp.toString(), category, direction, active.deviceId, active.consent, metadata, false)
        EventCodec.encode(event)
        journal.enqueue(event)
    }
    fun captureAndSend(metadata: () -> Map<String, Any?>): IngestResult = synchronized(gate) {
        val active = activeCredential()
        if (!visible()) throw MonitoringPaused()
        val now = clock().truncatedTo(ChronoUnit.MICROS)
        check(!now.isBefore(Instant.parse(active.consent.acceptedAt))) { "La fecha es anterior al consentimiento. Corrige el reloj." }
        journal.prune(now.minus(7, ChronoUnit.DAYS))
        val event = EventDraft(UUID.randomUUID().toString(), now.toString(), "app_lifecycle", "occurred", active.deviceId, active.consent, metadata())
        EventCodec.encode(event)
        journal.enqueue(event)
        flushPending(maxBatches = 1)
    }

    fun flushPending(maxBatches: Int = 10): IngestResult = synchronized(gate) {
        require(maxBatches in 1..100)
        val credential = activeCredential()
        journal.prune(clock().minus(7, ChronoUnit.DAYS))
        if (!visible()) throw MonitoringPaused()
        var accepted = 0
        var duplicates = 0
        repeat(maxBatches) {
            if (!visible()) throw MonitoringPaused()
            check(!gate.withdrawalRequested) { "Hay una retirada de consentimiento en curso." }
            val batch = journal.pending(100)
            if (batch.isEmpty()) return@synchronized IngestResult(accepted, duplicates)
            check(batch.all { it.sourceDevice == credential.deviceId && it.consent == credential.consent }) {
                "La cola no coincide con la inscripción activa. No se enviaron datos."
            }
            if (!visible()) throw MonitoringPaused()
            try {
                val result = api.sendEvents(credential.deviceToken, batch)
                if (result.accepted < 0 || result.duplicates < 0 || result.accepted + result.duplicates != batch.size)
                    throw ApiException(502, "INVALID_RESPONSE", null, "El servicio no confirmó el lote completo.")
                journal.acknowledge(batch.map { e -> e.eventId })
                accepted += result.accepted
                duplicates += result.duplicates
            } catch (error: ApiException) {
                when {
                    error.isDeviceRevoked -> retireRemote(credential.deviceId)
                    error.status == 401 || error.status == 403 || error.code in setOf("TLS_ERROR", "INVALID_RESPONSE", "RESPONSE_TOO_LARGE") -> {
                        try { journal.rejectCredential() }
                        catch (_: Exception) { gate.storageBlocked = true; runCatching { credentials.clear() }; throw StorageFailure() }
                        state = State.Blocked("Credencial rechazada. Revoca y vuelve a inscribirte.")
                    }
                    !error.isRetryable -> journal.reject(batch.map { e -> e.eventId }, error.code)
                }
                throw error
            }
        }
        IngestResult(accepted, duplicates)
    }

    private fun activeCredential(): StoredCredential {
        check(!gate.storageBlocked) { "Almacenamiento bloqueado. Reintenta la retirada del consentimiento." }
        check(!gate.withdrawalRequested) { "Hay una retirada de consentimiento en curso." }
        refresh()
        check(state is State.Enrolled) { "El dispositivo no está inscrito o tiene la transmisión bloqueada." }
        return credentials.load() ?: throw StorageFailure()
    }

    private inline fun <T> withdrawal(action: () -> T): T {
        gate.withdrawalRequested = true
        return synchronized(gate) { action() }
    }
    fun revokeLocally(): RevokeOutcome = withdrawal {
        val row = runCatching { journal.registration() }.getOrNull()
        val stored = runCatching { credentials.load() }.getOrNull()
        val id = stored?.deviceId ?: row?.deviceId ?: "local"
        val target = stored?.serverUrl ?: row?.serverUrl ?: serverUrl
        try {
            journal.beginRevocation(id, target, remote = false)
        } catch (_: Exception) {
            gate.storageBlocked = true
            state = State.Blocked("No se pudo guardar la retirada. No se confirma persistencia; reintenta.")
            runCatching { credentials.clear() }
            throw StorageFailure()
        }
        consent = null
        state = State.Revoked(id, RevokeReason.LOCAL)
        retryRevocation()
    }

    fun retryRevocation(): RevokeOutcome = synchronized(gate) {
        val row = journal.registration() ?: return@synchronized revokeLocally()
        check(row.phase in setOf(AgentPhase.REVOKING, AgentPhase.REVOKED, AgentPhase.LOCAL_ONLY)) { "No hay retirada pendiente." }
        journal.deleteEvents()
        val credential = runCatching { credentials.load() }.getOrNull()
        if (row.phase != AgentPhase.REVOKING) {
            credentials.clear(); gate.storageBlocked = false
            state = State.Revoked(row.deviceId, if (row.remoteRevocation) RevokeReason.REMOTE else RevokeReason.LOCAL)
            return@synchronized if (row.phase == AgentPhase.REVOKED) RevokeOutcome.NOTIFIED else RevokeOutcome.LOCAL_ONLY
        }
        if (row.phase == AgentPhase.REVOKING && !row.remoteRevocation && credential != null) {
            if (requireBoundCredential && credential.serverUrl == null) {
                journal.completeRevocation(false); credentials.clear(); gate.storageBlocked = false
                return@synchronized RevokeOutcome.LOCAL_ONLY
            }
            check(row.serverUrl == serverUrl && (credential.serverUrl == null || credential.serverUrl == serverUrl)) {
                "El servicio no coincide con la credencial protegida. No se transmitió la credencial."
            }
            try { api.revokeConsent(credential.deviceToken, row.deviceId) }
            catch (e: ApiException) {
                if (e.isRetryable) return@synchronized RevokeOutcome.LOCAL_ONLY
                if (!e.isDeviceRevoked) {
                    journal.completeRevocation(false); credentials.clear(); gate.storageBlocked = false
                    return@synchronized RevokeOutcome.LOCAL_ONLY
                }
            }
        }
        val confirmed = row.remoteRevocation || credential != null
        journal.completeRevocation(confirmed)
        credentials.clear()
        gate.storageBlocked = false
        state = State.Revoked(row.deviceId, if (row.remoteRevocation) RevokeReason.REMOTE else RevokeReason.LOCAL)
        if (confirmed) RevokeOutcome.NOTIFIED else RevokeOutcome.LOCAL_ONLY
    }

    private fun retireRemote(id: String) {
        gate.withdrawalRequested = true
        try { journal.beginRevocation(id, serverUrl, remote = true) }
        catch (_: Exception) {
            gate.storageBlocked = true
            state = State.Blocked("No se pudo guardar la retirada remota. Las transmisiones están bloqueadas.")
            runCatching { credentials.clear() }
            throw StorageFailure()
        }
        state = State.Revoked(id, RevokeReason.REMOTE)
        retryRevocation()
    }
    fun resetAfterRevocation() = synchronized(gate) {
        check(journal.registration()?.phase in setOf(AgentPhase.REVOKED, AgentPhase.LOCAL_ONLY)) { "Completa la retirada pendiente antes de aceptar de nuevo." }
        credentials.clear(); journal.deleteEvents(); journal.reset(); gate.storageBlocked = false; gate.withdrawalRequested = false
        consent = null; state = State.ConsentPending
    }
}
