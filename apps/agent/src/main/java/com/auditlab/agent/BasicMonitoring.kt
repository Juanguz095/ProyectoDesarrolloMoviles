package com.auditlab.agent

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.auditlab.common.*
import com.auditlab.design.*
import kotlinx.coroutines.*

internal data class BasicRecoveryReceipt(val device: String,val consent: String,val autoResume: Boolean)
internal object BasicRecoveryPolicy {
    fun allows(receipt: BasicRecoveryReceipt?,state: AgentCore.State,paused: Boolean,notifications: Boolean,
               withdrawn: Boolean,storageBlocked: Boolean,automatic: Boolean): Boolean =
        receipt!=null && (!automatic || receipt.autoResume) && !paused && notifications && !withdrawn && !storageBlocked &&
            state is AgentCore.State.Enrolled && state.deviceId==receipt.device && state.consent.consentId==receipt.consent
}
internal object BasicMonitoringState {
    var running by mutableStateOf(false)
    var message by mutableStateOf("Estado básico detenido.")
}

/** Receipt only, no device token. No communication readers or sharing services are started here. */
class BasicMonitoring(private val context: Context,private val runtime: AgentRuntime) {
    private val preferences=context.getSharedPreferences("auditlab_basic_recovery",Context.MODE_PRIVATE)
    internal fun receipt(): BasicRecoveryReceipt? {
        if(!preferences.getBoolean("enabled",false)) return null
        val device=preferences.getString("device",null) ?: return null
        val consent=preferences.getString("consent",null) ?: return null
        return BasicRecoveryReceipt(device,consent,preferences.getBoolean("auto",false))
    }
    internal fun eligible(automatic: Boolean): Boolean = synchronized(runtime.gate) {
        BasicRecoveryPolicy.allows(receipt(),runtime.core().refresh(),runtime.indicator.isPaused(),runtime.indicator.allowed(),
            runtime.gate.withdrawalRequested,runtime.gate.storageBlocked,automatic)
    }
    fun start(autoResume: Boolean) = synchronized(runtime.gate) {
        val state=runtime.core().refresh() as? AgentCore.State.Enrolled ?: error("Primero inscribe este dispositivo.")
        check(runtime.indicator.allowed()) { "Permite la notificación visible antes de iniciar." }
        check(!runtime.gate.withdrawalRequested && !runtime.gate.storageBlocked)
        runtime.realEvents.stop()
        check(runtime.indicator.show()) { "No se pudo mostrar el indicador visible." }
        if(!preferences.edit().putBoolean("enabled",true).putString("device",state.deviceId)
                .putString("consent",state.consent.consentId).putBoolean("auto",autoResume).commit()) throw StorageFailure()
        try { ContextCompat.startForegroundService(context,Intent(context,BasicMonitoringService::class.java)) }
        catch(_: Exception) { stop();error("Android no permitió iniciar el servicio. Mantén la app abierta e inténtalo de nuevo.") }
    }
    fun stop() {
        if(!preferences.edit().clear().commit()) {
            runtime.gate.storageBlocked=true
            runtime.indicator.hide()
            throw StorageFailure()
        }
        context.stopService(Intent(context,BasicMonitoringService::class.java))
        BasicMonitoringState.running=false
        BasicMonitoringState.message="Estado básico detenido. No se reanudará al encender."
    }
    fun refreshNow() {
        check(BasicMonitoringState.running && eligible(false))
        ContextCompat.startForegroundService(context,Intent(context,BasicMonitoringService::class.java).setAction("refresh"))
    }
}

/** System boot only, after credential-encrypted storage becomes available. */
class BasicBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action!=Intent.ACTION_BOOT_COMPLETED) return
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                val runtime=AgentRuntime.get(context)
                if(runtime.basicMonitoring.eligible(automatic=true))
                    ContextCompat.startForegroundService(context,Intent(context,BasicMonitoringService::class.java).setAction("boot"))
            } catch(_: Exception) { BasicMonitoringState.message="No se pudo reanudar el estado básico. Abre AuditLab para revisar permisos y consentimiento." }
            finally { pending.finish() }
        }
    }
}

