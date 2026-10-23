package com.auditlab.academic.tests

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import com.auditlab.academic.*
import com.auditlab.academic.storage.*
import com.auditlab.domain.*
import java.io.FileNotFoundException
import java.util.UUID

internal object PrivacyUiTests {
    fun cases(instrumentation: Instrumentation): List<InstrumentedCase> {
        fun case(name: String, action: (UiFixture) -> Unit) = InstrumentedCase("PrivacyUiTests", name) {
            UiFixture(instrumentation).use { f ->
                f.accept(); f.main { f.button(R.id.call_incoming).performClick() }; f.idle(); action(f)
            }
        }
        return listOf(
            case("clearCancellationPreservesDataAndConfirmedClearPreservesAcceptance") { f ->
                val previous = f.session.state.acceptance; val ids = f.session.state.events.map { it.eventId }
                confirm(f, R.id.clear_button, false)
                f.main { check(f.session.state.acceptance == previous); check(f.session.state.events.map { it.eventId } == ids) }
                confirm(f, R.id.clear_button, true); message(f, "Registros eliminados: 1")
                f.main { check(f.session.state.events.isEmpty()); check(f.session.state.acceptance == previous) }
                reopen(f)
                f.main { check(f.session.state.events.isEmpty()); check(f.session.state.acceptance == previous) }
            },
            case("revocationCancellationChangesNeitherAcceptanceNorEvents") { f ->
                val previous = f.session.state
                confirm(f, R.id.revoke_button, false)
                f.main { check(f.session.state.acceptance == previous.acceptance); check(f.session.state.events == previous.events) }
            },
            case("confirmedRevocationBlocksActionsAndNewAcceptanceHasNewContext") { f ->
                val previous = (f.session.state.acceptance as Acceptance.Granted).context
                confirm(f, R.id.revoke_button, true); message(f, "Aceptación revocada y guardada")
                f.main {
                    check(f.session.state.acceptance is Acceptance.Revoked && f.session.state.events.isEmpty())
                    check(f.events.all { !f.button(it).isEnabled }); check(!f.button(R.id.export_button).isEnabled)
                    check(!f.button(R.id.accept_button).isEnabled)
                    repeat(20) { f.button(R.id.message_outgoing).performClick() }
                }
                f.idle(); reopen(f)
                f.main { check(f.session.state.acceptance is Acceptance.Revoked && f.session.state.events.isEmpty()) }
                f.accept()
                f.main { check((f.session.state.acceptance as Acceptance.Granted).context.consentId != previous.consentId); check(f.session.state.events.isEmpty()) }
            },
            case("deleteFailureKeepsWithdrawalAcrossReopenAndRetryFinishesCleanup") { f ->
                val db = AuditDatabase(instrumentation.targetContext, f.app.databaseName)
                try {
                    db.writableDatabase.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON events BEGIN SELECT RAISE(ABORT, 'test'); END")
                    confirm(f, R.id.revoke_button, true); message(f, "el borrado sigue pendiente")
                    reopen(f)
                    f.main {
                        check((f.session.state.acceptance as Acceptance.Revoked).deletionPending)
                        check(!f.button(R.id.accept_button).isEnabled && !f.button(R.id.export_button).isEnabled)
                    }
                    check(SQLiteEventRepository(db).snapshot().size == 1)
                    db.writableDatabase.execSQL("DROP TRIGGER fail_delete")
                    f.main { f.button(R.id.retry_privacy_button).performClick() }; message(f, "Aceptación revocada y guardada")
                    check(SQLiteEventRepository(db).snapshot().isEmpty())
                    f.main { check(!(f.session.state.acceptance as Acceptance.Revoked).deletionPending); f.session.retryPrivacy() }
                    f.idle(); check(SQLiteEventRepository(db).snapshot().isEmpty())
                } finally { db.close() }
            },
            case("failedWithdrawalSaveBlocksSessionAndExplicitRetrySavesIt") { f ->
                val db = AuditDatabase(instrumentation.targetContext, f.app.databaseName)
                try {
                    db.writableDatabase.execSQL("CREATE TRIGGER fail_withdrawal BEFORE UPDATE ON acceptance WHEN NEW.state = 'REVOKED' BEGIN SELECT RAISE(ABORT, 'test'); END")
                    confirm(f, R.id.revoke_button, true); message(f, "No se pudo guardar la revocación")
                    f.main {
                        check(f.session.state.privacyBlocked)
                        check(f.events.all { !f.button(it).isEnabled })
                        check(!f.button(R.id.accept_button).isEnabled && !f.button(R.id.export_button).isEnabled)
                    }
                    check(SQLiteAcceptanceStore(db, SynchronizedAuditCoordinator()).read() is Acceptance.Granted)
                    check(SQLiteEventRepository(db).snapshot().size == 1)
                    db.writableDatabase.execSQL("DROP TRIGGER fail_withdrawal")
                    f.main { f.button(R.id.retry_privacy_button).performClick() }; message(f, "Aceptación revocada y guardada")
                    f.main { check(!f.session.state.privacyBlocked && f.session.state.acceptance is Acceptance.Revoked) }
                } finally { db.close() }
            },
            case("failedCleanupConfirmationDoesNotReactivateAndCanRetry") { f ->
                val db = AuditDatabase(instrumentation.targetContext, f.app.databaseName)
                try {
                    db.writableDatabase.execSQL("CREATE TRIGGER fail_confirmation BEFORE UPDATE ON acceptance WHEN NEW.state = 'REVOKED' AND NEW.deletion_pending = 0 BEGIN SELECT RAISE(ABORT, 'test'); END")
                    confirm(f, R.id.revoke_button, true); message(f, "falta confirmar el fin de limpieza")
                    f.main { check((f.session.state.acceptance as Acceptance.Revoked).deletionPending) }
                    check(SQLiteEventRepository(db).snapshot().isEmpty())
                    db.writableDatabase.execSQL("DROP TRIGGER fail_confirmation")
                    f.main { f.button(R.id.retry_privacy_button).performClick() }; message(f, "Aceptación revocada y guardada")
                } finally { db.close() }
            },
            case("revocationInvalidatesLatePickerCallbackBeforeOpeningItsDocument") { f -> stalePicker(f, revoke = true) },
            case("clearInvalidatesLatePickerCallbackAndKeepsAcceptance") { f -> stalePicker(f, revoke = false) },
            case("unreadableDecisionCanStillBeWithdrawnThroughTheScreen") { f ->
                val db = AuditDatabase(instrumentation.targetContext, f.app.databaseName)
                try {
                    db.writableDatabase.execSQL("PRAGMA ignore_check_constraints = ON")
                    try { db.writableDatabase.execSQL("UPDATE acceptance SET consent_id = 'malformed'") }
                    finally { db.writableDatabase.execSQL("PRAGMA ignore_check_constraints = OFF") }
                    f.main { f.session.refresh() }; message(f, "No se pudo leer la aceptación")
                    confirm(f, R.id.revoke_button, true); message(f, "Aceptación revocada y guardada")
                    check(SQLiteAcceptanceStore(db, SynchronizedAuditCoordinator()).read() is Acceptance.Revoked)
                    check(SQLiteEventRepository(db).snapshot().isEmpty())
                } finally { db.close() }
            },
        )
    }

