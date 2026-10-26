package com.auditlab.common

import java.time.Instant

class StorageFailure : IllegalStateException("No se pudo confirmar la operación en el almacenamiento local.")
class QueueFull : IllegalStateException("La cola alcanzó su límite. Sincroniza o revoca para liberar espacio.")
class MonitoringPaused : IllegalStateException("El monitoreo está pausado: habilita su notificación visible.")
enum class AgentPhase { ACTIVE, REVOKING, REVOKED, LOCAL_ONLY, REJECTED }
enum class DeliveryState { PENDING, SENT, FAILED }
data class AgentRegistration(val deviceId: String, val serverUrl: String, val consent: ConsentContext?,
                             val phase: AgentPhase, val remoteRevocation: Boolean = false)
data class QueueStats(val pending: Int = 0, val sent: Int = 0, val failed: Int = 0)

/** No tokens here. Capacity and insertion must be a single transaction. */
interface AgentJournal {
    fun registration(): AgentRegistration?
    fun activate(credential: StoredCredential, serverUrl: String)
    fun enqueue(event: EventDraft)
    fun pending(limit: Int): List<EventDraft>
    fun acknowledge(eventIds: List<String>)
    fun reject(eventIds: List<String>, code: String)
    fun rejectCredential()
    fun beginRevocation(deviceId: String, serverUrl: String, remote: Boolean)
    fun completeRevocation(confirmed: Boolean = true)
    fun deleteEvents()
    fun reset()
    fun prune(before: Instant): Int
    fun stats(): QueueStats
}
/** Shared by every producer and worker in the Android process. */
class AgentGate {
    var storageBlocked: Boolean = false
    @Volatile var withdrawalRequested: Boolean = false
}

/** JVM adapter. Android always supplies Room. */
class MemoryAgentJournal(private val capacity: Int = 10_000) : AgentJournal {
    private var current: AgentRegistration? = null
    private val rows = linkedMapOf<String, Pair<EventDraft, DeliveryState>>()
    @Synchronized override fun registration() = current
    @Synchronized override fun activate(credential: StoredCredential, serverUrl: String) {
        check(current == null) { "Retira la inscripción anterior antes de inscribirte de nuevo." }
        current = AgentRegistration(credential.deviceId, serverUrl, credential.consent, AgentPhase.ACTIVE)
    }
    @Synchronized override fun enqueue(event: EventDraft) {
        val active = current ?: throw StorageFailure()
        check(active.phase == AgentPhase.ACTIVE && event.sourceDevice == active.deviceId && event.consent == active.consent)
        if (event.eventId in rows) throw StorageFailure()
        if (rows.size >= capacity) {
            val sent = rows.entries.firstOrNull { it.value.second == DeliveryState.SENT }
            if (sent == null) throw QueueFull()
            rows.remove(sent.key)
        }
        rows[event.eventId] = event to DeliveryState.PENDING
    }
    @Synchronized override fun pending(limit: Int) = rows.values.filter { it.second == DeliveryState.PENDING }
        .map { it.first }.sortedWith(compareBy<EventDraft> { Instant.parse(it.timestamp) }.thenBy { it.eventId }).take(limit)
    @Synchronized override fun acknowledge(eventIds: List<String>) { eventIds.forEach { id -> rows[id]?.let { rows[id] = it.first to DeliveryState.SENT } } }
    @Synchronized override fun reject(eventIds: List<String>, code: String) { eventIds.forEach { id -> rows[id]?.let { rows[id] = it.first to DeliveryState.FAILED } } }
    @Synchronized override fun rejectCredential() { current = current?.copy(phase = AgentPhase.REJECTED) }
    @Synchronized override fun beginRevocation(deviceId: String, serverUrl: String, remote: Boolean) {
        current = AgentRegistration(deviceId, serverUrl, null, AgentPhase.REVOKING, remote)
    }
    @Synchronized override fun completeRevocation(confirmed: Boolean) { current = current?.copy(phase = if (confirmed) AgentPhase.REVOKED else AgentPhase.LOCAL_ONLY, consent = null) }
    @Synchronized override fun deleteEvents() { rows.clear() }
    @Synchronized override fun reset() { check(current?.phase in setOf(AgentPhase.REVOKED, AgentPhase.LOCAL_ONLY)); rows.clear(); current = null }
    @Synchronized override fun prune(before: Instant): Int {
        val ids = rows.filterValues { Instant.parse(it.first.timestamp).isBefore(before) }.keys.toList()
        ids.forEach(rows::remove)
        return ids.size
    }
    @Synchronized override fun stats() = QueueStats(rows.count { it.value.second == DeliveryState.PENDING },
        rows.count { it.value.second == DeliveryState.SENT }, rows.count { it.value.second == DeliveryState.FAILED })
}
