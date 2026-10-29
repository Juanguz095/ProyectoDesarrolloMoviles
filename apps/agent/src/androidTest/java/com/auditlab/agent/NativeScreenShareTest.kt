package com.auditlab.agent

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.app.NotificationManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auditlab.common.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real MediaProjection, OS consent, ImageReader, notification stop and authenticated relay. */
@RunWith(AndroidJUnit4::class)
class NativeScreenShareTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun until(seconds: Int = 45, action: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + seconds * 1000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (action()) return
            android.os.SystemClock.sleep(300)
        }
        throw AssertionError("Screen-share condition timed out")
    }
    private fun button(text: String) {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.equals(text, true) == true && node.isClickable) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        until { find(instrumentation.uiAutomation.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
    }
    @Test fun sharesOnlyAfterLocalConsentAndStopsFromNotification() {
        val cfg = JSONObject(File(context.filesDir, "qa-screen.json").readText())
        check(cfg.getBoolean("isolated") && cfg.getString("url") == "http://10.0.2.2:8081")
        val runtime = AgentRuntime.get(context)
        assertNull("Use an empty dedicated QA registration", runtime.credentials.load())
        assertNull(runtime.journal.registration())
        val admin = ControlCore(ApiClient(cfg.getString("url")))
        admin.login("qa_admin", cfg.getString("password"))
        val core = runtime.core(cfg.getString("url"))
        try {
            core.acceptConsent(); val enrolled = core.enroll(admin.createEnrollmentCode().code)
            assertTrue(runtime.indicator.show())
            runtime.after(core)
            val relay = ScreenRelayClient(cfg.getString("url"))
            // Obtain a separate user session only inside QA memory, never in a URL or log.
            val session = ApiClient(cfg.getString("url")).login("qa_admin", cfg.getString("password"))
            try {
                context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                context.startActivity(Intent(context, ScreenShareActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                button("Cancelar")
                assertNull(relay.request(enrolled.deviceId, session.accessToken))
                // A new launch before the previous task finishes can be delivered to
                // that finishing instance. Wait for the local dialog to disappear.
                until { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.let {
                    it != context.packageName
                } == true }
                instrumentation.waitForIdleSync()
                context.startActivity(Intent(context, ScreenShareActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                button("Continuar con Android")
                button("START NOW")
                var frame: ByteArray? = null
                until(90) { frame = relay.request(enrolled.deviceId, session.accessToken); frame != null }
                val image = BitmapFactory.decodeByteArray(frame, 0, frame!!.size)
                assertNotNull(image); assertEquals(480, image.width); assertTrue(image.height > 100)
                image.recycle()
                // The relay expires after five seconds. A static screen must keep
                // sending a heartbeat, not disappear after its first frame.
                repeat(12) {
                    android.os.SystemClock.sleep(1000)
                    assertNotNull("Static screen lost its heartbeat", relay.request(enrolled.deviceId, session.accessToken))
                }
                val stable = relay.request(enrolled.deviceId, session.accessToken)!!
                val stableImage = BitmapFactory.decodeByteArray(stable, 0, stable.size)
                var bright = 0
                for (x in 1..8) for (y in 2..7) {
                    val color = stableImage.getPixel(stableImage.width*x/10, stableImage.height*y/10)
                    if (android.graphics.Color.red(color)+android.graphics.Color.green(color)+android.graphics.Color.blue(color)>180) bright++
                }
                stableImage.recycle()
                assertTrue("Captured Settings content remained black", bright > 10)
                if (cfg.optBoolean("livePairHold", false)) {
                    File(context.filesDir, "qa-screen-ready.json").writeText(JSONObject().put("device", enrolled.deviceId).toString())
                    until(240) { File(context.filesDir, "qa-screen-finish").exists() }
                }
                val manager = context.getSystemService(NotificationManager::class.java)
                val notice = manager.activeNotifications.first { it.id == 5202 }
                assertTrue(notice.notification.actions.any { it.title.toString() == "DETENER" })
                notice.notification.actions.first { it.title.toString() == "DETENER" }.actionIntent.send()
                until { manager.activeNotifications.none { it.id == 5202 } }
                until { relay.request(enrolled.deviceId, session.accessToken) == null }
                assertTrue(ScreenSharingState.message.contains("detenida", true))
                if (cfg.optBoolean("livePairHold", false)) {
                    File(context.filesDir,"qa-screen-stopped").writeText("ready")
                    until(240) { File(context.filesDir,"qa-screen-viewer-cleared").exists() }
                }
            } finally { ApiClient(cfg.getString("url")).logout(session.accessToken) }
        } finally {
            context.stopService(Intent(context, ScreenShareService::class.java))
            core.revokeLocally(); core.resetAfterRevocation(); runtime.indicator.hide(); runtime.after(core)
            admin.logout()
        }
    }
}
