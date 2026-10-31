package com.auditlab.design

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.auditlab.common.*
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Private cache + explicit system installer. Never installs, grants permissions or runs commands itself. */
object VerifiedApkInstaller {
    @Suppress("DEPRECATION")
    private fun flags() = PackageManager.GET_SIGNATURES or
        (if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
    @Suppress("DEPRECATION")
    fun installed(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName,flags())
    @Suppress("DEPRECATION")
    fun version(info: PackageInfo): Long = if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION")
    fun signers(info: PackageInfo): Set<String> =
        (if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners?.takeIf {it.isNotEmpty()} ?: info.signatures else info.signatures)
            ?.map { digest(it.toByteArray()) }?.toSet().orEmpty()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun applicable(context: Context, release: AppRelease): Boolean {
        val current=installed(context)
        return release.isUpgrade(context.packageName,version(current),Build.VERSION.SDK_INT,signers(current))
    }
    fun matchesInstalled(context: Context, release: AppRelease): Boolean {
        val current=installed(context)
        return current.packageName==release.packageName && version(current)==release.versionCode && signers(current)==setOf(release.signerSha256)
    }

    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, release: AppRelease) {
        check(applicable(context,release)) { "La versión no es compatible, no es superior o tiene otra firma." }
        check(file.isFile && file.length()==release.bytes) { "El tamaño del APK no coincide." }
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(32768);while(true) {val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count)} }
        check(digest.digest().joinToString("") { "%02x".format(it) }==release.sha256) { "El archivo no supera la comprobación de integridad." }
        val archive=context.packageManager.getPackageArchiveInfo(file.absolutePath,flags()) ?: error("El archivo no es un APK válido.")
        check(archive.packageName==release.packageName) { "El paquete del APK no coincide." }
        check(version(archive)==release.versionCode && archive.versionName==release.versionName) { "La versión del APK no coincide." }
        check(signers(archive)==setOf(release.signerSha256)) { "El certificado del APK no coincide." }
        check(archive.applicationInfo?.minSdkVersion==release.minSdk) { "La compatibilidad Android del APK no coincide." }
    }

    suspend fun download(context: Context, release: AppRelease): File = withContext(Dispatchers.IO) {
        check(applicable(context,release)) { "Esta actualización no corresponde a esta aplicación." }
        val directory=File(context.cacheDir,"app-updates").also { check(it.mkdirs() || it.isDirectory) }
        // One file per approved release; no shared/external storage or path from the server.
        val partial=File(directory,"${release.releaseId}.part")
        val target=File(directory,"${release.releaseId}.apk")
        var url=release.downloadUrl
        var connection: HttpURLConnection?=null
        var complete=false
        try {
            for(hop in 0..3) {
                coroutineContext.ensureActive()
                check(AppRelease.allowedUrl(url,release.app,release.versionName)) { "Origen de descarga no permitido." }
                connection=URI(url).toURL().openConnection() as HttpURLConnection
                connection!!.apply { connectTimeout=15_000;readTimeout=30_000;instanceFollowRedirects=false;setRequestProperty("Accept-Encoding","identity") }
                val status=connection!!.responseCode
                if(status in setOf(301,302,303,307,308)) {
                    val next=connection!!.getHeaderField("Location") ?: error("Redirección inválida.")
                    url=URI(url).resolve(next).toString();connection!!.disconnect();connection=null
                    check(hop<3) { "Demasiadas redirecciones." };continue
                }
                check(status==200) { "El servidor de descargas no entregó el APK." }
                val deadline=android.os.SystemClock.elapsedRealtime()+120_000
                connection!!.inputStream.use { input -> partial.outputStream().use { output ->
                    val buffer=ByteArray(32768);var total=0L
                    while(true) {
                        coroutineContext.ensureActive()
                        check(android.os.SystemClock.elapsedRealtime()<=deadline) { "La descarga tardó demasiado." }
                        val count=input.read(buffer);if(count<0) break
                        total+=count;check(total<=release.bytes) { "Descarga superior al tamaño aprobado." };output.write(buffer,0,count)
                    }
                    check(total==release.bytes) { "Descarga incompleta." }
                } }
                verify(context,partial,release)
                check(partial.renameTo(target)) { "No se pudo conservar el APK verificado." }
                complete=true;break
            }
            check(complete)
            target
        } finally { connection?.disconnect();partial.delete() }
    }

    fun installerIntent(context: Context, file: File, release: AppRelease): Intent {
        verify(context,file,release)
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",file)
        @Suppress("DEPRECATION")
        return Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true)
    }
}

