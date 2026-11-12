package com.auditlab.agent

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.auditlab.design.*
import kotlinx.coroutines.*

internal data class MonitoringSelection(
    val communications: Boolean = false, val numbers: Boolean = false, val smsText: Boolean = false,
    val whatsApp: Boolean = false, val screen: Boolean = false, val location: Boolean = false, val recoverBattery: Boolean = false,
) {
    fun normalized() = if(communications) this else copy(numbers=false,smsText=false)
}

/** One local activation, with independent scopes. Pending consent stays in RAM only. */
class UnifiedMonitoringActivity : ComponentActivity() {
    private var pending: MonitoringSelection? = null
    private var epoch: Long = -1
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private val runtime get() = AgentRuntime.get(this)
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) requestPhone() else fail("Permite las notificaciones para mantener el monitoreo visible.")
    }
    private val phone = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startBase() }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestPhone() }
    private val whatsappAccess = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startWhatsApp() }
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(pending?.screen==true && runtime.activationValid(epoch) && result.resultCode==RESULT_OK && result.data!=null && runtime.indicator.isVisible()) {
            startForegroundService(Intent(this,ScreenShareService::class.java)
                .putExtra("result",result.resultCode).putExtra("capture",result.data))
        }
        pending=null;finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AuditTheme { MonitoringSetup(busy,message,server=runtime.target(),onCancel={finish()},onActivate=::activate) } }
    }
    override fun onDestroy() {
        if(isFinishing && pending!=null) runtime.cancelActivation()
        super.onDestroy()
    }
    private fun fail(text: String) { pending=null;busy=false;message=text }
    private fun activate(value: MonitoringSelection) {
        if(busy) return
        epoch=runtime.beginActivation()
        pending=value.normalized();busy=true;message=""
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else requestPhone()
    }
    private fun requestPhone() {
        val selection=pending ?: return
        if(selection.location && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION); return
        }
        val missing=if(selection.communications) arrayOf(Manifest.permission.READ_SMS,Manifest.permission.READ_CALL_LOG)
            .filter {checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED} else emptyList()
        if(missing.isNotEmpty()) phone.launch(missing.toTypedArray()) else startBase()
    }
    private fun startBase() {
        val selection=pending ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { runtime.activateMonitoring(selection,epoch) }
                if(selection.whatsApp && !WhatsAppAccess.granted(this@UnifiedMonitoringActivity))
                    whatsappAccess.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                else startWhatsApp()
            } catch(cancelled: CancellationException) {throw cancelled}
            catch(error: IllegalStateException) {fail(error.message ?: "No se pudo activar; revisa el estado del Agente.")}
            catch(_: Exception) {fail("No se pudo activar. Revisa inscripción y notificaciones; no borres los datos.")}
        }
    }
    private fun startWhatsApp() {
        val selection=pending ?: return
        lifecycleScope.launch {
            if(!runtime.activationValid(epoch)) {pending=null;finish();return@launch}
            if(selection.whatsApp && !WhatsAppSharingState.active && WhatsAppAccess.granted(this@UnifiedMonitoringActivity)) {
                if(!WhatsAppSharingState.listenerConnected) {
                    runCatching {NotificationListenerService.requestRebind(ComponentName(this@UnifiedMonitoringActivity,WhatsAppNotificationListener::class.java))}
                    repeat(25) {if(!WhatsAppSharingState.listenerConnected) delay(200)}
                }
                if(runtime.activationValid(epoch) && WhatsAppSharingState.listenerConnected && runtime.indicator.isVisible() && !runtime.gate.withdrawalRequested)
                    runCatching { startForegroundService(Intent(this@UnifiedMonitoringActivity,WhatsAppSharingService::class.java)
                        .putExtra("acceptedAt",System.currentTimeMillis())) }
                        .onFailure {WhatsAppSharingState.message="WhatsApp pendiente: Android no permitió iniciar su servicio."}
                else WhatsAppSharingState.message="WhatsApp pendiente: Android no conectó el acceso a notificaciones."
            } else if(selection.whatsApp && !WhatsAppAccess.granted(this@UnifiedMonitoringActivity)) {
                WhatsAppSharingState.message="WhatsApp pendiente: falta conceder el acceso de Android."
            }
            // Always leave Android's per-session screen confirmation visible and cancelable.
            if(runtime.activationValid(epoch) && pending===selection && selection.screen && !ScreenSharingState.active && runtime.indicator.isVisible())
                projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            else {pending=null;finish()}
        }
    }
}

