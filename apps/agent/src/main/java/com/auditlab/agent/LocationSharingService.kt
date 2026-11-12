package com.auditlab.agent

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/**
 * Ubicación aproximada continua (plan 0.9, M3). Solo se inicia por acción local
 * confirmada; la notificación permanente muestra que está activa y permite cortarla.
 * Precisión aproximada (D8): las coordenadas se redondean a dos decimales (~1 km).
 * No se presenta como ubicación actual: cada lectura lleva su fecha.
 */
internal object LocationSharingState {
    var running by mutableStateOf(false)
    var message by mutableStateOf("Ubicación detenida.")
    var lastFixAt by mutableStateOf<String?>(null)
}

class LocationSharingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var runtime: AgentRuntime
    private var listener: LocationListener? = null
    private var manager: LocationManager? = null

    override fun onCreate() { super.onCreate(); runtime = AgentRuntime.get(this) }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopUpdates()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val stop = PendingIntent.getService(this, 0,
                Intent(this, LocationSharingService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, MonitoringIndicator.CHANNEL)
                .setSmallIcon(R.drawable.ic_auditlab)
                .setContentTitle("AuditLab · Ubicación aproximada")
                .setContentText("Lectura periódica activa. Toca para revisar o detener.")
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
                .addAction(0, "Detener ubicación", stop).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ID, notification)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (listener == null) {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                LocationSharingState.message = "Ubicación detenida: falta el permiso aproximado."
                LocationSharingState.running = false
                stopSelf()
                return START_NOT_STICKY
            }
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
            if (providers.isEmpty()) {
                LocationSharingState.message = "Ubicación detenida: activa un proveedor de ubicación en Android."
                LocationSharingState.running = false
                stopSelf()
                return START_NOT_STICKY
            }
            val target = object : LocationListener {
                override fun onLocationChanged(location: Location) { scope.launch { publish(location) } }
            }
            listener = target
            manager = locationManager
            var requested = false
            providers.forEach { provider ->
                runCatching { locationManager.requestLocationUpdates(provider, 60_000L, 0f, target) }.onSuccess { requested = true }
                runCatching { locationManager.requestSingleUpdate(provider, target, null) }
            }
            if (!requested) {
                LocationSharingState.message = "Ubicación detenida: Android no permitió las lecturas."
                LocationSharingState.running = false
                stopSelf()
                return START_NOT_STICKY
            }
            LocationSharingState.running = true
            LocationSharingState.message = "Ubicación aproximada activa: lectura cada minuto. Deténla cuando quieras."
        }
        return START_STICKY
    }

    private fun publish(location: Location) {
        synchronized(runtime.gate) {
            if (runtime.gate.withdrawalRequested || runtime.gate.storageBlocked || !runtime.indicator.isVisible()) {
                stopUpdates(); stopSelf()
                return
            }
            val lat = Math.round(location.latitude * 100.0) / 100.0
            val lon = Math.round(location.longitude * 100.0) / 100.0
            try {
                runtime.core().captureRealEvent("location", "occurred", mapOf(
                    "appEventCode" to "location_update",
                    "locationLat" to lat,
                    "locationLon" to lon,
                    "locationAccuracy" to Math.round(location.accuracy * 10.0) / 10.0,
                ))
                runtime.core().flushPending(maxBatches = 1)
                LocationSharingState.lastFixAt = java.time.Instant.now().toString()
                LocationSharingState.message = "Ubicación aproximada activa: lectura cada minuto. Deténla cuando quieras."
            } catch (_: Exception) {
                LocationSharingState.message = "Ubicación guardada localmente; se enviará al recuperar conexión."
            }
            runtime.after(runtime.core())
        }
    }

    private fun stopUpdates() {
        val target = listener
        if (target != null) runCatching { manager?.removeUpdates(target) }
        listener = null
        LocationSharingState.running = false
        LocationSharingState.message = "Ubicación detenida."
    }

    override fun onDestroy() {
        stopUpdates()
        scope.cancel()
        super.onDestroy()
    }

    companion object { private const val ID = 1042 }
}
