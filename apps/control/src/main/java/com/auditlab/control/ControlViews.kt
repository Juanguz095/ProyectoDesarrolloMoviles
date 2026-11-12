package com.auditlab.control

import android.app.DatePickerDialog
import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.auditlab.common.*
import com.auditlab.design.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
internal fun RefreshControls(session: ControlSession, screen: String, deviceId: String?, scope: CoroutineScope, showSummary: Boolean = true) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton({session.refreshVisible(screen,deviceId,scope)},shape=RectangleShape,enabled=!session.busy && !session.refreshing,
            modifier=Modifier.weight(1f).heightIn(min=48.dp)) {
            Text(if(session.refreshing) "Actualizando…" else "Actualizar ahora")
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Text("Automático",style=MaterialTheme.typography.labelMedium)
            Switch(session.autoRefresh,session::updateAutoRefresh)
        }
    }
    if(showSummary) {
    val stamp=when(screen) { "eventos"->session.eventsUpdatedAt;"dispositivos"->session.devicesUpdatedAt;else->session.dataUpdatedAt }
    val mode=if(session.autoRefresh && session.canAutoRefresh(screen)) "Automático cada 10 s"
        else if(session.autoRefresh) "Automático pausado en esta página"
        else "Actualización manual"
    Text(mode+(stamp?.let {" · Consulta ${displayLocalClock(it)}"} ?: ""),style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
    }
    session.refreshError?.let { Text(it,color=AuditColors.Red,style=MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun DeviceReadingCard(reading: AuditEvent?, now: Instant, showReadingTime: Boolean = true) {
    AuditPanel {
        Text("Batería y conexión",style=MaterialTheme.typography.titleMedium)
        BatteryMeter((reading?.metadata?.get("batteryPercent") as? Number)?.toInt())
        Text(networkLabel(reading?.metadata?.get("networkState")),style=MaterialTheme.typography.bodyLarge)
        if(reading==null) {
            Text("Sin lecturas. Pulsa Activar en el Agente.",color=AuditColors.Muted)
        } else {
            if(showReadingTime) Text("Lectura del teléfono: ${displayLocalTime(reading.timestamp)} · ${ageLabel(reading.timestamp,now)}",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
            val stale=runCatching { Duration.between(Instant.parse(reading.timestamp),now).seconds>120 }.getOrDefault(true)
            if(stale) Text("Lectura antigua. Revisa el Agente y su conexión.",color=AuditColors.Warning,style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun Dashboard(session: ControlSession, device: DeviceSummary?, scope: CoroutineScope,
    onEvents: () -> Unit, onDevice: () -> Unit = {}, onChoose: (String) -> Unit = {}) {
    val reading=session.overviewReading?.takeIf { it.sourceDevice==device?.deviceId && session.overviewDevice==device.deviceId }
    val recent=session.overviewEvents.filter { it.sourceDevice==device?.deviceId && session.overviewDevice==device.deviceId }
    val now by produceState(Instant.now()) { while(true) { delay(10_000);value=Instant.now() } }
    AuditHeader("AUDITLAB")
    Text("Tu dispositivo de un vistazo",style=MaterialTheme.typography.titleLarge)
    if(session.visibleDevices.size>1) AuditChoice("Dispositivo seleccionado",device?.let { session.deviceName(it.deviceId) } ?: "Selecciona un equipo",
        session.visibleDevices.map { it.deviceId to session.deviceName(it.deviceId) },!session.busy,onChoose)
    RefreshControls(session,"inicio",device?.deviceId,scope,showSummary=false)
    if(device==null) {
        AuditEmpty("SIN DISPOSITIVOS","Vincula un teléfono desde Más → Crear código de inscripción.")
        return
    }
    Column {
        Text(session.deviceName(device.deviceId),style=MaterialTheme.typography.titleMedium)
        Text(if(device.isActive) "Inscripción activa" else "Dispositivo revocado",color=if(device.isActive) AuditColors.Green else AuditColors.Red)
    }
    Text("Último contacto: ${device.lastSeenAt?.let { ageLabel(it,now) } ?: "Aún sin contacto"}",color=AuditColors.Muted,style=MaterialTheme.typography.bodySmall)
    DeviceReadingCard(reading,now,showReadingTime=false)
    Text("Ubicación aproximada",style=MaterialTheme.typography.titleMedium)
    LaunchedEffect(device.deviceId) { session.loadLocations(device.deviceId, scope) }
    val points = session.locationEvents.filter { it.sourceDevice==device.deviceId }.mapNotNull { event ->
        val lat = (event.metadata["locationLat"] as? Number)?.toDouble()
        val lon = (event.metadata["locationLon"] as? Number)?.toDouble()
        if (lat != null && lon != null) Triple(lat, lon, event.timestamp) else null
    }
    if (points.isEmpty()) {
        AuditPanel { Text("Aún no hay lecturas de ubicación. Activa «Ubicación aproximada» en el Agente; cada lectura aparecerá aquí con su fecha.",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted) }
    } else {
        DeviceLocationMap(points)
        Text("Cada punto conserva su fecha; no es la ubicación actual.",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
    }
    val viewerContext = androidx.compose.ui.platform.LocalContext.current
    if(device.isActive) AuditButton("Ver pantalla compartida", {
        viewerContext.startActivity(android.content.Intent(viewerContext, ScreenViewerActivity::class.java)
            .putExtra("device", device.deviceId).putExtra("deviceLabel", session.deviceName(device.deviceId)))
    }, !session.busy, outlined = true)
    Text("Actividad reciente",style=MaterialTheme.typography.titleMedium)
    if(recent.isEmpty()) AuditEmpty("SIN EVENTOS TODAVÍA","Activa la captura en el Agente. Los eventos aparecerán aquí cuando lleguen al servidor.")
    recent.take(5).forEach { EventRow(it,!session.busy) {session.showDetail(it,scope)} }
    AuditButton("Ver historial de eventos",onEvents,!session.busy,outlined=true)

}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun EventExplorer(session: ControlSession, scope: CoroutineScope, onExport: (() -> Unit)?, onOpenReport: (() -> Unit)?, advancedFilters: Boolean = false) {
    var device by rememberSaveable(session.query) { mutableStateOf(session.query.device.orEmpty()) }
    var category by rememberSaveable(session.query) { mutableStateOf(session.query.category.orEmpty()) }
    var start by rememberSaveable(session.query) { mutableStateOf(session.query.from.orEmpty()) }
    var end by rememberSaveable(session.query) { mutableStateOf(session.query.to.orEmpty()) }
    var search by rememberSaveable(session.query) { mutableStateOf(session.query.q.orEmpty()) }
    var filterError by remember { mutableStateOf<String?>(null) }
    var manualDevice by rememberSaveable { mutableStateOf(false) }
    var preciseDates by rememberSaveable { mutableStateOf(false) }
    val showFilters=advancedFilters
    AuditHeader(if(showFilters) "FILTROS AVANZADOS" else "EVENTOS")
    RefreshControls(session,"eventos",session.query.device,scope)
    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        listOf("communication" to "Todo","whatsapp" to "WhatsApp","sms" to "SMS","call" to "Llamadas","manual_share" to "Compartidos").forEach { (value,label) ->
            AuditCategoryFilter(value,label,session.query.category==value,!session.busy) {
                session.applyFilters(session.query.copy(category=value),scope)
            }
        }
    }
    if(showFilters) {
    AuditPanel {
    AuditChoice("Dispositivo", if (manualDevice) "Escribir identificador" else device.takeIf {it.isNotEmpty()}?.let(session::deviceName) ?: "Todos los dispositivos",
        listOf("" to "Todos los dispositivos") + session.devices.map { it.deviceId to session.deviceName(it.deviceId) } + listOf("manual" to "Escribir identificador"),
        !session.busy) { value -> if (value == "manual") manualDevice = true else { device = value; manualDevice = false } }
    if (manualDevice) AuditField("Identificador del dispositivo", device, { device = it }, !session.busy, placeholder = "UUID completo")
    AuditChoice("Categoría", if (category.isEmpty()) "Todas las categorías" else categoryName(category),
        listOf("communication" to "Mensajes y llamadas","whatsapp" to "WhatsApp","sms" to "SMS","call" to "Llamadas","manual_share" to "Compartidos"), !session.busy) { category = it }
    SectionLabel("Rango UTC")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DateChoice("Desde", start, false, !session.busy, { start = it }, Modifier.weight(1f))
        DateChoice("Hasta", end, true, !session.busy, { end = it }, Modifier.weight(1f))
    }
    AuditField("ID o código exacto", search, { search = it }, !session.busy, placeholder = "Todos")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      AuditButton("Aplicar filtros", {
        try {
            val query = EventQuery(device.trim().ifEmpty { null }, category.ifEmpty { null },
                start.trim().ifEmpty { null }, end.trim().ifEmpty { null }, search.trim().ifEmpty { null })
            filterError = null; session.applyFilters(query, scope)
        } catch (_: Exception) { filterError = "Revisa las fechas UTC, su orden, la categoría y el ID o código exacto." }
      }, !session.busy, modifier = Modifier.weight(1f))
      onExport?.let { AuditButton("Exportar CSV", it, !session.busy && session.eventsLoaded, outlined = true, modifier = Modifier.weight(1f)) }
    }
    filterError?.let { Text(it, color = AuditColors.Red) }
    }
    }
    if(!showFilters && onExport!=null) AuditButton("Exportar CSV",onExport,!session.busy && session.eventsLoaded,outlined=true)
    AuditRule()
    SectionLabel("Resultados")
    var showLive by rememberSaveable {mutableStateOf(false)}
    if(session.query.category in setOf(null,"communication","whatsapp")) {
        if(session.query.category!="whatsapp") AuditLink(if(showLive) "Ocultar WhatsApp en vivo" else "Ver WhatsApp en vivo",{showLive=!showLive})
        if(session.query.category=="whatsapp" || showLive) {
        SectionLabel("WhatsApp · En vivo")
        if(session.query.from!=null || session.query.to!=null || session.query.q!=null) {
            Text("Quita fechas e identificador para ver las vistas previas en vivo.",color=AuditColors.Muted)
        } else {
            val target=session.query.device ?: session.visibleDevices.singleOrNull {it.isActive}?.deviceId
            AuditChoice("Teléfono para WhatsApp", target?.let(session::deviceName) ?: "Selecciona un teléfono",
                session.devices.filter { it.isActive }.map { it.deviceId to session.deviceName(it.deviceId) },!session.busy) {
                session.applyFilters(session.query.copy(device=it),scope)
            }
            if(target==null) Text("Selecciona un teléfono.")
            else androidx.compose.runtime.key(target) { WhatsAppMessagesInline(session,target) }
            AuditLink("Actualizar teléfonos",{session.refresh(scope)},!session.busy)
        }
        AuditRule()
    }
    }
    SectionLabel("Historial · ${session.events.size} eventos")
    if (session.events.isEmpty()) AuditEmpty(message = if(session.query.category=="whatsapp")
        "Sin actividad. Activa WhatsApp en el Agente."
        else if(session.query.category in setOf("sms","call")) "Activa SMS y llamadas en el Agente. Las llamadas aparecen al finalizar."
        else if (session.eventsLoaded) "No se recibieron eventos para los filtros aplicados." else "Aplica filtros o actualiza la consulta para recibir eventos.")
    var previousDay: String?=null
    session.events.forEach { event ->
        val day=displayLocalDay(event.timestamp)
        if(day!=previousDay) { SectionLabel(day);previousDay=day }
        EventRow(event,!session.busy,session.deviceName(event.sourceDevice)) {session.showDetail(event,scope)}
    }
    Text("Página ${session.eventPageNumber}",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
    AuditRule()
    if(showFilters) {
    SectionLabel("Opciones adicionales")
    AuditLink(if (preciseDates) "Ocultar edición de horas" else "Editar fechas y horas exactas", { preciseDates = !preciseDates }, !session.busy)
    if (preciseDates) {
        AuditField("Desde (incluida)", start, { start = it }, !session.busy, placeholder = "Sin límite")
        AuditField("Hasta (excluida)", end, { end = it }, !session.busy, placeholder = "Sin límite")
        Text("Formato UTC: 2026-10-28T00:00:00Z. Deja vacío para no limitar.", color = AuditColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
    AuditLink("Limpiar filtros", {
        filterError = null; manualDevice = false; device = ""; category = "communication"; start = ""; end = ""; search = ""
        session.applyFilters(EventQuery(category="communication"), scope)
    }, !session.busy)
    if (device.trim().ifEmpty { null } != session.query.device || category.ifEmpty { null } != session.query.category ||
        start.trim().ifEmpty { null } != session.query.from || end.trim().ifEmpty { null } != session.query.to || search.trim().ifEmpty { null } != session.query.q) {
        Text("Hay cambios sin aplicar. La lista y el CSV usan los últimos filtros aplicados.", color = AuditColors.Warning)
    }
    }
    if(showFilters) Text("CSV: filtros aplicados, hasta 10 000 eventos. Las consultas quedan auditadas.",
        color = AuditColors.Muted, style = MaterialTheme.typography.bodySmall)
    if (session.eventCursor != null) AuditButton("Siguiente página", { session.moreEvents(scope) }, !session.busy)
    AuditLink("Volver a la primera página", { session.applyFilters(session.query, scope) }, !session.busy)
    if (session.lastReportUri != null && onOpenReport != null) AuditButton("Abrir último CSV guardado", onOpenReport, !session.busy, outlined = true)
}

@Composable
internal fun DeviceList(session: ControlSession, scope: CoroutineScope, onDetail: (String) -> Unit) {
    AuditHeader("EQUIPOS")
    RefreshControls(session,"dispositivos",null,scope)
    AuditRule()
    AuditChoice("Estado de inscripción", when (session.status) { "active" -> "Activos"; "revoked" -> "Revocados"; else -> "Todos" },
        listOf("" to "Todos", "active" to "Activos", "revoked" to "Revocados"), !session.busy) {
        session.filterDevices(it.ifEmpty { null }, scope)
    }
    Text("Actualizado: ${displayTime(session.devicesUpdatedAt)}", color = AuditColors.Muted, style = MaterialTheme.typography.bodySmall)
    if (session.devices.isEmpty()) AuditEmpty("SIN DISPOSITIVOS", if (session.devicesLoaded) "No hay dispositivos en esta página para el filtro actual." else "Actualiza la lista para consultar tus dispositivos.")
    var showArchived by rememberSaveable { mutableStateOf(false) }
    AuditChoice("Mostrar",if(showArchived) "Archivados" else "Sin archivar",
        listOf("visible" to "Sin archivar","archived" to "Archivados"),true) { showArchived=it=="archived" }
    Text("Archivar oculta el equipo de Inicio; conserva eventos y no detiene envíos.",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
    val displayed=session.devices.filter { session.isArchived(it.deviceId)==showArchived }
    if(displayed.isEmpty() && session.devices.isNotEmpty()) Text("Sin equipos en esta página; cambia de página si hay más resultados.")
    val now by produceState(Instant.now()) { while(true) { delay(10_000);value=Instant.now() } }
    displayed.forEach { device ->
        AuditPanel {
            Text(session.deviceName(device.deviceId), style = MaterialTheme.typography.titleMedium)
            Text(deviceContactStatus(device,now), style = MaterialTheme.typography.titleSmall)
            Text("Inscrito: ${displayFriendlyTime(device.enrolledAt)}",style=MaterialTheme.typography.bodySmall)
            if(session.isArchived(device.deviceId)) Text("Archivado en este Centro",color=AuditColors.Muted)
            Text("Último contacto: ${device.lastSeenAt?.let(::displayFriendlyTime) ?: "Sin contacto"}", color = AuditColors.Muted)
            AuditButton("Ver dispositivo", { onDetail(device.deviceId) }, !session.busy, outlined = true)
        }
    }
    Text("${session.devices.size} dispositivos en esta página. El estado activo indica inscripción, no conexión en vivo.", color = AuditColors.Muted)
    if (session.deviceCursor != null) AuditButton("Siguiente página de dispositivos", { session.moreDevices(scope) }, !session.busy)
}

@Composable
internal fun DeviceDetail(session: ControlSession, device: DeviceSummary?, scope: CoroutineScope,
    onBack: () -> Unit, onEvents: () -> Unit, onAudit: () -> Unit, onWhatsApp: () -> Unit = {},
    onGallery: () -> Unit = {}, onMap: () -> Unit = {}) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val composeScope = rememberCoroutineScope()
    AuditHeader("DISPOSITIVO")
    AuditLink("← Volver a dispositivos", onBack, !session.busy)
    RefreshControls(session,"dispositivo",device?.deviceId,scope)
    if (device == null) { AuditEmpty("DISPOSITIVO NO DISPONIBLE", "Actualiza la lista de dispositivos para consultar su detalle."); return }
    AuditRule()
    Text(session.deviceName(device.deviceId),style=MaterialTheme.typography.titleLarge)
    DevicePresentationActions(session,device,onBack)
    SectionLabel("Inscripción")
    Text("Inscrito: ${displayFriendlyTime(device.enrolledAt)}")
    Text(deviceContactStatus(device,Instant.now()))
    Text(enrollmentStatus(device), style = MaterialTheme.typography.titleLarge)
    Text("Último contacto: ${device.lastSeenAt?.let(::displayFriendlyTime) ?: "Sin contacto"}", color = AuditColors.Muted)
    AuditRule()
    var technical by remember {mutableStateOf(false)}
    AuditLink(if(technical) "Ocultar información técnica" else "Información técnica",{technical=!technical})
    if(technical) {
    SectionLabel("Identificador")
    Text(device.deviceId, style = MaterialTheme.typography.titleSmall)
    AuditLink("Copiar identificador", { composeScope.launch {
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Dispositivo AuditLab", device.deviceId)))
    } })
    Text("Versión de la app: ${device.appVersion ?: "Sin datos"}")
    Text("Inscrito: ${displayTime(device.enrolledAt)}")
    device.revokedAt?.let { Text("Revocado: ${displayTime(it)}") }
    }
    AuditDisclosure("Consentimiento") { AuditPanel {
        SectionLabel("Consentimiento registrado")
        Text(if (device.isActive) "INSCRIPCIÓN ACTIVA" else "INSCRIPCIÓN REVOCADA", style = MaterialTheme.typography.titleMedium)
        Text("Aceptado: ${displayFriendlyTime(device.consent.acceptedAt)}")
        if(technical) Text("Política: ${device.consent.policyVersion}")
        Text("El usuario lo retira desde el Agente; el servidor no confirma si su indicador está visible ahora.", color = AuditColors.Muted)
    }
    }
    val now by produceState(Instant.now()) { while(true) { delay(10_000);value=Instant.now() } }
    DeviceReadingCard(session.overviewReading?.takeIf { it.sourceDevice==device.deviceId },now)
    SectionLabel("Consultar y compartir")
    Text("WhatsApp o pantalla solo con sesión iniciada por el propietario en el Agente.",color=AuditColors.Muted)
    if(device.isActive) {
        WhatsAppAlertControls(device.deviceId,session.deviceName(device.deviceId))
        AuditButton("Ver notificaciones de WhatsApp",onWhatsApp,!session.busy)
    }
    if (device.isActive) AuditButton("Ver pantalla compartida", {
        context.startActivity(android.content.Intent(context, ScreenViewerActivity::class.java).putExtra("device", device.deviceId).putExtra("deviceLabel",session.deviceName(device.deviceId)))
    }, !session.busy)
    else { AuditButton("Ver pantalla compartida", {}, enabled = false); Text("Disponible cuando el propietario activa «Compartir pantalla» en el Agente.", style = MaterialTheme.typography.bodySmall, color = AuditColors.Muted) }
    AuditButton("Ver eventos", onEvents, !session.busy)
    AuditButton("Ver fotos compartidas", onGallery, !session.busy)
    AuditButton("Ver ubicación en el mapa", onMap, !session.busy)
    AuditButton("Ver auditoría del grupo", onAudit, !session.busy, outlined = true)
    if (session.isAdmin && device.isActive) AuditButton("Revocar dispositivo", { session.requestRevoke(device.deviceId) }, !session.busy)
    Text("La revocación bloquea nuevos envíos; sin conexión, el Agente la detecta al volver a comunicarse.", color = AuditColors.Muted)
}

@Composable
internal fun EnrollmentView(session: ControlSession, scope: CoroutineScope, onBack: () -> Unit) {
    AuditHeader("INSCRIPCIÓN", "VINCULA UN DISPOSITIVO AUTORIZADO")
    AuditLink("← Volver", onBack, !session.busy)
    if (!session.isAdmin) { AuditNotice("Solo un administrador puede crear códigos de inscripción."); return }
    AuditPanel {
        Text("UN CÓDIGO · UN DISPOSITIVO", style = MaterialTheme.typography.titleMedium)
        Text("Introdúcelo en el Agente tras aceptar el consentimiento. Vence en 10 minutos y tiene un solo uso.")
    }
    AuditButton("Crear código de un uso", { session.createEnrollmentCode(scope) }, !session.busy)
    session.enrollmentCode?.let { code ->
        AuditPanel {
            SectionLabel("Código visible solo durante esta sesión")
            androidx.compose.foundation.text.selection.SelectionContainer { Text(code.code, style = MaterialTheme.typography.titleMedium) }
            Text("Vence: ${displayTime(code.expiresAt)}")
            Text("No lo publiques ni lo compartas con personas ajenas al dispositivo autorizado.", color = AuditColors.Muted)
        }
    }
}

@Composable
internal fun AuditHistory(session: ControlSession, scope: CoroutineScope, onBack: () -> Unit) {
    AuditHeader("AUDITORÍA", "QUIÉN CONSULTÓ O MODIFICÓ QUÉ")
    AuditLink("← Volver", onBack, !session.busy)
    AuditButton("Actualizar auditoría", { session.loadAudit(scope) }, !session.busy)
    if (session.audit.isEmpty()) AuditEmpty("SIN REGISTROS RECIBIDOS", if (session.auditLoaded)
        "No hay registros en esta página." else "Actualiza para consultar la trazabilidad del grupo.")
    session.audit.forEach { entry ->
        AuditPanel {
            Text(auditActionName(entry.action), style = MaterialTheme.typography.titleMedium)
            Text(displayFriendlyTime(entry.occurredAt), color = AuditColors.Muted)
            Text("Resultado: ${when(entry.result) { "success"->"Completado"; "failure"->"Fallido";else->entry.result }}")
            var technical by remember(entry.correlationId) {mutableStateOf(false)}
            AuditLink(if(technical) "Ocultar información técnica" else "Información técnica",{technical=!technical})
            if(technical) {
            Text("Acción: ${entry.action}")
            Text("Actor: ${entry.actor}")
            Text("Recurso: ${entry.resource}")
            Text("Resultado: ${entry.result}")
            Text("Filtros: ${entry.filters}", style = MaterialTheme.typography.bodySmall)
            Text("Correlación: ${entry.correlationId}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (session.auditCursor != null) AuditButton("Siguiente página de auditoría", { session.loadAudit(scope, true) }, !session.busy)
}

@Composable
internal fun AuditChoice(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(label)
        Box {
            Row(Modifier.fillMaxWidth().background(AuditColors.Panel).border(0.7.dp, AuditColors.Muted)
                .clickable(enabled = enabled) { expanded = true }.heightIn(min = 50.dp).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("⌄", style = MaterialTheme.typography.bodyMedium)
            }
            DropdownMenu(expanded && enabled, { expanded = false }, modifier = Modifier.heightIn(max = 340.dp),
                shape = RectangleShape, containerColor = AuditColors.Panel) {
                options.forEach { (key, title) -> DropdownMenuItem(text = { Text(title, style = MaterialTheme.typography.bodyMedium) },
                    onClick = { expanded = false; onSelect(key) }) }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun EventRow(event: AuditEvent, enabled: Boolean, deviceName: String?=null, onClick: () -> Unit) {
    AuditPanel(Modifier.clickable(enabled=enabled,onClick=onClick).semantics {contentDescription="Ver detalle →"},accent=AuditCategoryColors.category(event.category).ink) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            AuditCategoryTag(event.category)
            if(event.isSimulated) AuditStateTag("Simulado",attention=true)
        }
        Text(eventTitle(event),style=MaterialTheme.typography.titleMedium,color=AuditColors.Ink)
        if(event.category in setOf("sms","call") && event.metadata["phoneNumber"]==null) Text("Número no compartido o no disponible",color=AuditColors.Muted)
        (event.metadata["phoneNumber"] as? String)?.let {
            val label=when(event.direction) { "inbound"->"De"; "outbound"->"Para";else->"Número" }
            Text("$label: $it",style=MaterialTheme.typography.bodyLarge,color=AuditColors.Ink)
        }
        if(event.category=="manual_share") {
            AuditEventPreview(event.metadata["sharedText"] as? String ?: "Texto no disponible",event.category)
        }
        if(event.category=="sms") {
            val body=event.metadata["smsText"] as? String
            if(body==null) Text("Texto no compartido o no disponible",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
            else AuditEventPreview(body,event.category)
            if(event.metadata["smsTextTruncated"]==true) Text("Texto limitado a 2 000 caracteres",style=MaterialTheme.typography.bodySmall)
        }
        if(event.category=="whatsapp") Text(if(event.direction=="outbound")
            "Respuesta observada en una notificación · Sin texto disponible"
            else "Notificación registrada · Texto temporal en la vista en vivo",
            style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
        deviceName?.let { Text(it,color=AuditColors.Muted,style=MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(displayFriendlyTime(event.timestamp),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
            Text("Ver detalle →",style=MaterialTheme.typography.labelLarge)
        }
    }
}

internal fun shortDeviceId(id: String)=if(id.length<=12) id else "${id.take(8)}…${id.takeLast(4)}"
internal fun deviceLabel(device: DeviceSummary)="Equipo ${shortDeviceId(device.deviceId)}"

private fun enrollmentStatus(device: DeviceSummary): String = if (device.isActive) "Activa" else "Revocada"

private fun ageLabel(timestamp: String, now: Instant): String = runCatching {
    val seconds = Duration.between(Instant.parse(timestamp), now).seconds
    when {
        seconds < 0 -> "Fecha posterior al reloj actual"
        seconds < 60 -> "Hace menos de 1 min"
        seconds < 3600 -> "Hace ${seconds / 60} min"
        seconds < 86400 -> "Hace ${seconds / 3600} h"
        else -> "Hace ${seconds / 86400} días"
    }
}.getOrDefault("Fecha no disponible")

@Composable
private fun DateChoice(label: String, value: String, exclusiveEnd: Boolean, enabled: Boolean,
    onSelect: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val date = runCatching {
        val instant = Instant.parse(value)
        (if (exclusiveEnd) instant.minusNanos(1) else instant).atOffset(ZoneOffset.UTC).toLocalDate()
    }.getOrNull()
    val display = date?.format(DateTimeFormatter.ofPattern("dd/MM/yy")) ?: "sin límite"
    OutlinedButton(onClick = {
        val initial = date ?: LocalDate.now(ZoneOffset.UTC)
        DatePickerDialog(context, { _, year, month, day ->
            val selected = LocalDate.of(year, month + 1, day)
            val boundary = if (exclusiveEnd) selected.plusDays(1) else selected
            onSelect(boundary.atStartOfDay().toInstant(ZoneOffset.UTC).toString())
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }, enabled = enabled, shape = RectangleShape, border = androidx.compose.foundation.BorderStroke(0.7.dp, AuditColors.Muted),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = AuditColors.Panel),
        modifier = modifier.heightIn(min = 50.dp), contentPadding = PaddingValues(8.dp)) {
        Text("$label: $display", style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun deviceContactStatus(device: DeviceSummary,now: Instant): String {
    if(!device.isActive) return "Revocado"
    val last=device.lastSeenAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: return "Sin contacto registrado"
    val age=java.time.Duration.between(last,now).seconds
    return if(age in 0..120) "Contacto reciente · Inscripción activa" else "Sin contacto reciente · Inscripción activa"
}

@Composable
private fun DevicePresentationActions(session: ControlSession,device: DeviceSummary,onArchived: () -> Unit) {
    var editing by remember(device.deviceId) { mutableStateOf(false) }
    var label by remember(device.deviceId) { mutableStateOf("") }
    var error by remember(device.deviceId) { mutableStateOf<String?>(null) }
    var confirming by remember(device.deviceId) { mutableStateOf(false) }
    val archived=session.isArchived(device.deviceId)
    AuditLink("Cambiar nombre",{label=session.deviceName(device.deviceId);editing=true},!session.busy)
    Text("Nombre y archivo locales a este Centro de control.",style=MaterialTheme.typography.bodySmall,color=AuditColors.Muted)
    if(archived) Text("ARCHIVADO · Sus eventos siguen disponibles")
    AuditButton(if(archived) "Restaurar dispositivo" else "Archivar dispositivo",{confirming=true},!session.busy,outlined=true)
    if(editing) AlertDialog(onDismissRequest={editing=false;error=null},title={Text("Nombre del dispositivo")},text={
        Column { AuditField("Nombre (1 a 60 caracteres)",label,{label=it;error=null},true)
            error?.let {Text(it,color=AuditColors.Red)} }
    },confirmButton={AuditLink("Guardar",{if(session.renameDevice(device.deviceId,label)) {editing=false;error=null} else error="Usa entre 1 y 60 caracteres sin saltos de línea. Comprueba el almacenamiento local."})},dismissButton={AuditLink("Cancelar",{editing=false;error=null})})
    if(confirming) AlertDialog(onDismissRequest={confirming=false},title={Text(if(archived) "Restaurar dispositivo" else "Archivar dispositivo")},text={Text(if(archived)
        "Volverá a aparecer en Inicio y en la lista habitual de este Centro."
        else "Se oculta de Inicio y la lista habitual; conserva su historial y sigue enviando eventos. Para bloquear envíos, usa Revocar.")},
        confirmButton={AuditLink("Confirmar",{if(session.archiveDevice(device.deviceId,!archived)) {confirming=false;onArchived()}})},dismissButton={AuditLink("Cancelar",{confirming=false})})
}
