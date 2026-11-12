package com.auditlab.agent

import android.Manifest
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auditlab.common.ConsentModulesPolicy
import com.auditlab.common.ModuleState
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.auditlab.common.AgentCore
import com.auditlab.design.*
import kotlinx.coroutines.*

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val session = AgentSession(application)
    init { session.initialize(scope) }
    override fun onCleared() { scope.cancel() }
}

class MainActivity : ComponentActivity() {
    private lateinit var model: AgentViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0xFFDAD7C0.toInt(), 0xFFDAD7C0.toInt()),
            navigationBarStyle = SystemBarStyle.light(0xFFDAD7C0.toInt(), 0xFFDAD7C0.toInt()),
        )
        model = ViewModelProvider(this)[AgentViewModel::class.java]
        setContent {
            AuditTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentScreen(model.session, workScope=model.scope, onActivateMonitoring = {
                        startActivity(Intent(this, UnifiedMonitoringActivity::class.java))
                    }, onShareText = {
                        startActivity(Intent(this, ManualTextShareActivity::class.java))
                    }, platformControls = {
                        AgentUpdateControls()
                    })
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.session.refresh(model.scope)
    }
}

@Composable
internal fun AgentScreen(session: AgentSession, workScope: CoroutineScope? = null,
                         onActivateMonitoring: (() -> Unit)? = null,
                         onShareText: (() -> Unit)? = null,
                         platformControls: (@Composable () -> Unit)? = null) {
    val composeScope = rememberCoroutineScope()
    val scope = workScope ?: composeScope
    var policy by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable(session.consentEpoch) { mutableStateOf(false) }
    var confirmWithdrawal by remember { mutableStateOf(false) }
    // Enrollment secrets never enter saved instance state.
    var code by remember(session.consentEpoch) { mutableStateOf("") }
    var serverDraft by remember(session.serverUrl) { mutableStateOf(session.serverUrl) }
    var configureServer by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    val communicationEvents=session.recentEvents.filter { !it.event.isSimulated && it.event.category in setOf("sms","call","whatsapp") }
    var selectedEvent by remember { mutableStateOf<AgentSession.LocalEvent?>(null) }
    androidx.compose.runtime.LaunchedEffect(session.state) {
        if (session.state !is AgentCore.State.Enrolled) { history = false; selectedEvent = null }
        if (session.state is AgentCore.State.Enrolled) code = ""
    }
    BackHandler(policy || history) { policy = false; history = false }
    fun saveServer() {
        try {
            val normalized = com.auditlab.common.ApiClient(serverDraft.trim()).baseUrl
            if (normalized != session.serverUrl) session.updateServerUrl(normalized)
            serverError = null; configureServer = false
        } catch (_: IllegalArgumentException) {
            serverError = "Usa una URL HTTPS sin rutas ni credenciales; HTTP solo en direcciones locales."
        }
    }
    if (configureServer) AlertDialog(onDismissRequest = { configureServer = false },
        title = { Text("CONEXIÓN CON EL CENTRO", style = MaterialTheme.typography.titleLarge) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AuditField("URL del servicio", serverDraft, { serverDraft = it; serverError = null }, !session.busy,
                placeholder = "https://tu-servidor")
            Text("La proporciona el administrador. Al cambiarla se vuelve a aceptar el consentimiento.")
            serverError?.let { Text(it, color = AuditColors.Red) }
        } },
        confirmButton = { AuditLink("Guardar dirección", ::saveServer, !session.busy && serverDraft.isNotBlank()) },
        dismissButton = { AuditLink("Cancelar", { configureServer = false }) })

    if (confirmWithdrawal) AlertDialog(onDismissRequest = { confirmWithdrawal = false },
        title = { Text("Retirar consentimiento", style = MaterialTheme.typography.titleLarge) },
        text = { Text("Detiene captura y envíos y elimina la cola local; sin conexión, el aviso queda pendiente.") },
        confirmButton = { AuditLink("Confirmar retirada", { confirmWithdrawal = false; session.revoke(scope) }) },
        dismissButton = { AuditLink("Cancelar", { confirmWithdrawal = false }) })
    selectedEvent?.let { row -> AlertDialog(onDismissRequest = { selectedEvent = null },
        title = { Text(metadataTitle(row.event.metadata), style = MaterialTheme.typography.titleLarge) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (row.event.isSimulated) "Simulado" else "Real")
            Text(displayFriendlyTime(row.event.timestamp))
            Text(deliveryLabel(row.delivery))
            Text(metadataDescription(row.event.metadata))
            (row.event.metadata["smsText"] as? String)?.let { Text(it) }
            row.errorCode?.let { Text("Error: $it", color = AuditColors.Red) }
        } }, confirmButton = { AuditLink("Cerrar detalle", { selectedEvent = null }) }) }

    val page = when {
        policy -> "policy"
        history && session.state is AgentCore.State.Enrolled -> "history"
        else -> session.state::class.simpleName + (session.consent != null)
    }
    AuditPage(key = page, feedback = session.message, working = session.busy) {
        if (!session.ready) {
            AuditHeader("AUDITLAB", "AGENTE")
            Text("Cargando el estado guardado…")
        } else if (policy) {
            AuditHeader("POLÍTICA DE DATOS", "CONSENTIMIENTO INFORMADO")
            AuditRule()
            AuditPanel {
                Text("Tú decides cuándo monitorear.", style = MaterialTheme.typography.titleMedium)
                Text("Registra eventos propios, simulados (identificados) y metadatos autorizados del dispositivo.")
            }
            SectionLabel("Datos permitidos")
            Text("Batería y conexión. Con autorización adicional y permisos de Android: tipo y fecha de SMS y llamadas (al finalizar). El número solo con casilla aparte; misma retención en historial y reportes.")
            SectionLabel("Datos que no se recopilan")
            Text("Texto de SMS nuevos solo con su autorización; se guarda y exporta con los eventos. Sin contactos. Pantalla es opcional con otra autorización: muestra lo que abras (incluidos chats de WhatsApp); sin audio ni video guardado.")
            Text("Por separado puedes autorizar el texto de notificaciones nuevas de WhatsApp: acceso de Android y consentimiento por sesión. Sin historial ni archivos; hasta 20 vistas previas/5 min en memoria; no se guardan ni exportan.")
            SectionLabel("Destino y conservación")
            Text("Servidor configurado: ${session.serverUrl}")
            Text("Los eventos van al servidor y los consultan administradores y auditores de tu grupo; se conserva el contexto de consentimiento y se registran los accesos.")
            Text("Cola local: 7 días y 10 000 registros. Servidor: eventos 30 días y auditoría 90 días por defecto; configurable por el responsable.")
            SectionLabel("Pausa y retirada")
            Text("La captura es visible; puedes pausar o retirar. La retirada detiene envíos y elimina la cola local; sin red, el aviso se reintenta después.")
            AuditButton("Volver", { policy = false })
        } else when (val state = session.state) {
            AgentCore.State.ConsentPending -> {
                if (session.consent == null) {
                    AuditHeader("AUDITLAB")
                    AuditPanel {
                        Text("TÚ DECIDES QUÉ COMPARTES CON AUDITLAB.", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("ANTES DE EMPEZAR", color = AuditColors.Warning, style = MaterialTheme.typography.labelMedium)
                    Text("¿Quieres activar el monitoreo visible en este dispositivo?", style = MaterialTheme.typography.titleLarge)
                    Text("Puedes retirarlo cuando quieras.")
                    AuditButton(if (session.busy) "Registrando…" else "Aceptar consentimiento", { session.acceptConsent(scope) }, !session.busy)
                    AuditButton("Ver política de datos", { policy = true })
                } else {
                    AuditHeader("INSCRIBIR DISPOSITIVO", "PASO 2 · CONECTA CON TU CENTRO")
                    AuditPanel {
                        AuditStatus("CONSENTIMIENTO REGISTRADO", false)
                        Text("Todavía no se recopilan eventos. Completa la inscripción y autoriza la captura.")
                    }
                    AuditField("URL del servicio", serverDraft, { serverDraft = it }, !session.busy,
                        placeholder = "https://tu-servidor")
                    Text("La proporciona el administrador.", color = AuditColors.Muted)
                    if (serverDraft.trim() != session.serverUrl) {
                        Text("Al cambiar de servidor debes revisar y aceptar de nuevo el consentimiento.", color = AuditColors.Warning)
                        AuditButton("Guardar servidor y revisar consentimiento", ::saveServer,
                            !session.busy && serverDraft.isNotBlank(), outlined = true)
                    }
                    serverError?.let { Text(it, color = AuditColors.Red) }
                    AuditField("Código de inscripción", code, { code = it }, !session.busy, secret = true,
                        placeholder = "Código de un solo uso")
                    Text("Solicítalo al centro: un solo uso, vence en 10 minutos.")
                    AuditButton(if (session.busy) "Inscribiendo…" else "Inscribir dispositivo",
                        { session.enroll(code.trim(), scope) }, code.trim().length >= 32 && !session.busy && serverDraft.trim() == session.serverUrl)
                    AuditLink("Ver política de datos", { policy = true })
                }
            }
            is AgentCore.State.Enrolled -> {
                if (history) {
                    AuditHeader("ACTIVIDAD DEL AGENTE", "HISTORIAL LOCAL · ÚLTIMOS 100 REGISTROS")
                    AuditLink("← Volver a monitoreo", { history = false })
                    Text("Según retención local; no es el historial completo del servidor.", color = AuditColors.Muted)
                    AuditRule()
                    if (session.recentEvents.isEmpty()) AuditEmpty("SIN ACTIVIDAD REGISTRADA", "Los nuevos eventos aparecerán aquí durante el monitoreo autorizado.")
                    communicationEvents.forEach { row -> LocalEventRow(row) { selectedEvent = row } }
                    if(communicationEvents.isEmpty()) Text("Aún no hay mensajes, llamadas ni avisos en estos registros locales.")
                } else {
                    AuditHeader("MI TELÉFONO")
                    val active=session.realCaptureRunning || BasicMonitoringState.running || WhatsAppSharingState.active || ScreenSharingState.active
                    val lastSent=session.recentEvents.firstOrNull {it.delivery=="SENT"}
                    AuditPanel {
                        AuditStatus(if(active) "MONITOREO ACTIVO" else if(session.realMonitoring) "INICIANDO" else "DETENIDO",active)
                        Text(Build.MODEL,style=MaterialTheme.typography.titleMedium)
                        if(active) AuditButton("Detener todo",{session.pause(scope)},!session.busy,outlined=true)
                        else AuditButton("Activar",{onActivateMonitoring?.invoke()},!session.busy && !session.realMonitoring && onActivateMonitoring!=null)
                    }
                    AuditPanel {
                        BatteryMeter((session.lastDeviceStatus?.metadata?.get("batteryPercent") as? Number)?.toInt())
                        Text(networkLabel(session.lastDeviceStatus?.metadata?.get("networkState")))
                        Text("Último envío: ${lastSent?.event?.timestamp?.let(::displayFriendlyTime) ?: "Aún sin envíos"}",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
                        if(session.stats.pending>0) Text("${session.stats.pending} pendientes",color=AuditColors.Warning)
                        if(session.stats.failed>0) Text("${session.stats.failed} rechazados",color=AuditColors.Red)
                    }
                    Text("Funciones",style=MaterialTheme.typography.titleMedium)
                    AuditFunctionStatus("Batería",if(session.realCaptureRunning || BasicMonitoringState.running) "Activa" else "Detenida",active=session.realCaptureRunning || BasicMonitoringState.running)
                    AuditFunctionStatus("SMS",if(!session.smsPermission) "Sin permiso" else if(session.realCaptureRunning) "Activo" else "Detenido",active=session.smsPermission && session.realCaptureRunning,attention=!session.smsPermission)
                    AuditFunctionStatus("Llamadas",if(!session.callPermission) "Sin permiso" else if(session.realCaptureRunning) "Activas" else "Detenidas",active=session.callPermission && session.realCaptureRunning,attention=!session.callPermission)
                    AuditFunctionStatus("WhatsApp",if(WhatsAppSharingState.active && WhatsAppSharingState.listenerConnected) "Activo" else if(WhatsAppSharingState.active) "Reconectando" else "Detenido",active=WhatsAppSharingState.active && WhatsAppSharingState.listenerConnected,attention=WhatsAppSharingState.active && !WhatsAppSharingState.listenerConnected)
                    AuditFunctionStatus("Pantalla",if(ScreenSharingState.active && ScreenSharingState.confirmed) "Envío confirmado" else if(ScreenSharingState.active) "Esperando imagen" else "No compartida",active=ScreenSharingState.active && ScreenSharingState.confirmed,attention=ScreenSharingState.active && !ScreenSharingState.confirmed)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("Actividad reciente",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        TextButton({history=true}) {Text("Ver todos")}
                    }
                    if(communicationEvents.isEmpty()) Text("Sin eventos todavía",color=AuditColors.Muted)
                    communicationEvents.take(3).forEach {row->LocalEventRow(row) {selectedEvent=row}}
                    onShareText?.let {AuditButton("Compartir un texto",it,!session.busy,outlined=true)}
                    val galleryContext = androidx.compose.ui.platform.LocalContext.current
                    AuditButton("Compartir fotos", { galleryContext.startActivity(Intent(galleryContext, GalleryShareActivity::class.java)) }, !session.busy, outlined=true)
                    var details by rememberSaveable {mutableStateOf(false)}
                    AuditLink(if(details) "Ocultar detalles" else "Detalles y ajustes",{details=!details})
                    if(details) {
                        AuditButton("Enviar estado ahora",{session.refreshDeviceStatus(scope)},active && session.visibleAvailable && !session.busy,outlined=true)
                        AuditButton("Generar evento simulado",{session.sendSimulatedEvent(scope)},!session.busy && session.visibleAvailable,outlined=true)
                        platformControls?.invoke()
                        SectionLabel("Consentimiento y funciones")
                        Text("Al activar aceptas las funciones que marcas. Aquí puedes pausar o revocar cada una por separado.", style = MaterialTheme.typography.bodySmall, color = AuditColors.Muted)
                        ConsentModulesPolicy.available.forEach { moduleId ->
                            val module = session.consentModules.firstOrNull { it.module == moduleId }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(ConsentModulesPolicy.labels[moduleId] ?: moduleId, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                AuditStateTag(when(module?.state) { ModuleState.ACCEPTED -> "Aceptado"; ModuleState.PAUSED -> "Pausado"; ModuleState.REVOKED -> "Revocado"; else -> "Sin aceptar" },
                                    active=module?.state==ModuleState.ACCEPTED, attention=module==null || module.state==ModuleState.PAUSED, failed=module?.state==ModuleState.REVOKED)
                            }
                            Row {
                                when(module?.state) {
                                    ModuleState.ACCEPTED -> {
                                        AuditLink("Pausar", { session.setModuleState(scope, moduleId, ModuleState.PAUSED) }, !session.busy)
                                        AuditLink("Revocar", { session.setModuleState(scope, moduleId, ModuleState.REVOKED) }, !session.busy)
                                    }
                                    ModuleState.PAUSED -> {
                                        AuditLink("Reanudar", { session.setModuleState(scope, moduleId, ModuleState.ACCEPTED) }, !session.busy)
                                        AuditLink("Revocar", { session.setModuleState(scope, moduleId, ModuleState.REVOKED) }, !session.busy)
                                    }
                                    ModuleState.REVOKED -> AuditLink("Aceptar de nuevo", { session.setModuleState(scope, moduleId, ModuleState.ACCEPTED) }, !session.busy)
                                    else -> AuditLink("Aceptar", { session.setModuleState(scope, moduleId, ModuleState.ACCEPTED) }, !session.busy)
                                }
                            }
                        }
                        Text("Retirar el consentimiento general revoca todas las funciones.", style = MaterialTheme.typography.bodySmall, color = AuditColors.Muted)
                        AuditLink("Ver política de datos",{policy=true})
                        AuditButton("Retirar consentimiento",{confirmWithdrawal=true},!session.revoking,outlined=true)
                    }

                }
            }
            is AgentCore.State.Revoked -> {
                val remote = state.reason == AgentCore.RevokeReason.REMOTE
                AuditHeader(if (remote) "DISPOSITIVO REVOCADO" else "CONSENTIMIENTO RETIRADO",
                    if (remote) "EL CENTRO DE CONTROL BLOQUEÓ NUEVOS ENVÍOS" else "MONITOREO DETENIDO EN ESTE DISPOSITIVO")
                Text("La captura local se detuvo.", style = MaterialTheme.typography.titleMedium)
                Text("Los eventos pendientes no serán enviados.")
                AuditPanel {
                    Text("Contacta al administrador para volver a inscribir el dispositivo.", style = MaterialTheme.typography.titleSmall)
                }
                if (session.revocationPending) {
                    AuditNotice("La retirada local está guardada. Falta confirmar el aviso al servidor o la limpieza.")
                    AuditButton("Reintentar retirada pendiente", { session.retryPrivacy(scope) }, !session.busy)
                }
                AuditLink("Volver a aceptar consentimiento", { session.restartConsent(scope) }, !session.busy && !session.revocationPending)
            }
            is AgentCore.State.Blocked -> {
                AuditHeader("TRANSMISIONES BLOQUEADAS")
                AuditPanel { Text(state.message) }
                AuditButton("Retirar consentimiento y limpiar", { confirmWithdrawal = true }, !session.revoking)
            }
        }
    }
}

private fun deliveryLabel(value: String): String = when (value) {
    "SENT" -> "Enviado"
    "FAILED" -> "Rechazado"
    else -> "Pendiente"
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun LocalEventRow(row: AgentSession.LocalEvent, onClick: () -> Unit) {
    AuditPanel(Modifier.clickable(onClick=onClick),accent=AuditCategoryColors.category(row.event.category).ink) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            AuditCategoryTag(row.event.category)
            AuditStateTag(deliveryLabel(row.delivery),active=row.delivery=="SENT",attention=row.delivery=="PENDING",failed=row.delivery=="FAILED")
            if(row.event.isSimulated) AuditStateTag("Simulado",attention=true)
        }
        Text(metadataTitle(row.event.metadata),style=MaterialTheme.typography.titleMedium)
        (row.event.metadata["smsText"] as? String)?.let { AuditEventPreview(it,row.event.category) }
        metadataDescription(row.event.metadata).takeIf { it.isNotEmpty() }?.let {
            Text(it,color=AuditColors.Muted,style=MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(displayFriendlyTime(row.event.timestamp),Modifier.weight(1f),color=AuditColors.Muted,style=MaterialTheme.typography.bodySmall)
            Text("Ver detalle →",style=MaterialTheme.typography.labelLarge)
        }
    }
}
