package com.auditlab.academic

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import com.auditlab.academic.storage.*
import com.auditlab.domain.*
import java.util.UUID

data class ExportRequest(val id: String, val permit: ExportPermit)
data class ScreenData(val acceptance: Acceptance? = null, val events: List<Event> = emptyList(), val message: String = "", val busy: Boolean = false, val exportRequest: ExportRequest? = null, val privacyBlocked: Boolean = false)

/** Process-owned dependencies. Only observers reference Views and they detach in onStop. */
class AuditSession(context: Context, databaseName: String = "auditlab-events.db") {
    private val context = context.applicationContext
    private val queue = SerialOperationQueue()
    private val main = Handler(Looper.getMainLooper())
    private val observers = linkedSetOf<(ScreenData) -> Unit>()
    private val database = AuditDatabase(this.context, databaseName)
    private val coordinator = SynchronizedAuditCoordinator()
    private val store = SQLiteAcceptanceStore(database, coordinator)
    private val clock = EpochClock { System.currentTimeMillis() }
    private val ids = EventIdGenerator { UUID.randomUUID() }
    private val decisions = AcceptanceService(store, clock, ids, "1.0", coordinator)
    private val repository = SQLiteEventRepository(database)
    private val audit = AuditService(repository, store, clock, ids, "1.0", coordinator)
    private val exporter = ExportService(audit, repository, coordinator)
    @Volatile private var pendingExport: ExportRequest? = null
    @Volatile private var withdrawalUnconfirmed = false
    var state = ScreenData()
        private set

    fun observe(observer: (ScreenData) -> Unit) { requireMain(); observers += observer; observer(state) }
    fun removeObserver(observer: (ScreenData) -> Unit) { requireMain(); observers -= observer }
    fun refresh(preserveMessage: Boolean = false): Boolean {
        val message = if (preserveMessage) state.message else ""
        return perform { readScreen(message) }
    }
    fun accept(): Boolean {
        requireMain()
        if (withdrawalUnconfirmed) return false
        return perform {
            val result = decisions.accept()
            readScreen(if (result is AuditResult.Success) text(R.string.accepted) else errorText((result as AuditResult.Rejected).reason))
        }
    }
    fun record(kind: EventKind, direction: EventDirection) = perform {
        val result = audit.record(kind, direction)
        readScreen(if (result is AuditResult.Success) text(R.string.event_saved) else errorText((result as AuditResult.Rejected).reason))
    }

    fun prepareExport(): Boolean {
        requireMain()
        if (pendingExport != null || withdrawalUnconfirmed) return false
        return perform {
            when (val prepared = exporter.prepare()) {
                is AuditResult.Rejected -> readScreen(errorText(prepared.reason))
                is AuditResult.Success -> {
                    val loaded = readScreen(text(R.string.export_choose))
                    if (loaded.acceptance is Acceptance.Granted) pendingExport = ExportRequest(UUID.randomUUID().toString(), prepared.value)
                    loaded
                }
            }
        }
    }

    fun receiveExportResult(requestId: String, uri: Uri?, cancelled: Boolean) {
        requireMain()
        if (state.busy) {
            queue.afterPending { main.post { receiveExportResult(requestId, uri, cancelled) } }
            return
        }
        val request = pendingExport
        if (request?.id == requestId) pendingExport = null
        perform {
            when {
                cancelled -> readScreen(text(R.string.export_cancelled))
                request == null || request.id != requestId -> readScreen(text(R.string.export_expired))
                uri == null || uri.scheme != "content" || uri.authority.isNullOrBlank() -> readScreen(text(R.string.export_failed))
                else -> {
                    var opened = false
                    val result = exporter.write(request.permit, CsvDestination { bytes ->
                        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No document stream")
                        // A refused open has not touched the document: never delete its old contents.
                        opened = true
                        stream.use { it.write(bytes); it.flush() }
                    })
                    if (result is AuditResult.Rejected && opened) {
                        try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (_: Exception) { /* Provider may refuse cleanup. */ }
                    }
                    readScreen(if (result is AuditResult.Success) context.getString(R.string.export_success, result.value.eventCount)
                        else errorText((result as AuditResult.Rejected).reason))
                }
            }
        }
    }

