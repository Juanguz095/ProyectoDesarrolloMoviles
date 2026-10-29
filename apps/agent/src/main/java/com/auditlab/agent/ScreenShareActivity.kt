package com.auditlab.agent

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.graphics.PixelFormat
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import com.auditlab.common.ScreenRelayClient
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import androidx.compose.runtime.*

object ScreenSharingState {
    var message by mutableStateOf("Pantalla no compartida.")
    var active by mutableStateOf(false)
}

/** Each session starts with the local person's confirmation and Android's own capture dialog. */
class ScreenShareActivity : ComponentActivity() {
    private var requestingCapture = false
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenShareService::class.java)
                .putExtra("result", result.resultCode).putExtra("capture", result.data)
            startForegroundService(intent)
        }
        finish()
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestingCapture = state?.getBoolean("requestingCapture") == true
        if (requestingCapture) return
        AlertDialog.Builder(this).setTitle("Compartir pantalla en vivo")
            .setMessage("Los administradores y auditores de AuditLab podrán ver lo que muestres en pantalla, incluidos mensajes abiertos de WhatsApp y otros datos visibles. No se guarda el video. Puedes detener la sesión desde la notificación. No se comparte audio.")
            .setNegativeButton("Cancelar") { _, _ -> finish() }
            .setPositiveButton("Continuar con Android") { _, _ ->
                requestingCapture = true
                capture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            }.setOnCancelListener { finish() }.show()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("requestingCapture", requestingCapture)
        super.onSaveInstanceState(outState)
    }
}

class ScreenShareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var upload: Job? = null
    private var relay: ScreenRelayClient? = null
    private var credential: com.auditlab.common.StoredCredential? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        val runtime = AgentRuntime.get(this)
        val stored = runCatching { runtime.credentials.load() }.getOrNull()
        if (stored?.serverUrl == null || !runtime.indicator.isVisible() || runtime.gate.withdrawalRequested) {
            stopSelf(); return START_NOT_STICKY
        }
        credential = stored; relay = ScreenRelayClient(stored.serverUrl!!)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("screen_share", "Pantalla compartida", NotificationManager.IMPORTANCE_LOW))
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel("screen_share").importance == NotificationManager.IMPORTANCE_NONE) {
            stopSelf(); return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(5202, NotificationCompat.Builder(this, "screen_share").setSmallIcon(R.drawable.ic_auditlab)
            .setContentTitle("AuditLab · Pantalla compartida")
            .setContentText("El Centro puede ver tu pantalla. Pulsa DETENER para finalizar.")
            .setOngoing(true).addAction(0, "DETENER", stop).build())
        ScreenSharingState.message = "Compartiendo pantalla. El Centro puede ver lo que muestras."
        ScreenSharingState.active = true
        try {
            @Suppress("DEPRECATION")
            val data = intent?.getParcelableExtra<Intent>("capture") ?: error("Falta permiso de captura")
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra("result", 0), data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(mainLooper))
            val metrics = resources.displayMetrics
            val width = 480
            val height = (metrics.heightPixels.toDouble() / metrics.widthPixels * width).toInt()
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            display = projection!!.createVirtualDisplay("AuditLab visible sharing", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, Handler(mainLooper))
            upload = scope.launch {
                // ImageReader only reports changes. Poll the latest buffer and keep one
                // frame in RAM so a static screen keeps its live session heartbeat.
                var latest: ByteArray? = null
                try {
                    while (isActive) {
                        if (!runtime.indicator.isVisible() || runtime.gate.withdrawalRequested ||
                            manager.getNotificationChannel("screen_share").importance == NotificationManager.IMPORTANCE_NONE) {
                            stopSelf(); break
                        }
                        val captured = withContext(Dispatchers.Main.immediate) {
                            reader?.acquireLatestImage()?.use { image ->
                                val plane = image.planes[0]
                                val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
                                try {
                                    padded.copyPixelsFromBuffer(plane.buffer)
                                    val bitmap = Bitmap.createBitmap(padded, 0, 0, width, height)
                                    try {
                                        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
                                    } finally { if (bitmap !== padded) bitmap.recycle() }
                                } finally { padded.recycle() }
                            }
                        }
                        if (captured != null) latest = captured
                        latest?.let { relay!!.request(stored.deviceId, stored.deviceToken, "POST", it) }
                        delay(1000)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    ScreenSharingState.message = "Captura detenida: ${sharingFailure(error)}"
                    stopSelf()
                } finally { latest = null }
            }
        } catch (_: Exception) { ScreenSharingState.message = "Android no permitió iniciar la captura. Vuelve a autorizarla desde el Agente."; stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        ScreenSharingState.active = false
        if (ScreenSharingState.message.startsWith("Compartiendo")) ScreenSharingState.message = "Pantalla compartida detenida."
        display?.release(); reader?.close(); projection?.stop(); projection = null
        scope.cancel()
        credential?.let { stored ->
            CoroutineScope(Dispatchers.IO).launch {
                upload?.join()
                runCatching { relay?.request(stored.deviceId, stored.deviceToken, "DELETE") }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