@Composable
internal fun MonitoringSetup(busy: Boolean,message: String,server: String = "",onCancel: ()->Unit,onActivate: (MonitoringSelection)->Unit) {
    var selection by remember {mutableStateOf(MonitoringSelection())}
    AuditPage(feedback=message,working=busy) {
        AuditHeader("ACTIVAR MONITOREO")
        Text("Elige qué compartir",style=MaterialTheme.typography.titleLarge)
        Text("Destino: $server",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
        Text("El Centro de administradores y auditores verá lo que autorices. Puedes detener todo cuando quieras.")
        Text("Batería y conexión",style=MaterialTheme.typography.titleMedium)
        ScopeChoice("SMS y llamadas", "Tipo y fecha de eventos nuevos; no incluye RCS ni audio.",selection.communications,!busy) {
            selection=selection.copy(communications=it).normalized()
        }
        if(selection.communications) {
            ScopeChoice("Compartir números", "Se guardan y exportan con los eventos; sin leer contactos.",selection.numbers,!busy) {selection=selection.copy(numbers=it)}
            ScopeChoice("Compartir texto de SMS", "Hasta 2 000 caracteres de SMS nuevos; se guarda y puede exportarse.",selection.smsText,!busy) {selection=selection.copy(smsText=it)}
        }
        ScopeChoice("Notificaciones de WhatsApp", "Texto y remitente de hasta 20 notificaciones nuevas/5 min. Actividad sin texto en el historial. Sin importar chats.",selection.whatsApp,!busy) {selection=selection.copy(whatsApp=it)}
        ScopeChoice("Compartir pantalla", "El Centro verá todo lo visible. Sin audio ni grabación; Android pedirá confirmación.",selection.screen,!busy) {selection=selection.copy(screen=it)}
        ScopeChoice("Ubicación aproximada", "Lectura cada minuto aproximadamente hasta que la detengas. Sin ubicación precisa.",selection.location,!busy) {selection=selection.copy(location=it)}
        ScopeChoice("Reanudar batería al encender", "Solo batería y conexión. SMS, WhatsApp y pantalla requieren una nueva sesión local.",selection.recoverBattery,!busy) {selection=selection.copy(recoverBattery=it)}
        Text("Eventos: 30 días por defecto (política del servidor). Cada casilla autoriza ese alcance aquí.",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
        AuditDisclosure("Más información") {Text("Android da acceso general a notificaciones; solo se procesan WhatsApp/Business. Las vistas previas no se guardan ni exportan; SMS y números autorizados sí tienen retención y CSV. Sin audio ni historial de chats.",style=MaterialTheme.typography.bodySmall)}
        AuditButton(if(busy) "Activando…" else "Activar lo seleccionado",{onActivate(selection.normalized())},!busy)
        AuditButton("Cancelar",onCancel,!busy,outlined=true)
    }
}

@Composable
private fun ScopeChoice(title: String,detail: String,checked: Boolean,enabled: Boolean,onChange: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).toggleable(value=checked,enabled=enabled,role=Role.Checkbox,onValueChange=onChange)) {
        Checkbox(checked,null,enabled=enabled)
        Column(Modifier.weight(1f)) {Text(title,style=MaterialTheme.typography.titleSmall);Text(detail,style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)}
    }
}
