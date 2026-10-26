package com.auditlab.agent

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.auditlab.common.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomAgentJournalTest {
    private val url = "https://qa.example.invalid"
    private val consent = ConsentContext(UUID.randomUUID().toString(), "mvp-1", "2026-10-25T10:00:00Z")
    private val credential = StoredCredential(UUID.randomUUID().toString(), "fixture-only-never-in-room", consent, url)
    private fun event(at: String = "2026-10-25T10:01:00Z") = EventDraft(UUID.randomUUID().toString(), at,
        "app_lifecycle", "occurred", credential.deviceId, consent, mapOf("appEventCode" to "agent_started"))
    private fun io(action: () -> Unit) {
        val pool = Executors.newSingleThreadExecutor()
        try { pool.submit(Callable { action() }).get(90, TimeUnit.SECONDS) } finally { pool.shutdownNow() }
    }
    private fun fixture(capacity: Int = 10_000, action: (RoomAgentJournal, AgentDatabase, Context, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "qa-room-${UUID.randomUUID()}.db"
        io {
            val db = AgentDatabase.open(context, name)
            try { action(RoomAgentJournal(db, capacity), db, context, name) }
            finally { db.close(); context.deleteDatabase(name) }
        }
    }
    @Test fun `confirmed event and registration survive close and reopen`() = fixture { journal, db, context, name ->
        journal.activate(credential, url); val e = event(); journal.enqueue(e); db.close()
        val reopened = AgentDatabase.open(context, name)
        try {
            val j = RoomAgentJournal(reopened)
            assertEquals(AgentPhase.ACTIVE, j.registration()?.phase)
            assertEquals(e, j.pending(100).single()); assertEquals(QueueStats(1, 0, 0), j.stats())
            assertFalse(EventCodec.encode(e).contains(credential.deviceToken))
        } finally { reopened.close() }
    }
    @Test fun `acknowledgement and permanent rejection preserve statuses`() = fixture { j, _, _, _ ->
        j.activate(credential, url); val first = event(); val second = event(); j.enqueue(first); j.enqueue(second)
        j.acknowledge(listOf(first.eventId)); j.reject(listOf(second.eventId), "INVALID_EVENT")
        assertEquals(QueueStats(0, 1, 1), j.stats()); assertTrue(j.pending(100).isEmpty())
    }
    @Test fun `capacity and duplicate IDs never overwrite an unsent event`() = fixture(2) { j, _, _, _ ->
        j.activate(credential, url); val e = event(); j.enqueue(e); j.enqueue(event())
        val ids = j.pending(100).map { it.eventId }
        assertThrows(QueueFull::class.java) { j.enqueue(event()) }
        assertThrows(Exception::class.java) { j.enqueue(e.copy(direction = "inbound")) }
        assertEquals(ids, j.pending(100).map { it.eventId })
    }
    @Test fun `sent history is evicted before refusing a valid new event`() = fixture(2) { j, _, _, _ ->
        j.activate(credential, url); val e = event(); j.enqueue(e); j.acknowledge(listOf(e.eventId))
        j.enqueue(event()); j.enqueue(event()); assertEquals(QueueStats(2, 0, 0), j.stats())
    }
    @Test fun `retention preserves the exact cutoff and removes only older events`() = fixture { j, _, _, _ ->
        j.activate(credential, url)
        val old = event("2026-10-25T10:00:00Z"); val valid = event("2026-10-26T10:00:00Z")
        j.enqueue(old); j.enqueue(valid)
        assertEquals(1, j.prune(Instant.parse(valid.timestamp))); assertEquals(listOf(valid), j.pending(100))
    }
    @Test fun `persisted withdrawal blocks capture before cleanup and after reopen`() = fixture { j, db, context, name ->
        j.activate(credential, url); j.enqueue(event()); j.beginRevocation(credential.deviceId, url, false)
        assertThrows(StorageFailure::class.java) { j.enqueue(event()) }; db.close()
        val reopened = AgentDatabase.open(context, name)
        try {
            val second = RoomAgentJournal(reopened)
            assertEquals(AgentPhase.REVOKING, second.registration()?.phase); assertEquals(1, second.stats().pending)
            second.deleteEvents(); second.completeRevocation(false)
            assertEquals(AgentPhase.LOCAL_ONLY, second.registration()?.phase); assertEquals(QueueStats(), second.stats())
            second.reset(); assertNull(second.registration())
        } finally { reopened.close() }
    }
    @Test fun `foreign consent is rejected in the insertion transaction`() = fixture { j, _, _, _ ->
        j.activate(credential, url)
        assertThrows(StorageFailure::class.java) { j.enqueue(event().copy(sourceDevice = UUID.randomUUID().toString())) }
        assertThrows(StorageFailure::class.java) { j.enqueue(event().copy(consent = consent.copy(consentId = UUID.randomUUID().toString()))) }
        assertEquals(QueueStats(), j.stats())
    }
    @Test fun `corrupt payload does not appear as an empty successful history`() = fixture { j, db, _, _ ->
        j.activate(credential, url); j.enqueue(event())
        db.openHelper.writableDatabase.execSQL("UPDATE agent_outbox SET payload_json='invalid'")
        assertThrows(StorageFailure::class.java) { j.pending(100) }; assertEquals(1, db.dao().total())
        j.beginRevocation(credential.deviceId, url, false); j.deleteEvents(); j.completeRevocation(false)
        assertEquals(QueueStats(), j.stats())
    }
    @Test fun `numeric corruption and unknown delivery state are refused`() = fixture { j, db, _, _ ->
        j.activate(credential, url); j.enqueue(event())
        db.openHelper.writableDatabase.execSQL("UPDATE agent_outbox SET timestamp_ms=timestamp_ms+0.5")
        assertThrows(StorageFailure::class.java) { j.pending(100) }
        db.openHelper.writableDatabase.execSQL("UPDATE agent_registration SET remote=4294967297")
        assertThrows(StorageFailure::class.java) { j.registration() }
        j.beginRevocation(credential.deviceId, url, false); j.deleteEvents(); j.completeRevocation()
        assertEquals(AgentPhase.REVOKED, j.registration()?.phase)
    }
    @Test fun `two connections enforce the same queue capacity`() = fixture(10) { j, db, context, name ->
        j.activate(credential, url)
        val other = AgentDatabase.open(context, name)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val stores = listOf(j, RoomAgentJournal(other, 10))
            val tasks = (0 until 20).map { index -> pool.submit(Callable {
                try { stores[index % 2].enqueue(event()); true } catch (_: QueueFull) { false }
            }) }
            assertEquals(10, tasks.count { it.get(20, TimeUnit.SECONDS) }); assertEquals(10, db.dao().total())
        } finally { pool.shutdownNow(); other.close() }
    }
    @Test fun `ten thousand queued events are bounded and paged in batches of one hundred`() = fixture { j, db, _, _ ->
        j.activate(credential, url)
        db.runInTransaction { repeat(10_000) { j.enqueue(event()) } }
        assertEquals(10_000, j.stats().pending); assertEquals(100, j.pending(100).size)
        assertThrows(QueueFull::class.java) { j.enqueue(event()) }
    }
    @Test fun `schema one migrates preserving the event and registration`() {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "qa-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        io {
            val e = event()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("CREATE TABLE agent_registration (singleton INTEGER NOT NULL PRIMARY KEY,device_id TEXT NOT NULL,server_url TEXT NOT NULL,consent_json TEXT,phase TEXT NOT NULL,remote INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE agent_outbox (event_id TEXT NOT NULL PRIMARY KEY,payload_json TEXT NOT NULL,timestamp_ms INTEGER NOT NULL,delivery TEXT NOT NULL)")
                db.execSQL("CREATE INDEX index_agent_outbox_delivery_timestamp_ms_event_id ON agent_outbox(delivery,timestamp_ms,event_id)")
                db.execSQL("INSERT INTO agent_registration VALUES(1,?,?,?,'ACTIVE',0)", arrayOf(credential.deviceId, url, EventCodec.consent(consent).toString()))
                db.execSQL("INSERT INTO agent_outbox VALUES(?,?,?,'PENDING')", arrayOf<Any>(e.eventId, EventCodec.encode(e), Instant.parse(e.timestamp).toEpochMilli()))
                db.version = 1
            }
            val migrated = AgentDatabase.open(context, name)
            try {
                val j = RoomAgentJournal(migrated)
                assertEquals(e, j.pending(100).single()); assertEquals(AgentPhase.ACTIVE, j.registration()?.phase)
                assertEquals(0L, migrated.dao().pending(1).single().attempts)
            } finally { migrated.close(); context.deleteDatabase(name) }
        }
    }
}
