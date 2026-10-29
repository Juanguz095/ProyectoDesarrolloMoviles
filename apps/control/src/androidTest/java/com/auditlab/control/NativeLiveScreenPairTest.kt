package com.auditlab.control

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auditlab.common.ApiClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Consume the current frame of the other AVD; no synthetic frame is published here. */
@RunWith(AndroidJUnit4::class)
class NativeLiveScreenPairTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shown(text: String, description: Boolean = false): Boolean {
        fun find(item: AccessibilityNodeInfo?): Boolean {
            if (item == null) return false
            if ((if (description) item.contentDescription else item.text)?.toString() == text) return true
            return (0 until item.childCount).any { find(item.getChild(it)) }
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun until(check: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime()+150000
        while (android.os.SystemClock.elapsedRealtime()<end) {
            if (check()) return
            android.os.SystemClock.sleep(300)
        }
        throw AssertionError("Live paired viewer did not reach expected state")
    }
    @Test fun showsOtherAvdThenRemovesItsImageAfterLocalStop() {
        val context = instrumentation.targetContext
        val cfg = JSONObject(File(context.filesDir,"qa-screen.json").readText())
        check(cfg.getBoolean("isolated") && cfg.getString("url")=="http://10.0.2.2:8081")
        val store = KeystoreSessionStore(context)
        assertNull(store.load())
        val api = ApiClient(cfg.getString("url"))
        val session = api.login("qa_admin",cfg.getString("password"))
        store.save(session)
        try {
            val scenario = ActivityScenario.launch<ScreenViewerActivity>(Intent(context,ScreenViewerActivity::class.java).putExtra("device",cfg.getString("device")))
            try {
                until { shown("Pantalla compartida por el usuario",true) }
                File(context.filesDir,"qa-live-image-seen").writeText("ready")
                until { shown("No hay una sesión de pantalla activa.") }
                assertFalse(shown("Pantalla compartida por el usuario",true))
            } finally { scenario.close() }
        } finally { api.logout(session.accessToken); store.clear() }
    }
}