/** The download starts only from this local button. Returning from Settings never auto-starts it. */
@Composable
fun AppUpdateInstallerCard(release: AppRelease?, onOpened: suspend () -> Unit = {},
    onInstalled: suspend () -> Unit = {}, onFailed: suspend (String) -> Unit = {}) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var busy by remember(release?.releaseId) { mutableStateOf(false) }
    var message by remember(release?.releaseId) { mutableStateOf<String?>(null) }
    val installed=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch {
            try {
                if(release!=null && withContext(Dispatchers.IO) {VerifiedApkInstaller.matchesInstalled(context,release)}) {
                    withContext(Dispatchers.IO) {onInstalled()};message="Actualización verificada en este teléfono."
                } else message="No se confirmó la instalación. Puedes volver a intentarlo o rechazar la solicitud."
            } catch(_: Exception) {message="No se pudo confirmar el resultado con el servidor. Actualiza la consulta para reintentar."}
        }
    }
    AuditPanel {
        Text("Actualizar esta aplicación",style=MaterialTheme.typography.titleMedium)
        Text("Versión instalada: ${VerifiedApkInstaller.installed(context).versionName}")
        if(release!=null) {
            Text("Versión disponible: ${release.versionName}")
            val eligible=runCatching {VerifiedApkInstaller.applicable(context,release)}.getOrDefault(false)
            if(!eligible) Text("Esta versión ya está instalada o no es compatible con este teléfono y su firma.",color=AuditColors.Muted)
            Text("Descarga verificada. Android te pedirá confirmar la instalación. No se conceden permisos automáticamente.",style=MaterialTheme.typography.bodySmall)
            AuditButton(if(busy) "Descargando y verificando…" else "Descargar e instalar",{
                if(!context.packageManager.canRequestPackageInstalls()) {
                    message="Android necesita que autorices esta fuente. Vuelve y pulsa Descargar e instalar otra vez."
                    try {context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))}
                    catch(_: Exception) {message="Abre Ajustes de Android → Instalar aplicaciones desconocidas y revisa AuditLab."}
                } else scope.launch {
                    busy=true;message="Descargando la versión aprobada…"
                    var phase="DOWNLOAD_FAILED"
                    try {
                        val file=VerifiedApkInstaller.download(context,release)
                        phase="VERIFICATION_FAILED"
                        val intent=withContext(Dispatchers.IO) {VerifiedApkInstaller.installerIntent(context,file,release)}
                        withContext(Dispatchers.IO) {onOpened()}
                        phase="INSTALLER_UNAVAILABLE"
                        installed.launch(intent);message="Instalador abierto. Confirma o cancela en Android."
                    } catch(cancelled: CancellationException) {throw cancelled}
                    catch(_: Exception) {
                        message="No se pudo descargar, verificar o abrir la actualización. Conservamos la aplicación actual."
                        runCatching {withContext(Dispatchers.IO) {onFailed(phase)}}
                    } finally {busy=false}
                }
            },eligible && !busy)
        } else Text("No hay una versión aprobada seleccionada. Consulta las actualizaciones del servidor.",color=AuditColors.Muted)
        message?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
    }
}

fun updateStatusLabel(status: String) = when(status) {
    "pending" -> "Esperando confirmación en el teléfono"
    "installer_opened" -> "Instalación pendiente de verificar"
    "installed" -> "Versión y firma verificadas por el Agente"
    "declined" -> "Rechazada en el teléfono"
    "failed" -> "Actualización fallida"
    "expired" -> "Solicitud vencida"
    else -> "Estado no disponible"
}
