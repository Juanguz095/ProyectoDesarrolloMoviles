package com.auditlab.academic.tests

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.*
import com.auditlab.academic.*
import com.auditlab.academic.storage.AuditDatabase
import com.auditlab.domain.*

internal object SimulationUiTests {
    fun cases(instrumentation: Instrumentation): List<InstrumentedCase> {
        fun case(name: String, action: (UiFixture) -> Unit) = InstrumentedCase("SimulationUiTests", name) {
            UiFixture(instrumentation).use(action)
        }
        return listOf(
            case("externalIntentCannotGrantAcceptanceGenerateOrExport") { f ->
                val intent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("acceptance", "GRANTED")
                    putExtra("generateEvent", true)
                    putExtra("export", true)
                }
                f.main { f.activity.startActivity(intent) }
                instrumentation.waitForIdleSync()
                f.idle()
                f.main {
                    check(f.session.state.acceptance == Acceptance.Pending)
                    check(f.session.state.events.isEmpty() && f.session.state.exportRequest == null)
                    check(f.events.all { !f.button(it).isEnabled })
                    check(!f.button(R.id.export_button).isEnabled)
                }
            },
            case("decliningKeepsAllFourButtonsDisabled") { f ->
                f.main {
                    check(!f.button(R.id.accept_button).isEnabled)
                    f.activity.findViewById<CheckBox>(R.id.consent_checkbox).isChecked = true
                    f.button(R.id.decline_button).performClick()
                    check(f.events.all { !f.button(it).isEnabled })
                    check(f.session.state.acceptance == Acceptance.Pending)
                    check(f.session.state.events.isEmpty())
                }
            },
            case("explicitAcceptanceAndFourButtonsCreateExactlyFourSimulatedEvents") { f ->
                f.accept()
                f.main { check(f.session.state.events.isEmpty()) }
                f.events.forEach { id -> f.main { f.button(id).performClick() }; f.idle() }
                f.main {
                    val events = f.session.state.events
                    check(events.size == 4 && events.all { it.isSimulated })
                    check(events.map { it.kind to it.direction }.toSet().size == 4)
                    check(f.activity.findViewById<LinearLayout>(R.id.history_rows).childCount == 4)
                    check((f.activity.findViewById<LinearLayout>(R.id.history_rows).getChildAt(0) as TextView).text.contains("SIMULADO"))
                }
            },
            case("rapidClicksAndRecreationDoNotDuplicateAnEvent") { f ->
                f.accept()
                val previous = f.activity
                f.main { repeat(30) { f.button(R.id.call_incoming).performClick() }; previous.recreate() }
                f.await { f.app.foreground != null && f.app.foreground !== previous }
                f.idle()
                f.main { check(f.session.state.events.size == 1); check(f.session.state.acceptance is Acceptance.Granted) }
            },
            case("finishAndReopenKeepConfirmedEventWithoutAutomaticGeneration") { f ->
                f.accept(); f.main { f.button(R.id.message_outgoing).performClick() }; f.idle()
                val id = f.session.state.events.single().eventId
                f.main { f.activity.finish() }; f.await { f.app.foreground == null }
                f.open(); f.idle()
                f.main { check(f.session.state.events.single().eventId == id) }
            },
            case("storageFailureBlocksScreenAndReadRetryRecovers") { f ->
                f.accept()
                val db = AuditDatabase(instrumentation.targetContext, f.app.databaseName)
                try {
                    db.writableDatabase.execSQL("ALTER TABLE events RENAME TO unavailable_events")
                    f.main { f.button(R.id.refresh_button).performClick() }; f.idle()
                    f.main { check(f.session.state.acceptance == null); check(f.events.all { !f.button(it).isEnabled }) }
                    db.writableDatabase.execSQL("ALTER TABLE unavailable_events RENAME TO events")
                    f.main { f.button(R.id.refresh_button).performClick() }; f.idle()
                    f.main { check(f.session.state.acceptance is Acceptance.Granted) }
                } finally { db.close() }
            },
            case("screenHasScrollViewAndReceivesSystemInsets") { f ->
                f.main {
                    val root = f.activity.findViewById<ScrollView>(R.id.screen_root)
                    check(root.paddingTop > 0)
                    check(root.findViewById<View>(R.id.message_outgoing) != null)
                }
            },
        )
    }
}

internal class UiFixture(val instrumentation: Instrumentation) : AutoCloseable {
    val app = instrumentation.targetContext.applicationContext as TestApplication
    val activity get() = checkNotNull(app.foreground)
    val session get() = app.auditSession
    val events = listOf(R.id.call_incoming, R.id.call_outgoing, R.id.message_incoming, R.id.message_outgoing)
    init { app.resetFixture(); open(); idle() }
    fun open() {
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await { app.foreground != null }
    }
    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun button(id: Int): Button = activity.findViewById(id)
    fun idle() = await {
        var idle = false; main { idle = !session.state.busy && session.state.acceptance != null }
        // Errors have null acceptance and are still a completed operation.
        if (!idle) main { idle = !session.state.busy && session.state.message.isNotEmpty() }
        idle
    }
    fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (!predicate()) { check(SystemClock.uptimeMillis() < deadline) { "UI timeout" }; SystemClock.sleep(30) }
    }
    fun accept() { main { activity.findViewById<CheckBox>(R.id.consent_checkbox).isChecked = true; button(R.id.accept_button).performClick() }; idle() }
    override fun close() {
        main { app.foreground?.finish() }
        await { app.foreground == null }
        instrumentation.waitForIdleSync()
        app.resetFixture()
    }
}
