package com.auditlab.agent

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.auditlab.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/** The projection deliberately excludes addresses, numbers, message bodies and recordings. */
internal object PhoneMetadata {
    val smsProjection = arrayOf(Telephony.Sms._ID, Telephony.Sms.DATE, Telephony.Sms.TYPE)
    val callProjection = arrayOf(CallLog.Calls._ID, CallLog.Calls.DATE, CallLog.Calls.TYPE)
    // Call-log DATE is the start time, even when the row is written much later.
    // Reconcile the active consent window so a long call cannot fall behind a polling cursor.
    fun captureSince(activation: Long, now: Long): Long = maxOf(activation, now - 7 * 86_400_000L)
    fun code(sms: Boolean, type: Int): String? = if (sms) when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> "sms_received"
        Telephony.Sms.MESSAGE_TYPE_SENT -> "sms_sent"
        else -> null
    } else when (type) {
        CallLog.Calls.INCOMING_TYPE -> "call_received"
        CallLog.Calls.OUTGOING_TYPE -> "call_outgoing"
        CallLog.Calls.MISSED_TYPE -> "call_missed"
        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> "call_rejected"
        else -> null
    }
    fun eventId(device: String, consent: String, code: String, row: Long, timestamp: Long): String =
        UUID.nameUUIDFromBytes("$device|$consent|$code|$row|$timestamp".toByteArray(Charsets.UTF_8)).toString()
}

/** Starts only from a user's action. Process death/reboot never silently restarts capture. */
class RealEventMonitor(private val context: Context, private val runtime: AgentRuntime) {
    private val serviceIntent = Intent(context, RealMonitoringService::class.java)
    @Volatile var requested = false; private set
    @Volatile var running = false; internal set
    @Volatile var message = "Captura real detenida."; internal set
    @Volatile var startedAt = 0L; private set
    @Volatile var deviceId: String? = null; private set
    fun hasPermission(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun permissionsText() = "SMS: ${if (hasPermission(Manifest.permission.READ_SMS)) "autorizado" else "sin permiso"}. " +
        "Llamadas: ${if (hasPermission(Manifest.permission.READ_CALL_LOG)) "autorizadas" else "sin permiso"}."
    fun start() = synchronized(runtime.gate) {
        val state = runtime.core().refresh() as? AgentCore.State.Enrolled ?: error("Primero inscribe el dispositivo.")
        check(runtime.indicator.isVisible()) { "Habilita primero el indicador visible." }
        check(!runtime.gate.withdrawalRequested)
        if (requested) return@synchronized
        startedAt = System.currentTimeMillis()
        deviceId = state.deviceId
        val receipt = context.getSharedPreferences("auditlab_real_consent", Context.MODE_PRIVATE)
        if (!receipt.edit().putString("device", state.deviceId).putString("consent", state.consent.consentId)
                .putString("scope", "sms_call_type_timestamp_device_status_v1").putLong("acceptedAt", startedAt).commit()) {
            deviceId = null
            throw StorageFailure()
        }
        requested = true
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            message = "Iniciando captura real. ${permissionsText()}"
        } catch (_: Exception) {
            requested = false; deviceId = null
            error("Android no permitió iniciar la captura. Mantén la app abierta e inténtalo de nuevo.")
        }
    }
    fun stop() {
        requested = false; running = false; deviceId = null
        context.stopService(serviceIntent)
    }
    fun refreshNow() {
        check(running && canCapture()) { "Activa primero la captura de batería y eventos en este teléfono." }
        ContextCompat.startForegroundService(context,Intent(context,RealMonitoringService::class.java).setAction(RealMonitoringService.REFRESH))
    }
    fun canCapture(): Boolean = requested && !runtime.gate.withdrawalRequested && runtime.indicator.isVisible() &&
        (runtime.core().refresh() as? AgentCore.State.Enrolled)?.deviceId == deviceId
}

