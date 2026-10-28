package com.auditlab.agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auditlab.common.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs only on the dedicated empty QA AVD. Host injects emulator GSM/SMS after the ready marker. */
@RunWith(AndroidJUnit4::class)
class NativeRealEventsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val runtime get() = AgentRuntime.get(context)
    private fun await(condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 120_000
        while (!condition()) {
            if (android.os.SystemClock.elapsedRealtime() >= until) throw AssertionError("Real-event condition timed out")
            android.os.SystemClock.sleep(300)
        }
    }
    private fun tap(text: String) {
        compose.waitUntil(45_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        } }
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    @Test fun capturesAuthorizedPhoneMetadataAndStopsOnWithdrawal() {
        val input = File(context.filesDir, "qa-real-events.json")
        val config = JSONObject(input.readText())
        check(config.getBoolean("isolated") && config.getString("url") == "http://10.0.2.2:8081")
        if (config.optBoolean("resumeOwned", false)) {
            val previous = runtime.journal.registration()
            check(previous != null && previous.serverUrl == config.getString("url"))
            if (previous.phase == AgentPhase.ACTIVE) runtime.core().revokeLocally()
            runtime.core().resetAfterRevocation()
            runtime.after(runtime.core())
        }
        check(runtime.journal.registration() == null && runtime.credentials.load() == null)
        assertFalse(runtime.realEvents.requested)
        assertEquals(QueueStats(), runtime.journal.stats())
        val api = ApiClient(config.getString("url"))
        val control = ControlCore(api).also { it.login("qa_admin", config.getString("password")) }
        try {
            val code = control.createEnrollmentCode().code
            compose.waitUntil(45_000) { compose.onAllNodesWithText("Registrar consentimiento").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(isToggleable()).performScrollTo().performClick()
            tap("Registrar consentimiento")
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Paso 2 · Inscripción").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput(code)
            tap("Inscribir dispositivo")
            await { runtime.journal.registration()?.phase == AgentPhase.ACTIVE }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Autorizar e iniciar captura real").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Autorizar e iniciar captura real").assertIsNotEnabled()
            compose.onNode(isToggleable()).performScrollTo().performClick()
            tap("Autorizar e iniciar captura real")
            await { runtime.realEvents.running }
            File(context.filesDir, "qa-real-ready").writeText("ready")
            val device = runtime.journal.registration()!!.deviceId
            fun events() = control.eventPage(EventQuery(device = device), null).items
            await {
                val codes = events().map { it.metadata["appEventCode"] }.toSet()
                codes.containsAll(setOf("sms_received", "call_missed", "sms_sent", "call_outgoing", "device_status_changed"))
            }
            val captured = events()
            assertTrue(captured.all { java.time.Instant.parse(it.timestamp).toEpochMilli() >= runtime.realEvents.startedAt })
            assertTrue(captured.all { !it.isSimulated })
            assertTrue(captured.all { it.metadata.keys.all { key -> key in setOf("appEventCode", "networkState", "batteryPercent") } })
            val ids = captured.map { it.eventId }.toSet()
            assertEquals(captured.size, ids.size)
            val csv = control.export(EventQuery(device = device))
            assertTrue(csv.contains("sms,false,inbound"))
            // A second scan must not duplicate the rows injected by the host.
            android.os.SystemClock.sleep(12_000)
            assertEquals(ids, events().map { it.eventId }.toSet())
            tap("Revocar consentimiento")
            await { runtime.journal.registration()?.phase == AgentPhase.REVOKED }
            assertFalse(runtime.realEvents.requested)
            assertFalse(runtime.realEvents.running)
            assertNull(runtime.credentials.load())
            assertEquals(QueueStats(), runtime.journal.stats())
            File(context.filesDir, "qa-real-result.json").writeText(JSONObject()
                .put("eventCount", captured.size).put("realEvents", true).put("deduplicated", true)
                .put("withdrawalStopped", true).put("device", device).toString())
        } finally {
            runtime.realEvents.stop()
            runCatching {
                if (runtime.journal.registration()?.phase == AgentPhase.ACTIVE) runtime.core().revokeLocally()
                runtime.after(runtime.core())
            }
            runCatching { control.logout() }
            input.delete()
        }
    }
}
