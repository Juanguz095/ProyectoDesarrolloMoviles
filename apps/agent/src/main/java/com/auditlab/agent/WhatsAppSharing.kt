package com.auditlab.agent

import android.app.*
import android.content.*
import android.os.*
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.auditlab.common.*
import com.auditlab.design.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.time.Instant
import java.util.UUID

object WhatsAppSharingState {
    var active by mutableStateOf(false)
    var listenerConnected by mutableStateOf(false)
    var message by mutableStateOf("No se comparten notificaciones de WhatsApp.")
}

object WhatsAppAccess {
    fun granted(context: Context): Boolean {
        val component=ComponentName(context,WhatsAppNotificationListener::class.java)
        return if(Build.VERSION.SDK_INT>=27) context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        else Settings.Secure.getString(context.contentResolver,"enabled_notification_listeners").orEmpty()
            .split(':').mapNotNull(ComponentName::unflattenFromString).contains(component)
    }
}

/** Permission and fresh local checkbox are distinct; granting Android access never starts capture. */
class WhatsAppShareActivity : ComponentActivity() {
    private var access by mutableStateOf(false)
    override fun onResume() {
        super.onResume();access=WhatsAppAccess.granted(this)
        if(access && !WhatsAppSharingState.listenerConnected)
            NotificationListenerService.requestRebind(ComponentName(this,WhatsAppNotificationListener::class.java))
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { AuditTheme {
            var accepted by remember { mutableStateOf(false) }
            AuditPage(key="whatsapp-consent") {
                AuditHeader("NOTIFICACIONES DE WHATSAPP","AUTORIZACIÓN ADICIONAL")
                Text("Se compartirán el título o remitente y el texto de las notificaciones nuevas de WhatsApp y WhatsApp Business con los administradores y auditores de tu Centro.")
                Text("También quedará en el historial un evento sin contenido: notificación recibida o respuesta propia observada en una notificación. El registro guarda tipo y fecha, sin texto, nombres ni números; utiliza la retención de eventos del sistema.")
                Text("Los mensajes escritos dentro de un chat abierto normalmente no producen una notificación y no se pueden detectar con este permiso. Una respuesta observada no confirma que se haya entregado al destinatario.")
                Text("Android concede acceso general a notificaciones; AuditLab procesa únicamente las de WhatsApp. No recupera chats, mensajes anteriores, archivos ni texto ocultado por Android o WhatsApp.")
                Text("Últimas 20 vistas previas, hasta 5 minutos en memoria del servidor. Al detener se retiran; sin conexión el servidor puede tardar hasta 30 segundos. No entran en Room, reportes CSV ni copias de la base de datos.")
                AuditButton("Configurar acceso de Android", {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                })
                Text(if(access) "Acceso de Android concedido." else "Acceso de Android pendiente. No se captura contenido.")
                Text(if(WhatsAppSharingState.listenerConnected) "Servicio de notificaciones conectado." else "Android aún no conectó el servicio.")
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(accepted,{accepted=it})
                    Text("Autorizo compartir estas vistas previas y registrar los eventos de actividad sin contenido durante esta sesión. Puedo detenerla desde la app o la notificación.",Modifier.weight(1f))
                }
                AuditButton("Autorizar e iniciar sesión", {
                    startForegroundService(Intent(this@WhatsAppShareActivity,WhatsAppSharingService::class.java)
                        .putExtra("acceptedAt",System.currentTimeMillis()))
                    finish()
                },accepted && access && WhatsAppSharingState.listenerConnected && !WhatsAppSharingState.active)
                AuditButton("Volver",{finish()},outlined=true)
            }
        } }
    }
}

/** The OS may bind this listener after a restart; a fresh local session is still required. */
class WhatsAppNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { WhatsAppSharingState.listenerConnected=true }
    override fun onListenerDisconnected() {
        WhatsAppSharingState.listenerConnected=false
        WhatsAppSharingRuntime.stop(this)
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) { WhatsAppSharingRuntime.offer(this,sbn) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if(sbn.packageName==packageName && sbn.id==WhatsAppSharingService.ID && WhatsAppSharingState.active) WhatsAppSharingRuntime.stop(this)
    }
}

