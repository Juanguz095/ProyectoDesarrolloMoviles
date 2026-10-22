package com.auditlab.academic

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.*
import com.auditlab.academic.storage.*
import com.auditlab.domain.*
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var database: AuditDatabase
    private lateinit var decisions: AcceptanceService
    private lateinit var audit: AuditService
    private var screen = ScreenData(null, emptyList())
    private var busy = false
    private val eventButtons = listOf(R.id.call_incoming, R.id.call_outgoing, R.id.message_incoming, R.id.message_outgoing)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        database = AuditDatabase(applicationContext)
        val coordinator = SynchronizedAuditCoordinator()
        val store = SQLiteAcceptanceStore(database, coordinator)
        val clock = EpochClock { System.currentTimeMillis() }
        val ids = EventIdGenerator { UUID.randomUUID() }
        decisions = AcceptanceService(store, clock, ids, "1.0", coordinator)
        audit = AuditService(SQLiteEventRepository(database), store, clock, ids, "1.0", coordinator)
        findViewById<CheckBox>(R.id.consent_checkbox).setOnCheckedChangeListener { _, _ -> renderControls() }
        findViewById<Button>(R.id.accept_button).setOnClickListener {
            if (findViewById<CheckBox>(R.id.consent_checkbox).isChecked) perform {
                val result = decisions.accept()
                readScreen(if (result is AuditResult.Success) getString(R.string.accepted) else errorText((result as AuditResult.Rejected).reason))
            }
        }
        findViewById<Button>(R.id.decline_button).setOnClickListener {
            findViewById<CheckBox>(R.id.consent_checkbox).isChecked = false
            findViewById<TextView>(R.id.operation_message).setText(R.string.declined)
        }
        eventButtons.forEachIndexed { index, id -> findViewById<Button>(id).setOnClickListener {
            perform {
                val kind = if (index < 2) EventKind.CALL else EventKind.MESSAGE
                val direction = if (index % 2 == 0) EventDirection.INCOMING else EventDirection.OUTGOING
                val result = audit.record(kind, direction)
                readScreen(if (result is AuditResult.Success) getString(R.string.event_saved) else errorText((result as AuditResult.Rejected).reason))
            }
        } }
        findViewById<Button>(R.id.refresh_button).setOnClickListener { perform { readScreen() } }
        perform { readScreen() }
    }

    private fun readScreen(message: String = ""): ScreenData {
        val loaded = decisions.load()
        if (loaded is AuditResult.Rejected) {
            return ScreenData(if (loaded.reason == AuditError.POLICY_CHANGED) Acceptance.Pending else null,
                emptyList(), errorText(loaded.reason))
        }
        val state = (loaded as AuditResult.Success).value
        if (state !is Acceptance.Granted) return ScreenData(state, emptyList(), message)
        return when (val history = audit.history()) {
            is AuditResult.Success -> ScreenData(state, history.value, message)
            is AuditResult.Rejected -> ScreenData(null, emptyList(), errorText(history.reason))
        }
    }

    private fun perform(action: () -> ScreenData) {
        if (busy) return
        busy = true
        renderControls()
        worker.execute {
            val result = try { action() } catch (_: RuntimeException) {
                ScreenData(null, emptyList(), getString(R.string.operation_failed))
            }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    screen = result; busy = false; render()
                }
            }
        }
    }

    private fun render() {
        findViewById<TextView>(R.id.acceptance_status).setText(when (val state = screen.acceptance) {
            Acceptance.Pending -> R.string.status_pending
            is Acceptance.Granted -> R.string.status_active
            is Acceptance.Revoked -> if (state.deletionPending) R.string.status_cleanup else R.string.status_revoked
            null -> R.string.status_error
        })
        findViewById<TextView>(R.id.operation_message).text = screen.message
        val rows = findViewById<LinearLayout>(R.id.history_rows); rows.removeAllViews()
        screen.events.forEach { event ->
            val label = when (event.kind to event.direction) {
                EventKind.CALL to EventDirection.INCOMING -> R.string.call_incoming
                EventKind.CALL to EventDirection.OUTGOING -> R.string.call_outgoing
                EventKind.MESSAGE to EventDirection.INCOMING -> R.string.message_incoming
                else -> R.string.message_outgoing
            }
            rows.addView(TextView(this).apply {
                text = getString(R.string.event_row, getString(label), DateFormat.getDateTimeInstance().format(Date(event.timestampEpochMs)),
                    TimeZone.getDefault().id, event.eventId.toString())
                textSize = 15f; setPadding(0, 18, 0, 18); setTextIsSelectable(true)
            })
        }
        findViewById<TextView>(R.id.history_empty).apply {
            visibility = if (screen.events.isEmpty()) View.VISIBLE else View.GONE
            setText(if (screen.acceptance is Acceptance.Granted) R.string.history_empty else R.string.history_locked)
        }
        renderControls()
    }

    private fun renderControls() {
        val state = screen.acceptance
        val canAccept = !busy && (state == Acceptance.Pending || state is Acceptance.Revoked && !state.deletionPending)
        findViewById<CheckBox>(R.id.consent_checkbox).isEnabled = canAccept
        findViewById<Button>(R.id.accept_button).isEnabled = canAccept && findViewById<CheckBox>(R.id.consent_checkbox).isChecked
        findViewById<Button>(R.id.decline_button).isEnabled = canAccept
        eventButtons.forEach { findViewById<Button>(it).isEnabled = !busy && state is Acceptance.Granted }
        findViewById<Button>(R.id.refresh_button).isEnabled = !busy
        findViewById<ProgressBar>(R.id.busy_indicator).visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun errorText(reason: AuditError): String = getString(when (reason) {
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

    override fun onDestroy() {
        if (::database.isInitialized) worker.execute { database.close() }
        worker.shutdown()
        super.onDestroy()
    }

    private data class ScreenData(val acceptance: Acceptance?, val events: List<Event>, val message: String = "")
}
