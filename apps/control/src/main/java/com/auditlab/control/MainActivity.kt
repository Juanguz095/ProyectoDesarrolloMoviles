package com.auditlab.control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auditlab.common.*
import com.auditlab.design.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class ControlViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
    val session = ControlSession(application)
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    var pendingExport: ControlSession.ExportTicket? = null
    override fun onCleared() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
}
class MainActivity : ComponentActivity() {
    private val model by lazy { androidx.lifecycle.ViewModelProvider(this)[ControlViewModel::class.java] }
    private val saveCsv = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val ticket = model.pendingExport; model.pendingExport = null
        if (uri == null || ticket == null) model.session.notifyExportCancelled()
        else if (!ExportDestination.isAllowed(uri)) model.session.notifyExportUnavailable()
        else model.session.export(ticket, model.scope, uri) { bytes ->
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
                ?: error("No se pudo abrir el documento")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0xFFDAD7C0.toInt(), 0xFFDAD7C0.toInt()),
            navigationBarStyle = SystemBarStyle.light(0xFFDAD7C0.toInt(), 0xFFDAD7C0.toInt()),
        )
        setContent { AuditTheme { Surface(modifier = Modifier.fillMaxSize()) {
            ControlScreen(model.session, onExport = {
                model.session.exportTicket()?.let { ticket ->
                    model.pendingExport = ticket
                    try { saveCsv.launch("AuditLab-eventos.csv") }
                    catch (_: android.content.ActivityNotFoundException) {
                        model.pendingExport = null
                        model.session.notifyExportUnavailable()
                    } catch (_: SecurityException) {
                        model.pendingExport = null
                        model.session.notifyExportUnavailable()
                    }
                }
            }, workScope = model.scope, onOpenReport = {
                model.session.lastReportUri?.let { uri ->
                    try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                        .setDataAndType(uri, "text/csv").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    catch (_: android.content.ActivityNotFoundException) { model.session.notifyViewerUnavailable() }
                    catch (_: SecurityException) { model.session.notifyViewerUnavailable() }
                }
            })
        } } }
    }
}

