package com.auditlab.control

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.auditlab.common.*
import com.auditlab.design.*
import kotlinx.coroutines.*
import java.time.Instant

/** UI and service share one rotating session; concurrent refreshes must not fork credentials. */
internal object SharedControlCore {
    private var url: String?=null
    private var core: ControlCore?=null
    @Synchronized fun get(context: Context,origin: String): ControlCore {
        if(url!=origin || core==null) { url=origin;core=ControlCore(ApiClient(origin),KeystoreSessionStore(context),origin) }
        return core!!
    }
    @Synchronized fun clear(context: Context) {
        context.stopService(Intent(context,WhatsAppAlertsService::class.java))
        try { core?.discardLocalSession() ?: KeystoreSessionStore(context).clear() }
        finally { core=null;url=null }
    }
}

internal object WhatsAppAlertState {
    var device by mutableStateOf<String?>(null)
    var message by mutableStateOf("Alertas detenidas.")
}

/** RAM only. Never notify pre-start history, duplicate IDs or expired content. */
internal class WhatsAppAlertPolicy(private val started: Instant) {
    private val seen=linkedSetOf<String>()
    fun fresh(snapshot: WhatsAppPreviewSnapshot,now: Instant): List<WhatsAppPreview> {
        if(!snapshot.active) return emptyList()
        return snapshot.items.filter { p ->
            val time=runCatching { Instant.parse(p.timestamp) }.getOrNull()
            time!=null && !time.isBefore(started) && !time.isAfter(now.plusSeconds(30)) &&
                time.isAfter(now.minusSeconds(300)) && seen.add(p.messageId)
        }.also { while(seen.size>200) seen.remove(seen.first()) }
    }
}

@Composable
internal fun WhatsAppAlertControls(device: String,label: String) {
    val context=LocalContext.current
    fun start() {
        try {
            context.startForegroundService(Intent(context,WhatsAppAlertsService::class.java)
                .putExtra("device",device).putExtra("label",label))
        } catch(_: Exception) { WhatsAppAlertState.message="No se pudieron iniciar las alertas. Mantén el Centro visible e inténtalo de nuevo." }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) start() else WhatsAppAlertState.message="Permite las notificaciones del Centro para recibir alertas."
    }
    Text("Alertas de WhatsApp",style=MaterialTheme.typography.titleMedium)
    Text("Avisa con el texto de notificaciones nuevas autorizadas. Mantiene un servicio visible en este Centro, sin Firebase. Android puede detenerlo; no captura lo enviado dentro del chat.",style=MaterialTheme.typography.bodySmall)
    Text(WhatsAppAlertState.message,style=MaterialTheme.typography.bodySmall)
    if(WhatsAppAlertState.device!=null) AuditButton("Detener alertas",{
        context.stopService(Intent(context,WhatsAppAlertsService::class.java))
    },outlined=true)
    else AuditButton("Activar alertas para este teléfono",{
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else start()
    },outlined=true)
}

class WhatsAppAlertsService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job: Job?=null
    private val messages=linkedSetOf<String>()
    private val manager get()=getSystemService(NotificationManager::class.java)
    override fun onBind(intent: Intent?) : IBinder?=null
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel("whatsapp-alert-service","Servicio de alertas",NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("whatsapp-alert-messages","Mensajes WhatsApp autorizados",NotificationManager.IMPORTANCE_DEFAULT).apply { lockscreenVisibility=Notification.VISIBILITY_SECRET })
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="stop") { stopSelf();return START_NOT_STICKY }
        if(job?.isActive==true) return START_NOT_STICKY
        val device=intent?.getStringExtra("device")
        val stored=runCatching { KeystoreSessionStore(this).load() }.getOrNull()
        if(device==null || stored?.serverUrl==null || !manager.areNotificationsEnabled() || manager.getNotificationChannel("whatsapp-alert-messages").importance==NotificationManager.IMPORTANCE_NONE) {
            WhatsAppAlertState.message="No se pudieron activar las alertas: inicia sesión y permite las notificaciones del Centro."
            stopSelf();return START_NOT_STICKY
        }
        val label=intent.getStringExtra("label").orEmpty().take(60)
        val stop=PendingIntent.getService(this,0,Intent(this,WhatsAppAlertsService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        try {
            startForeground(7400,Notification.Builder(this,"whatsapp-alert-service")
                .setSmallIcon(com.auditlab.control.R.drawable.ic_auditlab).setContentTitle("AuditLab · Alertas activas")
                .setContentText("Consultando $label cada 5 segundos").setOngoing(true)
                .setContentIntent(open(device)).addAction(Notification.Action.Builder(null,"Detener",stop).build()).build())
        } catch(_: Exception) { WhatsAppAlertState.message="Android no permitió iniciar las alertas.";stopSelf();return START_NOT_STICKY }
        job?.cancel();clearMessages()
        WhatsAppAlertState.device=device
        WhatsAppAlertState.message="Alertas activas para $label."
        val policy=WhatsAppAlertPolicy(Instant.now())
        job=scope.launch {
            try {
                val core=SharedControlCore.get(this@WhatsAppAlertsService,stored.serverUrl!!)
                while(isActive) {
                    if(core.session?.sessionId!=stored.sessionId) break
                    val snapshot=withContext(Dispatchers.IO) { core.whatsAppPreviews(device) }
                    if(!isActive || !core.isSignedIn || core.session?.sessionId!=stored.sessionId) break
                    if(!snapshot.active) { WhatsAppAlertState.message="Alertas detenidas: autoriza una sesión de WhatsApp en el Agente.";break }
                    val available=snapshot.items.map {it.messageId}.toSet()
                    messages.filter {it !in available}.toList().forEach {manager.cancel(it,7401);messages.remove(it)}
                    policy.fresh(snapshot,Instant.now()).forEach { preview ->
                        val notification=Notification.Builder(this@WhatsAppAlertsService,"whatsapp-alert-messages")
                            .setSmallIcon(com.auditlab.control.R.drawable.ic_auditlab)
                            .setContentTitle("$label · ${preview.title.take(200)}")
                            .setContentText(preview.text).setStyle(Notification.BigTextStyle().bigText(preview.text))
                            .setVisibility(Notification.VISIBILITY_SECRET).setTimeoutAfter(300_000)
                            .setContentIntent(open(device)).setAutoCancel(true).build()
                        manager.notify(preview.messageId,7401,notification);messages.add(preview.messageId)
                    }
                    delay(5000)
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { WhatsAppAlertState.message="Alertas detenidas: comprueba la conexión, la sesión y los permisos. Abre el Centro para reactivarlas." }
            finally { stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun open(device: String)=PendingIntent.getActivity(this,0,
        Intent(this,MainActivity::class.java).putExtra("whatsappAlertDevice",device).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun clearMessages() { messages.forEach {manager.cancel(it,7401)};messages.clear() }
    override fun onTimeout(startId: Int,fgsType: Int) { WhatsAppAlertState.message="Android alcanzó el límite de alertas. Abre el Centro para reactivarlas.";stopSelf() }
    override fun onDestroy() {
        job?.cancel();scope.cancel();clearMessages();stopForeground(STOP_FOREGROUND_REMOVE)
        WhatsAppAlertState.device=null
        if(WhatsAppAlertState.message.startsWith("Alertas activas")) WhatsAppAlertState.message="Alertas detenidas."
        super.onDestroy()
    }
}
