package com.auditlab.academic.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.Looper
import com.auditlab.domain.StorageException
import java.util.UUID

/** Owned by the future shared I/O scope. Never close while an operation is in flight. */
class AuditDatabase(context: Context, name: String = "auditlab-events.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_EVENTS)
        db.execSQL("CREATE INDEX events_history_order ON events(timestamp_ms DESC, event_id ASC)")
        db.execSQL("""
            CREATE TRIGGER events_capacity BEFORE INSERT ON events
            WHEN (SELECT COUNT(*) FROM events) >= 1000
            BEGIN SELECT RAISE(ABORT, 'event capacity reached'); END
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // An unsupported version must fail without dropping the user's records.
        throw SQLiteException("Unsupported database migration")
    }

    companion object {
        const val VERSION = 1
        internal val CREATE_EVENTS = """
            CREATE TABLE events (
                event_id TEXT NOT NULL PRIMARY KEY CHECK(${uuidCheck("event_id")}),
                timestamp_ms INTEGER NOT NULL CHECK(typeof(timestamp_ms) = 'integer' AND timestamp_ms >= accepted_at_ms),
                kind TEXT NOT NULL CHECK(kind IN ('CALL', 'MESSAGE')),
                direction TEXT NOT NULL CHECK(direction IN ('INCOMING', 'OUTGOING')),
                is_simulated INTEGER NOT NULL CHECK(is_simulated = 1),
                consent_id TEXT NOT NULL CHECK(${uuidCheck("consent_id")}),
                policy_version TEXT NOT NULL CHECK(length(policy_version) BETWEEN 1 AND 40
                    AND substr(policy_version, 1, 1) GLOB '[A-Za-z0-9]'
                    AND policy_version NOT GLOB '*[^A-Za-z0-9_.-]*'),
                accepted_at_ms INTEGER NOT NULL CHECK(typeof(accepted_at_ms) = 'integer' AND accepted_at_ms >= 0)
            )
        """.trimIndent()

        private fun uuidCheck(column: String) = """
            length($column) = 36 AND $column NOT GLOB '*[^0-9a-f-]*'
            AND substr($column, 9, 1) = '-' AND substr($column, 14, 1) = '-'
            AND substr($column, 19, 1) = '-' AND substr($column, 24, 1) = '-'
            AND length(replace($column, '-', '')) = 32
        """.trimIndent()
    }
}

internal fun requireWorkerThread() {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Storage requires a worker thread" }
}

internal fun canonicalUuid(value: String): UUID = UUID.fromString(value).also {
    require(it.toString() == value) { "Noncanonical stored UUID" }
}

/** Map expected platform/decode failures; never return an empty success on failure. */
internal fun <T> storageOperation(action: () -> T): T = try {
    action()
} catch (_: SQLiteException) {
    throw StorageException()
} catch (_: IllegalArgumentException) {
    throw StorageException()
} catch (_: IllegalStateException) {
    throw StorageException()
}

internal fun <T> SQLiteDatabase.independentTransaction(action: () -> T): T {
    // In particular, a future event rollback must not roll back a saved revocation.
    check(!inTransaction()) { "Storage write must own its transaction" }
    beginTransaction()
    return try {
        val value = action()
        setTransactionSuccessful()
        value
    } finally {
        endTransaction()
    }
}
