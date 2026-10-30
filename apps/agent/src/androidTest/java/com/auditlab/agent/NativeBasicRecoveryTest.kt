package com.auditlab.agent

import android.app.NotificationManager
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auditlab.common.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Two invocations separated by an actual host-driven reboot, on an isolated QA emulator. */
@RunWith(AndroidJUnit4::class)
class NativeBasicRecoveryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val runtime get()=AgentRuntime.get(context)
    private fun config()=JSONObject(File(context.filesDir,"qa-basic-recovery.json").readText()).also {
        check(it.getBoolean("isolated") && it.getString("url")=="http://10.0.2.2:8081")
    }
    private fun waitFor(check:()->Boolean) {
        val until=android.os.SystemClock.elapsedRealtime()+90_000
        while(!check()) {if(android.os.SystemClock.elapsedRealtime()>until) fail("Basic recovery QA condition timed out");android.os.SystemClock.sleep(300)}
    }
    @Test fun prepareExplicitBasicConsent() {
        val cfg=config()
        val core=runtime.core(cfg.getString("url"))
        val previous=runtime.journal.registration()
        if(previous!=null) {
            check(previous.serverUrl==cfg.getString("url")) {"Only the owned loopback QA fixture may be reset"}
            if(previous.phase==AgentPhase.ACTIVE) core.revokeLocally()
            core.resetAfterRevocation();runtime.after(core)
        }
        assertNull(runtime.credentials.load())
        val admin=ControlCore(ApiClient(cfg.getString("url")))
        admin.login("qa_admin",cfg.getString("password"))
        try {
            core.acceptConsent();core.enroll(admin.createEnrollmentCode().code)
            val activity=ActivityScenario.launch(MainActivity::class.java)
            try {
                runtime.basicMonitoring.start(autoResume=true)
                waitFor {BasicMonitoringState.running}
                waitFor {runtime.journal.stats().let {it.pending+it.sent>0}}
                assertFalse(runtime.realEvents.requested)
                assertNull(WhatsAppSharingRuntime.current)
                assertFalse(ScreenSharingState.active)
                val boot=Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)
                check(boot>=0)
                File(context.filesDir,"qa-basic-before.json").writeText(JSONObject().put("bootCount",boot).toString())
            } finally {activity.close()}
        } finally {admin.logout()}
    }
    @Test fun verifyAfterActualRebootAndStop() {
        config()
        val before=JSONObject(File(context.filesDir,"qa-basic-before.json").readText()).getInt("bootCount")
        assertTrue(Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)>before)
        assertTrue(JSONObject(File(context.filesDir,"qa-basic-observed.json").readText()).getBoolean("serviceStartedBeforeTestRunner"))
        assertTrue(runtime.basicMonitoring.eligible(true))
        // Instrumentation may kill the app process it attaches to. Host already verified
        // autonomous foreground recovery; reattach the same authorized service for stop checks.
        context.startForegroundService(android.content.Intent(context,BasicMonitoringService::class.java).setAction("boot"))
        val manager=context.getSystemService(NotificationManager::class.java)
        waitFor {BasicMonitoringState.running && manager.activeNotifications.any {it.id==BasicMonitoringService.ID}}
        assertTrue(runtime.indicator.isVisible())
        assertFalse(runtime.realEvents.requested)
        assertNull(WhatsAppSharingRuntime.current)
        assertFalse(ScreenSharingState.active)
        val rows=runtime.database.dao().recent(100)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all {EventCodec.decode(it.payloadJson).category=="device_status"})
        manager.activeNotifications.single {it.id==BasicMonitoringService.ID}.notification.actions
            .single {it.title.toString()=="Detener estado básico"}.actionIntent.send()
        waitFor {!BasicMonitoringState.running && manager.activeNotifications.none {it.id==BasicMonitoringService.ID}}
        assertFalse(runtime.basicMonitoring.eligible(true))
        runtime.core().revokeLocally();runtime.after(runtime.core())
        File(context.filesDir,"qa-basic-result.json").writeText("""{"actual_reboot":true,"visible_basic_service":true,"device_status_only":true,"communication_capture_inactive":true,"whatsapp_inactive":true,"screen_share_inactive":true,"stop_disables_recovery":true,"service_reattached_for_instrumentation":true}""")
    }
}
