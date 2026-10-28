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

/** Real activity, Room, Android Keystore, notification, WorkManager and loopback Ktor.
 * Host orchestrator alone stops/restarts the QA API and process between stages.
 * Configuration is a private, temporary file; no password/token in instrumentation arguments.
 */
@RunWith(AndroidJUnit4::class)
class NativeAgentFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val runtime get() = AgentRuntime.get(context)
    private fun config(): JSONObject {
        val name = requireNotNull(InstrumentationRegistry.getArguments().getString("fixture")) { "Prepare the private QA fixture with scripts/check_android.py" }
        require(name.matches(Regex("qa-native-[a-f0-9]{16}\\.json")))
        return JSONObject(File(context.filesDir, name).readText()).also {
            check(it.getBoolean("isolated") && it.getString("url") == "http://10.0.2.2:8081")
        }
    }
    private fun api() = ApiClient(config().getString("url"))
    private fun control(): ControlCore = ControlCore(api()).also { it.login("qa_admin", config().getString("password")) }
    private fun tap(text: String) {
        compose.waitUntil(45_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().any {
                !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        val clickable = hasText(text) and hasClickAction()
        val scrollable = clickable and hasAnyAncestor(hasScrollAction())
        if (compose.onAllNodes(scrollable).fetchSemanticsNodes().isNotEmpty()) compose.onNode(scrollable).performScrollTo()
        compose.onNode(clickable).performClick()
    }
    private fun waitFor(text: String) {
        try {
            compose.waitUntil(45_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            File(context.filesDir, "qa-last-ui.txt").writeText(compose.onRoot().printToString())
            throw error
        }
    }
    private fun notificationSwitch(): android.view.accessibility.AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val serviceInfo = automation.serviceInfo
        if (serviceInfo.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            serviceInfo.flags = serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = serviceInfo
        }
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.isCheckable && node.className?.toString()?.endsWith("Switch") == true) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        fun waitButton(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.isClickable &&
                (node.viewIdResourceName == "android:id/aerr_wait" || node.text?.toString() == "Wait")) return node
            for (i in 0 until node.childCount) waitButton(node.getChild(i))?.let { return it }
            return null
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }
            val blocked = roots.firstNotNullOfOrNull { waitButton(it) }
            if (blocked != null) {
                blocked.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                android.os.SystemClock.sleep(500)
                continue
            }
            for (root in roots) {
                if (root.packageName?.toString()?.contains("settings") == true) find(root)?.let { return it }
            }
            android.os.SystemClock.sleep(200)
        }
        throw AssertionError("System notification settings switch unavailable")
    }
    private fun openNotificationSettings() {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun returnToAgent() {
        context.startActivity(android.content.Intent(context, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun assertCaptureDisabled() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("GENERAR EVENTO SIMULADO").fetchSemanticsNodes().any {
                it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        compose.onNodeWithText("GENERAR EVENTO SIMULADO").assertIsNotEnabled()
    }
    private fun enqueueAndWait() {
        tap("GENERAR EVENTO SIMULADO"); waitFor("Evento enviado:")
        assertEquals(QueueStats(0, 1, 0), runtime.journal.stats())
    }
    @Test fun runAuthorizedStage() {
        when (InstrumentationRegistry.getArguments().getString("stage")) {
            "enroll" -> {
                waitFor("ACEPTAR CONSENTIMIENTO")
                compose.onNodeWithText("ACEPTAR CONSENTIMIENTO").assertIsEnabled()
                assertEquals(QueueStats(), runtime.journal.stats())
                assertNull(runtime.journal.registration())
                val admin = control()
                try {
                    val code = admin.createEnrollmentCode().code
                                        tap("ACEPTAR CONSENTIMIENTO"); waitFor("CONSENTIMIENTO REGISTRADO")
                    compose.onNode(hasSetTextAction() and hasContentDescription("Código de inscripción")).performScrollTo().performTextInput(code)
                    tap("INSCRIBIR DISPOSITIVO"); waitFor("MONITOREO")
                    assertEquals(AgentPhase.ACTIVE, runtime.journal.registration()?.phase)
                    assertNotNull(runtime.credentials.load())
                    assertTrue(runtime.indicator.isVisible())
                    enqueueAndWait()
                    val device = runtime.journal.registration()!!.deviceId
                    assertEquals(1, admin.events(device).items.size)
                } finally { admin.logout() }
            }
            "prepare-new-consent" -> {
                waitFor("MONITOREO")
                tap("RETIRAR CONSENTIMIENTO"); tap("Confirmar retirada"); waitFor("Consentimiento revocado y servidor notificado.")
                assertEquals(AgentPhase.REVOKED, runtime.journal.registration()?.phase)
                assertNull(runtime.credentials.load()); assertEquals(QueueStats(), runtime.journal.stats())
                tap("Volver a aceptar consentimiento"); waitFor("ACEPTAR CONSENTIMIENTO")
                compose.onNodeWithText("ACEPTAR CONSENTIMIENTO").assertIsEnabled()
            }
            "restore" -> {
                waitFor("MONITOREO")
                assertEquals(AgentPhase.ACTIVE, runtime.journal.registration()?.phase)
                assertNotNull(runtime.credentials.load())
                assertEquals(QueueStats(0, 1, 0), runtime.journal.stats())
                if (!runtime.indicator.isVisible()) {
                    assertCaptureDisabled()
                    tap("HABILITAR INDICADOR Y REANUDAR"); waitFor("Indicador habilitado.")
                }
                assertTrue(runtime.indicator.isVisible())
            }
            "offline" -> {
                waitFor("MONITOREO")
                if (!runtime.indicator.isVisible()) { tap("HABILITAR INDICADOR Y REANUDAR"); waitFor("Indicador habilitado.") }
                tap("GENERAR EVENTO SIMULADO"); waitFor("Sin confirmación del servicio.")
                assertEquals(QueueStats(1, 1, 0), runtime.journal.stats())
            }
            "recover" -> {
                waitFor("MONITOREO")
                assertEquals(QueueStats(1, 1, 0), runtime.journal.stats())
                val id = runtime.journal.pending(100).single().eventId
                tap("HABILITAR INDICADOR Y REANUDAR"); waitFor("Indicador habilitado.")
                compose.waitUntil(90_000) { runtime.journal.stats().pending == 0 }
                val admin = control()
                try {
                    val items = admin.events(runtime.journal.registration()!!.deviceId).items
                    assertEquals(2, items.size); assertEquals(1, items.count { it.eventId == id })
                } finally { admin.logout() }
                tap("PAUSAR MONITOREO"); waitFor("Monitoreo pausado.")
                assertFalse(runtime.indicator.isVisible())
                assertCaptureDisabled()
            }
            "verify-recovery" -> {
                waitFor("MONITOREO")
                assertEquals(QueueStats(0, 2, 0), runtime.journal.stats())
                val ids = mutableSetOf<String>()
                runtime.database.openHelper.readableDatabase.query("SELECT event_id FROM agent_outbox").use {
                    while (it.moveToNext()) ids += it.getString(0)
                }
                val admin = control()
                try { assertEquals(ids, admin.events(runtime.journal.registration()!!.deviceId).items.map { it.eventId }.toSet()) }
                finally { admin.logout() }
                if (!runtime.indicator.isVisible()) { tap("HABILITAR INDICADOR Y REANUDAR"); waitFor("Indicador habilitado.") }
                tap("PAUSAR MONITOREO"); waitFor("Monitoreo pausado.")
                assertFalse(runtime.indicator.isVisible())
                assertCaptureDisabled()
            }
            "permission-denied" -> {
                waitFor("MONITOREO")
                assertTrue(runtime.indicator.allowed()); assertTrue(runtime.indicator.show())
                try {
                    openNotificationSettings()
                    val toggle = notificationSwitch()
                    assertTrue(toggle.isChecked)
                    assertTrue(toggle.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                    compose.waitUntil(15_000) { !runtime.indicator.allowed() }
                    returnToAgent(); waitFor("MONITOREO")
                    assertFalse(runtime.indicator.allowed()); assertFalse(runtime.indicator.isVisible())
                    assertCaptureDisabled()
                    var reads = 0
                    try { runtime.core().captureAndSend { reads++; emptyMap() }; fail("Monitoring must stay paused") }
                    catch (_: MonitoringPaused) { }
                    assertEquals(0, reads)
                } finally {
                    openNotificationSettings()
                    val toggle = notificationSwitch()
                    if (!toggle.isChecked) assertTrue(toggle.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                    compose.waitUntil(15_000) { runtime.indicator.allowed() }
                    returnToAgent()
                }
            }
            "remote-revoked" -> {
                waitFor("MONITOREO")
                if (!runtime.indicator.isVisible()) { tap("HABILITAR INDICADOR Y REANUDAR"); waitFor("Indicador habilitado.") }
                tap("GENERAR EVENTO SIMULADO"); waitFor("El centro de control revocó este dispositivo.")
                assertEquals(AgentPhase.REVOKED, runtime.journal.registration()?.phase)
                assertEquals(QueueStats(), runtime.journal.stats()); assertNull(runtime.credentials.load())
                assertFalse(runtime.indicator.isVisible())
                tap("Volver a aceptar consentimiento"); waitFor("ACEPTAR CONSENTIMIENTO")
                compose.onNodeWithText("ACEPTAR CONSENTIMIENTO").assertIsEnabled()
            }
            "withdraw-offline" -> {
                waitFor("MONITOREO")
                // A delayed request from the already revoked enrollment must not
                // prevent the current device's privacy job from running later.
                val previous = config().getString("previousDevice")
                assertNotEquals(previous, runtime.journal.registration()!!.deviceId)
                runtime.scheduler.privacy(previous)
                tap("RETIRAR CONSENTIMIENTO"); tap("Confirmar retirada"); waitFor("Retirada local guardada.")
                assertEquals(AgentPhase.REVOKING, runtime.journal.registration()?.phase)
                assertEquals(QueueStats(), runtime.journal.stats()); assertFalse(runtime.indicator.isVisible())
            }
            "restore-withdrawal" -> {
                waitFor("CONSENTIMIENTO RETIRADO")
                assertEquals(AgentPhase.REVOKING, runtime.journal.registration()?.phase)
                assertEquals(QueueStats(), runtime.journal.stats())
                compose.onNodeWithText("GENERAR EVENTO SIMULADO").assertDoesNotExist()
                compose.onNodeWithText("Volver a aceptar consentimiento").assertIsNotEnabled()
            }
            "confirm-withdrawal" -> {
                waitFor("CONSENTIMIENTO RETIRADO")
                // No manual retry: real WorkManager must finish after connectivity
                // returns, including when an older generation also has queued work.
                compose.waitUntil(90_000) { runtime.journal.registration()?.phase == AgentPhase.REVOKED }
                assertNull(runtime.credentials.load()); assertEquals(QueueStats(), runtime.journal.stats())
                val admin = control()
                try { assertEquals("revoked", admin.devices().items.first { it.deviceId == runtime.journal.registration()!!.deviceId }.status) }
                finally { admin.logout() }
                tap("Volver a aceptar consentimiento"); waitFor("ACEPTAR CONSENTIMIENTO")
                compose.onNodeWithText("ACEPTAR CONSENTIMIENTO").assertIsEnabled()
            }
            else -> error("Unknown native QA stage")
        }
    }
}
