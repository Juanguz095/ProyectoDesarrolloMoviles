package com.auditlab.academic.tests

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import com.auditlab.academic.storage.AuditDatabase
import com.auditlab.academic.storage.SQLiteEventRepository
import com.auditlab.domain.*
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object SQLiteEventTests {
    fun cases(context: Context): List<InstrumentedCase> {
        fun case(name: String, run: (DatabaseFixture) -> Unit) = InstrumentedCase("SQLiteEventTests", name) {
            DatabaseFixture(context).use(run)
        }
        return listOf(
            case("fourCombinationsSurviveCloseAndReopen") { f ->
                val items = EventKind.entries.flatMap { kind -> EventDirection.entries.map { event(kind = kind, direction = it) } }
                items.forEach { check(f.repository.insertIfBelowCapacity(it, 1000) == InsertResult.INSERTED) }
                f.reopen()
                check(f.repository.latest(100).toSet() == items.toSet())
            },
            case("duplicateDoesNotOverwrite") { f ->
                val item = event()
                check(f.repository.insertIfBelowCapacity(item, 1000) == InsertResult.INSERTED)
                check(f.repository.insertIfBelowCapacity(item.copy(kind = EventKind.MESSAGE), 1000) == InsertResult.DUPLICATE_ID)
                check(f.repository.latest(100) == listOf(item))
            },
            case("capacityOneThousandSurvivesReopen") { f ->
                repeat(1000) { check(f.repository.insertIfBelowCapacity(event(id = UUID(0, it.toLong())), 1000) == InsertResult.INSERTED) }
                val ids = f.ids()
                f.reopen()
                check(f.repository.insertIfBelowCapacity(event(), 2000) == InsertResult.CAPACITY_REACHED)
                check(f.ids() == ids && ids.size == 1000)
            },
            case("smallerCapacityIsHonoredAtomically") { f ->
                repeat(2) { check(f.repository.insertIfBelowCapacity(event(), 2) == InsertResult.INSERTED) }
                val ids = f.ids()
                check(f.repository.insertIfBelowCapacity(event(), 2) == InsertResult.CAPACITY_REACHED)
                check(f.ids() == ids)
            },
            case("latestHundredHasStableOrderWithoutDeletingOlderRows") { f ->
                val items = List(150) { event(id = UUID(0, it.toLong()), time = 2000L + it / 3) }
                items.forEach { f.repository.insertIfBelowCapacity(it, 1000) }
                val expected = items.sortedWith(compareByDescending<Event> { it.timestampEpochMs }.thenBy { it.eventId.toString() }).take(100)
                check(f.repository.latest(500) == expected)
                check(f.ids().size == 150)
            },
            case("deleteIsCountedIdempotentAndPersistent") { f ->
                repeat(3) { f.repository.insertIfBelowCapacity(event(), 1000) }
                check(f.repository.deleteAll() == 3)
                f.reopen()
                check(f.repository.latest(100).isEmpty())
                check(f.repository.deleteAll() == 0)
            },
            case("abortedDeleteRollsBackAllRows") { f ->
                repeat(3) { f.repository.insertIfBelowCapacity(event(), 1000) }
                val ids = f.ids()
                f.database.writableDatabase.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON events BEGIN SELECT RAISE(ABORT, 'test'); END")
                expectStorage { f.repository.deleteAll() }
                check(f.ids() == ids)
            },
            case("abortedInsertDoesNotConsumeSlot") { f ->
                f.database.writableDatabase.execSQL("CREATE TRIGGER fail_insert AFTER INSERT ON events BEGIN SELECT RAISE(ABORT, 'test'); END")
                expectStorage { f.repository.insertIfBelowCapacity(event(), 1000) }
                check(f.ids().isEmpty())
                f.database.writableDatabase.execSQL("DROP TRIGGER fail_insert")
                check(f.repository.insertIfBelowCapacity(event(), 1) == InsertResult.INSERTED)
            },
            case("schemaRejectsInvalidStoredValues") { f ->
                val item = event(); f.repository.insertIfBelowCapacity(item, 1000)
                val invalid = listOf(
                    ContentValues().apply { put("kind", "SMS_REAL") },
                    ContentValues().apply { put("direction", "OTHER") },
                    ContentValues().apply { put("is_simulated", 0) },
                    ContentValues().apply { put("accepted_at_ms", -1) },
                    ContentValues().apply { put("timestamp_ms", 999) },
                    ContentValues().apply { put("event_id", "bad-id") },
                    ContentValues().apply { put("policy_version", "=1+1") },
                )
                invalid.forEach { value ->
                    var rejected = false
                    try { f.database.writableDatabase.update("events", value, "event_id = ?", arrayOf(item.eventId.toString())) }
                    catch (_: SQLiteConstraintException) { rejected = true }
                    check(rejected)
                }
                check(f.repository.latest(100) == listOf(item))
            },
            case("independentConnectionsCannotExceedCapacity") { f ->
                val peerDatabase = AuditDatabase(context, f.name)
                val peer = SQLiteEventRepository(peerDatabase)
                val pool = Executors.newFixedThreadPool(2); val gate = CountDownLatch(1)
                try {
                    val jobs = List(2) { worker -> pool.submit(Callable {
                        check(gate.await(10, TimeUnit.SECONDS))
                        List(15) { (if (worker == 0) f.repository else peer).insertIfBelowCapacity(event(), 20) }
                    }) }
                    gate.countDown()
                    val results = jobs.flatMap { it.get(30, TimeUnit.SECONDS) }
                    check(results.count { it == InsertResult.INSERTED } == 20)
                    check(results.count { it == InsertResult.CAPACITY_REACHED } == 10)
                    check(f.ids().size == 20)
                } finally { pool.shutdownNow(); peerDatabase.close() }
            },
            case("unavailableTableRaisesErrorRatherThanEmptySuccess") { f ->
                f.database.writableDatabase.execSQL("ALTER TABLE events RENAME TO unavailable_events")
                expectStorage { f.repository.latest(100) }
            },
            case("schemaVersionAndIndexExist") { f ->
                val db = f.database.readableDatabase
                check(db.version == AuditDatabase.VERSION)
                check(db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf("events_history_order")).use { it.moveToFirst() })
            },
        )
    }
}

internal class DatabaseFixture(private val context: Context) : AutoCloseable {
    val name = "auditlab-test-${UUID.randomUUID()}.db"
    var database = AuditDatabase(context, name)
        private set
    val repository get() = SQLiteEventRepository(database)
    fun reopen() { database.close(); database = AuditDatabase(context, name) }
    fun ids(): Set<String> = database.readableDatabase.rawQuery("SELECT event_id FROM events", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    override fun close() { database.close(); check(context.deleteDatabase(name)) }
}

internal fun event(
    id: UUID = UUID.randomUUID(), time: Long = 2000,
    kind: EventKind = EventKind.CALL, direction: EventDirection = EventDirection.INCOMING,
) = Event(id, time, kind, direction, ConsentContext(UUID(0, 1), "1.0", 1000))

internal fun expectStorage(action: () -> Unit) {
    var failed = false
    try { action() } catch (_: StorageException) { failed = true }
    check(failed) { "Expected StorageException" }
}
