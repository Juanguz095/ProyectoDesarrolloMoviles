package com.auditlab.control

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auditlab.common.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/** Render a synthetic device-owned JPEG through the real API and the production viewer. */
@RunWith(AndroidJUnit4::class)
class NativeScreenViewerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(item: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (item == null) return null
            if (predicate(item)) return item
            for (i in 0 until item.childCount) find(item.getChild(i))?.let { return it }
            return null
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun until(action: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 45000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (action()) return
            android.os.SystemClock.sleep(300)
        }
        throw AssertionError("Native viewer condition timed out")
    }
    @Test fun rendersAuthorizedFrameAndClearsItWhenSessionEnds() {
        val context = instrumentation.targetContext
        val cfg = JSONObject(File(context.filesDir, "qa-screen.json").readText())
        check(cfg.getBoolean("isolated") && cfg.getString("url") == "http://10.0.2.2:8081")
        val store = KeystoreSessionStore(context)
        assertNull("Existing control session is preserved", store.load())
        val api = ApiClient(cfg.getString("url"))
        val session = api.login("qa_admin", cfg.getString("password"))
        val device = api.enroll(api.createEnrollmentCode(session.accessToken).code,
            ConsentContext(java.util.UUID.randomUUID().toString(), "qa-screen", Instant.now().toString()), "qa-screen")
        val relay = ScreenRelayClient(cfg.getString("url"))
        val publishing = java.util.concurrent.atomic.AtomicBoolean(false)
        var publisher: Thread? = null
        try {
            store.save(session)
            val bitmap = Bitmap.createBitmap(480, 720, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLUE)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
            bitmap.recycle()
            relay.request(device.deviceId, device.deviceToken, "POST", bytes)
            publishing.set(true)
            publisher = kotlin.concurrent.thread {
                while (publishing.get()) {
                    relay.request(device.deviceId, device.deviceToken, "POST", bytes)
                    Thread.sleep(500)
                }
            }
            val scenario = ActivityScenario.launch<ScreenViewerActivity>(Intent(context, ScreenViewerActivity::class.java).putExtra("device", device.deviceId))
            try {
                until { node { it.contentDescription?.toString() == "Pantalla compartida por el usuario" } != null }
                publishing.set(false); publisher.join(10000)
                relay.request(device.deviceId, device.deviceToken, "DELETE")
                until { node { it.text?.toString() == "No hay una sesión de pantalla activa." } != null }
                assertNull(node { it.contentDescription?.toString() == "Pantalla compartida por el usuario" })
            } finally { scenario.close() }
        } finally {
            publishing.set(false); publisher?.join(10000)
            api.revokeConsent(device.deviceToken, device.deviceId)
            api.logout(session.accessToken); store.clear()
        }
    }
}
