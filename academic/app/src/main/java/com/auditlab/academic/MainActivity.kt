package com.auditlab.academic

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.view.WindowInsets
import android.view.View
import android.content.Intent
import android.widget.*
import com.auditlab.domain.*
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

class MainActivity : Activity() {
    private lateinit var session: AuditSession
    private var screen = ScreenData()
    private val observer: (ScreenData) -> Unit = { screen = it; render() }
    private var exportRequestId: String? = null
    private var exportRequestCode = 100
    private var nextExportCode = 101
    private var resumed = false
    private val eventButtons = listOf(R.id.call_incoming, R.id.call_outgoing, R.id.message_incoming, R.id.message_outgoing)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        exportRequestId = savedInstanceState?.getString("exportRequestId")
        exportRequestCode = savedInstanceState?.getInt("exportRequestCode", 100) ?: 100
        nextExportCode = savedInstanceState?.getInt("nextExportCode", 101) ?: 101
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
        findViewById<Button>(R.id.export_button).setOnClickListener { session.prepareExport() }
    }

    override fun onStart() {
        super.onStart()
        session.observe(observer)
        if (exportRequestId != null && session.state.exportRequest == null) {
            val expired = checkNotNull(exportRequestId)
            exportRequestId = null
            session.receiveExportResult(expired, null, false)
        }
        if (!session.state.busy && (exportRequestId == null || session.state.acceptance == null)) session.refresh(preserveMessage = true)
    }

    override fun onStop() {
        session.removeObserver(observer)
        super.onStop()
    }

    override fun onPostResume() { super.onPostResume(); resumed = true; launchPendingExport() }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportRequestId", exportRequestId)
        outState.putInt("exportRequestCode", exportRequestCode)
        outState.putInt("nextExportCode", nextExportCode)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    private fun launchPendingExport() {
        val request = screen.exportRequest ?: return
        if (!resumed || screen.busy || exportRequestId != null || screen.acceptance !is Acceptance.Granted) return
        exportRequestId = request.id
        exportRequestCode = nextExportCode
        nextExportCode = if (nextExportCode == 65535) 100 else nextExportCode + 1
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(Intent.EXTRA_TITLE, "auditlab-eventos.csv")
            }, exportRequestCode)
        } catch (_: android.content.ActivityNotFoundException) {
            exportRequestId = null
            session.receiveExportResult(request.id, null, false)
        }
    }

    @Deprecated("Framework callback required without additional Activity libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == exportRequestCode) {
            val id = exportRequestId ?: return
            exportRequestId = null
            session.receiveExportResult(id, data?.data, resultCode != RESULT_OK)
        }
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
        launchPendingExport()
    }

    private fun renderControls() {
        val state = screen.acceptance
        val canAccept = !screen.busy && (state == Acceptance.Pending || state is Acceptance.Revoked && !state.deletionPending)
        findViewById<CheckBox>(R.id.consent_checkbox).isEnabled = canAccept
        findViewById<Button>(R.id.accept_button).isEnabled = canAccept && findViewById<CheckBox>(R.id.consent_checkbox).isChecked
        findViewById<Button>(R.id.decline_button).isEnabled = canAccept
        eventButtons.forEach { findViewById<Button>(it).isEnabled = !screen.busy && state is Acceptance.Granted }
        findViewById<Button>(R.id.refresh_button).isEnabled = !screen.busy
        findViewById<Button>(R.id.export_button).isEnabled = !screen.busy && state is Acceptance.Granted && screen.exportRequest == null
        findViewById<ProgressBar>(R.id.busy_indicator).visibility = if (screen.busy) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        if (isFinishing) session.closeDatabaseWhenIdle()
        super.onDestroy()
    }
}