@Composable
internal fun ControlScreen(session: ControlSession, onExport: (() -> Unit)? = null,
    workScope: CoroutineScope? = null, onOpenReport: (() -> Unit)? = null) {
    val composeScope = rememberCoroutineScope()
    val scope = workScope ?: composeScope
    var screen by rememberSaveable { mutableStateOf("inicio") }
    var chosenDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val currentDevice = session.devices.firstOrNull { it.deviceId == chosenDevice } ?: session.devices.firstOrNull()
    val lifecycle=(LocalActivity.current as? androidx.lifecycle.LifecycleOwner)?.lifecycle
    var foreground by remember { mutableStateOf(lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)!=false) }
    DisposableEffect(lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_START) foreground=true
            if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP) { foreground=false;session.cancelRefresh() }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer);session.cancelRefresh() }
    }
    LaunchedEffect(screen,currentDevice?.deviceId,session.signedIn,foreground,session.autoRefresh) {
        session.cancelRefresh()
        if(foreground && session.signedIn && session.autoRefresh) {
            while(isActive && foreground) {
                session.refreshVisible(screen,currentDevice?.deviceId,composeScope,automatic=true)
                delay(10_000)
            }
        }
    }
    LaunchedEffect(session.signedIn) {
        if (session.signedIn) {
            password = ""; user = ""
            if (!session.devicesLoaded && !session.busy) session.refresh(scope)
        } else { screen = "inicio"; chosenDevice = null }
    }
    LaunchedEffect(screen, currentDevice?.deviceId, session.overviewDevice, session.busy, session.signedIn,foreground,session.refreshing) {
        if (screen in setOf("inicio","dispositivo") && session.signedIn && foreground && !session.busy && !session.refreshing && currentDevice != null && session.overviewDevice != currentDevice.deviceId)
            session.loadOverview(currentDevice.deviceId, scope)
    }
    BackHandler(session.signedIn && screen != "inicio") {
        screen = when (screen) { "whatsapp" -> "dispositivo"; "dispositivo" -> "dispositivos"; "auditoria", "inscripcion" -> "mas"; else -> "inicio" }
    }
    if (session.logoutConfirmation && session.signedIn) AlertDialog(onDismissRequest = session::cancelLogout,
        title = { Text("Cerrar sesión", style = MaterialTheme.typography.titleLarge) },
        text = { Text("Se eliminará la sesión local de este centro de control.") },
        confirmButton = { AuditLink("Confirmar salida", { session.cancelLogout(); session.logout(scope) }) },
        dismissButton = { AuditLink("Cancelar", session::cancelLogout) })
    session.revokeTarget?.takeIf { session.isAdmin }?.let { target ->
        AlertDialog(onDismissRequest = session::cancelRevoke,
            title = { Text("Revocar dispositivo", style = MaterialTheme.typography.titleLarge) },
            text = { Text("Se bloquearán nuevas transmisiones de ${session.deviceName(target)}. El agente detendrá la captura al recibir la revocación del servidor. Volver a inscribirlo requiere consentimiento y código nuevos.") },
            confirmButton = { AuditLink("Confirmar revocación", { session.cancelRevoke(); session.revoke(target, scope) }) },
            dismissButton = { AuditLink("Cancelar", session::cancelRevoke) })
    }
    session.detail?.let { event -> EventDetail(event, session::closeDetail) }
    fun openEvents(device: String?) {
        if (session.busy) return
        session.applyFilters(EventQuery(device = device,category="communication"), scope); screen = "eventos"
    }
    val selectedTab = when (screen) { "whatsapp", "dispositivo" -> "dispositivos"; "inscripcion", "auditoria" -> "mas"; else -> screen }
    AuditPage(key = if (session.signedIn) screen else "login", feedback = session.message, working = session.busy, bottom = if (session.signedIn) ({
        ControlNavigation(selectedTab, !session.busy) { next ->
            screen = next
            if (next == "eventos" && !session.eventsLoaded) session.applyFilters(session.query, scope)
        }
    }) else null) {
        if (!session.signedIn) {
            AuditHeader("AUDITLAB", "CENTRO DE CONTROL")
            Spacer(Modifier.height(10.dp))
            Text("INICIAR SESIÓN", style = MaterialTheme.typography.titleLarge)
            Text("Conecta con el servidor para ver tus dispositivos.", color = AuditColors.Muted)
            session.refreshError?.let { AuditNotice(it) }
            AuditField("URL del servicio", session.serverUrl, session::updateServerUrl, !session.busy && !session.storageBlocked,
                placeholder = "https://tu-servidor")
            Text("Pega la dirección HTTPS de tu PC, sin /ready ni /v1. En los teléfonos, 127.0.0.1 apunta al propio teléfono.",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
            AuditField("Usuario", user, { user = it }, !session.busy)
            AuditField("Contraseña", password, { password = it }, !session.busy, secret = true)
            AuditButton(if (session.busy) "Conectando…" else "Iniciar sesión", { session.login(user.trim(), password, scope) },
                !session.busy && !session.storageBlocked && user.isNotBlank() && password.isNotEmpty())
            if (session.storageBlocked) AuditButton("Borrar sesión local", { session.clearLocal(scope) }, !session.busy, outlined = true)
            Text("Acceso por rol: administrador o auditor. Todas las consultas quedan registradas.", color = AuditColors.Muted)
        } else {
            when (screen) {
                "inicio" -> Dashboard(session, currentDevice, scope, onEvents = { openEvents(currentDevice?.deviceId) },
                    onDevice={screen="dispositivo"},onChoose={chosenDevice=it})
                "eventos" -> EventExplorer(session, scope, onExport, onOpenReport)
                "whatsapp" -> WhatsAppPreviewsView(session,currentDevice?.deviceId) { screen="dispositivo" }
                "dispositivos" -> DeviceList(session, scope) { id -> chosenDevice = id; screen = "dispositivo" }
                "dispositivo" -> {
                    DeviceDetail(session, currentDevice, scope, onBack = { screen = "dispositivos" },
                        onEvents = { openEvents(currentDevice?.deviceId) },
                        onAudit = { session.loadAudit(scope); screen = "auditoria" }, onWhatsApp = { screen="whatsapp" })
                }
                "auditoria" -> AuditHistory(session, scope) { screen = "mas" }
                "inscripcion" -> EnrollmentView(session, scope) { screen = "mas" }
                else -> {
                    AuditHeader("AJUSTES", "CUENTA, SERVIDOR Y DISPOSITIVOS")
                    AuditPanel {
                        Text(session.username, style = MaterialTheme.typography.titleMedium)
                        Text("Rol: ${if (session.isAdmin) "administrador" else "auditor"}")
                        Text("Servidor conectado",style=MaterialTheme.typography.labelMedium)
                        Text(session.serverUrl, color = AuditColors.Muted)
                        Text("Para cambiar la dirección del servidor, cierra sesión y edita URL del servicio al volver a entrar.",style=MaterialTheme.typography.bodySmall)
                    }
                    AuditButton("Consultar auditoría", { session.loadAudit(scope); screen = "auditoria" }, !session.busy)
                    if (session.isAdmin) AuditButton("Crear código de inscripción", { screen = "inscripcion" }, !session.busy)
                    AuditButton("Actualizar dispositivos", { session.refresh(scope) }, !session.busy, outlined = true)
                    AuditRule()
                    Text("Última actualización de la lista", color = AuditColors.Muted)
                    Text(displayTime(session.devicesUpdatedAt))
                    AuditButton("Cerrar sesión", session::requestLogout, !session.busy, outlined = true)
                }
            }
        }
    }
}