class BasicMonitoringService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var loop: Job?=null
    @Volatile private var forceStatus=false
    private lateinit var runtime: AgentRuntime
    override fun onCreate() {super.onCreate();runtime=AgentRuntime.get(this)}
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action==STOP) {
            try {runtime.basicMonitoring.stop()} catch(_: Exception) {BasicMonitoringState.message="No se confirmó el guardado de la detención. Transmisiones bloqueadas."}
            finally {stopSelf()}
            return START_NOT_STICKY
        }
        // Promote immediately, before disk/Keystore work, to meet Android's foreground deadline.
        try {
            val stop=PendingIntent.getService(this,0,Intent(this,BasicMonitoringService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
            val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
            val notification=NotificationCompat.Builder(this,MonitoringIndicator.CHANNEL)
                .setSmallIcon(R.drawable.ic_auditlab).setContentTitle("AuditLab · Batería y conexión")
                .setContentText("Estado básico visible. Toca para revisar o detener.")
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).addAction(0,"Detener estado básico",stop).build()
            if(Build.VERSION.SDK_INT>=34) startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID,notification)
        } catch(_: Exception) {stopSelf();return START_NOT_STICKY}
        val automatic=intent==null || intent.action=="boot"
        if(intent?.action=="refresh") forceStatus=true
        if(loop==null) loop=scope.launch {
            try {
                if(!runtime.basicMonitoring.eligible(automatic) || !runtime.indicator.show()) {runtime.basicMonitoring.stop();stopSelf();return@launch}
                BasicMonitoringState.running=true
                var last: Map<String,Any?>?=null
                var lastAt=0L
                while(isActive) {
                    synchronized(runtime.gate) {
                        if(!runtime.basicMonitoring.eligible(false) || !runtime.indicator.isVisible()) {runtime.basicMonitoring.stop();stopSelf();return@synchronized}
                        val elapsed=SystemClock.elapsedRealtime()
                        val metadata=runtime.metadata().filterKeys {it in setOf("batteryPercent","networkState")}
                        if(forceStatus || metadata!=last || elapsed-lastAt>=60_000) {
                            runtime.core().captureRealEvent("device_status","occurred",metadata+mapOf("appEventCode" to "device_status_changed"))
                            last=metadata;lastAt=elapsed;forceStatus=false
                        }
                        BasicMonitoringState.message="Estado básico activo: batería y conexión. La red o Android pueden retrasar el envío."
                        try { runtime.core().flushPending(maxBatches=1) }
                        catch(_: ApiException) {BasicMonitoringState.message="Estado básico guardado. Sin confirmación de envío; se reintentará."}
                        runtime.after(runtime.core())
                    }
                    delay(10_000)
                }
            } catch(cancelled: CancellationException) {throw cancelled}
            catch(_: Exception) {BasicMonitoringState.message="Estado básico detenido por un error. Abre AuditLab para revisar la inscripción.";stopSelf()}
        }
        // Null-intent restarts are rechecked against the same opt-in receipt in the coroutine.
        return if(runCatching {runtime.basicMonitoring.receipt()?.autoResume==true}.getOrDefault(false)) START_STICKY else START_NOT_STICKY
    }
    override fun onDestroy() {
        scope.cancel();BasicMonitoringState.running=false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {const val ID=5110;const val STOP="com.auditlab.agent.STOP_BASIC"}
}

@Composable
internal fun BasicMonitoringControls() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var autoResume by remember {mutableStateOf(runCatching {AgentRuntime.get(context).basicMonitoring.receipt()?.autoResume==true}.getOrDefault(false))}
    var busy by remember {mutableStateOf(false)}
    LaunchedEffect(BasicMonitoringState.running) {
        if(!BasicMonitoringState.running && runCatching {AgentRuntime.get(context).basicMonitoring.receipt()==null}.getOrDefault(false)) autoResume=false
    }
    fun start() {scope.launch {
        busy=true
        try {withContext(Dispatchers.IO) {AgentRuntime.get(context).basicMonitoring.start(autoResume)}}
        catch(_: Exception) {BasicMonitoringState.message="No se pudo iniciar. Revisa inscripción, consentimiento y permiso de notificaciones."}
        finally {busy=false}
    }}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) start() else BasicMonitoringState.message="El estado básico necesita una notificación visible. Permiso denegado."
    }
    AuditPanel {
        Text("Batería y conexión",style=MaterialTheme.typography.titleMedium)
        Text("Solo estado básico, sin leer mensajes ni llamadas. Se mantiene un servicio visible y puedes detenerlo.")
        Row {Checkbox(autoResume,{autoResume=it},enabled=!busy && !BasicMonitoringState.running);Text("Reanudar estado básico al encender, mientras siga vigente mi consentimiento.")}
        Text("Esta opción se guarda al pulsar Activar batería y conexión.",style=MaterialTheme.typography.bodySmall)
        Text("No funciona con el teléfono apagado. Android puede detenerlo por sus restricciones.",style=MaterialTheme.typography.bodySmall)
        Text(BasicMonitoringState.message)
        if(BasicMonitoringState.running) AuditButton("Detener estado básico",{scope.launch {
            try {withContext(Dispatchers.IO) {AgentRuntime.get(context).basicMonitoring.stop()}}
            catch(_: Exception) {BasicMonitoringState.message="No se pudo confirmar el guardado de la detención. Revisa el estado y retira el consentimiento si es necesario."}
        }},!busy,outlined=true)
        else AuditButton("Activar batería y conexión",{
            if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else start()
        },!busy)
    }
}
