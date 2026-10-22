package com.auditlab.domain

/** Expected adapter failures. Services map these to stable errors without exposing storage details. */
class StorageException : RuntimeException()

enum class InsertResult { INSERTED, CAPACITY_REACHED, DUPLICATE_ID }

interface EventRepository {
    /** Count + insertion must be atomic; duplicate IDs and full capacity leave storage unchanged. */
    @Throws(StorageException::class)
    fun insertIfBelowCapacity(event: Event, capacity: Int): InsertResult

    /** At most limit records, descending timestamp then ascending canonical UUID string. */
    @Throws(StorageException::class)
    fun latest(limit: Int): List<Event>

    /** Atomic event deletion. Returns the count removed, never a negative count. */
    @Throws(StorageException::class)
    fun deleteAll(): Int
}

interface AcceptanceStore {
    /** Missing decision is Pending. Read failure throws; it must never be interpreted as Granted. */
    @Throws(StorageException::class)
    fun read(): Acceptance

    /**
     * Return only after a durable write. Revoked is saved before attempting event deletion.
     * A caller must not grant new acceptance over a pending cleanup; all writes share the coordinator.
     * I06 provides a SQLite adapter; in-memory test doubles do not prove its durability.
     */
    @Throws(StorageException::class)
    fun save(acceptance: Acceptance)
}

fun interface EpochClock { fun nowEpochMillis(): Long }
fun interface EventIdGenerator { fun nextId(): java.util.UUID }

/** Shared by services using the same stores; it serializes business operations, not Android UI work. */
interface AuditCoordinator {
    fun <T> exclusive(action: () -> T): T
}
