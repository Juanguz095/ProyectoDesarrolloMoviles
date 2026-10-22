package com.auditlab.academic

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.auditlab.academic.storage.*
import com.auditlab.domain.*
import java.util.UUID

data class ScreenData(val acceptance: Acceptance? = null, val events: List<Event> = emptyList(), val message: String = "", val busy: Boolean = false)

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
    private val audit = AuditService(SQLiteEventRepository(database), store, clock, ids, "1.0", coordinator)
    var state = ScreenData()
        private set

    fun observe(observer: (ScreenData) -> Unit) { requireMain(); observers += observer; observer(state) }
    fun removeObserver(observer: (ScreenData) -> Unit) { requireMain(); observers -= observer }
    fun refresh() = perform { readScreen() }
    fun accept() = perform {
        val result = decisions.accept()
        readScreen(if (result is AuditResult.Success) text(R.string.accepted) else errorText((result as AuditResult.Rejected).reason))
    }
    fun record(kind: EventKind, direction: EventDirection) = perform {
        val result = audit.record(kind, direction)
        readScreen(if (result is AuditResult.Success) text(R.string.event_saved) else errorText((result as AuditResult.Rejected).reason))
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
            if (loaded.reason == AuditError.POLICY_CHANGED) Acceptance.Pending else null, message = errorText(loaded.reason))
        val decision = (loaded as AuditResult.Success).value
        if (decision !is Acceptance.Granted) return ScreenData(decision, message = message)
        return when (val history = audit.history()) {
            is AuditResult.Success -> ScreenData(decision, history.value, message)
            is AuditResult.Rejected -> ScreenData(message = errorText(history.reason))
        }
    }

    private fun publish(value: ScreenData) { requireMain(); state = value; observers.toList().forEach { it(value) } }
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
    })

    fun closeDatabaseWhenIdle() { queue.afterPending { database.close() } }
    fun dispose(completed: () -> Unit) { queue.closeAfterPending { database.close(); completed() } }
}
