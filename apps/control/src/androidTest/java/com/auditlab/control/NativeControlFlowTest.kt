package com.auditlab.control

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

/** Exercises the production activity/session/Keystore against the separate native agent's event. */
@RunWith(AndroidJUnit4::class)
class NativeControlFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun config(): JSONObject {
        val name = requireNotNull(InstrumentationRegistry.getArguments().getString("fixture")) { "Prepare the private QA fixture with scripts/check_android.py" }
        require(name.matches(Regex("qa-native-[a-f0-9]{16}\\.json")))
        return JSONObject(File(context.filesDir, name).readText()).also {
            check(it.getBoolean("isolated") && it.getString("url") == "http://10.0.2.2:8081")
        }
    }
    private fun tap(text: String) {
        compose.waitUntil(45_000) {
            compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().any {
                !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        val clickable = hasText(text) and hasClickAction()
        val scrollable = clickable and hasAnyAncestor(hasScrollAction())
        if (compose.onAllNodes(scrollable).fetchSemanticsNodes().isNotEmpty()) compose.onAllNodes(scrollable)[0].performScrollTo()
        compose.onAllNodes(clickable)[0].performClick()
    }
    private fun waitFor(text: String) {
        compose.waitUntil(45_000) {
            try { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
            catch (error: IllegalStateException) {
                if (error.message?.contains("No compose hierarchies") == true) false else throw error
            }
        }
    }
    private fun externalNode(predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }
            for (root in roots) if (root.packageName?.toString()?.contains("documentsui") == true) find(root)?.let { return it }
            android.os.SystemClock.sleep(200)
        }
        throw AssertionError("Expected system document picker control unavailable")
    }
    private fun clickExternal(find: () -> android.view.accessibility.AccessibilityNodeInfo) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            // Rotation can invalidate a node between discovery and ACTION_CLICK.
            // Reacquire only when the OS did not accept the click; keep the final
            // saved URI/CSV assertions as proof that the action really completed.
            var node: android.view.accessibility.AccessibilityNodeInfo? = find()
            while (node != null && !node.isClickable) node = node.parent
            if (node?.refresh() == true && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return
            android.os.SystemClock.sleep(100)
        }
        throw AssertionError("System document picker did not accept the click")
    }
    private fun cancelPicker() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(8) {
            val now = android.os.SystemClock.uptimeMillis()
            assertTrue(automation.injectInputEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK, 0), true))
            assertTrue(automation.injectInputEvent(android.view.KeyEvent(now, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK, 0), true))
            val deadline = android.os.SystemClock.elapsedRealtime() + 2_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (automation.rootInActiveWindow?.packageName?.toString() == context.packageName) return
                android.os.SystemClock.sleep(100)
            }
        }
        throw AssertionError("System picker did not return to AuditLab after cancellation")
    }
    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    }
    @Test fun readNativeAgentEvent() {
        val cfg = config()
        when (InstrumentationRegistry.getArguments().getString("stage")) {
            "logout" -> {
                val existing = KeystoreSessionStore(context).load()
                check(existing != null && existing.serverUrl == cfg.getString("url") && existing.username == "qa_admin")
                waitFor("VISTA DE DISPOSITIVOS"); tap("MÁS"); tap("CERRAR SESIÓN"); waitFor("Confirmar salida")
                compose.onNodeWithText("Confirmar salida").performClick(); waitFor("INICIAR SESIÓN")
                assertNull(KeystoreSessionStore(context).load())
            }
            "login-read" -> {
                compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextReplacement("qa_admin")
                compose.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextReplacement(cfg.getString("password"))
                tap("INICIAR SESIÓN"); waitFor("VISTA DE DISPOSITIVOS")
                val stored = KeystoreSessionStore(context).load()
                assertNotNull(stored); assertEquals(cfg.getString("url"), stored!!.serverUrl)
                tap("VER HISTORIAL\nDE EVENTOS")
                waitFor("app_lifecycle")
                compose.onNodeWithContentDescription("Ver detalle →").performScrollTo().performClick(); waitFor("DETALLE DE EVENTO")
                assertTrue(compose.onAllNodesWithText(cfg.getString("device")).fetchSemanticsNodes().isNotEmpty())
                assertTrue(compose.onAllNodesWithText(cfg.getString("event")).fetchSemanticsNodes().isNotEmpty())
                tap("Cerrar detalle")
                tap("MÁS"); tap("CONSULTAR AUDITORÍA"); waitFor("event_queried")
            }
            "create-code" -> {
                waitFor("VISTA DE DISPOSITIVOS"); tap("MÁS")
                tap("CREAR CÓDIGO DE INSCRIPCIÓN"); tap("CREAR CÓDIGO DE UN USO")
                waitFor("CÓDIGO VISIBLE SOLO DURANTE ESTA SESIÓN")
                val code = androidx.lifecycle.ViewModelProvider(compose.activity)[ControlViewModel::class.java].session.enrollmentCode
                assertNotNull(code)
                assertTrue(code!!.code.matches(Regex("enr_[A-Za-z0-9_-]{32,}")))
                assertEquals(600L, code.expiresIn)
                assertTrue(java.time.Instant.parse(code.expiresAt).isAfter(java.time.Instant.now()))
            }
            "restore-read" -> {
                waitFor("VISTA DE DISPOSITIVOS")
                assertNotNull(KeystoreSessionStore(context).load())
                tap("EQUIPOS"); tap("ACTUALIZAR DISPOSITIVOS"); waitFor("Actualizado:")
                tap("INICIO"); tap("VER HISTORIAL\nDE EVENTOS")
                waitFor("app_lifecycle")
            }
            "export-rotation" -> {
                waitFor("VISTA DE DISPOSITIVOS"); tap("VER HISTORIAL\nDE EVENTOS")
                waitFor("app_lifecycle")
                tap("Todas las categorías"); tap("Actividad de la app")
                compose.onNode(hasSetTextAction() and hasContentDescription("ID o código exacto"))
                    .performScrollTo().performTextReplacement(cfg.getString("event"))
                tap("APLICAR FILTROS"); waitFor("Historial actualizado: 1 evento(s).")
                compose.activityRule.scenario.recreate(); waitFor("EVENTOS")
                compose.onNodeWithText("Actividad de la app").assertExists()
                compose.onNode(hasSetTextAction() and hasContentDescription("ID o código exacto"))
                    .assertTextContains(cfg.getString("event"))
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                val info = automation.serviceInfo
                val previousFlags = info.flags
                info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                automation.serviceInfo = info
                val previousAuto = shell("settings get system accelerometer_rotation")
                val previousRotation = shell("settings get system user_rotation")
                try {
                    tap("EXPORTAR CSV")
                    externalNode { it.className?.toString() == "android.widget.EditText" }
                    cancelPicker()
                    waitFor("Exportación cancelada;")
                    tap("EXPORTAR CSV")
                    val title = externalNode { it.className?.toString() == "android.widget.EditText" }
                    val filename = "qa-native-" + java.util.UUID.randomUUID().toString() + ".csv"
                    val text = android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, filename) }
                    assertTrue(title.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, text))
                    shell("settings put system accelerometer_rotation 0")
                    shell("settings put system user_rotation 1")
                    compose.waitUntil(20_000) { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                    clickExternal { externalNode { it.isEnabled && (it.viewIdResourceName?.endsWith(":id/button_save") == true || it.text?.toString()?.equals("Save", true) == true) } }
                    waitFor("CSV guardado con los filtros seleccionados.")
                    val model = androidx.lifecycle.ViewModelProvider(compose.activity)[ControlViewModel::class.java]
                    val uri = model.session.lastReportUri
                    assertNotNull(uri); assertEquals("content", uri!!.scheme)
                    val csv = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    val rows = csv.lineSequence().filter { it.isNotBlank() }.toList()
                    assertEquals(2, rows.size); assertTrue(rows.first().startsWith("eventId,timestamp,"))
                    assertTrue(rows[1].contains(cfg.getString("event"))); assertTrue(rows[1].contains(cfg.getString("device")))
                    File(context.filesDir, "qa-export-filename.txt").writeText(filename)
                } finally {
                    shell(if (previousAuto == "null") "settings delete system accelerometer_rotation" else "settings put system accelerometer_rotation " + previousAuto)
                    shell(if (previousRotation == "null") "settings delete system user_rotation" else "settings put system user_rotation " + previousRotation)
                    info.flags = previousFlags; automation.serviceInfo = info
                }
            }
            "tls-client" -> {
                val address = "https://10.0.2.2:18443"
                fun rejectedByDefault() {
                    try { ApiClient(address).login("qa_admin", cfg.getString("password")); fail("Untrusted QA certificate must fail") }
                    catch (error: ApiException) { assertEquals("TLS_ERROR", error.code) }
                }
                rejectedByDefault()
                val certificate = java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(cfg.getString("caPem").byteInputStream())
                val trust = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {
                    load(null, null); setCertificateEntry("qa-only", certificate)
                }
                val managers = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
                val tls = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
                val original = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory()
                try {
                    // Test process only; hostname verification remains enabled and production trust is unchanged.
                    javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
                    val admin = ControlCore(ApiClient(address), expectedServerUrl = address)
                    try {
                        assertEquals("admin", admin.login("qa_admin", cfg.getString("password")).role)
                        assertTrue(admin.devices().items.isNotEmpty())
                    } finally { admin.logout() }
                } finally { javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(original) }
                rejectedByDefault()
            }
            "auditor" -> {
                val existing = KeystoreSessionStore(context).load()
                if (existing != null) {
                    check(existing.serverUrl == cfg.getString("url") && existing.username in setOf("qa_admin", cfg.getString("auditorUser")))
                    waitFor("VISTA DE DISPOSITIVOS"); tap("MÁS"); tap("CERRAR SESIÓN"); waitFor("Confirmar salida")
                    compose.onNodeWithText("Confirmar salida").performClick(); waitFor("INICIAR SESIÓN")
                } else waitFor("INICIAR SESIÓN")
                assertNull(KeystoreSessionStore(context).load())
                compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextReplacement(cfg.getString("auditorUser"))
                compose.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextReplacement(cfg.getString("auditorPassword"))
                tap("INICIAR SESIÓN"); waitFor("VISTA DE DISPOSITIVOS")
                tap("MÁS")
                compose.onNodeWithText("CREAR CÓDIGO DE INSCRIPCIÓN").assertDoesNotExist()
                tap("EQUIPOS"); tap("VER DISPOSITIVO")
                compose.onNodeWithText("REVOCAR DISPOSITIVO").assertDoesNotExist()
                val session = KeystoreSessionStore(context).load()!!
                val client = ApiClient(cfg.getString("url"))
                try { client.createEnrollmentCode(session.accessToken); fail("Auditor must not create enrollment codes") }
                catch (error: ApiException) { assertEquals(403, error.status) }
                try { client.revokeDevice(session.accessToken, cfg.getString("device")); fail("Auditor must not revoke") }
                catch (error: ApiException) { assertEquals(403, error.status) }
                val previousFont = shell("settings get system font_scale")
                try {
                    shell("settings put system font_scale 1.5")
                    compose.waitUntil(20_000) { context.resources.configuration.fontScale >= 1.49f }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    compose.waitForIdle(); waitFor("DISPOSITIVO")
                    tap("MÁS"); tap("CERRAR SESIÓN"); waitFor("Confirmar salida")
                    compose.onNodeWithText("Confirmar salida").performClick(); waitFor("INICIAR SESIÓN")
                    assertNull(KeystoreSessionStore(context).load())
                } finally {
                    shell(if (previousFont == "null") "settings delete system font_scale" else "settings put system font_scale " + previousFont)
                }
            }
            "revoke" -> {
                waitFor("VISTA DE DISPOSITIVOS"); tap("EQUIPOS"); tap("VER DISPOSITIVO")
                tap("REVOCAR DISPOSITIVO")
                compose.onNodeWithText("Confirmar revocación").assertExists()
                compose.onNodeWithText("Cancelar").performClick()
                val admin = ControlCore(ApiClient(cfg.getString("url"))).also { it.login("qa_admin", cfg.getString("password")) }
                try { assertEquals("active", admin.devices().items.first { it.deviceId == cfg.getString("device") }.status) }
                finally { admin.logout() }
                tap("REVOCAR DISPOSITIVO")
                compose.onNodeWithText("Confirmar revocación").performClick()
                waitFor("Dispositivo revocado.")
            }
            else -> error("Unknown native QA stage")
        }
    }
}
