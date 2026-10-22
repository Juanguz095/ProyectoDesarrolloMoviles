package com.auditlab.academic.tests

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.os.Handler
import android.os.Looper
import com.auditlab.academic.storage.AuditDatabase
import com.auditlab.academic.storage.SQLiteAcceptanceStore
import com.auditlab.domain.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object SQLiteAcceptanceTests {
    fun cases(context: Context): List<InstrumentedCase> {
        fun case(name: String, action: (DatabaseFixture, AuditCoordinator) -> Unit) = InstrumentedCase("SQLiteAcceptanceTests", name) {
            DatabaseFixture(context).use { action(it, SynchronizedAuditCoordinator()) }
        }
        return listOf(
            case("missingDecisionIsPendingAfterReopen") { f, c ->
                check(store(f, c).read() == Acceptance.Pending)
                f.reopen()
                check(store(f, c).read() == Acceptance.Pending)
                check(f.ids().isEmpty())
            },
            case("confirmedAcceptanceSurvivesCloseAndReopen") { f, c ->
                val decision = Acceptance.Granted(consent())
                store(f, c).save(decision)
                f.reopen()
                check(store(f, c).read() == decision)
                check(decisions(f, c).load() == AuditResult.Success(decision))
            },
            case("withdrawalAndPendingCleanupSurviveReopen") { f, c ->
                store(f, c).save(Acceptance.Granted(consent()))
                val revoked = Acceptance.Revoked(null, true)
                store(f, c).save(revoked)
                f.reopen()
                check(store(f, c).read() == revoked)
                check(audit(f, c).record(EventKind.CALL, EventDirection.INCOMING) == AuditResult.Rejected(AuditError.ACCEPTANCE_REQUIRED))
            },
            case("explicitAcceptDoesNotGenerateAnEvent") { f, c ->
                check(decisions(f, c).accept() is AuditResult.Success)
                check(f.ids().isEmpty())
                f.reopen()
                check(audit(f, c).record(EventKind.CALL, EventDirection.OUTGOING) is AuditResult.Success)
            },
            case("pendingCleanupCannotBeOverwrittenByAcceptOrPending") { f, c ->
                val revoked = Acceptance.Revoked(3000, true)
                store(f, c).save(revoked)
                check(decisions(f, c).accept() == AuditResult.Rejected(AuditError.CLEANUP_REQUIRED))
                expectStorage { store(f, c).save(Acceptance.Granted(consent())) }
                expectStorage { store(f, c).save(Acceptance.Pending) }
                f.reopen()
                check(store(f, c).read() == revoked)
            },
            case("completedRevocationAllowsNewExplicitAcceptanceWithoutRestoringHistory") { f, c ->
                val previous = consent(); store(f, c).save(Acceptance.Granted(previous))
                f.repository.insertIfBelowCapacity(event(), 1000)
                check(audit(f, c).revokeAcceptance() is AuditResult.Success)
                f.reopen()
                val result = decisions(f, c).accept()
                check(result is AuditResult.Success && result.value.consentId != previous.consentId)
                check(f.ids().isEmpty())
            },
            case("failedDeleteNeverRollsBackSavedWithdrawalAndCanRetryAfterReopen") { f, c ->
                store(f, c).save(Acceptance.Granted(consent()))
                f.repository.insertIfBelowCapacity(event(), 1000)
                f.database.writableDatabase.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON events BEGIN SELECT RAISE(ABORT, 'test'); END")
                val result = audit(f, c).revokeAcceptance()
                check(result is AuditResult.Success && result.value.deletionStatus == DeletionStatus.PENDING)
                f.reopen()
                check((store(f, c).read() as Acceptance.Revoked).deletionPending)
                check(f.ids().size == 1)
                check(decisions(f, c).accept() == AuditResult.Rejected(AuditError.CLEANUP_REQUIRED))
                f.database.writableDatabase.execSQL("DROP TRIGGER fail_delete")
                val retry = audit(f, c).retryRevokedDeletion()
                check(retry is AuditResult.Success && retry.value.deletionStatus == DeletionStatus.CLEARED)
                f.reopen()
                check(f.ids().isEmpty())
                check(!(store(f, c).read() as Acceptance.Revoked).deletionPending)
            },
            case("failedWithdrawalWriteReportsErrorAndLeavesDataUntouched") { f, c ->
                val previous = Acceptance.Granted(consent()); store(f, c).save(previous)
                f.repository.insertIfBelowCapacity(event(), 1000)
                f.database.writableDatabase.execSQL("CREATE TRIGGER fail_withdrawal BEFORE UPDATE ON acceptance WHEN NEW.state = 'REVOKED' BEGIN SELECT RAISE(ABORT, 'test'); END")
                val service = audit(f, c)
                check(service.revokeAcceptance() == AuditResult.Rejected(AuditError.ACCEPTANCE_WRITE_FAILED))
                check(service.record(EventKind.CALL, EventDirection.INCOMING) == AuditResult.Rejected(AuditError.REVOCATION_NOT_CONFIRMED))
                check(f.ids().size == 1)
                f.reopen()
                // The write failed: do not pretend withdrawal survived restart.
                check(store(f, c).read() == previous)
            },
            case("failedGrantWriteDoesNotChangePendingDecision") { f, c ->
                f.database.writableDatabase.execSQL("CREATE TRIGGER fail_grant BEFORE INSERT ON acceptance WHEN NEW.state = 'GRANTED' BEGIN SELECT RAISE(ABORT, 'test'); END")
                check(decisions(f, c).accept() == AuditResult.Rejected(AuditError.ACCEPTANCE_WRITE_FAILED))
                f.reopen()
                check(store(f, c).read() == Acceptance.Pending)
                check(audit(f, c).record(EventKind.CALL, EventDirection.INCOMING) == AuditResult.Rejected(AuditError.ACCEPTANCE_REQUIRED))
            },
            case("malformedDecisionFailsClosedButCanBeWithdrawn") { f, c ->
                store(f, c).save(Acceptance.Granted(consent()))
                val db = f.database.writableDatabase
                db.execSQL("PRAGMA ignore_check_constraints = ON")
                try { db.execSQL("UPDATE acceptance SET consent_id = 'malformed'") }
                finally { db.execSQL("PRAGMA ignore_check_constraints = OFF") }
                expectStorage { store(f, c).read() }
                check(decisions(f, c).load() == AuditResult.Rejected(AuditError.ACCEPTANCE_READ_FAILED))
                check(audit(f, c).record(EventKind.CALL, EventDirection.INCOMING) == AuditResult.Rejected(AuditError.ACCEPTANCE_READ_FAILED))
                check(audit(f, c).revokeAcceptance() is AuditResult.Success)
                f.reopen()
                check(store(f, c).read() is Acceptance.Revoked)
            },
            case("oldPolicyCannotGenerateUntilExplicitNewAcceptance") { f, c ->
                val old = consent().copy(policyVersion = "old"); store(f, c).save(Acceptance.Granted(old))
                f.reopen()
                check(decisions(f, c).load() == AuditResult.Rejected(AuditError.POLICY_CHANGED))
                check(audit(f, c).record(EventKind.MESSAGE, EventDirection.INCOMING) == AuditResult.Rejected(AuditError.POLICY_CHANGED))
                val accepted = decisions(f, c).accept()
                check(accepted is AuditResult.Success && accepted.value.consentId != old.consentId)
                check(audit(f, c).record(EventKind.MESSAGE, EventDirection.INCOMING) is AuditResult.Success)
            },
            case("migrationOneToTwoPreservesEveryEventIdAndStartsPending") { f, c ->
                repeat(3) { f.repository.insertIfBelowCapacity(event(), 1000) }
                val before = f.ids()
                // Version 1 has the identical events schema/index/trigger and no decision table.
                val db = f.database.writableDatabase
                db.execSQL("DROP TABLE acceptance")
                db.version = 1
                f.reopen()
                check(f.database.readableDatabase.version == AuditDatabase.VERSION)
                check(f.ids() == before)
                check(store(f, c).read() == Acceptance.Pending)
            },
            case("schemaRejectsIncoherentDecisionAndMultipleRows") { f, c ->
                store(f, c).save(Acceptance.Granted(consent()))
                val invalid = listOf(
                    ContentValues().apply { put("singleton", 2) },
                    ContentValues().apply { put("state", "UNKNOWN") },
                    ContentValues().apply { put("state", "REVOKED") },
                    ContentValues().apply { putNull("consent_id") },
                    ContentValues().apply { put("deletion_pending", 1) },
                    ContentValues().apply { put("accepted_at_ms", -1) },
                )
                invalid.forEach { value ->
                    var failed = false
                    try { f.database.writableDatabase.update("acceptance", value, "singleton = ?", arrayOf("1")) }
                    catch (_: SQLiteConstraintException) { failed = true }
                    check(failed)
                }
                check(store(f, c).read() == Acceptance.Granted(consent()))
            },
            case("cleanupConfirmationWriteFailureKeepsWithdrawalAfterReopen") { f, c ->
                store(f, c).save(Acceptance.Granted(consent()))
                f.repository.insertIfBelowCapacity(event(), 1000)
                f.database.writableDatabase.execSQL("CREATE TRIGGER fail_confirmation BEFORE UPDATE ON acceptance WHEN NEW.state = 'REVOKED' AND NEW.deletion_pending = 0 BEGIN SELECT RAISE(ABORT, 'test'); END")
                val result = audit(f, c).revokeAcceptance()
                check(result is AuditResult.Success && result.value.deletionStatus == DeletionStatus.CLEARED_CONFIRMATION_PENDING)
                f.reopen()
                check(f.ids().isEmpty())
                check((store(f, c).read() as Acceptance.Revoked).deletionPending)
                f.database.writableDatabase.execSQL("DROP TRIGGER fail_confirmation")
                check(audit(f, c).retryRevokedDeletion() is AuditResult.Success)
            },
            case("mainThreadCannotReadOrWriteStorage") { f, c ->
                val failures = AtomicReference<List<Throwable>>(); val finished = CountDownLatch(1)
                check(Handler(Looper.getMainLooper()).post {
                    try {
                        val errors = mutableListOf<Throwable>()
                        listOf<() -> Unit>({ store(f, c).read() }, { store(f, c).save(Acceptance.Pending) },
                            { f.repository.latest(100) }).forEach { action ->
                            try { action() } catch (error: Throwable) { errors += error }
                        }
                        failures.set(errors)
                    }
                    finally { finished.countDown() }
                })
                check(finished.await(5, TimeUnit.SECONDS))
                check(failures.get().size == 3 && failures.get().all { it is IllegalStateException })
            },
        )
    }

    private fun consent() = ConsentContext(UUID(0, 10), "1.0", 1000)
    private fun store(f: DatabaseFixture, c: AuditCoordinator) = SQLiteAcceptanceStore(f.database, c)
    private fun decisions(f: DatabaseFixture, c: AuditCoordinator) = AcceptanceService(store(f, c),
        EpochClock { 4000 }, EventIdGenerator { UUID.randomUUID() }, "1.0", c)
    private fun audit(f: DatabaseFixture, c: AuditCoordinator) = AuditService(f.repository, store(f, c),
        EpochClock { 4000 }, EventIdGenerator { UUID.randomUUID() }, "1.0", c)
}
