package com.auditlab.control

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auditlab.common.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real Android service/notifications and real QA API; message input is synthetic, not WhatsApp. */
@RunWith(AndroidJUnit4::class)
class NativeWhatsAppAlertsTest {
    @Test fun nativeAlertsContainTextSurviveHomeOpenEventsAndStop() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val context=inst.targetContext
        val cfg=JSONObject(File(context.filesDir,"qa-alerts.json").readText())
        check(cfg.getBoolean("isolated") && cfg.getString("url")=="http://10.0.2.2:8081")
        val store=KeystoreSessionStore(context)
        assertNull("Dedicated QA emulator must have no user session",store.load())
        val api=ApiClient(cfg.getString("url"))
        val user=api.login("qa_admin",cfg.getString("password"))
        val consent=ConsentContext(UUID.randomUUID().toString(),"mvp-1",Instant.now().toString())
        val device=api.enroll(api.createEnrollmentCode(user.accessToken).code,consent,"qa-alerts")
        val sharing=UUID.randomUUID().toString()
        api.beginWhatsApp(device.deviceToken,device.deviceId,sharing,Instant.now().toString(),consent)
        val heartbeat=Executors.newSingleThreadScheduledExecutor()
        heartbeat.scheduleAtFixedRate({runCatching {api.heartbeatWhatsApp(device.deviceToken,device.deviceId,sharing)}},2,8,TimeUnit.SECONDS)
        store.save(user)
        val manager=context.getSystemService(NotificationManager::class.java)
        fun waitFor(check: ()->Boolean) {
            val end=android.os.SystemClock.elapsedRealtime()+90_000
            while(android.os.SystemClock.elapsedRealtime()<end) {
                if(check()) return
                android.os.SystemClock.sleep(400)
            }
            fail("Native alerts QA condition timed out")
        }
        fun message(text: String)=WhatsAppPreview(UUID.randomUUID().toString(),Instant.now().toString(),"com.whatsapp","Contacto QA",text)
        var scenario: ActivityScenario<MainActivity>?=null
        try {
            val old=message("QA prior history must not notify")
            api.sendWhatsAppPreview(device.deviceToken,device.deviceId,sharing,old)
            scenario=ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity -> activity.startForegroundService(Intent(activity,WhatsAppAlertsService::class.java)
                .putExtra("device",device.deviceId).putExtra("label","Teléfono QA")) }
            waitFor { manager.activeNotifications.any {it.id==7400} }
            android.os.SystemClock.sleep(2000)
            assertFalse(manager.activeNotifications.any {it.id==7401})
            inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            val new=message("QA real Android alert with synthetic input")
            api.sendWhatsAppPreview(device.deviceToken,device.deviceId,sharing,new)
            waitFor {manager.activeNotifications.any {it.tag==new.messageId && it.id==7401} }
            val notification=manager.activeNotifications.single {it.tag==new.messageId}
            assertEquals(new.text,notification.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
            assertEquals(Notification.VISIBILITY_SECRET,notification.notification.visibility)
            File(context.filesDir,"qa-alerts-native-result.json").writeText("""{"message_notification":true,"text_matches":true,"home_background":true,"lockscreen_secret":true}""")
            val posted=notification.postTime
            api.sendWhatsAppPreview(device.deviceToken,device.deviceId,sharing,new)
            android.os.SystemClock.sleep(6500)
            assertEquals(posted,manager.activeNotifications.single {it.tag==new.messageId}.postTime)
            notification.notification.contentIntent.send()
            waitFor {
                fun find(node: android.view.accessibility.AccessibilityNodeInfo?):Boolean = node!=null &&
                    (node.text?.toString()=="ACTIVIDAD RECIBIDA DEL TELÉFONO" || (0 until node.childCount).any {find(node.getChild(it))})
                find(inst.uiAutomation.rootInActiveWindow)
            }
            manager.activeNotifications.single {it.id==7400}.notification.actions
                .single {it.title.toString()=="Detener"}.actionIntent.send()
            waitFor {manager.activeNotifications.none {it.id in setOf(7400,7401)}}
            assertNull(WhatsAppAlertState.device)
            scenario.onActivity { activity -> activity.startForegroundService(Intent(activity,WhatsAppAlertsService::class.java)
                .putExtra("device",device.deviceId).putExtra("label","Teléfono QA")) }
            waitFor {manager.activeNotifications.any {it.id==7400}}
            api.endWhatsApp(device.deviceToken,device.deviceId,sharing)
            waitFor {manager.activeNotifications.none {it.id in setOf(7400,7401)}}
            assertNull(WhatsAppAlertState.device)
            File(context.filesDir,"qa-alerts-native-result.json").writeText("""{"passed":true,"message_notification":true,"text_matches":true,"home_background":true,"lockscreen_secret":true,"deduplicated":true,"tap_opens_events":true,"stop_button_clears_notifications":true,"source_stop_clears_notifications":true,"synthetic_input":true,"real_whatsapp":false}""")
        } finally {
            context.stopService(Intent(context,WhatsAppAlertsService::class.java))
            heartbeat.shutdownNow()
            runCatching {api.endWhatsApp(device.deviceToken,device.deviceId,sharing)}
            scenario?.close()
            SharedControlCore.clear(context)
            runCatching {api.logout(user.accessToken)}
            store.clear()
        }
    }
}