    fun clearHistory(): Boolean {
        requireMain()
        if (state.busy) return false
        pendingExport = null
        return perform {
            when (val result = audit.clearHistory()) {
                is AuditResult.Success -> readScreen(context.getString(R.string.clear_success, result.value))
                is AuditResult.Rejected -> readScreen(errorText(result.reason))
            }
        }
    }

    fun revoke(): Boolean {
        requireMain()
        if (state.busy) return false
        // Invalidate the picker and block protected controls immediately on confirmed intent.
        pendingExport = null
        withdrawalUnconfirmed = true
        return perform {
            val result = audit.revokeAcceptance()
            if (result is AuditResult.Success) withdrawalUnconfirmed = false
            readScreen(revocationMessage(result))
        }
    }

    fun retryPrivacy(): Boolean {
        requireMain()
        if (withdrawalUnconfirmed) return revoke()
        return perform { readScreen(revocationMessage(audit.retryRevokedDeletion())) }
    }

    private fun revocationMessage(result: AuditResult<RevocationReport>): String = when (result) {
        is AuditResult.Rejected -> errorText(result.reason)
        is AuditResult.Success -> when (result.value.deletionStatus) {
            DeletionStatus.PENDING -> text(R.string.revoked_delete_pending)
            DeletionStatus.CLEARED_CONFIRMATION_PENDING -> text(R.string.revoked_confirmation_pending)
            DeletionStatus.CLEARED -> context.getString(R.string.revoked_success, result.value.deletedEvents ?: 0)
        }
    }

    private fun perform(action: () -> ScreenData): Boolean {
        requireMain()
        if (state.busy) return false
        var result = ScreenData()
        val accepted = queue.tryExecute({
            result = try { action() } catch (_: RuntimeException) { ScreenData(message = text(R.string.operation_failed)) }
        }, { main.post { publish(result) } })
        if (accepted) publish(state.copy(busy = true))
        return accepted
    }

    private fun readScreen(message: String = ""): ScreenData {
        val loaded = decisions.load()
        if (loaded is AuditResult.Rejected) return ScreenData(
            if (loaded.reason == AuditError.POLICY_CHANGED) Acceptance.Pending else null, message = combine(message, errorText(loaded.reason)))
        val decision = (loaded as AuditResult.Success).value
        if (decision !is Acceptance.Granted) return ScreenData(decision, message = message)
        return when (val history = audit.history()) {
            is AuditResult.Success -> ScreenData(decision, history.value, message)
            is AuditResult.Rejected -> ScreenData(message = combine(message, errorText(history.reason)))
        }
    }

    private fun combine(first: String, second: String) = listOf(first, second).filter { it.isNotBlank() }.distinct().joinToString("\n")
    private fun publish(value: ScreenData) { requireMain(); state = value.copy(exportRequest = pendingExport, privacyBlocked = withdrawalUnconfirmed); observers.toList().forEach { it(state) } }
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    private fun text(id: Int) = context.getString(id)
    private fun errorText(reason: AuditError): String = text(when (reason) {
        AuditError.ACCEPTANCE_REQUIRED -> R.string.error_acceptance
        AuditError.POLICY_CHANGED -> R.string.error_policy
        AuditError.ACCEPTANCE_READ_FAILED -> R.string.error_read
        AuditError.ACCEPTANCE_WRITE_FAILED -> R.string.error_write
        AuditError.CAPACITY_REACHED -> R.string.error_capacity
        AuditError.INVALID_TIMESTAMP, AuditError.CLOCK_UNAVAILABLE -> R.string.error_clock
        AuditError.ID_GENERATION_FAILED, AuditError.DUPLICATE_EVENT_ID -> R.string.error_id
        AuditError.CLEANUP_REQUIRED -> R.string.error_cleanup
        AuditError.REVOCATION_NOT_CONFIRMED -> R.string.error_revocation
        AuditError.NO_REVOKED_ACCEPTANCE -> R.string.error_no_revocation
        AuditError.STORAGE_UNAVAILABLE, AuditError.INVALID_STORAGE_RESPONSE -> R.string.error_storage
        AuditError.EXPORT_EXPIRED -> R.string.export_expired
        AuditError.EXPORT_WRITE_FAILED -> R.string.export_failed
    })

    fun closeDatabaseWhenIdle() { queue.afterPending { database.close() } }
    fun dispose(completed: () -> Unit) { queue.closeAfterPending { database.close(); completed() } }
}
