package com.auditlab.control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

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
        setContent { MaterialTheme { Surface(modifier = Modifier.fillMaxSize()) {
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
    workScope: kotlinx.coroutines.CoroutineScope? = null, onOpenReport: (() -> Unit)? = null) {
    val composeScope = rememberCoroutineScope()
    val scope = workScope ?: composeScope
    val revokeTarget = session.revokeTarget
    val confirmLogout = session.logoutConfirmation
    var category by rememberSaveable(session.query) { mutableStateOf(session.query.category.orEmpty()) }
    var start by rememberSaveable(session.query) { mutableStateOf(session.query.from.orEmpty()) }
    var end by rememberSaveable(session.query) { mutableStateOf(session.query.to.orEmpty()) }
    var search by rememberSaveable(session.query) { mutableStateOf(session.query.q.orEmpty()) }
    var filterError by remember { mutableStateOf<String?>(null) }
    if (confirmLogout && session.signedIn) androidx.compose.material3.AlertDialog(onDismissRequest = { session.cancelLogout() },
        title = { Text("Cerrar sesión") }, text = { Text("Se eliminará la sesión local de este centro.") },
        confirmButton = { TextButton(onClick = { session.cancelLogout(); session.logout(scope) }) { Text("Confirmar salida") } },
        dismissButton = { TextButton(onClick = { session.cancelLogout() }) { Text("Cancelar") } })
    revokeTarget?.takeIf { session.isAdmin }?.let { target -> androidx.compose.material3.AlertDialog(onDismissRequest = { session.cancelRevoke() },
        title = { Text("Revocar dispositivo") }, text = { Text("Se bloquearán nuevas transmisiones de $target. Reinscribirlo exigirá un consentimiento nuevo.") },
        confirmButton = { TextButton(onClick = { session.cancelRevoke(); session.revoke(target, scope) }) { Text("Confirmar revocación") } },
        dismissButton = { TextButton(onClick = { session.cancelRevoke() }) { Text("Cancelar") } }) }
    session.detail?.let { event -> androidx.compose.material3.AlertDialog(onDismissRequest = session::closeDetail,
        title = { Text("Detalle de evento") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("ID: ${event.eventId}"); Text("Dispositivo: ${event.sourceDevice}")
            Text("Fecha: ${event.timestamp}"); Text("Recibido: ${event.receivedAt}")
            Text(eventTitle(event)); Text(if (event.isSimulated) "Evento simulado" else "Evento real del dispositivo")
            Text("Consentimiento: ${event.consent.consentId} · ${event.consent.policyVersion}")
            Text(event.metadata.entries.joinToString { "${it.key}=${it.value}" })
        } }, confirmButton = { TextButton(onClick = session::closeDetail) { Text("Cerrar detalle") } }) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(session.signedIn) {
        if (session.signedIn) { password = ""; user = "" }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AuditLab · Centro de control", style = MaterialTheme.typography.headlineMedium)

        if (!session.signedIn) {
            Text("Inicia sesión con una cuenta de AuditLab. Los roles admin y auditor se aplican en el servidor.",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = session.serverUrl,
                onValueChange = session::updateServerUrl,
                label = { Text("URL del servicio") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Usuario") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Contraseña") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (session.storageBlocked) Button(onClick = { session.clearLocal(scope) }, enabled = !session.busy) { Text("Borrar sesión local") }
            Button(
                onClick = { session.login(user, password, scope) },
                enabled = !session.busy && !session.storageBlocked && user.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (session.busy) "Conectando…" else "Iniciar sesión") }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(session.username, style = MaterialTheme.typography.titleMedium)
                    Text("Rol: ${session.role}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { session.requestLogout() }, enabled = !session.busy) { Text("Salir") }
            }
            Button(
                onClick = { session.refresh(scope) },
                enabled = !session.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (session.busy) "Actualizando…" else "Actualizar dispositivos") }

            Text("Estado de dispositivos")
            Row { TextButton(onClick = { session.filterDevices(null, scope) }, enabled = !session.busy) { Text("Todos") }
                TextButton(onClick = { session.filterDevices("active", scope) }, enabled = !session.busy) { Text("Activos") }
                TextButton(onClick = { session.filterDevices("revoked", scope) }, enabled = !session.busy) { Text("Revocados") } }
            if (session.deviceCursor != null) Button(onClick = { session.moreDevices(scope) }, enabled = !session.busy) { Text("Más dispositivos") }
            Button(onClick = { session.applyFilters(com.auditlab.common.EventQuery(), scope) }, enabled = !session.busy) { Text("Consultar todos los eventos") }
            Button(onClick = { session.loadAudit(scope) }, enabled = !session.busy) { Text("Consultar auditoría") }
            if (session.auditCursor != null) TextButton(onClick = { session.loadAudit(scope, true) }, enabled = !session.busy) { Text("Más auditoría") }
            session.audit.forEach { entry -> Text("${entry.occurredAt} · ${entry.action} · ${entry.result}\n${entry.actor}\n${entry.resource}\nFiltros: ${entry.filters}\nCorrelación: ${entry.correlationId}") }
            if (session.isAdmin) {
                HorizontalDivider()
                Text("Inscripción de dispositivos", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { session.createEnrollmentCode(scope) },
                    enabled = !session.busy,
                ) { Text("Crear código de un uso") }
                session.enrollmentCode?.let { code ->
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Código (visible solo ahora)", style = MaterialTheme.typography.labelMedium)
                            Text(code.code, style = MaterialTheme.typography.bodyMedium)
                            Text("Vence: ${code.expiresAt}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("Dispositivos (${session.devices.size})", style = MaterialTheme.typography.titleMedium)
            if (session.devices.isEmpty()) {
                Text("No hay dispositivos visibles para este rol.", style = MaterialTheme.typography.bodySmall)
            }
            session.devices.forEach { device ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${device.status.uppercase()} · ${device.appVersion ?: "versión desconocida"}",
                            style = MaterialTheme.typography.titleSmall)
                        Text(device.deviceId, style = MaterialTheme.typography.bodySmall)
                        Text("Inscrito: ${device.enrolledAt}", style = MaterialTheme.typography.bodySmall)
                        Text("Última recepción: ${device.lastSeenAt ?: "sin eventos"}", style = MaterialTheme.typography.bodySmall)
                        Text("La conectividad actual no se deduce solo del estado activo.", style = MaterialTheme.typography.bodySmall)
                        device.revokedAt?.let { Text("Revocado: $it", style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { session.selectDevice(device.deviceId, scope) },
                                enabled = !session.busy,
                            ) { Text("Ver eventos") }
                            if (session.isAdmin && device.isActive) {
                                TextButton(
                                    onClick = { session.requestRevoke(device.deviceId) },
                                    enabled = !session.busy,
                                ) { Text("Revocar") }
                            }
                        }
                    }
                }
            }

            run {
                OutlinedTextField(category, { category = it }, label = { Text("Categoría (vacía para todas)") }, enabled = !session.busy)
                OutlinedTextField(start, { start = it }, label = { Text("Desde UTC: 2026-10-25T00:00:00Z") }, enabled = !session.busy)
                OutlinedTextField(end, { end = it }, label = { Text("Hasta UTC (exclusiva)") }, enabled = !session.busy)
                OutlinedTextField(search, { search = it }, label = { Text("ID o código de evento exacto") }, enabled = !session.busy)
                Button(onClick = {
                    try { val query = com.auditlab.common.EventQuery(session.selectedDevice, category.trim().ifEmpty { null },
                        start.trim().ifEmpty { null }, end.trim().ifEmpty { null }, search.trim().ifEmpty { null })
                        filterError = null; session.applyFilters(query, scope)
                    } catch (_: Exception) { filterError = "Revisa categoría, fechas UTC e ID/código permitido." }
                }, enabled = !session.busy) { Text("Aplicar filtros") }
                filterError?.let { Text(it) }
                Text("Dispositivo consultado: ${session.selectedDevice ?: "todos"}")
                Text("Filtros aplicados: categoría ${session.query.category ?: "todas"}; desde ${session.query.from ?: "sin límite"}; hasta ${session.query.to ?: "sin límite"}; ID/código ${session.query.q ?: "todos"}.")
                Text("CSV: todos los resultados de los filtros aplicados, máximo 10 000 eventos. Sin límite parcial silencioso.")
                if (onExport != null) Button(onClick = onExport, enabled = !session.busy && session.signedIn) { Text("Guardar CSV") }
                Text("Se muestra una página de hasta 50 eventos. Exportar incluye todos los resultados filtrados.")
                TextButton(onClick = { session.applyFilters(session.query, scope) }, enabled = !session.busy) { Text("Primera página de eventos") }
                if (session.lastReportUri != null && onOpenReport != null) TextButton(onClick = onOpenReport, enabled = !session.busy) { Text("Abrir último CSV guardado") }
                if (session.eventCursor != null) Button(onClick = { session.moreEvents(scope) }, enabled = !session.busy) { Text("Más eventos") }
                HorizontalDivider()
                Text(if (session.selectedDevice != null) "Eventos del dispositivo seleccionado (${session.events.size})" else "Historial general (${session.events.size})",
                    style = MaterialTheme.typography.titleMedium)
                Text("La consulta queda registrada en la auditoría del servidor.",
                    style = MaterialTheme.typography.bodySmall)
                if (session.events.isEmpty()) {
                    Text("Sin eventos en la página consultada.", style = MaterialTheme.typography.bodySmall)
                }
                session.events.forEach { event ->
                    Card {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(eventTitle(event),
                                style = MaterialTheme.typography.titleSmall)
                            Text("${if (event.isSimulated) "Simulado" else "Real"} · ${event.timestamp}",
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { session.showDetail(event, scope) }, enabled = !session.busy) { Text("Detalle ${event.eventId}") }
                            Text(event.metadata.entries.joinToString { "${it.key}=${it.value}" },
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        session.message?.let {
            Card { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

internal fun eventTitle(event: com.auditlab.common.AuditEvent): String = when (event.metadata["appEventCode"]) {
    "sms_received" -> "SMS recibido"
    "sms_sent" -> "SMS enviado"
    "call_received" -> "Llamada recibida"
    "call_outgoing" -> "Llamada saliente"
    "call_missed" -> "Llamada perdida"
    "call_rejected" -> "Llamada rechazada o bloqueada"
    "device_status_changed" -> "Cambio de batería o conexión"
    else -> "${event.category} · ${event.direction}"
}
