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
            LaunchedEffect(model.session.state,model.session.ready,model.session.busy) {
                if(model.session.ready && !model.session.busy && model.session.state is AgentCore.State.Enrolled) {
                    val onboarding=getSharedPreferences("auditlab_permission_onboarding",MODE_PRIVATE)
                    if(!onboarding.getBoolean("notificationsAsked",false) && onboarding.edit().putBoolean("notificationsAsked",true).commit()) {
                        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            AuditTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentScreen(model.session, workScope = model.scope, onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else model.session.resume(model.scope)
                    }, onNotificationSettings = {
                        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    }, onRequestRealMonitoring = {
                        val missing=arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG).filter {checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
                        if(missing.isEmpty()) model.session.startRealMonitoring(model.scope) else phonePermissions.launch(missing.toTypedArray())
                    }, onShareScreen = {
                        startActivity(Intent(this, ScreenShareActivity::class.java))
                    }, onShareWhatsApp = {
                        startActivity(Intent(this, WhatsAppShareActivity::class.java))
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
                         onRequestRealMonitoring: (() -> Unit)? = null,
                         onShareScreen: (() -> Unit)? = null,
                         onShareWhatsApp: (() -> Unit)? = null) {
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
    val communicationEvents=session.recentEvents.filter { !it.event.isSimulated && it.event.category in setOf("sms","call","whatsapp") }
    var technicalHistory by rememberSaveable {mutableStateOf(false)}
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
            Text(displayFriendlyTime(row.event.timestamp))
            Text(deliveryLabel(row.delivery))
            Text(metadataDescription(row.event.metadata))
            (row.event.metadata["smsText"] as? String)?.let { Text(it) }
            var technical by remember {mutableStateOf(false)}
            AuditLink(if(technical) "Ocultar información técnica" else "Información técnica",{technical=!technical})
            if(technical) {
            Text("ID: ${row.event.eventId}")
            Text("Dispositivo: ${row.event.sourceDevice}")
            Text("Política: ${row.event.consent.policyVersion}")
            row.errorCode?.let { Text("Error: $it") }
            }
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
            Text("Batería y conexión. Si das una autorización adicional y Android concede los permisos: tipo y fecha de SMS y llamadas. Puedes autorizar también el número en una casilla adicional; se conservará en el historial y reportes según la misma retención. Las llamadas aparecen al finalizar.")
            SectionLabel("Datos que no se recopilan")
            Text("El texto de SMS nuevos solo se lee si marcas su autorización adicional; puede guardarse y exportarse con los eventos. No se leen contactos. Compartir pantalla es opcional y requiere otra autorización: permite ver lo que abras, incluidos chats de WhatsApp. No comparte audio ni guarda video.")
            Text("También puedes autorizar, por separado, compartir el texto de notificaciones nuevas de WhatsApp. Requiere acceso de Android y consentimiento por sesión. No recupera historial ni archivos; conserva hasta 20 vistas previas durante 5 minutos en memoria, sin exportarlas ni guardarlas en la base de datos.")
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
                    AuditLink(if(technicalHistory) "Ocultar eventos técnicos" else "Mostrar eventos técnicos",{technicalHistory=!technicalHistory})
                    (if(technicalHistory) session.recentEvents else communicationEvents).forEach { row -> LocalEventRow(row) { selectedEvent = row } }
                    if(!technicalHistory && communicationEvents.isEmpty()) Text("Aún no hay mensajes ni llamadas en estos registros locales.")
                } else {
                    AuditHeader("MI TELÉFONO", "AGENTE AUDITLAB")
                    val active=session.visibleAvailable && session.realCaptureRunning && !session.revoking
                    val lastSent=session.recentEvents.firstOrNull {it.delivery=="SENT"}
                    AuditPanel {
                        AuditStatus(when {
                            session.revoking -> "DETENIENDO EL MONITOREO"
                            !session.visibleAvailable -> "MONITOREO PAUSADO"
                            active -> "MONITOREO ACTIVO"
                            session.realMonitoring -> "INICIANDO CAPTURA"
                            else -> "FALTA ACTIVAR LA CAPTURA"
                        },active)
                        Text(when {
                            active -> "Este teléfono comparte batería, conexión y los metadatos que autorizaste. Puedes detenerlo cuando quieras."
                            !session.visibleAvailable -> "Habilita la notificación visible para poder compartir información."
                            else -> "El teléfono está vinculado. Activa la captura de abajo para que el Centro reciba batería y eventos."
                        })
                        Text("${Build.MODEL}",color=AuditColors.Muted,style=MaterialTheme.typography.bodySmall)
                        if(session.visibleAvailable) AuditButton("Pausar monitoreo",{session.pause(scope)},!session.busy,outlined=true)
                        else {
                            AuditButton("Habilitar indicador y reanudar",{onRequestNotifications?.invoke() ?: session.resume(scope)},!session.busy)
                            onNotificationSettings?.let {AuditLink("Ajustes de notificación",it)}
                        }
                    }
                    AuditPanel {
                        Text("1. Batería y eventos del teléfono",style=MaterialTheme.typography.titleMedium)
                        Text("Se lee el estado cada 10 segundos y se envía cuando cambia; también se confirma al menos una vez por minuto mientras hay conexión.")
                        Text("SMS y llamadas muestran tipo y fecha. Puedes autorizar también el número; Android debe conceder sus permisos. La batería no necesita acceso a SMS o llamadas.",color=AuditColors.Muted,style=MaterialTheme.typography.bodySmall)
                        if(!session.realMonitoring) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(realAccepted,{realAccepted=it},enabled=!session.busy)
                                Text("Autorizo la captura y el envío de estos metadatos reales.",Modifier.weight(1f))
                            }
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(session.numberConsent,session::updateNumberConsent,enabled=!session.busy)
                                Text("Autorizo compartir el número del remitente/destinatario de SMS y llamadas. Quedará en el historial y reportes, según la retención de eventos.",Modifier.weight(1f))
                            }
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(session.smsTextConsent,session::updateSmsTextConsent,enabled=!session.busy)
                                Text("Autorizo compartir el texto de SMS nuevos enviados y recibidos (hasta 2 000 caracteres). Los administradores y auditores podrán leerlo y exportarlo; se guarda con la retención de eventos. Puede contener información sensible. No incluye RCS ni WhatsApp.",Modifier.weight(1f))
                            }
                            AuditButton("Autorizar e iniciar captura real",
                                {onRequestRealMonitoring?.invoke() ?: session.startRealMonitoring(scope)},
                                realAccepted && session.visibleAvailable && !session.busy && !session.revoking)
                        } else {
                            AuditStatus(if(session.realCaptureRunning) "CAPTURA EN EJECUCIÓN" else "INICIANDO SERVICIO",session.realCaptureRunning)
                            AuditButton("Detener captura real",{session.stopRealMonitoring(scope)},!session.busy,outlined=true)
                        }
                        Text(session.realStatus,color=AuditColors.Muted,style=MaterialTheme.typography.bodySmall)
                    }
                    AuditPanel {
                        Text("Estado que comparte este teléfono",style=MaterialTheme.typography.titleMedium)
                        val reading=session.lastDeviceStatus
                        BatteryMeter((reading?.metadata?.get("batteryPercent") as? Number)?.toInt())
                        Text(networkLabel(reading?.metadata?.get("networkState")))
                        Text("Última lectura: ${displayLocalTime(reading?.timestamp)}",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
                        Text("Último evento enviado: ${displayLocalTime(lastSent?.event?.timestamp)}",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
                        Text("Pendientes de envío: ${session.stats.pending}",style=MaterialTheme.typography.bodyMedium)
                        if(session.stats.failed>0) Text("${session.stats.failed} eventos rechazados; revisa la conexión y la inscripción.",color=AuditColors.Red)
                        AuditButton(if(session.busy) "Solicitando…" else "Enviar estado ahora",{session.refreshDeviceStatus(scope)},
                            (session.realCaptureRunning || BasicMonitoringState.running) && session.visibleAvailable && !session.busy)
                        if(!session.realCaptureRunning && !BasicMonitoringState.running) Text("Activa batería y conexión o la captura de arriba para actualizar el estado.",color=AuditColors.Warning,style=MaterialTheme.typography.bodySmall)
                        Text("En el Centro, usa Actualizar ahora o deja activada la actualización automática.",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
                    }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("Actividad reciente",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        TextButton({history=true},shape=androidx.compose.ui.graphics.RectangleShape) {Text("Ver todos")}
                    }
                    if(communicationEvents.isEmpty()) AuditEmpty("SIN EVENTOS TODAVÍA","Activa la captura para empezar. Aquí verás qué se registró y si se envió.")
                    communicationEvents.take(4).forEach {row->LocalEventRow(row) {selectedEvent=row}}
                    AuditPanel {
                        Text("2. Notificaciones de WhatsApp",style=MaterialTheme.typography.titleMedium)
                        Text("Comparte vistas previas y registra eventos de notificaciones nuevas. Las respuestas propias se detectan solo si Android las muestra en una notificación. No recupera historial ni todos los envíos dentro del chat.")
                        Text(WhatsAppSharingState.message,color=AuditColors.Muted)
                        if(WhatsAppSharingState.active || WhatsAppSharingState.notificationsObserved>0) {
                            Text("Notificaciones observadas: ${WhatsAppSharingState.notificationsObserved} · Eventos reconocidos: ${WhatsAppSharingState.observationsAccepted}",style=MaterialTheme.typography.bodySmall)
                            Text("Textos enviados al servidor: ${WhatsAppSharingState.previewsUploaded}",style=MaterialTheme.typography.bodySmall)
                            WhatsAppSharingState.lastHeartbeat?.let {Text("Conexión confirmada: ${displayFriendlyTime(it)}",style=MaterialTheme.typography.bodySmall)}
                            if(WhatsAppSharingState.active && WhatsAppSharingState.notificationsObserved==0) Text("Android aún no entregó una notificación nueva de WhatsApp. Los mensajes dentro de un chat abierto pueden no generar notificación.",style=MaterialTheme.typography.bodySmall)
                            if(WhatsAppSharingState.parserFailures>0) Text("No se pudo interpretar ${WhatsAppSharingState.parserFailures} notificación(es).",color=AuditColors.Red)
                        }
                        if(WhatsAppSharingState.active) AuditButton("Detener vistas previas de WhatsApp",session::stopWhatsAppSharing,outlined=true)
                        else onShareWhatsApp?.let {AuditButton("Configurar vistas previas de WhatsApp",it,session.visibleAvailable && !session.busy)}
                    }
                    AuditPanel {
                        Text("3. Compartir mi pantalla",style=MaterialTheme.typography.titleMedium)
                        Text("El Centro podrá ver lo que muestras después de que aceptes el diálogo de Android. Sin audio ni grabación.")
                        Text(ScreenSharingState.message,color=AuditColors.Muted)
                        if(ScreenSharingState.active) AuditButton("Detener pantalla compartida",session::stopScreenSharing,outlined=true)
                        else onShareScreen?.let {AuditButton("Compartir pantalla en vivo",it,session.visibleAvailable && !session.busy)}
                    }
                    AuditRule()
                    var showTools by rememberSaveable {mutableStateOf(false)}
                    BasicMonitoringControls()
                    AuditLink(if(showTools) "Ocultar herramientas de conexión" else "Herramientas de conexión",{showTools=!showTools})
                    if(showTools) {
                        Text("Servidor: ${session.serverUrl}",style=MaterialTheme.typography.bodySmall)
                        AuditButton(if(session.busy) "Enviando…" else "Generar evento simulado",{session.sendSimulatedEvent(scope)},
                            !session.busy && session.visibleAvailable && !session.revoking,outlined=true)
                        Text("El evento simulado comprueba la conexión y se identifica como prueba en el Centro.",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
                    }
                    AuditLink("Ver política de datos",{policy=true})
                    AuditButton(if(session.revoking) "Retirando consentimiento…" else "Retirar consentimiento",{confirmWithdrawal=true},!session.revoking,outlined=true)
                    Text("Retirar el consentimiento detiene todas las funciones y elimina los eventos locales pendientes.",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)

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
        TextButton(onClick, modifier = Modifier.fillMaxWidth(),shape=androidx.compose.ui.graphics.RectangleShape, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(metadataTitle(row.event.metadata), style = MaterialTheme.typography.bodyMedium)
                    Text("${displayFriendlyTime(row.event.timestamp)}",
                        color = AuditColors.Muted, style = MaterialTheme.typography.bodySmall)
                    (row.event.metadata["smsText"] as? String)?.let { Text(it,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
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
