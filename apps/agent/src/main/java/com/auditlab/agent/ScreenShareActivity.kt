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
    var confirmed by mutableStateOf(false)
    var lastConfirmedAt by mutableStateOf<String?>(null)
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
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var frames: ScreenFramePipeline? = null
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
            .setContentText("Captura autorizada; esperando imagen y confirmación del servidor.")
            .setOngoing(true).addAction(0, "DETENER", stop).build())
        ScreenSharingState.message = "Captura autorizada. Esperando la primera imagen de Android…"
        ScreenSharingState.confirmed = false
        ScreenSharingState.lastConfirmedAt = null
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
            val pipeline=ScreenFramePipeline(android.os.SystemClock::elapsedRealtime,
                publish={relay!!.request(stored.deviceId,stored.deviceToken,"POST",it)},confirmed={
                    if(!ScreenSharingState.confirmed) manager.notify(5202,NotificationCompat.Builder(this@ScreenShareService,"screen_share")
                        .setSmallIcon(R.drawable.ic_auditlab).setContentTitle("AuditLab · Pantalla compartida")
                        .setContentText("Imagen enviada al servidor. Pulsa DETENER para finalizar.")
                        .setOngoing(true).setOnlyAlertOnce(true).addAction(0,"DETENER",stop).build())
                    ScreenSharingState.confirmed=true
                    ScreenSharingState.lastConfirmedAt=java.time.Instant.now().toString()
                    ScreenSharingState.message="Imagen recibida por el servidor."
                })
            frames=pipeline
            val thread=HandlerThread("AuditLab-screen-frames").also {it.start()};captureThread=thread
            val handler=Handler(thread.looper);captureHandler=handler
            val source=reader!!
            var lastEncoded=-1000L
            var scheduled=false
            val encode=Runnable {
                scheduled=false
                if(ScreenSharingState.active) try {
                    source.acquireLatestImage()?.use {image ->
                        lastEncoded=android.os.SystemClock.elapsedRealtime()
                        val plane=image.planes[0]
                        val pixels=ScreenRgbaPixels.pack(plane.buffer,image.width,image.height,plane.rowStride,plane.pixelStride)
                        val bitmap=Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixels))
                            val bytes=ByteArrayOutputStream().also {check(bitmap.compress(Bitmap.CompressFormat.JPEG,60,it))}.toByteArray()
                            pipeline.offer(bytes)
                        } finally {bitmap.recycle()}
                    }
                } catch(error: Exception) {
                    if(ScreenSharingState.active) {
                        pipeline.close()
                        ScreenSharingState.active=false;ScreenSharingState.confirmed=false
                        ScreenSharingState.message="No se pudo leer la imagen de Android. Detén y vuelve a autorizar la pantalla."
                        stopSelf()
                    }
                }
            }
            source.setOnImageAvailableListener({
                if(ScreenSharingState.active && !scheduled) {
                    scheduled=true
                    // Defer acquisition rather than discarding the last image of a quick change.
                    handler.postDelayed(encode,maxOf(0L,1000-(android.os.SystemClock.elapsedRealtime()-lastEncoded)))
                }
            },handler)
            // Register the listener first, so a static screen cannot lose its first frame.
            display = projection!!.createVirtualDisplay("AuditLab visible sharing", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, Handler(mainLooper))
            check(display!=null) {"Android no creó la superficie de captura"}
            upload = scope.launch {
                try {
                    while(isActive) {
                        if(!runtime.indicator.isVisible() || runtime.gate.withdrawalRequested ||
                            manager.getNotificationChannel("screen_share").importance==NotificationManager.IMPORTANCE_NONE) {stopSelf();break}
                        pipeline.tick()
                        delay(1000)
                    }
                } catch(cancelled: CancellationException) {throw cancelled}
                catch(error: Exception) {
                    ScreenSharingState.confirmed=false
                    ScreenSharingState.message=if(error is IllegalStateException) error.message ?: "Android no entregó imágenes."
                        else "Envío de pantalla detenido: ${sharingFailure(error)}"
                    if(error is com.auditlab.common.ApiException && error.isDeviceRevoked)
                        runCatching {runtime.core().confirmRemoteRevocation(stored.deviceId);runtime.after(runtime.core())}
                    stopSelf()
                }
            }

        } catch (_: Exception) { ScreenSharingState.message = "Android no permitió iniciar la captura. Vuelve a autorizarla desde el Agente."; stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        ScreenSharingState.active = false
        frames?.close();frames=null
        ScreenSharingState.confirmed = false
        if (ScreenSharingState.message.startsWith("Captura autorizada") || ScreenSharingState.message.startsWith("Imagen recibida")) ScreenSharingState.message = "Pantalla compartida detenida."
        val oldReader=reader;reader=null
        val oldDisplay=display;display=null
        val oldThread=captureThread;captureThread=null
        val oldHandler=captureHandler;captureHandler=null
        oldReader?.setOnImageAvailableListener(null,null)
        oldHandler?.removeCallbacksAndMessages(null)
        // Serialize resource release after any frame already being copied on this thread.
        if(oldHandler?.post {oldDisplay?.release();oldReader?.close();oldThread?.quitSafely()}!=true) {
            oldDisplay?.release();oldReader?.close();oldThread?.quitSafely()
        }
        projection?.stop();projection=null
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
