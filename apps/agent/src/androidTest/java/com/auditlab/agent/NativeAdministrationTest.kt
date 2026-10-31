package com.auditlab.agent

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auditlab.common.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real QA API/Room/notifications, synthetic approved release; never downloads or installs it. */
@RunWith(AndroidJUnit4::class)
class NativeAdministrationTest {
    @Test fun requestNotifiesRecipientLocalDeclinePersistsAndRevocationStopsBasicService() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cfg=JSONObject(File(context.filesDir,"qa-administration.json").readText())
        check(cfg.getBoolean("isolated") && cfg.getString("url")=="http://10.0.2.2:8081")
        val runtime=AgentRuntime.get(context)
        val core=runtime.core(cfg.getString("url"))
        runtime.journal.registration()?.let {
            check(it.serverUrl==cfg.getString("url")) {"Only a loopback QA registration can be replaced"}
            if(it.phase==AgentPhase.ACTIVE) core.revokeLocally()
            core.resetAfterRevocation();runtime.after(core)
        }
        val admin=ControlCore(ApiClient(cfg.getString("url")))
        val manager=context.getSystemService(NotificationManager::class.java)
        val power=context.getSystemService(android.os.PowerManager::class.java)
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun waitFor(check: ()->Boolean) {
            val end=android.os.SystemClock.elapsedRealtime()+60_000
            while(!check()) {if(android.os.SystemClock.elapsedRealtime()>end) fail("QA administration condition timed out");android.os.SystemClock.sleep(300)}
        }
        admin.login("qa_admin",cfg.getString("password"))
        try {
            core.acceptConsent();val device=core.enroll(admin.createEnrollmentCode().code)
            runtime.basicMonitoring.start(autoResume=false)
            waitFor {manager.activeNotifications.any {it.id==5110}}
            val release=admin.appReleases("agent").first()
            val pending=admin.requestAppUpdate(device.deviceId,release.releaseId)
            runBlocking {runtime.updates.checkNow()}
            assertEquals(pending.requestId,runtime.updates.request?.requestId)
            waitFor {manager.activeNotifications.any {it.id==5115}}
            assertEquals("pending",admin.appUpdate(device.deviceId)?.status)
            assertFalse(com.auditlab.design.VerifiedApkInstaller.matchesInstalled(context,release))
            runBlocking {runtime.updates.report(pending,"declined")}
            assertEquals("declined",admin.appUpdate(device.deviceId)?.status)
            assertFalse(manager.activeNotifications.any {it.id==5115})
            waitFor {runtime.journal.stats().sent>0}
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            automation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            waitFor {!power.isInteractive}
            val sentWhileAwake=runtime.journal.stats().sent
            runtime.basicMonitoring.refreshNow()
            waitFor {runtime.journal.stats().sent>sentWhileAwake}
            assertFalse(power.isInteractive)
            assertTrue(manager.activeNotifications.any {it.id==5110})
            automation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            waitFor {power.isInteractive}
            admin.revoke(device.deviceId)
            synchronized(runtime.gate) {
                if(core.refresh() is AgentCore.State.Enrolled) {
                    core.captureRealEvent("device_status","occurred",mapOf("networkState" to "online","appEventCode" to "device_status_changed"))
                    try {core.flushPending();fail("Revoked credential must not send a batch")}
                    catch(error: ApiException) {assertTrue(error.isDeviceRevoked)}
                }
            }
            runtime.after(core)
            assertTrue(core.state is AgentCore.State.Revoked)
            waitFor {manager.activeNotifications.none {it.id==5110 || it.id==5115}}
            assertNull(runtime.basicMonitoring.receipt())
            assertFalse(WhatsAppSharingState.active)
            assertFalse(ScreenSharingState.active)
            File(context.filesDir,"qa-administration-result.json").writeText("""{"passed":true,"real_qa_api":true,"update_notification":true,"local_decline_persisted":true,"screen_off_transmission":true,"home_background":true,"revocation_stops_basic":true,"sensitive_sessions_inactive":true,"apk_downloaded":false,"apk_installed":false}""")
        } finally {
            automation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            runCatching {runtime.basicMonitoring.stop()};runtime.updates.clear()
            runCatching {core.revokeLocally();runtime.after(core)};runCatching {admin.logout()}
        }
    }
}