    private fun message(f: UiFixture, expected: String) = f.await {
        var ready = false; f.main { ready = !f.session.state.busy && f.session.state.message.contains(expected) }; ready
    }
    private fun reopen(f: UiFixture) {
        f.main { f.activity.finish() }; f.await { f.app.foreground == null }; f.open(); f.idle()
    }
    private fun confirm(f: UiFixture, button: Int, positive: Boolean) {
        f.main { check(f.button(button).isEnabled); f.button(button).performClick() }
        val automation = f.instrumentation.uiAutomation
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = info
        var node: AccessibilityNodeInfo? = null
        f.await {
            node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(if (positive) "android:id/button1" else "android:id/button2")?.firstOrNull()
            node != null
        }
        check(checkNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        f.instrumentation.waitForIdleSync()
    }

    private fun stalePicker(f: UiFixture, revoke: Boolean) {
        val uri: Uri = DocumentsContract.buildDocumentUri("com.auditlab.academic.test.documents", "late-${UUID.randomUUID()}")
        grantTestDocument(f.instrumentation, uri)
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
                check(if (revoke) f.session.revoke() else f.session.clearHistory())
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        f.instrumentation.addMonitor(monitor)
        try {
            f.main { f.button(R.id.export_button).performClick() }; message(f, "La exportación ya no es válida")
            f.main {
                check(f.session.state.events.isEmpty())
                check(if (revoke) f.session.state.acceptance is Acceptance.Revoked else f.session.state.acceptance is Acceptance.Granted)
            }
            var absent = false
            try { f.instrumentation.targetContext.contentResolver.openInputStream(uri)?.close() }
            catch (_: FileNotFoundException) { absent = true }
            check(absent)
        } finally { f.instrumentation.removeMonitor(monitor) }
    }
}