object WhatsAppSharingRuntime {
    class Session(val id: String,val startedAt: Long) {
        val queue=Channel<WhatsAppObservation>(20,BufferOverflow.DROP_OLDEST)
        val seen=linkedSetOf<String>()
        val replyHistory=linkedMapOf<String,String?>()
    }
    @Volatile var current: Session? = null; private set
    @Synchronized fun activate(session: Session) { current?.queue?.cancel();current=session;WhatsAppSharingState.active=true }
    @Synchronized fun clear() { current?.queue?.cancel();current=null;WhatsAppSharingState.active=false }
    fun allowed(context: Context): Boolean {
        val runtime=AgentRuntime.get(context)
        val manager=context.getSystemService(NotificationManager::class.java)
        return !runtime.gate.withdrawalRequested && runtime.indicator.isVisible() && WhatsAppAccess.granted(context) &&
            WhatsAppSharingState.listenerConnected && manager.getNotificationChannel(WhatsAppSharingService.CHANNEL)?.importance?.let { it!=NotificationManager.IMPORTANCE_NONE }==true &&
            (!WhatsAppSharingState.active || manager.activeNotifications.any { it.id==WhatsAppSharingService.ID })
    }
    fun stop(context: Context) { clear();context.stopService(Intent(context,WhatsAppSharingService::class.java)) }
    fun offer(context: Context,sbn: StatusBarNotification) {
        val session=current ?: return
        if(!allowed(context)) { stop(context);return }
        if(sbn.packageName !in WhatsAppPreviewPolicy.PACKAGES || sbn.postTime<session.startedAt || sbn.postTime>System.currentTimeMillis()+300000 ||
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY!=0) return
        val rows=runCatching { WhatsAppNotificationParser.observe(sbn,session.id,session.startedAt) }.getOrElse { emptyList() }.toMutableList()
        synchronized(session) {
            if(current!==session) return
            // RemoteInputHistory has no per-message timestamp. Establish a baseline
            // first; only a later changed front entry can describe a new local reply.
            if(sbn.notification.category==Notification.CATEGORY_MESSAGE) {
                val history=runCatching { sbn.notification.extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)?.firstOrNull()?.toString()?.takeIf(String::isNotBlank) }.getOrNull()
                val fingerprint=history?.let { UUID.nameUUIDFromBytes(it.toByteArray(Charsets.UTF_8)).toString() }
                val known=session.replyHistory.containsKey(sbn.key)
                val previous=session.replyHistory.put(sbn.key,fingerprint)
                if(known && fingerprint!=null && fingerprint!=previous && rows.none { it.direction=="outbound" }) {
                    val key=UUID.nameUUIDFromBytes("${sbn.key}|${sbn.postTime}|outbound".toByteArray(Charsets.UTF_8)).toString()
                    rows += WhatsAppObservation(key,Instant.ofEpochMilli(sbn.postTime).toString(),"outbound","whatsapp_reply_observed",null)
                }
                while(session.replyHistory.size>128) session.replyHistory.remove(session.replyHistory.keys.first())
            }
            for(row in rows) if(session.seen.add(row.key)) session.queue.trySend(row)
            while(session.seen.size>512) session.seen.remove(session.seen.first())
        }
    }
}

