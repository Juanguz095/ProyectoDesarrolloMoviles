package com.auditlab.agent

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.auditlab.common.*
import com.auditlab.design.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** Checks only update metadata; never downloads, installs or activates a capture session. */
class AgentUpdates(private val context: Context, private val runtime: AgentRuntime) {
    private val mutex=Mutex()
    private var checkedAt=0L
    var request by mutableStateOf<AppUpdateRequest?>(null);private set
    var release by mutableStateOf<AppRelease?>(null);private set
    var message by mutableStateOf("Consulta las versiones aprobadas del servidor.");private set
    var busy by mutableStateOf(false);private set
    private fun credential(): StoredCredential = synchronized(runtime.gate) {
        val state=runtime.core().refresh() as? AgentCore.State.Enrolled ?: error("Primero inscribe este teléfono.")
        check(!runtime.gate.withdrawalRequested && !runtime.gate.storageBlocked)
        val stored=runtime.credentials.load() ?: error("No hay credencial de dispositivo.")
        check(stored.deviceId==state.deviceId && stored.consent.consentId==state.consent.consentId)
        stored
    }
    suspend fun checkNow(automatic: Boolean = false) = mutex.withLock {
        if(automatic) {
            if(!runtime.indicator.isVisible() || android.os.SystemClock.elapsedRealtime()-checkedAt<60_000) return@withLock
            checkedAt=android.os.SystemClock.elapsedRealtime()
        }
        withContext(Dispatchers.Main) {busy=true}
        try {
            val result=withContext(Dispatchers.IO) {
                val credential=credential();val api=ApiClient(runtime.target())
                var current=api.appUpdate(credential.deviceToken,credential.deviceId)
                if(current!=null && current.status in UpdatePolicy.active &&
                    VerifiedApkInstaller.matchesInstalled(context,current.release)) {
                    current=api.appUpdateResult(credential.deviceToken,credential.deviceId,current.requestId,"installed",
                        current.release.versionCode,current.release.signerSha256)
                }
                val versions=if(automatic) emptyList() else api.appReleases(credential.deviceToken,"agent")
                check(credential().deviceId==credential.deviceId)
                current to versions
            }
            withContext(Dispatchers.Main) {
                request=result.first
                if(!automatic) release=result.second.firstOrNull {VerifiedApkInstaller.applicable(context,it)} ?: result.second.firstOrNull()
                message=if(result.first?.status in UpdatePolicy.active) "El administrador solicitó una actualización. Tú decides si instalarla."
                    else if(!automatic && result.second.isEmpty()) "El servidor todavía no tiene versiones aprobadas en su catálogo."
                    else "Consulta completada."
            }
            val pending=result.first
            if(pending!=null && pending.status in UpdatePolicy.active && runtime.indicator.isVisible()) showNotice(pending) else clearNotice()
        } catch(cancelled: CancellationException) {throw cancelled}
        catch(error: Exception) {
            withContext(Dispatchers.Main) {
                message=if(error is ApiException && error.status==404) "El servidor necesita la actualización de API 0.8 para gestionar versiones."
                    else "No se pudo consultar el servidor. Revisa Internet, Tailscale y la inscripción."
            }
        } finally {withContext(NonCancellable+Dispatchers.Main) {busy=false}}
    }
    suspend fun report(current: AppUpdateRequest, status: String, error: String? = null) = mutex.withLock {
        val updated=withContext(Dispatchers.IO) {
            val credential=credential()
            check(current.deviceId==credential.deviceId && Instant.parse(current.expiresAt)>Instant.now())
            if(status=="installed") check(VerifiedApkInstaller.matchesInstalled(context,current.release))
            ApiClient(runtime.target()).appUpdateResult(credential.deviceToken,credential.deviceId,current.requestId,status,
                if(status=="installed") current.release.versionCode else null,
                if(status=="installed") current.release.signerSha256 else null,error)
        }
        withContext(Dispatchers.Main) {request=updated}
        if(updated.status !in UpdatePolicy.active) clearNotice()
    }
    private fun showNotice(value: AppUpdateRequest) {
        if(!runtime.indicator.allowed()) return
        val open=PendingIntent.getActivity(context,7201,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {NotificationManagerCompat.from(context).notify(NOTICE,NotificationCompat.Builder(context,MonitoringIndicator.CHANNEL)
            .setSmallIcon(R.drawable.ic_auditlab).setContentTitle("Actualización de AuditLab disponible")
            .setContentText("Versión ${value.release.versionName}. Abre AuditLab para revisar y confirmar.")
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build())}
    }
    fun clearNotice() {NotificationManagerCompat.from(context).cancel(NOTICE)}
    fun clear() {clearNotice();request=null;release=null}
    companion object {private const val NOTICE=5115}
}

@Composable
fun AgentUpdateControls() {
    val updates=AgentRuntime.get(LocalContext.current).updates
    val scope=rememberCoroutineScope()
    LaunchedEffect(updates) {updates.checkNow()}
    val pending=updates.request?.takeIf {it.status in UpdatePolicy.active && runCatching {Instant.parse(it.expiresAt)>Instant.now()}.getOrDefault(false)}
    AuditPanel {
        Text("Actualizaciones de AuditLab",style=MaterialTheme.typography.titleMedium)
        Text(updates.message)
        updates.request?.let {Text("Solicitud: ${updateStatusLabel(it.status)}")}
        AuditButton(if(updates.busy) "Consultando…" else "Consultar actualizaciones",{scope.launch {updates.checkNow()}},!updates.busy,outlined=true)
        if(pending!=null) AuditButton("Rechazar esta solicitud",{scope.launch {
            try {updates.report(pending,"declined")} catch(cancelled: CancellationException) {throw cancelled}
            catch(_: Exception) {updates.checkNow()}
        }},!updates.busy,outlined=true)
    }
    AppUpdateInstallerCard(pending?.release ?: updates.release,
        onOpened={if(pending!=null) updates.report(pending,"installer_opened")},
        onInstalled={if(pending!=null) updates.report(pending,"installed")},
        onFailed={if(pending!=null) updates.report(pending,"failed",it)})
}