@Composable
private fun ControlNavigation(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        AuditRule()
        Row(Modifier.fillMaxWidth()) {
            listOf("inicio" to "INICIO", "eventos" to "EVENTOS", "dispositivos" to "EQUIPOS", "mas" to "MÁS").forEach { (id, label) ->
                TextButton({ onSelect(id) }, Modifier.weight(1f).heightIn(min = 52.dp), enabled = enabled,
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    colors = ButtonDefaults.textButtonColors(containerColor = if (selected == id) AuditColors.Panel else androidx.compose.ui.graphics.Color.Transparent),
                    contentPadding = PaddingValues(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected == id) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun EventDetail(event: AuditEvent, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose,
        title = { Text("DETALLE DE EVENTO", style = MaterialTheme.typography.titleLarge) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(eventTitle(event), style = MaterialTheme.typography.titleMedium)
            if(event.category=="whatsapp") Text(if(event.direction=="outbound")
                "Android mostró una respuesta propia en una notificación. Este registro no confirma entrega ni detecta todos los mensajes escritos dentro del chat. No se guardó texto, remitente ni número."
                else "Se observó una notificación nueva de WhatsApp. Este evento guarda solo tipo y fecha; el texto de la vista previa pertenece a la sesión temporal autorizada.")
            if(event.category=="sms") Text("Android registró un SMS ${if(event.direction=="outbound") "enviado" else "recibido"}. El número identifica al remitente o destinatario. El texto se comparte solo con autorización adicional. No se relaciona automáticamente un recibido con una respuesta.")
            (event.metadata["smsText"] as? String)?.let {
                SectionLabel("Texto del SMS");Text(it)
                if(event.metadata["smsTextTruncated"]==true) Text("El mensaje superó el límite: se muestran sus primeros 2 000 caracteres.")
            }
            Text(if (event.isSimulated) "Evento simulado" else "Evento real del dispositivo")
            AuditRule()
            SectionLabel("Fecha del evento"); Text(displayFriendlyTime(event.timestamp))
            metadataDescription(event.metadata).takeIf {it.isNotBlank()}?.let {Text(it)}
            var technical by remember {mutableStateOf(false)}
            AuditLink(if(technical) "Ocultar información técnica" else "Información técnica",{technical=!technical})
            if(technical) {
            SectionLabel("Recibido por el servidor"); Text(displayLocalTime(event.receivedAt))
            SectionLabel("Dispositivo"); Text(event.sourceDevice)
            SectionLabel("Identificador del evento"); Text(event.eventId)
            metadataDescription(event.metadata).takeIf { it.isNotEmpty() }?.let { Text(it) }
            Text("Código: ${event.metadata["appEventCode"] ?: "Sin código"}")
            Text("Categoría: ${categoryName(event.category)}")
            Text("Dirección: ${when (event.direction) { "inbound" -> "Entrante"; "outbound" -> "Saliente"; else -> "Ocurrido" }}")
            AuditRule()
            SectionLabel("Consentimiento"); Text(event.consent.consentId)
            Text("Política: ${event.consent.policyVersion}")
            Text("Aceptado: ${displayTime(event.consent.acceptedAt)}")
            }
        } }, confirmButton = { AuditLink("Cerrar detalle", onClose) })
}

internal fun eventTitle(event: AuditEvent): String = metadataTitle(event.metadata, categoryName(event.category))

internal fun categoryName(category: String): String = when (category) {
    "device_status" -> "Batería y conexión"
    "sms" -> "SMS"
    "call" -> "Llamadas"
    "whatsapp" -> "WhatsApp"
    "communication" -> "Mensajes y llamadas"
    "app_lifecycle" -> "Actividad de la app"
    "sync" -> "Sincronización"
    else -> category
}

internal fun auditActionName(action: String): String = when(action) {
    "events_queried", "event_queried" -> "Consulta de eventos"
    "report_exported" -> "Exportación de eventos"
    "events_received" -> "Recepción de eventos"
    "devices_listed" -> "Consulta de dispositivos"
    "device_queried" -> "Consulta de un dispositivo"
    "device_revoked" -> "Dispositivo revocado"
    "device_enrolled" -> "Inscripción de dispositivo"
    "enrollment_code_created" -> "Código de inscripción creado"
    "consent_revoked" -> "Consentimiento retirado"
    "login_succeeded" -> "Inicio de sesión"
    "login_failed" -> "Intento de acceso rechazado"
    "session_revoked" -> "Cierre de sesión"
    "session_refreshed" -> "Renovación de sesión"
    "audit_log_queried" -> "Consulta de auditoría"
    "retention_executed" -> "Limpieza por retención"
    "bootstrap_user_created" -> "Usuario inicial creado"
    "screen_frame_queried" -> "Consulta de pantalla compartida"
    "whatsapp_sharing_started" -> "Sesión de WhatsApp iniciada"
    "whatsapp_sharing_stopped" -> "Sesión de WhatsApp detenida"
    "whatsapp_previews_queried" -> "Consulta de mensajes temporales de WhatsApp"
    else -> "Actividad registrada"
}
