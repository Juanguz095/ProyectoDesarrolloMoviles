package com.auditlab.academic.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.auditlab.domain.*

/** Same database, independent transactions: failed event deletion cannot undo withdrawal. */
class SQLiteAcceptanceStore(
    private val database: AuditDatabase,
    private val coordinator: AuditCoordinator,
) : AcceptanceStore {
    override fun read(): Acceptance {
        requireWorkerThread()
        return coordinator.exclusive { storageOperation { readFrom(database.readableDatabase) } }
    }

    override fun save(acceptance: Acceptance) {
        requireWorkerThread()
        coordinator.exclusive {
            storageOperation {
                val db = database.writableDatabase
                db.independentTransaction {
                    // A new withdrawal must still be possible when the old decision cannot decode.
                    if (!(acceptance is Acceptance.Revoked && acceptance.deletionPending)) {
                        val previous = readFrom(db)
                        if (previous is Acceptance.Revoked && previous.deletionPending && acceptance !is Acceptance.Revoked) {
                            throw StorageException()
                        }
                        if (acceptance is Acceptance.Revoked && !acceptance.deletionPending &&
                            (previous !is Acceptance.Revoked || previous.revokedAtEpochMs != acceptance.revokedAtEpochMs)) {
                            throw StorageException()
                        }
                    }
                    val updated = db.update("acceptance", values(acceptance), "singleton = ?", arrayOf("1"))
                    if (updated == 0) db.insertOrThrow("acceptance", null, values(acceptance))
                }
            }
        }
    }

    private fun readFrom(db: SQLiteDatabase): Acceptance = db.query("acceptance", COLUMNS, "singleton = ?",
        arrayOf("1"), null, null, null).use { cursor ->
        if (!cursor.moveToFirst()) return@use Acceptance.Pending
        val state = cursor.getString(0)
        val pending = cursor.getInt(5)
        check(pending == 0 || pending == 1)
        when (state) {
            "PENDING" -> {
                check((1..4).all { cursor.isNull(it) } && pending == 0)
                Acceptance.Pending
            }
            "GRANTED" -> {
                check((1..3).none { cursor.isNull(it) } && cursor.isNull(4) && pending == 0)
                Acceptance.Granted(ConsentContext(canonicalUuid(cursor.getString(1)), cursor.getString(2), cursor.getLong(3)))
            }
            "REVOKED" -> {
                check((1..3).all { cursor.isNull(it) })
                Acceptance.Revoked(if (cursor.isNull(4)) null else cursor.getLong(4), pending == 1)
            }
            else -> throw StorageException()
        }
    }

    private fun values(state: Acceptance) = ContentValues().apply {
        put("singleton", 1)
        putNull("consent_id"); putNull("policy_version"); putNull("accepted_at_ms"); putNull("revoked_at_ms")
        put("deletion_pending", 0)
        when (state) {
            Acceptance.Pending -> put("state", "PENDING")
            is Acceptance.Granted -> {
                put("state", "GRANTED")
                put("consent_id", state.context.consentId.toString())
                put("policy_version", state.context.policyVersion)
                put("accepted_at_ms", state.context.acceptedAtEpochMs)
            }
            is Acceptance.Revoked -> {
                put("state", "REVOKED")
                state.revokedAtEpochMs?.let { put("revoked_at_ms", it) }
                put("deletion_pending", if (state.deletionPending) 1 else 0)
            }
        }
    }

    companion object {
        private val COLUMNS = arrayOf("state", "consent_id", "policy_version", "accepted_at_ms", "revoked_at_ms", "deletion_pending")
    }
}