class WhatsAppSharingService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var sender: Job?=null
    private var credential: StoredCredential?=null
    private var sessionId: String?=null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="stop") { WhatsAppSharingRuntime.clear();stopSelf();return START_NOT_STICKY }
        if(sender!=null) return START_NOT_STICKY
        val acceptedAt=intent?.getLongExtra("acceptedAt",0) ?: 0
        val runtime=AgentRuntime.get(this)
        val stored=runCatching { runtime.credentials.load() }.getOrNull()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Textos de WhatsApp compartidos",NotificationManager.IMPORTANCE_LOW))
        if(acceptedAt !in (System.currentTimeMillis()-30000)..System.currentTimeMillis() || stored?.serverUrl==null || !WhatsAppSharingRuntime.allowed(this)) {
            WhatsAppSharingState.message="No se inició: revisa inscripción, permiso de Android e indicador visible."
            stopSelf();return START_NOT_STICKY
        }
        credential=stored
        val id=UUID.randomUUID().toString();sessionId=id
        val stop=PendingIntent.getService(this,0,Intent(this,javaClass).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        startForeground(ID,NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_auditlab)
            .setContentTitle("AuditLab · Actividad y vistas previas de WhatsApp")
            .setContentText("Vistas previas nuevas autorizadas. Pulsa DETENER para finalizar.")
            .setOngoing(true).setOnlyAlertOnce(true).addAction(0,"DETENER",stop).build())
        WhatsAppSharingState.message="Iniciando la sesión autorizada…"
        sender=scope.launch {
            val api=ApiClient(stored.serverUrl!!)
            val session=WhatsAppSharingRuntime.Session(id,acceptedAt)
            var began=false
            try {
                api.beginWhatsApp(stored.deviceToken,stored.deviceId,id,Instant.ofEpochMilli(acceptedAt).toString(),stored.consent)
                began=true
                if(!isActive || !WhatsAppSharingRuntime.allowed(this@WhatsAppSharingService)) return@launch
                WhatsAppSharingRuntime.activate(session)
                WhatsAppSharingState.message="Compartiendo notificaciones nuevas de WhatsApp."
                while(isActive && WhatsAppSharingRuntime.current===session) {
                    if(!WhatsAppSharingRuntime.allowed(this@WhatsAppSharingService)) { stopSelf();break }
                    val observation=withTimeoutOrNull(10000) { session.queue.receive() }
                    if(!isActive || WhatsAppSharingRuntime.current!==session) break
                    if(observation==null) api.heartbeatWhatsApp(stored.deviceToken,stored.deviceId,id)
                    else {
                        val recorded=synchronized(runtime.gate) {
                            if(WhatsAppSharingRuntime.current!==session || !WhatsAppSharingRuntime.allowed(this@WhatsAppSharingService)) return@synchronized false
                            val credential=runtime.credentials.load()
                            check(credential?.deviceId==stored.deviceId && credential.consent==stored.consent)
                            val eventId=UUID.nameUUIDFromBytes("${stored.deviceId}|${stored.consent.consentId}|${observation.key}".toByteArray(Charsets.UTF_8)).toString()
                            if(!runtime.database.dao().containsEvent(eventId)) runtime.core().captureRealEvent("whatsapp",observation.direction,
                                mapOf("appEventCode" to observation.code),Instant.parse(observation.timestamp),eventId)
                            runtime.after(runtime.core())
                            true
                        }
                        if(!recorded || WhatsAppSharingRuntime.current!==session || !isActive || !WhatsAppSharingRuntime.allowed(this@WhatsAppSharingService)) break
                        observation.preview?.let { api.sendWhatsAppPreview(stored.deviceToken,stored.deviceId,id,it) }
                        // Metadata uses the consent-bound durable outbox; preview text never enters it.
                        synchronized(runtime.gate) { runtime.core().flushPending(maxBatches=1);runtime.after(runtime.core()) }
                        WhatsAppSharingState.message="Actividad de WhatsApp enviada. Último evento: ${displayLocalTime(observation.timestamp)}"
                    }
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) {
                if(scope.isActive) WhatsAppSharingState.message=if(error is ApiException && (error.status==404 || !began && error.status==400))
                    "Sesión detenida: necesitas el servidor 0.5 y fecha/hora correctas para registrar actividad de WhatsApp."
                    else "Sesión detenida: falló la conexión o la autorización. El texto no se guarda; los eventos de tipo/fecha ya registrados conservan su cola."
                if(error is ApiException && error.isDeviceRevoked) runCatching { runtime.core().confirmRemoteRevocation(stored.deviceId) }.onSuccess { runtime.after(runtime.core()) }
            } finally {
                if(WhatsAppSharingRuntime.current===session) WhatsAppSharingRuntime.clear()
                if(scope.isActive) stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        WhatsAppSharingRuntime.clear()
        if(!WhatsAppSharingState.message.startsWith("Sesión detenida:")) WhatsAppSharingState.message="Notificaciones de WhatsApp detenidas."
        scope.cancel()
        val stored=credential;val id=sessionId
        if(stored!=null && id!=null) CoroutineScope(Dispatchers.IO).launch {
            sender?.join()
            runCatching { ApiClient(stored.serverUrl!!).endWhatsApp(stored.deviceToken,stored.deviceId,id) }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object { const val CHANNEL="whatsapp_sharing";const val ID=5303 }
}