/** Content observers plus periodic reconciliation; records completed log entries, not live audio. */
class RealMonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanLock = Mutex()
    private lateinit var runtime: AgentRuntime
    private val observers = mutableListOf<ContentObserver>()
    private var loop: Job? = null
    private var lastStatus: Map<String, Any?>? = null
    private var lastStatusAt=0L
    private var batteryReceiver: BroadcastReceiver?=null
    private val observedIds = mutableMapOf<String, Long>()
    private val sourceEnabledAt = mutableMapOf<Boolean, Long>()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); runtime = AgentRuntime.get(this) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!runtime.realEvents.requested || !runtime.indicator.isVisible()) { stopSelf(); return START_NOT_STICKY }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(MonitoringIndicator.ID, runtime.indicator.notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(MonitoringIndicator.ID, runtime.indicator.notification())
        } catch (_: Exception) {
            runtime.realEvents.message = "No se pudo mostrar el servicio visible; captura detenida."
            runtime.realEvents.stop(); stopSelf(); return START_NOT_STICKY
        }
        runtime.realEvents.running = true
        if (loop == null) {
            listOf(true, false).forEach { sms ->
                val permission = if (sms) Manifest.permission.READ_SMS else Manifest.permission.READ_CALL_LOG
                if (runtime.realEvents.hasPermission(permission)) sourceEnabledAt[sms] = runtime.realEvents.startedAt
            }
            registerObserver(Telephony.Sms.CONTENT_URI)
            registerObserver(CallLog.Calls.CONTENT_URI)
            batteryReceiver=object:BroadcastReceiver() {
                override fun onReceive(context: Context?,intent: Intent?) { scope.launch { scan() } }
            }.also { ContextCompat.registerReceiver(this,it,IntentFilter(Intent.ACTION_BATTERY_CHANGED),ContextCompat.RECEIVER_NOT_EXPORTED) }
            loop = scope.launch { while (isActive) { scan(); delay(10_000) } }
        }
        if(intent?.action==REFRESH) scope.launch { scan(forceStatus=true) }
        return START_NOT_STICKY
    }
    private fun registerObserver(uri: android.net.Uri) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scope.launch { scan() } }
        }
        try { contentResolver.registerContentObserver(uri, true, observer); observers += observer }
        catch (_: SecurityException) { /* denied sources remain explicitly unavailable */ }
    }
    private suspend fun scan(forceStatus: Boolean=false) = scanLock.withLock {
        try {
            synchronized(runtime.gate) {
                if (!runtime.realEvents.canCapture()) { runtime.realEvents.stop(); stopSelf(); return@withLock }
                val now = System.currentTimeMillis()
                val since = PhoneMetadata.captureSince(runtime.realEvents.startedAt, now)
                observedIds.entries.removeAll { it.value < since }
                val sourceWarnings=mutableListOf<String>()
                for(sms in listOf(true,false)) {
                    try { readSource(sms,since) }
                    catch(error: SecurityException) { sourceWarnings += if(sms) "Android restringió los SMS." else "Android restringió las llamadas." }
                    catch(error: android.database.sqlite.SQLiteException) { sourceWarnings += if(sms) "Proveedor de SMS no disponible." else "Proveedor de llamadas no disponible." }
                    catch(error: IllegalArgumentException) { sourceWarnings += if(sms) "No se pudieron consultar SMS." else "No se pudieron consultar llamadas." }
                    catch(error: UnsupportedOperationException) { sourceWarnings += if(sms) "Este teléfono no ofrece el proveedor de SMS." else "Este teléfono no ofrece el proveedor de llamadas." }
                }
                if (!runtime.realEvents.canCapture()) return@withLock
                val status = runtime.metadata().filterKeys { it != "appEventCode" }
                val elapsed=android.os.SystemClock.elapsedRealtime()
                if (forceStatus || status != lastStatus || elapsed-lastStatusAt>=60_000) {
                    runtime.core().captureRealEvent("device_status", "occurred", status + ("appEventCode" to "device_status_changed"))
                    lastStatus = status
                    lastStatusAt=elapsed
                }
                runtime.realEvents.message = "Captura real activa. ${runtime.realEvents.permissionsText()} Las llamadas aparecen al finalizar."
                if(sourceWarnings.isNotEmpty()) runtime.realEvents.message += " "+sourceWarnings.joinToString(" ")
                // Immediate delivery also works over USB loopback without Android's Internet
                // validation. WorkManager retains the durable network/backoff fallback.
                try { runtime.core().flushPending(maxBatches = 1) }
                catch (error: ApiException) {
                    runtime.realEvents.message = if (error.isRetryable) "Sin confirmación del servidor. Eventos guardados para reintento."
                        else "El servidor rechazó el envío (${error.code}). Revisa el estado de la inscripción."
                }
                runtime.after(runtime.core())
            }
        } catch (_: SecurityException) {
            runtime.realEvents.message = "Android denegó el acceso. Revisa los permisos; no se confirma la captura."
        } catch (_: QueueFull) {
            runtime.realEvents.message = "Cola local llena. Se intentará enviar los pendientes antes de capturar más."
            runCatching { runtime.core().flushPending(maxBatches = 1) }
            runCatching { runtime.after(runtime.core()) }
        } catch (_: MonitoringPaused) {
            runtime.realEvents.stop(); stopSelf()
        } catch (_: Exception) {
            runtime.realEvents.message = "No se pudo guardar un evento real. Revisa la cola, los permisos y el consentimiento."
        } finally { runtime.changes.tryEmit(Unit) }
    }
    private fun readSource(sms: Boolean, since: Long) {
        val permission = if (sms) Manifest.permission.READ_SMS else Manifest.permission.READ_CALL_LOG
        if (!runtime.realEvents.hasPermission(permission)) { sourceEnabledAt.remove(sms); return }
        val permittedSince = sourceEnabledAt.getOrPut(sms) { System.currentTimeMillis() }
        val projection = if (sms) PhoneMetadata.smsProjection else PhoneMetadata.callProjection
        val uri = if (sms) Telephony.Sms.CONTENT_URI else CallLog.Calls.CONTENT_URI
        val active = runtime.core().refresh() as? AgentCore.State.Enrolled ?: return
        val cursor = contentResolver.query(uri, projection, "date>=?", arrayOf(maxOf(since, permittedSince).toString()), "date ASC, _id ASC")
            ?: throw IllegalArgumentException("Proveedor de metadatos no disponible")
        cursor.use {
            while (cursor.moveToNext()) {
                if (!runtime.realEvents.hasPermission(permission) || !runtime.realEvents.canCapture()) return
                val code = PhoneMetadata.code(sms, cursor.getInt(2)) ?: continue
                val millis = cursor.getLong(1)
                if (millis < runtime.realEvents.startedAt || millis > System.currentTimeMillis()) continue
                val id = PhoneMetadata.eventId(active.deviceId, active.consent.consentId, code, cursor.getLong(0), millis)
                if (id in observedIds) continue
                if (!runtime.database.dao().containsEvent(id)) {
                    val (category, direction) = EventPolicy.realCodes.getValue(code)
                    runtime.core().captureRealEvent(category, direction, mapOf("appEventCode" to code), Instant.ofEpochMilli(millis), id)
                }
                // Preserve deduplication even after a sent outbox row is evicted for capacity.
                // A new service activation always starts a new time window.
                observedIds[id] = millis
            }
        }
    }
    override fun onDestroy() {
        scope.cancel()
        observers.forEach { runCatching { contentResolver.unregisterContentObserver(it) } }
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } };batteryReceiver=null
        runtime.realEvents.running = false
        // Detach the existing indicator; pause/withdrawal owns its explicit removal.
        stopForeground(STOP_FOREGROUND_DETACH)
        runtime.realEvents.stop()
        runtime.changes.tryEmit(Unit)
        super.onDestroy()
    }
    companion object { const val REFRESH="com.auditlab.agent.REFRESH_DEVICE_STATE" }
}
