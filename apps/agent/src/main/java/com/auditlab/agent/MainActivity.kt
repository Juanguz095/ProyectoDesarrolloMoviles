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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    private val phonePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.session.startRealMonitoring(model.scope)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.session.resume(model.scope) else model.session.refresh(model.scope)
    }
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
                    AgentScreen(model.session, workScope = model.scope, onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else model.session.resume(model.scope)
                    }, onNotificationSettings = {
                        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    }, onRequestRealMonitoring = {
                        phonePermissions.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG))
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
internal fun AgentScreen(session: AgentSession, onRequestNotifications: (() -> Unit)? = null,
                         onNotificationSettings: (() -> Unit)? = null, workScope: CoroutineScope? = null,
                         onRequestRealMonitoring: (() -> Unit)? = null) {
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
    var realAccepted by rememberSaveable(session.consentEpoch) { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<AgentSession.LocalEvent?>(null) }
    androidx.compose.runtime.LaunchedEffect(session.state) {
        if (session.state !is AgentCore.State.Enrolled) { history = false; selectedEvent = null; realAccepted = false }
        if (session.state is AgentCore.State.Enrolled) code = ""
    }
    BackHandler(policy || history) { policy = false; history = false }
    fun saveServer() {
        try {
            val normalized = com.auditlab.common.ApiClient(serverDraft.trim()).baseUrl
            if (normalized != session.serverUrl) session.updateServerUrl(normalized)
            serverError = null; configureServer = false
        } catch (_: IllegalArgumentException) {
            serverError = "Usa una dirección HTTPS sin rutas, usuario ni contraseña. HTTP solo se admite para las direcciones locales permitidas."
        }
    }
    if (configureServer) AlertDialog(onDismissRequest = { configureServer = false },
        title = { Text("CONEXIÓN CON EL CENTRO", style = MaterialTheme.typography.titleLarge) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AuditField("URL del servicio", serverDraft, { serverDraft = it; serverError = null }, !session.busy,
                placeholder = "https://tu-servidor")
            Text("El administrador te proporciona esta dirección. Al cambiarla debes aceptar de nuevo el consentimiento.")
            serverError?.let { Text(it, color = AuditColors.Red) }
        } },
        confirmButton = { AuditLink("Guardar dirección", ::saveServer, !session.busy && serverDraft.isNotBlank()) },
        dismissButton = { AuditLink("Cancelar", { configureServer = false }) })

    if (confirmWithdrawal) AlertDialog(onDismissRequest = { confirmWithdrawal = false },
        title = { Text("Retirar consentimiento", style = MaterialTheme.typography.titleLarge) },
        text = { Text("Se detendrán la captura y los envíos. Se eliminará la cola local. Si no hay conexión, el aviso al servidor quedará pendiente.") },
        confirmButton = { AuditLink("Confirmar retirada", { confirmWithdrawal = false; session.revoke(scope) }) },
        dismissButton = { AuditLink("Cancelar", { confirmWithdrawal = false }) })
    selectedEvent?.let { row -> AlertDialog(onDismissRequest = { selectedEvent = null },
        title = { Text(metadataTitle(row.event.metadata), style = MaterialTheme.typography.titleLarge) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (row.event.isSimulated) "Simulado" else "Real")
            Text(displayTime(row.event.timestamp))
            Text(deliveryLabel(row.delivery))
            Text(metadataDescription(row.event.metadata))
            Text("ID: ${row.event.eventId}")
            Text("Dispositivo: ${row.event.sourceDevice}")
            Text("Política: ${row.event.consent.policyVersion}")
            row.errorCode?.let { Text("Error: $it") }
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
                Text("AuditLab registra eventos propios, eventos de prueba identificados como simulados y metadatos autorizados de este dispositivo.")
            }
            SectionLabel("Datos permitidos")
            Text("Batería y conexión. Si das una autorización adicional y Android concede los permisos: tipo y fecha de SMS y llamadas. Las llamadas aparecen al finalizar.")
            SectionLabel("Datos que no se recopilan")
            Text("Contenido de mensajes, números telefónicos, contactos, audio, chats, WhatsApp o RCS.")
            SectionLabel("Destino y conservación")
            Text("Servidor configurado: ${session.serverUrl}")
            Text("Los eventos se envían al servidor configurado y los consultan administradores y auditores autorizados de tu grupo. Se conserva el contexto de consentimiento y se registran los accesos.")
            Text("La cola local conserva hasta 7 días y 10 000 registros. La política predeterminada del servidor conserva eventos 30 días y auditoría 90 días; el responsable puede configurar estos plazos.")
            SectionLabel("Pausa y retirada")
            Text("La captura es visible. Puedes pausar o retirar tu consentimiento. La retirada detiene nuevos envíos y elimina la cola local; si estás sin red, el aviso al servidor se reintenta después.")
            AuditButton("Volver", { policy = false })
        } else when (val state = session.state) {
            AgentCore.State.ConsentPending -> {
                if (session.consent == null) {
                    AuditHeader("AUDITLAB")
                    AuditPanel {
                        Text("AUDITLAB NO LEE EL CONTENIDO DE OTRAS APLICACIONES.", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("ANTES DE EMPEZAR", color = AuditColors.Warning, style = MaterialTheme.typography.labelMedium)
                    Text("¿Quieres activar el monitoreo visible en este dispositivo?", style = MaterialTheme.typography.titleLarge)
                    Text("Puedes retirarlo cuando quieras.")
                    Text("Registra batería y conexión. SMS y llamadas requieren autorización adicional; solo se consulta tipo y fecha.", color = AuditColors.Muted)
                    AuditButton(if (session.busy) "Registrando…" else "Aceptar consentimiento", { session.acceptConsent(scope) }, !session.busy)
                    AuditButton("Ver política de datos", { policy = true })
                    AuditLink("Configurar conexión", { serverDraft = session.serverUrl; serverError = null; configureServer = true }, !session.busy)
                } else {
                    AuditHeader("INSCRIBIR DISPOSITIVO", "PASO 2 · CONECTA CON TU CENTRO")
                    AuditPanel {
                        AuditStatus("CONSENTIMIENTO REGISTRADO", false)
                        Text("Todavía no se recopilan eventos. Completa la inscripción y autoriza la captura.")
                    }
                    AuditField("URL del servicio", serverDraft, { serverDraft = it }, !session.busy,
                        placeholder = "https://tu-servidor")
                    Text("Usa la dirección del servidor que te indique el administrador.", color = AuditColors.Muted)
                    if (serverDraft.trim() != session.serverUrl) {
                        Text("Al cambiar de servidor debes revisar y aceptar de nuevo el consentimiento.", color = AuditColors.Warning)
                        AuditButton("Guardar servidor y revisar consentimiento", ::saveServer,
                            !session.busy && serverDraft.isNotBlank(), outlined = true)
                    }
                    serverError?.let { Text(it, color = AuditColors.Red) }
                    AuditField("Código de inscripción", code, { code = it }, !session.busy, secret = true,
                        placeholder = "Código de un solo uso")
                    Text("Solicita un código al centro de control. Tiene un solo uso y vence en 10 minutos.")
                    AuditButton(if (session.busy) "Inscribiendo…" else "Inscribir dispositivo",
                        { session.enroll(code.trim(), scope) }, code.trim().length >= 32 && !session.busy && serverDraft.trim() == session.serverUrl)
                    AuditLink("Ver política de datos", { policy = true })
                }
            }
            is AgentCore.State.Enrolled -> {
                if (history) {
                    AuditHeader("ACTIVIDAD DEL AGENTE", "HISTORIAL LOCAL · ÚLTIMOS 100 REGISTROS")
                    AuditLink("← Volver a monitoreo", { history = false })
                    Text("Los registros se conservan según la retención local; no es el historial completo del servidor.", color = AuditColors.Muted)
                    AuditRule()
                    if (session.recentEvents.isEmpty()) AuditEmpty("SIN ACTIVIDAD REGISTRADA", "Los nuevos eventos aparecerán aquí durante el monitoreo autorizado.")
                    session.recentEvents.forEach { row -> LocalEventRow(row) { selectedEvent = row } }
                } else {
                    AuditHeader("MONITOREO")
                    AuditPanel {
                        val active = session.visibleAvailable && session.realCaptureRunning && !session.revoking
                        AuditStatus(when {
                            session.revoking -> "DETENIENDO"
                            !session.visibleAvailable -> "PAUSADO"
                            active -> "ACTIVO"
                            session.realMonitoring -> "INICIANDO CAPTURA"
                            else -> "LISTO PARA ACTIVAR"
                        }, active)
                        Text("Sincronización: ${session.stats.pending} pendientes · ${session.stats.sent} enviados", style = MaterialTheme.typography.bodySmall)
                        if (session.stats.failed > 0) Text("${session.stats.failed} eventos rechazados", color = AuditColors.Red)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("DISPOSITIVO", style = MaterialTheme.typography.titleMedium)
                        Text("${Build.MODEL} / ${state.deviceId.take(8)}…${state.deviceId.takeLast(4)}", color = AuditColors.Muted,
                            style = MaterialTheme.typography.titleSmall)
                    }
                    Column {
                        Text("ACTIVIDAD RECIENTE", style = MaterialTheme.typography.titleMedium)
                        AuditRule()
                        if (session.recentEvents.isEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text("Sin actividad registrada.", color = AuditColors.Muted)
                        }
                        session.recentEvents.take(4).forEach { row -> LocalEventRow(row) { selectedEvent = row } }
                        AuditLink("Ver todos los eventos →", { history = true })
                    }
                    if (session.visibleAvailable) AuditButton("Pausar monitoreo", { session.pause(scope) }, !session.busy)
                    else {
                        Text("La captura y los envíos están pausados. Habilita la notificación visible para continuar.")
                        AuditButton("Habilitar indicador y reanudar", { onRequestNotifications?.invoke() ?: session.resume(scope) }, !session.busy)
                        onNotificationSettings?.let { AuditLink("Ajustes de notificación", it) }
                    }
                    AuditButton(if (session.revoking) "Retirando consentimiento…" else "Retirar consentimiento",
                        { confirmWithdrawal = true }, !session.revoking)

                    SectionLabel("Estado del dispositivo")
                    AuditPanel {
                        val reading = session.lastDeviceStatus
                        SectionLabel("Última lectura real guardada")
                        SectionLabel("Batería")
                        BatteryMeter((reading?.metadata?.get("batteryPercent") as? Number)?.toInt())
                        SectionLabel("Conexión")
                        Text(networkLabel(reading?.metadata?.get("networkState")), style = MaterialTheme.typography.titleSmall)
                        AuditRule()
                        Text(displayTime(reading?.timestamp), color = AuditColors.Muted)
                    }
                    SectionLabel("Captura autorizada")
                    AuditPanel {
                        Text("Batería · Conexión · SMS · Llamadas", style = MaterialTheme.typography.titleSmall)
                        Text("Solo metadatos desde la activación. Sin contenido, números ni audio. Android puede restringir SMS y llamadas.")
                        Text(session.realStatus, color = AuditColors.Muted)
                        if (!session.realMonitoring) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(realAccepted, { realAccepted = it }, enabled = !session.busy)
                                Text("Autorizo la captura y envío de estos metadatos reales.", Modifier.weight(1f))
                            }
                            AuditButton("Autorizar e iniciar captura real",
                                { onRequestRealMonitoring?.invoke() ?: session.startRealMonitoring(scope) },
                                realAccepted && session.visibleAvailable && !session.busy && !session.revoking)
                        } else AuditButton("Detener captura real", { session.stopRealMonitoring(scope) }, !session.busy, outlined = true)
                    }
                    AuditButton(if (session.busy) "Enviando…" else "Generar evento simulado", { session.sendSimulatedEvent(scope) },
                        !session.busy && session.visibleAvailable && !session.revoking, outlined = true)
                    AuditLink("Ver política de datos", { policy = true })
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
private fun LocalEventRow(row: AgentSession.LocalEvent, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick, modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(metadataTitle(row.event.metadata), style = MaterialTheme.typography.bodyMedium)
                    Text("${if (row.event.isSimulated) "Simulado" else "Real"} · ${displayTime(row.event.timestamp)}",
                        color = AuditColors.Muted, style = MaterialTheme.typography.bodySmall)
                    metadataDescription(row.event.metadata).takeIf { it.isNotEmpty() }?.let {
                        Text(it, color = AuditColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(deliveryLabel(row.delivery), style = MaterialTheme.typography.labelMedium)
            }
        }
        AuditRule()
    }
}
