package com.auditlab.common

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class AppUpdatesTest {
    private val release=AppRelease(UUID.randomUUID().toString(),"agent","com.auditlab.agent","0.8.0-preview",15,26,1200,
        "a".repeat(64),"b".repeat(64),"https://github.com/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/auditlab-agent-0.8.0-preview.apk")
    @Test fun `upgrade needs correct package increasing code compatible Android and same signer`() {
        assertTrue(release.isUpgrade("com.auditlab.agent",14,26,setOf(release.signerSha256)))
        assertFalse(release.isUpgrade("com.auditlab.agent",15,35,setOf(release.signerSha256)))
        assertFalse(release.isUpgrade("com.auditlab.agent",16,35,setOf(release.signerSha256)))
        assertFalse(release.isUpgrade("com.auditlab.control",14,35,setOf(release.signerSha256)))
        assertFalse(release.isUpgrade("com.auditlab.agent",14,25,setOf(release.signerSha256)))
        assertFalse(release.isUpgrade("com.auditlab.agent",14,35,emptySet()))
        assertFalse(release.isUpgrade("com.auditlab.agent",14,35,setOf("c".repeat(64))))
        assertFalse(release.isUpgrade("com.auditlab.agent",14,35,setOf(release.signerSha256,"c".repeat(64))))
    }
    @Test fun `only exact HTTPS distribution locations and bounded metadata are accepted`() {
        for(url in listOf(release.downloadUrl.replace("https:","http:"),release.downloadUrl+"?token=secret",
            release.downloadUrl.replace("github.com","github.com.evil.example"),release.downloadUrl.replace("Juanguz095","other"),
            release.downloadUrl.replace("auditlab-agent-","other-"),release.downloadUrl.replace("github.com","user@github.com"))) {
            assertThrows(IllegalArgumentException::class.java) {release.copy(downloadUrl=url)}
        }
        assertThrows(IllegalArgumentException::class.java) {release.copy(bytes=AppRelease.MAX_BYTES+1)}
        assertThrows(IllegalArgumentException::class.java) {release.copy(packageName="com.google.service")}
        assertThrows(IllegalArgumentException::class.java) {release.copy(sha256="wrong")}
        assertThrows(IllegalArgumentException::class.java) {release.copy(versionCode=0)}
    }
    @Test fun `catalog metadata survives client serialization without losing integrity fields`() {
        assertEquals(release,appReleaseFromJson(appReleaseJson(release)))
    }
    @Test fun `completed results cannot be silently reopened`() {
        assertTrue(UpdatePolicy.canTransition("pending","installer_opened"))
        assertTrue(UpdatePolicy.canTransition("installer_opened","installed"))
        assertTrue(UpdatePolicy.canTransition("installed","installed"))
        assertFalse(UpdatePolicy.canTransition("installed","installer_opened"))
        assertFalse(UpdatePolicy.canTransition("expired","installed"))
        assertFalse(UpdatePolicy.canTransition("declined","installed"))
    }
}
