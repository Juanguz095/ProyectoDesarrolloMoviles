package com.auditlab.design

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auditlab.common.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The host supplies a newer signed QA APK. Opens and cancels system UI; never confirms installation. */
@RunWith(AndroidJUnit4::class)
class NativeAppUpdateTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun release(): AppRelease = appReleaseFromJson(JSONObject(File(context.filesDir,"qa-update-release.json").readText()))
    private fun file()=File(context.cacheDir,"app-updates/qa-candidate.apk")
    @Test fun validNewerSignedApkProducesOnlyExplicitSystemInstallerIntent() {
        val release=release();val apk=file()
        assertTrue(VerifiedApkInstaller.applicable(context,release))
        assertFalse(VerifiedApkInstaller.matchesInstalled(context,release))
        VerifiedApkInstaller.verify(context,apk,release)
        val intent=VerifiedApkInstaller.installerIntent(context,apk,release)
        @Suppress("DEPRECATION")
        assertEquals(Intent.ACTION_INSTALL_PACKAGE,intent.action)
        assertEquals("content",intent.data!!.scheme)
        assertEquals("${context.packageName}.updates",intent.data!!.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT,false))
        assertNull(intent.component)
        assertFalse(VerifiedApkInstaller.matchesInstalled(context,release))
        val permissions=context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue("android.permission.REQUEST_INSTALL_PACKAGES" in permissions)
        assertFalse("android.permission.INSTALL_PACKAGES" in permissions)
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline=android.os.SystemClock.elapsedRealtime()+30_000
            var shown=false
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                val window=automation.rootInActiveWindow?.packageName?.toString().orEmpty()
                if(window.contains("packageinstaller") || window.contains("permissioncontroller")) {shown=true;break}
                android.os.SystemClock.sleep(300)
            }
            assertTrue("Android's installer or source confirmation must be visible",shown)
            assertFalse(VerifiedApkInstaller.matchesInstalled(context,release))
        } finally {automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)}
    }
    @Test fun corruptFileWrongSignerAndDowngradeAreRejectedBeforeInstaller() {
        val release=release();val original=file()
        val corrupt=File(original.parentFile,"qa-corrupt.apk")
        try {
            original.copyTo(corrupt,overwrite=true)
            java.io.RandomAccessFile(corrupt,"rw").use { it.seek(100);val value=it.readByte();it.seek(100);it.writeByte(value.toInt() xor 1) }
            assertThrows(IllegalStateException::class.java) {VerifiedApkInstaller.verify(context,corrupt,release)}
            assertThrows(IllegalStateException::class.java) {VerifiedApkInstaller.verify(context,original,release.copy(signerSha256="c".repeat(64)))}
            val installed=VerifiedApkInstaller.installed(context)
            assertThrows(IllegalStateException::class.java) {VerifiedApkInstaller.verify(context,original,release.copy(versionCode=VerifiedApkInstaller.version(installed)))}
            assertFalse(VerifiedApkInstaller.matchesInstalled(context,release))
        } finally {corrupt.delete()}
    }
}
