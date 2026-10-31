package com.auditlab.common

import java.net.URI
import java.util.UUID

/** Only AuditLab's two packages can be distributed. No commands or arbitrary APK URLs. */
data class AppRelease(val releaseId: String, val app: String, val packageName: String,
    val versionName: String, val versionCode: Long, val minSdk: Int, val bytes: Long,
    val sha256: String, val signerSha256: String, val downloadUrl: String) {
    init {
        require(UUID.fromString(releaseId).toString() == releaseId)
        require(app in setOf("agent", "control") && packageName == "com.auditlab.$app")
        require(versionName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")))
        require(versionCode in 1..Int.MAX_VALUE.toLong() && minSdk in 26..100)
        require(bytes in 1..MAX_BYTES && sha256.matches(HEX) && signerSha256.matches(HEX))
        require(allowedUrl(downloadUrl, app, versionName))
    }
    fun isUpgrade(installedPackage: String, installedCode: Long, sdk: Int, installedSigners: Set<String>) =
        packageName == installedPackage && versionCode > installedCode && minSdk <= sdk &&
            installedSigners == setOf(signerSha256)

    companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
        private val HEX = Regex("[0-9a-f]{64}")
        fun allowedUrl(value: String, app: String, version: String): Boolean = runCatching {
            val uri = URI(value)
            val file = "auditlab-$app-$version.apk"
            uri.scheme == "https" && uri.port == -1 && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                when (uri.host) {
                    "github.com" -> uri.rawPath == "/Juanguz095/ProyectoDesarrolloMoviles/raw/refs/heads/descargas-auditlab/$file"
                    "raw.githubusercontent.com" -> uri.rawPath == "/Juanguz095/ProyectoDesarrolloMoviles/refs/heads/descargas-auditlab/$file" ||
                        uri.rawPath == "/Juanguz095/ProyectoDesarrolloMoviles/descargas-auditlab/$file"
                    else -> false
                }
        }.getOrDefault(false)
    }
}

data class AppUpdateRequest(val requestId: String, val deviceId: String, val release: AppRelease,
    val status: String, val requestedAt: String, val expiresAt: String, val updatedAt: String,
    val errorCode: String? = null)

object UpdatePolicy {
    val active = setOf("pending", "installer_opened")
    val results = setOf("installer_opened", "installed", "declined", "failed")
    val errors = setOf("DOWNLOAD_FAILED", "VERIFICATION_FAILED", "INSTALLER_UNAVAILABLE", "LOCAL_CANCELLED")
    fun canTransition(from: String, to: String) = from == to ||
        (from in active && to in results && !(from == "installer_opened" && to == "pending"))
}

fun appReleaseFromJson(node: org.json.JSONObject) = AppRelease(node.getString("releaseId"),node.getString("app"),
    node.getString("packageName"),node.getString("versionName"),node.getLong("versionCode"),node.getInt("minSdk"),
    node.getLong("bytes"),node.getString("sha256"),node.getString("signerSha256"),node.getString("downloadUrl"))

fun appReleaseJson(value: AppRelease) = org.json.JSONObject().put("releaseId",value.releaseId).put("app",value.app)
    .put("packageName",value.packageName).put("versionName",value.versionName).put("versionCode",value.versionCode)
    .put("minSdk",value.minSdk).put("bytes",value.bytes).put("sha256",value.sha256)
    .put("signerSha256",value.signerSha256).put("downloadUrl",value.downloadUrl)

fun appUpdateFromJson(node: org.json.JSONObject) = AppUpdateRequest(node.getString("requestId"),node.getString("deviceId"),
    appReleaseFromJson(node.getJSONObject("release")),node.getString("status"),node.getString("requestedAt"),
    node.getString("expiresAt"),node.getString("updatedAt"),if(node.isNull("errorCode")) null else node.getString("errorCode"))
