package com.auditlab.academic

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.view.WindowInsets
import android.view.View
import android.widget.*
import com.auditlab.domain.*
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

class MainActivity : Activity() {
    private lateinit var session: AuditSession
    private var screen = ScreenData()
    private val observer: (ScreenData) -> Unit = { screen = it; render() }
    private val eventButtons = listOf(R.id.call_incoming, R.id.call_outgoing, R.id.message_incoming, R.id.message_outgoing)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        session = (application as AuditApplication).auditSession
        applyInsets()
        findViewById<CheckBox>(R.id.consent_checkbox).setOnCheckedChangeListener { _, _ -> renderControls() }
        findViewById<Button>(R.id.accept_button).setOnClickListener {
            if (findViewById<CheckBox>(R.id.consent_checkbox).isChecked) session.accept()
        }
        findViewById<Button>(R.id.decline_button).setOnClickListener {
            findViewById<CheckBox>(R.id.consent_checkbox).isChecked = false
            findViewById<TextView>(R.id.operation_message).setText(R.string.declined)
        }
        eventButtons.forEachIndexed { index, id -> findViewById<Button>(id).setOnClickListener {
            session.record(if (index < 2) EventKind.CALL else EventKind.MESSAGE,
                if (index % 2 == 0) EventDirection.INCOMING else EventDirection.OUTGOING)
        } }
        findViewById<Button>(R.id.refresh_button).setOnClickListener { session.refresh() }
    }

    override fun onStart() {
        super.onStart()
        session.observe(observer)
        if (!session.state.busy) session.refresh()
    }

    override fun onStop() {
        session.removeObserver(observer)
        super.onStop()
    }

    @Suppress("DEPRECATION")
    private fun applyInsets() {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        findViewById<View>(R.id.screen_root).setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        findViewById<View>(R.id.screen_root).requestApplyInsets()
    }

    private fun render() {
        findViewById<TextView>(R.id.acceptance_status).setText(if (screen.busy && screen.acceptance == null) R.string.loading else when (val state = screen.acceptance) {
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
        val canAccept = !screen.busy && (state == Acceptance.Pending || state is Acceptance.Revoked && !state.deletionPending)
        findViewById<CheckBox>(R.id.consent_checkbox).isEnabled = canAccept
        findViewById<Button>(R.id.accept_button).isEnabled = canAccept && findViewById<CheckBox>(R.id.consent_checkbox).isChecked
        findViewById<Button>(R.id.decline_button).isEnabled = canAccept
        eventButtons.forEach { findViewById<Button>(it).isEnabled = !screen.busy && state is Acceptance.Granted }
        findViewById<Button>(R.id.refresh_button).isEnabled = !screen.busy
        findViewById<ProgressBar>(R.id.busy_indicator).visibility = if (screen.busy) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        if (isFinishing) session.closeDatabaseWhenIdle()
        super.onDestroy()
    }
}
