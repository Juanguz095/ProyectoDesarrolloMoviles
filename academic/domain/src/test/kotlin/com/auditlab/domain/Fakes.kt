package com.auditlab.domain

import java.util.UUID

internal val eventOrder: Comparator<Event> = compareByDescending<Event> { it.timestampEpochMs }
    .thenBy { it.eventId.toString() }

internal fun context(time: Long = 1000, policy: String = "1.0") = ConsentContext(UUID.randomUUID(), policy, time)
internal fun event(
    id: UUID = UUID.randomUUID(),
    time: Long = 2000,
    consent: ConsentContext = context(),
    kind: EventKind = EventKind.CALL,
    direction: EventDirection = EventDirection.INCOMING,
) = Event(id, time, kind, direction, consent)

/** Unit-test adapter only: it provides no disk/Android persistence. */
internal class InMemoryEvents(private val journal: MutableList<String> = mutableListOf()) : EventRepository {
    private val events = linkedMapOf<UUID, Event>()
    var failInsert = false
    var failRead = false
    var failDelete = false
    var requestedCapacity: Int? = null
    var requestedHistoryLimit: Int? = null
    val snapshot: List<Event> @Synchronized get() = events.values.toList()

    @Synchronized override fun insertIfBelowCapacity(event: Event, capacity: Int): InsertResult {
        require(capacity > 0)
        journal += "events.insert"
        requestedCapacity = capacity
        if (failInsert) throw StorageException()
        if (events.containsKey(event.eventId)) return InsertResult.DUPLICATE_ID
        if (events.size >= capacity) return InsertResult.CAPACITY_REACHED
        events[event.eventId] = event
        return InsertResult.INSERTED
    }

    @Synchronized override fun latest(limit: Int): List<Event> {
        require(limit > 0)
        journal += "events.latest"
        requestedHistoryLimit = limit
        if (failRead) throw StorageException()
        return events.values.sortedWith(eventOrder).take(limit)
    }

    @Synchronized override fun deleteAll(): Int {
        journal += "events.delete"
        if (failDelete) throw StorageException()
        val deleted = events.size
        events.clear()
        return deleted
    }
}

internal class InMemoryAcceptance(
    var state: Acceptance = Acceptance.Pending,
    private val journal: MutableList<String> = mutableListOf(),
) : AcceptanceStore {
    var failRead = false
    val failSaves = mutableSetOf<Int>()
    var saveAttempts = 0
        private set

    @Synchronized override fun read(): Acceptance {
        journal += "acceptance.read"
        if (failRead) throw StorageException()
        return state
    }

    @Synchronized override fun save(acceptance: Acceptance) {
        journal += "acceptance.save"
        saveAttempts++
        if (saveAttempts in failSaves) throw StorageException()
        check(!(state is Acceptance.Revoked && (state as Acceptance.Revoked).deletionPending && acceptance is Acceptance.Granted)) {
            "Pending cleanup cannot be replaced by new acceptance"
        }
        state = acceptance
    }
}
