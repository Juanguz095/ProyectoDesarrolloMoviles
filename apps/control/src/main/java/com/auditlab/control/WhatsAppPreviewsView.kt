package com.auditlab.control

import android.view.WindowManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.LocalActivity
import com.auditlab.common.*
import com.auditlab.design.*
import kotlinx.coroutines.*

@Composable
internal fun WhatsAppPreviewsView(session: ControlSession,device: String?,onBack:()->Unit) =
    WhatsAppMessagesContent(session,device,onBack,false)

@Composable
internal fun WhatsAppMessagesInline(session: ControlSession,device: String) = WhatsAppMessagesContent(session,device,{},true)

@Composable
private fun WhatsAppMessagesContent(session: ControlSession,device: String?,onBack:()->Unit,compact: Boolean) {
    val activity=LocalActivity.current
    var snapshot by remember(device) { mutableStateOf<WhatsAppPreviewSnapshot?>(null) }
    val lifecycle=(activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle
    var foreground by remember { mutableStateOf(lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)!=false) }
    DisposableEffect(lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_START) foreground=true
            if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP) { foreground=false;snapshot=null }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    DisposableEffect(activity) {
        val window=activity?.window
        val alreadySecure=window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)!=0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if(!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var refreshKey by remember(device) { mutableStateOf(0) }
    var lastChecked by remember(device) { mutableStateOf<String?>(null) }
    var error by remember(device) { mutableStateOf<String?>(null) }
    LaunchedEffect(device,session.signedIn,foreground,refreshKey) {
        if(device!=null && session.signedIn && foreground) while(isActive && foreground && session.signedIn) {
            try {
                val latest=session.whatsAppPreviewSnapshot(device)
                // A request can finish while background composition is suspended.
                // Never repopulate sensitive state after the lifecycle cleared it.
                snapshot=if(foreground && session.signedIn) latest else null
                error=null
                lastChecked=java.time.Instant.now().toString()
            }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) {
                snapshot=null
                error=when {
                    failure is ApiException && failure.code=="DEVICE_REVOKED" -> "Dispositivo revocado. Las vistas previas dejaron de estar disponibles."
                    failure is ApiException && failure.status==401 -> "Sesión vencida. Inicia sesión de nuevo."
                    failure is ApiException && failure.status==404 -> "El servidor no encontró este teléfono o la función WhatsApp (404). Comprueba el teléfono seleccionado y la versión del servidor."
                    failure is ApiException && failure.status==403 -> "Tu sesión no tiene permiso para consultar este teléfono (403)."
                    failure is ApiException && failure.status>=500 -> "Error del servidor (${failure.status}). Revisa que la API siga funcionando."
                    failure is ApiException && failure.isNetwork -> "Sin conexión con el servicio. Se ocultaron las vistas previas."
                    else -> "No se pudieron consultar las vistas previas. Revisa la sesión y la conexión."
                }
            }
            delay(3000)
        }
    }
    if(!compact) {AuditHeader("WHATSAPP","VISTAS PREVIAS AUTORIZADAS");AuditLink("← Volver al dispositivo",onBack)}
    else Text("Mensajes recientes de WhatsApp",style=MaterialTheme.typography.titleMedium)
    Text("Texto disponible en notificaciones nuevas; no es el historial completo del chat. Solo se comparte si el propietario activa la sesión en el Agente.")
    AuditLink("Actualizar WhatsApp",{refreshKey++},device!=null && !session.busy)
    lastChecked?.let {Text("Última consulta: ${displayFriendlyTime(it)}",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)}
    AuditRule()
    error?.let { AuditNotice(it) }
    if(device==null) AuditEmpty("SIN DISPOSITIVO","Selecciona un dispositivo inscrito.")
    else snapshot?.let { current ->
        AuditStatus(if(current.active) "SESIÓN ACTIVA" else "SESIÓN DETENIDA",current.active)
        if(current.items.isEmpty()) AuditEmpty("SIN VISTAS PREVIAS",if(current.active) "Aparecerán cuando WhatsApp publique una notificación con texto permitido por Android." else "En el teléfono Agente, abre Configurar vistas previas de WhatsApp, concede acceso Android y autoriza la sesión. Aquí aparecerá el texto de notificaciones nuevas.")
        current.items.forEach { preview ->
            AuditPanel {
                Text(preview.title.ifBlank { "WhatsApp" },style=MaterialTheme.typography.titleMedium)
                Text(preview.text)
                Text(displayFriendlyTime(preview.timestamp),color=AuditColors.Muted,style=MaterialTheme.typography.bodySmall)
                Text(if(preview.packageName=="com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    if(snapshot==null && error==null && device!=null) Text("Consultando la sesión…")
    AuditRule()
    Text("Últimas 20 vistas previas, máximo 5 minutos en memoria. Al detener desaparecen; no se incluyen en CSV. Esta consulta queda registrada en auditoría.",color=AuditColors.Muted,style=MaterialTheme.typography.bodySmall)
}
