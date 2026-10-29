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
internal fun Dashboard(session: ControlSession, device: DeviceSummary?, scope: CoroutineScope,
    onEvents: () -> Unit) {
    // Never show a previous device's snapshot while the next request is being scheduled.
    val reading = session.overviewReading?.takeIf { it.sourceDevice == device?.deviceId && session.overviewDevice == device?.deviceId }
    val recent = session.overviewEvents.filter { it.sourceDevice == device?.deviceId && session.overviewDevice == device?.deviceId }
    val now by produceState(Instant.now()) { while (true) { delay(60_000); value = Instant.now() } }
    AuditHeader("AUDITLAB", "CENTRO DE CONTROL / ${if (session.isAdmin) "ADMIN" else "AUDITOR"}")
    Spacer(Modifier.height(2.dp))
    SectionLabel("Vista de dispositivos")
    AuditPanel {
        SectionLabel("Dispositivo")
        Text(device?.let { "${it.deviceId.take(8)}…${it.deviceId.takeLast(4)}" } ?: "Sin datos recibidos", style = MaterialTheme.typography.titleLarge)
        Text(device?.deviceId ?: if (session.devicesLoaded) "No hay dispositivos en esta página." else "Todavía no se consultó la lista.",
            color = AuditColors.Muted, style = MaterialTheme.typography.bodySmall)
        AuditRule()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Inscripción")
                Text(device?.let { enrollmentStatus(it) } ?: "Sin datos", style = MaterialTheme.typography.titleSmall)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Último contacto")
                Text(device?.lastSeenAt?.let { ageLabel(it, now) } ?: "Sin datos", style = MaterialTheme.typography.bodySmall)
            }
        }
        AuditRule()
        SectionLabel("Batería")
        BatteryMeter((reading?.metadata?.get("batteryPercent") as? Number)?.toInt())
        SectionLabel("Conexión")
        Text(networkLabel(reading?.metadata?.get("networkState")), style = MaterialTheme.typography.titleSmall)
    }
    Spacer(Modifier.height(2.dp))
    SectionLabel("Eventos recientes")
    AuditPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ÚLTIMOS EVENTOS RECIBIDOS", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onEvents, enabled = !session.busy, shape = RectangleShape,
                colors = ButtonDefaults.textButtonColors(containerColor = AuditColors.Muted, contentColor = AuditColors.Panel),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
                Text("VER HISTORIAL\nDE EVENTOS", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (recent.isEmpty()) {
            if (!session.overviewLoaded && device != null) AuditEmpty("SIN DATOS RECIBIDOS", "Consulta los eventos del servidor para este dispositivo.")
            else AuditEmpty()
        } else recent.take(3).forEach { event -> CompactEventRow(event, !session.busy) { session.showDetail(event, scope) } }
        AuditRule()
        Text("Batería · Conexión · SMS · Llamadas", color = AuditColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(2.dp))
    SectionLabel("Indicadores")
    AuditPanel {
        SectionLabel("Última lectura real recibida")
        Text(displayTime(reading?.timestamp), style = MaterialTheme.typography.titleMedium)
        Text(reading?.let { "${ageLabel(it.timestamp, now)}. Es el último estado disponible en la consulta, no una lectura en vivo." }
            ?: "No se puede calcular la antigüedad sin una lectura real.", color = AuditColors.Muted)
        AuditRule()
        SectionLabel("Estado de inscripción")
        Text(device?.let(::enrollmentStatus) ?: "Sin datos recibidos", style = MaterialTheme.typography.titleSmall)
        Text("La inscripción activa no confirma que el teléfono esté conectado.", color = AuditColors.Muted)
    }
}

