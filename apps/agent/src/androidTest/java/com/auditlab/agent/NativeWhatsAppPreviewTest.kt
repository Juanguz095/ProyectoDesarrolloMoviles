package com.auditlab.agent

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auditlab.common.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File

/** Actual Android permission/foreground service and HTTP. Only the callback input is synthetic. */
@RunWith(AndroidJUnit4::class)
class NativeWhatsAppPreviewTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val component="com.auditlab.agent/com.auditlab.agent.WhatsAppNotificationListener"
    private fun until(seconds:Int=90,check:()->Boolean) {
        val end=android.os.SystemClock.elapsedRealtime()+seconds*1000L
        while(android.os.SystemClock.elapsedRealtime()<end) {
            if(check()) return
            android.os.SystemClock.sleep(300)
        }
        throw AssertionError("WhatsApp QA condition timed out")
    }
    private fun startButton()=compose.onNodeWithText("AUTORIZAR E INICIAR SESIÓN")
    private fun openConsent() {
        context.startActivity(Intent(context,WhatsAppShareActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(90000) { compose.onAllNodesWithText("AUTORIZAR E INICIAR SESIÓN").fetchSemanticsNodes().isNotEmpty() }
        startButton().performScrollTo().assertIsNotEnabled()
    }
    private fun permission(allow:Boolean) {
        instrumentation.uiAutomation.executeShellCommand("cmd notification ${if(allow) "allow_listener" else "disallow_listener"} $component").use {
            java.io.FileInputStream(it.fileDescriptor).use { stream->stream.readBytes() }
        }
    }
    private fun authorize() {
        openConsent()
        compose.onNode(isToggleable()).performScrollTo().assertIsOff().performClick()
        startButton().performScrollTo().assertIsEnabled().performClick()
        until { WhatsAppSharingState.active }
    }
    private fun preview(pkg:String,time:Long):StatusBarNotification {
        val me=Person.Builder().setName("QA local").setKey("local").build()
        val sender=Person.Builder().setName("QA contact").setKey("contact").build()
        val n=NotificationCompat.Builder(context,"qa-input").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_MESSAGE).setWhen(time)
            .setStyle(NotificationCompat.MessagingStyle(me).addMessage("QA preview across devices",time,sender)).build()
        return StatusBarNotification(pkg,pkg,17,null,1000,0,0,n,UserHandle.getUserHandleForUid(1000),time)
    }
    @Test fun consentForegroundTransferStopAndPermissionWithdrawal() {
        val cfg=JSONObject(File(context.filesDir,"qa-whatsapp.json").readText())
        check(cfg.getBoolean("isolated") && cfg.getString("url")=="http://10.0.2.2:8081")
        val runtime=AgentRuntime.get(context)
        assertNull(runtime.credentials.load());assertNull(runtime.journal.registration())
        assertFalse("Use a dedicated AVD without an existing notification grant",WhatsAppAccess.granted(context))
        val admin=ControlCore(ApiClient(cfg.getString("url")))
        admin.login("qa_admin",cfg.getString("password"))
        val core=runtime.core(cfg.getString("url"))
        try {
            core.acceptConsent();val enrolled=core.enroll(admin.createEnrollmentCode().code)
            assertTrue(runtime.indicator.show());runtime.after(core)
            openConsent()
            assertFalse(WhatsAppSharingState.active)
            permission(true)
            until { WhatsAppAccess.granted(context) && WhatsAppSharingState.listenerConnected }
            assertFalse("Granting Android access must never start content sharing",WhatsAppSharingState.active)
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            android.os.SystemClock.sleep(700)
            authorize()
            val started=WhatsAppSharingRuntime.current!!.startedAt
            WhatsAppSharingRuntime.offer(context,preview("other.app",System.currentTimeMillis()))
            WhatsAppSharingRuntime.offer(context,preview("com.whatsapp",started-1000))
            assertTrue(admin.whatsAppPreviews(enrolled.deviceId).items.isEmpty())
            val message=preview("com.whatsapp",System.currentTimeMillis())
            WhatsAppSharingRuntime.offer(context,message);WhatsAppSharingRuntime.offer(context,message)
            until { admin.whatsAppPreviews(enrolled.deviceId).items.size==1 }
            assertEquals("QA preview across devices",admin.whatsAppPreviews(enrolled.deviceId).items.single().text)
            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if(cfg.optBoolean("livePairHold")) {
                File(context.filesDir,"qa-whatsapp-ready.json").writeText(JSONObject().put("device",enrolled.deviceId).toString())
                until(240) { File(context.filesDir,"qa-whatsapp-finish").exists() }
            }
            assertTrue("Foreground sharing survives leaving the agent Activity",WhatsAppSharingState.active)
            val manager=context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.first { it.id==WhatsAppSharingService.ID }.notification.actions
                .first { it.title.toString()=="DETENER" }.actionIntent.send()
            until { !WhatsAppSharingState.active && manager.activeNotifications.none { it.id==WhatsAppSharingService.ID } }
            until { !admin.whatsAppPreviews(enrolled.deviceId).active }
            WhatsAppSharingRuntime.offer(context,preview("com.whatsapp",System.currentTimeMillis()))
            assertTrue(admin.whatsAppPreviews(enrolled.deviceId).items.isEmpty())
            if(cfg.optBoolean("livePairHold")) {
                File(context.filesDir,"qa-whatsapp-stopped").writeText("ready")
                until(240) { File(context.filesDir,"qa-whatsapp-viewer-cleared").exists() }
            }
            authorize()
            permission(false)
            until { !WhatsAppSharingState.active }
            until { !admin.whatsAppPreviews(enrolled.deviceId).active }
            assertTrue(admin.whatsAppPreviews(enrolled.deviceId).items.isEmpty())
        } finally {
            WhatsAppSharingRuntime.stop(context);permission(false)
            core.revokeLocally();core.resetAfterRevocation();runtime.indicator.hide();runtime.after(core)
            admin.logout()
        }
    }
}
