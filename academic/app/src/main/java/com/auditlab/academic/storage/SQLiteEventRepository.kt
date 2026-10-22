package com.auditlab.academic.storage

import android.content.ContentValues
import android.database.Cursor
import com.auditlab.domain.*

class SQLiteEventRepository(private val database: AuditDatabase) : EventRepository {
    override fun insertIfBelowCapacity(event: Event, capacity: Int): InsertResult {
        require(capacity > 0)
        requireWorkerThread()
        return storageOperation {
            val db = database.writableDatabase
            db.independentTransaction {
                val duplicate = db.query("events", arrayOf("event_id"), "event_id = ?",
                    arrayOf(event.eventId.toString()), null, null, null, "1").use { it.moveToFirst() }
                when {
                    duplicate -> InsertResult.DUPLICATE_ID
                    db.rawQuery("SELECT COUNT(*) FROM events", null).use { it.moveToFirst(); it.getLong(0) } >=
                        minOf(capacity, AuditLimits.MAX_STORED_EVENTS) -> InsertResult.CAPACITY_REACHED
                    else -> {
                        db.insertOrThrow("events", null, values(event))
                        InsertResult.INSERTED
                    }
                }
            }
        }
    }

    override fun latest(limit: Int): List<Event> {
        require(limit > 0)
        requireWorkerThread()
        return storageOperation {
            database.readableDatabase.query("events", COLUMNS, null, null, null, null,
                "timestamp_ms DESC, event_id ASC", minOf(limit, AuditLimits.MAX_HISTORY_EVENTS).toString())
                .use { cursor -> buildList { while (cursor.moveToNext()) add(decode(cursor)) } }
        }
    }

    override fun deleteAll(): Int {
        requireWorkerThread()
        return storageOperation {
            val db = database.writableDatabase
            db.independentTransaction { db.delete("events", null, null) }
        }
    }

    private fun values(event: Event) = ContentValues().apply {
        put("event_id", event.eventId.toString())
        put("timestamp_ms", event.timestampEpochMs)
        put("kind", event.kind.name)
        put("direction", event.direction.name)
        put("is_simulated", 1)
        put("consent_id", event.consent.consentId.toString())
        put("policy_version", event.consent.policyVersion)
        put("accepted_at_ms", event.consent.acceptedAtEpochMs)
    }

    private fun decode(cursor: Cursor): Event {
        check(cursor.getInt(4) == 1)
        val consent = ConsentContext(canonicalUuid(cursor.getString(5)), cursor.getString(6), cursor.getLong(7))
        return Event(canonicalUuid(cursor.getString(0)), cursor.getLong(1), EventKind.valueOf(cursor.getString(2)),
            EventDirection.valueOf(cursor.getString(3)), consent)
    }

    companion object {
        private val COLUMNS = arrayOf("event_id", "timestamp_ms", "kind", "direction", "is_simulated",
            "consent_id", "policy_version", "accepted_at_ms")
    }
}
