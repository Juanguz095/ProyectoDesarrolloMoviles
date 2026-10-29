package com.auditlab.control

import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auditlab.common.ApiClient
import com.auditlab.design.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Production view, real encrypted session/HTTP and content from the other AVD's callback. */
@RunWith(AndroidJUnit4::class)
class NativeWhatsAppViewerTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun shown(text:String):Boolean {
        fun find(n:AccessibilityNodeInfo?):Boolean = n!=null && (n.text?.toString()==text || (0 until n.childCount).any { find(n.getChild(it)) })
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun until(check:()->Boolean) {
        val end=android.os.SystemClock.elapsedRealtime()+150000
        while(android.os.SystemClock.elapsedRealtime()<end) {
            if(check()) return
            android.os.SystemClock.sleep(300)
        }
        throw AssertionError("WhatsApp paired viewer condition timed out")
    }
    @Test fun rendersNewPreviewAndClearsAfterSourceStops() {
        val context=instrumentation.targetContext
        val cfg=JSONObject(File(context.filesDir,"qa-whatsapp.json").readText())
        check(cfg.getBoolean("isolated") && cfg.getString("url")=="http://10.0.2.2:8081")
        val store=KeystoreSessionStore(context);assertNull(store.load())
        val api=ApiClient(cfg.getString("url"))
        val auth=api.login("qa_admin",cfg.getString("password"))
        store.save(auth.copy(serverUrl=cfg.getString("url")))
        try {
            val scenario=ActivityScenario.launch(MainActivity::class.java)
            try {
                scenario.onActivity { activity->
                    val session=ControlSession(activity)
                    activity.setContent { AuditTheme { AuditPage(key="qa-preview") {
                        WhatsAppPreviewsView(session,cfg.getString("device")) {}
                    } } }
                }
                until { shown("QA preview across devices") }
                scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
                File(context.filesDir,"qa-whatsapp-image-seen").writeText("ready")
                until { shown("SESIÓN DETENIDA") }
                assertFalse(shown("QA preview across devices"))
            } finally { scenario.close() }
        } finally { api.logout(auth.accessToken);store.clear() }
    }
}