@Composable
internal fun EventExplorer(session: ControlSession, scope: CoroutineScope, onExport: (() -> Unit)?, onOpenReport: (() -> Unit)?) {
    var device by rememberSaveable(session.query) { mutableStateOf(session.query.device.orEmpty()) }
    var category by rememberSaveable(session.query) { mutableStateOf(session.query.category.orEmpty()) }
    var start by rememberSaveable(session.query) { mutableStateOf(session.query.from.orEmpty()) }
    var end by rememberSaveable(session.query) { mutableStateOf(session.query.to.orEmpty()) }
    var search by rememberSaveable(session.query) { mutableStateOf(session.query.q.orEmpty()) }
    var filterError by remember { mutableStateOf<String?>(null) }
    var manualDevice by rememberSaveable { mutableStateOf(false) }
    var preciseDates by rememberSaveable { mutableStateOf(false) }
    AuditHeader("EVENTOS", "CONSULTA EVENTOS RECIBIDOS · FILTRA Y EXPORTA")
    AuditRule()
    Spacer(Modifier.height(2.dp))
    AuditChoice("Dispositivo", if (manualDevice) "Escribir identificador" else device.ifEmpty { "Todos los dispositivos" },
        listOf("" to "Todos los dispositivos") + session.devices.map { it.deviceId to "${it.deviceId.take(8)}…${it.deviceId.takeLast(4)}" } + listOf("manual" to "Escribir identificador"),
        !session.busy) { value -> if (value == "manual") manualDevice = true else { device = value; manualDevice = false } }
    if (manualDevice) AuditField("Identificador del dispositivo", device, { device = it }, !session.busy, placeholder = "UUID completo")
    AuditChoice("Categoría", if (category.isEmpty()) "Todas las categorías" else categoryName(category),
        listOf("" to "Todas las categorías") + EventPolicy.categories.sorted().map { it to categoryName(it) }, !session.busy) { category = it }
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
    AuditRule()
    SectionLabel("Resultados")
    if (session.events.isEmpty()) AuditEmpty(message = if (session.eventsLoaded)
        "No se recibieron eventos para los filtros aplicados." else "Aplica filtros o actualiza la consulta para recibir eventos.")
    session.events.forEach { event -> CompactEventRow(event, !session.busy) { session.showDetail(event, scope) } }
    Text("La consulta queda registrada en auditoría.", style = MaterialTheme.typography.bodySmall)
    SectionLabel("Página ${session.eventPageNumber} · ${session.events.size} recibidos · Hasta 50 eventos")
    AuditRule()
    SectionLabel("Opciones adicionales")
    AuditLink(if (preciseDates) "Ocultar edición de horas" else "Editar fechas y horas exactas", { preciseDates = !preciseDates }, !session.busy)
    if (preciseDates) {
        AuditField("Desde (incluida)", start, { start = it }, !session.busy, placeholder = "Sin límite")
        AuditField("Hasta (excluida)", end, { end = it }, !session.busy, placeholder = "Sin límite")
        Text("Formato UTC: 2026-10-28T00:00:00Z. Deja vacío para no limitar.", color = AuditColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
    AuditLink("Limpiar filtros", {
        filterError = null; manualDevice = false; device = ""; category = ""; start = ""; end = ""; search = ""
        session.applyFilters(EventQuery(), scope)
    }, !session.busy)
    if (device.trim().ifEmpty { null } != session.query.device || category.ifEmpty { null } != session.query.category ||
        start.trim().ifEmpty { null } != session.query.from || end.trim().ifEmpty { null } != session.query.to || search.trim().ifEmpty { null } != session.query.q) {
        Text("Hay cambios sin aplicar. La lista y el CSV usan los últimos filtros aplicados.", color = AuditColors.Warning)
    }
    Text("El CSV incluye todos los resultados de los filtros aplicados, hasta 10 000 eventos. Si se supera el límite, se informa un error.",
        color = AuditColors.Muted, style = MaterialTheme.typography.bodySmall)
    if (session.eventCursor != null) AuditButton("Siguiente página", { session.moreEvents(scope) }, !session.busy)
    AuditLink("Volver a la primera página", { session.applyFilters(session.query, scope) }, !session.busy)
    if (session.lastReportUri != null && onOpenReport != null) AuditButton("Abrir último CSV guardado", onOpenReport, !session.busy, outlined = true)
}

@Composable
private fun CompactEventRow(event: AuditEvent, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick, Modifier.fillMaxWidth().semantics { contentDescription = "Ver detalle →" }, enabled = enabled,
        contentPadding = PaddingValues(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(displayClock(event.timestamp).removeSuffix(" UTC"), Modifier.width(58.dp), style = MaterialTheme.typography.bodySmall)
            Text(event.category, Modifier.weight(1f), color = AuditColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text((event.metadata["appEventCode"] ?: event.direction).toString(), Modifier.widthIn(min = 78.dp),
                color = AuditColors.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
    AuditRule()
}

@Composable
internal fun DeviceList(session: ControlSession, scope: CoroutineScope, onDetail: (String) -> Unit) {
    AuditHeader("DISPOSITIVOS", "INSCRIPCIÓN Y ÚLTIMO CONTACTO")
    AuditRule()
    AuditChoice("Estado de inscripción", when (session.status) { "active" -> "Activos"; "revoked" -> "Revocados"; else -> "Todos" },
        listOf("" to "Todos", "active" to "Activos", "revoked" to "Revocados"), !session.busy) {
        session.filterDevices(it.ifEmpty { null }, scope)
    }
    AuditButton("Actualizar dispositivos", { session.refresh(scope) }, !session.busy)
    Text("Actualizado: ${displayTime(session.devicesUpdatedAt)}", color = AuditColors.Muted, style = MaterialTheme.typography.bodySmall)
    if (session.devices.isEmpty()) AuditEmpty("SIN DISPOSITIVOS", if (session.devicesLoaded) "No hay dispositivos en esta página para el filtro actual." else "Actualiza la lista para consultar tus dispositivos.")
    session.devices.forEach { device ->
        AuditPanel {
            Text("${device.deviceId.take(8)}…${device.deviceId.takeLast(4)}", style = MaterialTheme.typography.titleMedium)
            Text(enrollmentStatus(device), style = MaterialTheme.typography.titleSmall)
            Text("Último contacto: ${displayTime(device.lastSeenAt)}", color = AuditColors.Muted)
            AuditButton("Ver dispositivo", { onDetail(device.deviceId) }, !session.busy, outlined = true)
        }
    }
    Text("${session.devices.size} dispositivos en esta página. El estado activo indica inscripción, no conexión en vivo.", color = AuditColors.Muted)
    if (session.deviceCursor != null) AuditButton("Siguiente página de dispositivos", { session.moreDevices(scope) }, !session.busy)
}

@Composable
internal fun DeviceDetail(session: ControlSession, device: DeviceSummary?, scope: CoroutineScope,
    onBack: () -> Unit, onEvents: () -> Unit, onAudit: () -> Unit, onWhatsApp: () -> Unit = {}) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val composeScope = rememberCoroutineScope()
    AuditHeader("DISPOSITIVO", "ESTADO, CONSENTIMIENTO Y ACCIONES")
    AuditLink("← Volver a dispositivos", onBack, !session.busy)
    if (device == null) { AuditEmpty("DISPOSITIVO NO DISPONIBLE", "Actualiza la lista de dispositivos para consultar su detalle."); return }
    AuditRule()
    SectionLabel("Inscripción")
    Text(enrollmentStatus(device), style = MaterialTheme.typography.titleLarge)
    Text("Último contacto: ${displayTime(device.lastSeenAt)}", color = AuditColors.Muted)
    AuditRule()
    SectionLabel("Identificador")
    Text(device.deviceId, style = MaterialTheme.typography.titleSmall)
    AuditLink("Copiar identificador", { composeScope.launch {
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Dispositivo AuditLab", device.deviceId)))
    } })
    Text("Versión de la app: ${device.appVersion ?: "Sin datos"}")
    Text("Inscrito: ${displayTime(device.enrolledAt)}")
    device.revokedAt?.let { Text("Revocado: ${displayTime(it)}") }
    AuditPanel {
        SectionLabel("Consentimiento registrado")
        Text(if (device.isActive) "INSCRIPCIÓN ACTIVA" else "INSCRIPCIÓN REVOCADA", style = MaterialTheme.typography.titleMedium)
        Text("Política: ${device.consent.policyVersion}")
        Text("Aceptado: ${displayTime(device.consent.acceptedAt)}")
        Text("El usuario puede retirar el consentimiento desde el agente. El servidor no confirma si la notificación está visible en este momento.", color = AuditColors.Muted)
    }
    SectionLabel("Acciones")
    if(device.isActive) AuditButton("Ver notificaciones de WhatsApp",onWhatsApp,!session.busy)
    if (device.isActive) AuditButton("Ver pantalla compartida", {
        context.startActivity(android.content.Intent(context, ScreenViewerActivity::class.java).putExtra("device", device.deviceId))
    }, !session.busy)
    AuditButton("Ver eventos", onEvents, !session.busy)
    AuditButton("Ver auditoría del grupo", onAudit, !session.busy, outlined = true)
    if (session.isAdmin && device.isActive) AuditButton("Revocar dispositivo", { session.requestRevoke(device.deviceId) }, !session.busy)
    Text("La revocación bloquea nuevos envíos al servidor. Un agente sin conexión la detectará al volver a comunicarse.", color = AuditColors.Muted)
}

@Composable
internal fun EnrollmentView(session: ControlSession, scope: CoroutineScope, onBack: () -> Unit) {
    AuditHeader("INSCRIPCIÓN", "VINCULA UN DISPOSITIVO AUTORIZADO")
    AuditLink("← Volver", onBack, !session.busy)
    if (!session.isAdmin) { AuditNotice("Solo un administrador puede crear códigos de inscripción."); return }
    AuditPanel {
        Text("UN CÓDIGO · UN DISPOSITIVO", style = MaterialTheme.typography.titleMedium)
        Text("Genera el código e introdúcelo en el agente después de aceptar el consentimiento. Vence en 10 minutos y tiene un solo uso.")
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
            Text(entry.action, style = MaterialTheme.typography.titleMedium)
            Text(displayTime(entry.occurredAt), color = AuditColors.Muted)
            Text("Actor: ${entry.actor}")
            Text("Recurso: ${entry.resource}")
            Text("Resultado: ${entry.result}")
            Text("Filtros: ${entry.filters}", style = MaterialTheme.typography.bodySmall)
            Text("Correlación: ${entry.correlationId}", style = MaterialTheme.typography.bodySmall)
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
internal fun EventRow(event: AuditEvent, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick, Modifier.fillMaxWidth(), enabled = enabled, contentPadding = PaddingValues(vertical = 10.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(eventTitle(event), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(if (event.isSimulated) "Simulado" else "Real", style = MaterialTheme.typography.labelMedium)
                }
                Text(displayTime(event.timestamp), color = AuditColors.Muted, style = MaterialTheme.typography.bodySmall)
                Text("Dispositivo: ${event.sourceDevice.take(8)}…${event.sourceDevice.takeLast(4)}", color = AuditColors.Muted, style = MaterialTheme.typography.bodySmall)
                metadataDescription(event.metadata).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Ver detalle →", style = MaterialTheme.typography.labelMedium)
            }
        }
        AuditRule()
    }
}

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
