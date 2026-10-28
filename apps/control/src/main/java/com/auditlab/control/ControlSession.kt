package com.auditlab.control

import android.content.Context
import androidx.compose.runtime.*
import com.auditlab.common.*
import kotlinx.coroutines.*

/** Network results are published on Main; busy serializes actions and cursors belong to immutable filters. */
class ControlSession internal constructor(private val store: SessionStore, private val apiFactory: (String) -> AuditApi,
    private val boundOrigin: Boolean = false) {
    constructor(context: Context) : this(KeystoreSessionStore(context), { ApiClient(it) }, true)
    private val restored = runCatching { store.load() }
    private var engine: ControlCore? = null
    var serverUrl by mutableStateOf(restored.getOrNull()?.serverUrl ?: DEFAULT_URL); private set
    var signedIn by mutableStateOf(restored.getOrNull() != null && (!boundOrigin || restored.getOrNull()?.serverUrl != null)); private set
    var username by mutableStateOf(restored.getOrNull()?.username.orEmpty()); private set
    var role by mutableStateOf(restored.getOrNull()?.role.orEmpty()); private set
    val isAdmin get() = signedIn && role == "admin"
    var devices by mutableStateOf<List<DeviceSummary>>(emptyList()); private set
    var events by mutableStateOf<List<AuditEvent>>(emptyList()); private set
    var audit by mutableStateOf<List<AuditRecord>>(emptyList()); private set
    var detail by mutableStateOf<AuditEvent?>(null); private set
    var lastReportUri by mutableStateOf<android.net.Uri?>(null); private set
    var selectedDevice by mutableStateOf<String?>(null); private set
    var query by mutableStateOf(EventQuery()); private set
    var status by mutableStateOf<String?>(null); private set
    var deviceCursor by mutableStateOf<String?>(null); private set
    var eventCursor by mutableStateOf<String?>(null); private set
    var auditCursor by mutableStateOf<String?>(null); private set
    var enrollmentCode by mutableStateOf<EnrollmentCode?>(null); private set
    var overviewDevice by mutableStateOf<String?>(null); private set
    var overviewEvents by mutableStateOf<List<AuditEvent>>(emptyList()); private set
    var overviewReading by mutableStateOf<AuditEvent?>(null); private set
    var overviewLoaded by mutableStateOf(false); private set
    var eventPageNumber by mutableStateOf(1); private set
    var devicesLoaded by mutableStateOf(false); private set
    var eventsLoaded by mutableStateOf(false); private set
    var auditLoaded by mutableStateOf(false); private set
    var devicesUpdatedAt by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var revokeTarget by mutableStateOf<String?>(null); private set
    var logoutConfirmation by mutableStateOf(false); private set
    fun requestRevoke(device: String) { if (isAdmin && !busy) revokeTarget = device }
    fun cancelRevoke() { revokeTarget = null }
    fun requestLogout() { if (signedIn && !busy) logoutConfirmation = true }
    fun cancelLogout() { logoutConfirmation = false }
    var message by mutableStateOf<String?>(if (restored.isFailure) "No se pudo leer la sesión cifrada. Borra la sesión local para iniciar de nuevo." else null); private set
    var storageBlocked by mutableStateOf(restored.isFailure || (boundOrigin && restored.getOrNull()?.serverUrl == null && restored.getOrNull() != null)); private set
    fun updateServerUrl(value: String) { if (!signedIn && !busy && !storageBlocked) { serverUrl = value; engine = null } }
    private fun core() = engine ?: ControlCore(apiFactory(serverUrl), store, if (boundOrigin) serverUrl else null).also { engine = it }
    fun login(user: String, password: String, scope: CoroutineScope) = run(scope) {
        core().login(user, password)
        val page = core().devices()
        return@run { devices = page.items; deviceCursor = page.nextCursor; devicesLoaded = true
            devicesUpdatedAt = java.time.Instant.now().toString(); message = "Sesión iniciada." }
    }
    fun logout(scope: CoroutineScope) { logoutConfirmation = false; doLogout(scope) }
    private fun doLogout(scope: CoroutineScope) = run(scope) {
        val outcome = core().logout()
        return@run {
            clearViews(); engine = null
            message = if (outcome == ControlCore.LogoutOutcome.NOTIFIED) "Sesión cerrada y servidor notificado."
                else "Sesión local eliminada. No se confirmó el cierre remoto; los tokens remotos siguen sujetos a caducidad."
        }
    }
    fun clearLocal(scope: CoroutineScope) = run(scope) {
        store.clear()
        return@run { engine = null; storageBlocked = false; clearViews(); message = "Sesión local eliminada." }
    }
    fun refresh(scope: CoroutineScope) = run(scope) {
        val page = core().devicePage(status, null)
        return@run { devices = page.items; deviceCursor = page.nextCursor; devicesLoaded = true
            devicesUpdatedAt = java.time.Instant.now().toString(); clearOverview()
            message = "Actualizado: ${page.items.size} dispositivo(s) en esta página." }
    }
    fun filterDevices(value: String?, scope: CoroutineScope) {
        if (busy) return
        require(value == null || value in setOf("active", "revoked"))
        status = value; devices = emptyList(); deviceCursor = null; refresh(scope)
    }
    fun moreDevices(scope: CoroutineScope) = run(scope) {
        val cursor = deviceCursor ?: error("No hay más dispositivos")
        val page = core().devicePage(status, cursor)
        return@run { devices = page.items; deviceCursor = page.nextCursor; devicesLoaded = true
            devicesUpdatedAt = java.time.Instant.now().toString() }
    }
    /** Separate dashboard data from history filters/cursors. Every query remains audited by the API. */
    fun loadOverview(deviceId: String, scope: CoroutineScope) {
        if (busy) return
        clearOverview(); overviewDevice = deviceId
        run(scope) {
            val recent = core().eventPage(EventQuery(device = deviceId), null)
            val readings = core().eventPage(EventQuery(device = deviceId, category = "device_status"), null)
            return@run {
                overviewEvents = recent.items
                overviewReading = readings.items.filter { !it.isSimulated }.maxByOrNull { java.time.Instant.parse(it.timestamp) }
                overviewLoaded = true
            }
        }
    }
    private fun clearOverview() { overviewDevice = null; overviewEvents = emptyList(); overviewReading = null; overviewLoaded = false }
    fun selectDevice(deviceId: String, scope: CoroutineScope) { applyFilters(EventQuery(device = deviceId), scope) }
    fun applyFilters(value: EventQuery, scope: CoroutineScope) {
        if (busy) return
        query = value; selectedDevice = value.device; events = emptyList(); eventCursor = null; detail = null
        eventPageNumber = 1; eventsLoaded = false
        loadEvents(scope, false)
    }
    fun moreEvents(scope: CoroutineScope) = loadEvents(scope, true)
    private fun loadEvents(scope: CoroutineScope, more: Boolean) = run(scope) {
        val page = core().eventPage(query, if (more) eventCursor ?: error("No hay más eventos") else null)
        return@run { events = page.items; eventsLoaded = true; if (more) eventPageNumber++
          eventCursor = page.nextCursor; message = "Historial actualizado: ${events.size} evento(s)." }
    }
    fun showDetail(event: AuditEvent, scope: CoroutineScope) = run(scope) {
        val result = core().detail(event.sourceDevice, event.eventId)
        return@run { detail = result }
    }
    fun closeDetail() { detail = null }
    fun loadAudit(scope: CoroutineScope, more: Boolean = false) = run(scope) {
        val page = core().audit(if (more) auditCursor ?: error("No hay más auditoría") else null)
        return@run { audit = page.items; auditCursor = page.nextCursor; auditLoaded = true }
    }
    fun createEnrollmentCode(scope: CoroutineScope) = run(scope) {
        val code = core().createEnrollmentCode()
        return@run { enrollmentCode = code; message = "Código creado. Es de un solo uso y vence en 10 minutos." }
    }
    fun revoke(deviceId: String, scope: CoroutineScope) = run(scope) {
        core().revoke(deviceId)
        val page = core().devicePage(status, null)
        return@run { devices = page.items; deviceCursor = page.nextCursor; devicesLoaded = true; clearOverview()
            devicesUpdatedAt = java.time.Instant.now().toString(); message = "Dispositivo revocado. Los nuevos lotes serán rechazados." }
    }
    // The picker holds no report or token. An export ticket is invalid after filter/session changes.
    data class ExportTicket(val session: String, val query: EventQuery)
    fun exportTicket(): ExportTicket? = if (!busy && signedIn) engine?.session?.let { ExportTicket(it.sessionId, query) } else null
    fun export(ticket: ExportTicket, scope: CoroutineScope, destination: android.net.Uri? = null, write: (ByteArray) -> Unit) = run(scope) {
        check(ticket.session == core().session?.sessionId && ticket.query == query) { "La sesión o los filtros cambiaron. Vuelve a exportar." }
        if (destination != null) check(ExportDestination.isAllowed(destination))
        val csv = core().export(ticket.query).toByteArray(Charsets.UTF_8)
        write(csv)
        return@run { lastReportUri = destination; message = "CSV guardado con los filtros seleccionados." }
    }
    fun notifyViewerUnavailable() { message = "El CSV está guardado, pero no hay un visor disponible para abrirlo." }
    fun notifyExportUnavailable() { message = "El selector de archivos no está disponible o fue bloqueado. No se exportó un reporte." }
    fun notifyExportCancelled() { message = "Exportación cancelada; no se guardó un reporte." }
    private fun clearViews() { signedIn = false; username = ""; role = ""; devices = emptyList(); events = emptyList()
        audit = emptyList(); detail = null; lastReportUri = null; revokeTarget = null; logoutConfirmation = false; query = EventQuery(); selectedDevice = null; enrollmentCode = null
        deviceCursor = null; eventCursor = null; auditCursor = null; clearOverview()
        eventPageNumber = 1; devicesLoaded = false; eventsLoaded = false; auditLoaded = false; devicesUpdatedAt = null }
    private fun run(scope: CoroutineScope, block: () -> (() -> Unit)) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { val publish = withContext(Dispatchers.IO) { block() }; publish() }
            catch (error: ApiException) {
                message = when {
                    error.code == "TLS_ERROR" -> "Certificado TLS rechazado. Revisa el servicio."
                    error.isNetwork -> "Sin conexión con el servicio. Revisa la URL y la red."
                    error.status == 401 -> "Credenciales rechazadas o sesión vencida."
                    error.status == 403 -> "Tu rol no permite esta operación."
                    error.code == "INVALID_CURSOR" -> "La página venció. Actualiza la consulta desde el inicio."
                    else -> "Solicitud rechazada (${error.code})."
                }
            } catch (_: StorageFailure) { storageBlocked = true; clearViews(); message = "No se pudo confirmar el almacenamiento cifrado. Borra la sesión local." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "No se pudo completar la operación. Revisa los datos o el archivo y vuelve a intentarlo." }
            finally {
                engine?.let { signedIn = it.isSignedIn && !storageBlocked; username = it.session?.username.orEmpty(); role = it.session?.role.orEmpty()
                    if (!signedIn) clearViews() }
                busy = false
            }
        }
    }
    companion object { const val DEFAULT_URL = "http://10.0.2.2:8081" }
}
