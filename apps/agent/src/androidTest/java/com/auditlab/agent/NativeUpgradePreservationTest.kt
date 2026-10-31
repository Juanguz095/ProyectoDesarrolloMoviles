package com.auditlab.agent

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auditlab.common.*
import com.auditlab.design.VerifiedApkInstaller
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Two invocations around a manually confirmed Android installer replacement in a QA emulator. */
@RunWith(AndroidJUnit4::class)
class NativeUpgradePreservationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val runtime get()=AgentRuntime.get(context)
    private fun hash(text: String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {"%02x".format(it)}
    private fun release()=appReleaseFromJson(JSONObject(File(context.filesDir,"qa-upgrade-release.json").readText()))
    @Test fun prepareQueueAndOpenInstallerForLocalConfirmation() {
        val config=JSONObject(File(context.filesDir,"qa-upgrade-config.json").readText())
        check(config.getBoolean("isolated") && config.getString("url")=="http://10.0.2.2:8081")
        val core=runtime.core(config.getString("url"))
        runtime.journal.registration()?.let {
            check(it.serverUrl==config.getString("url"))
            if(it.phase==AgentPhase.ACTIVE) core.revokeLocally()
            core.resetAfterRevocation();runtime.after(core)
        }
        val admin=ControlCore(ApiClient(config.getString("url")))
        admin.login("qa_admin",config.getString("password"))
        try {
            core.acceptConsent();val device=core.enroll(admin.createEnrollmentCode().code)
            assertTrue(runtime.indicator.show())
            core.captureRealEvent("device_status","occurred",mapOf("appEventCode" to "device_status_changed","batteryPercent" to 73))
            val credential=runtime.credentials.load()!!
            val pending=runtime.journal.pending(100)
            assertEquals(1,pending.size)
            val snapshot=JSONObject().put("deviceId",device.deviceId).put("consentId",device.consent.consentId)
                .put("credentialHash",hash(credential.deviceToken)).put("pendingHash",hash(pending.joinToString("\n",transform=EventCodec::encode)))
                .put("beforeVersionCode",VerifiedApkInstaller.version(VerifiedApkInstaller.installed(context)))
            File(context.filesDir,"qa-upgrade-before.json").writeText(snapshot.toString())
            val candidate=release()
            val intent=VerifiedApkInstaller.installerIntent(context,File(context.cacheDir,"app-updates/qa-upgrade.apk"),candidate)
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Finishing instrumentation revokes its URI grants. Wait for the system to stage
            // the APK and show the actual confirmation before ending this first invocation.
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            val end=android.os.SystemClock.elapsedRealtime()+90_000
            var ready=false
            while(android.os.SystemClock.elapsedRealtime()<end) {
                val root=automation.rootInActiveWindow
                if(root?.packageName?.toString()?.contains("packageinstaller")==true &&
                    root.findAccessibilityNodeInfosByText("INSTALL").any {it.text?.toString()?.equals("INSTALL",ignoreCase=true)==true && it.isEnabled}) {
                    ready=true;break
                }
                android.os.SystemClock.sleep(300)
            }
            assertTrue("System installer must stage the verified APK and offer local confirmation",ready)
            // The host operator must inspect Android UI and confirm; no automatic click here.
        } finally {admin.logout()}
    }
    @Test fun verifyEnrollmentKeystoreAndQueueAfterConfirmedReplacement() {
        val before=JSONObject(File(context.filesDir,"qa-upgrade-before.json").readText())
        val candidate=release()
        assertTrue(VerifiedApkInstaller.matchesInstalled(context,candidate))
        assertTrue(candidate.versionCode>before.getLong("beforeVersionCode"))
        val credential=runtime.credentials.load()!!
        assertEquals(before.getString("deviceId"),credential.deviceId)
        assertEquals(before.getString("consentId"),credential.consent.consentId)
        assertEquals(before.getString("credentialHash"),hash(credential.deviceToken))
        assertTrue(runtime.core().refresh() is AgentCore.State.Enrolled)
        val pending=runtime.journal.pending(100)
        assertEquals(1,pending.size)
        assertEquals(before.getString("pendingHash"),hash(pending.joinToString("\n",transform=EventCodec::encode)))
        assertFalse(runtime.realEvents.running)
        assertFalse(WhatsAppSharingState.active)
        assertFalse(ScreenSharingState.active)
        File(context.filesDir,"qa-upgrade-result.json").writeText("""{"passed":true,"installation_confirmed":true,"enrollment_preserved":true,"keystore_credential_preserved":true,"pending_event_preserved":true,"sensitive_sessions_inactive":true}""")
        // Leave this owned QA registration stopped; preserve its evidence until host collection.
        runtime.core().revokeLocally();runtime.after(runtime.core())
    }
}
