package com.auditlab.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.auditlab.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Android IO is serialized off the UI thread; state is published on the supplied main scope. */
class AgentSession internal constructor(
    private val store: CredentialStore,
    private val apiFactory: (String) -> AuditApi,
    private val platform: AgentRuntime? = null,
) {
    constructor(context: Context) : this(AgentRuntime.get(context).credentials, { ApiClient(it) }, AgentRuntime.get(context))
    var serverUrl by mutableStateOf(DEFAULT_URL)
        private set
    private var engine: AgentCore? = null
    var state by mutableStateOf<AgentCore.State>(if (platform == null) core().state else AgentCore.State.ConsentPending)
        private set
    var consent by mutableStateOf<ConsentContext?>(null)
        private set
    var stats by mutableStateOf(QueueStats())
        private set
    var revocationPending by mutableStateOf(false)
        private set
    var visibleAvailable by mutableStateOf(platform == null)
        private set
    var realMonitoring by mutableStateOf(false); private set
    var realCaptureRunning by mutableStateOf(false); private set
    var ready by mutableStateOf(platform == null); private set
    data class LocalEvent(val event: EventDraft, val delivery: String, val errorCode: String?)
    var recentEvents by mutableStateOf<List<LocalEvent>>(emptyList()); private set
    var lastDeviceStatus by mutableStateOf<EventDraft?>(null); private set
    var realStatus by mutableStateOf("Captura real detenida."); private set
    var busy by mutableStateOf(false)
        private set
    var revoking by mutableStateOf(false)
        private set
    var consentEpoch by mutableIntStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var log by mutableStateOf<List<String>>(emptyList())
        private set
    private val operations = Mutex()
    private var operationCount = 0
    private var initialized = platform == null

    fun initialize(scope: CoroutineScope) {
        if (initialized) return
        initialized = true
        perform(scope) { it.refresh(); null }
        scope.launch { platform?.changes?.collect { refresh(scope) } }
    }
    fun refresh(scope: CoroutineScope) {
        if (busy) return
        scope.launch {
            operations.withLock {
                if (busy) return@withLock
                try { publish(withContext(Dispatchers.IO) { snapshot() }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    state = AgentCore.State.Blocked("No se pudo leer el almacenamiento. Reintenta o revoca.")
                    visibleAvailable = false; ready = true
                }
            }
        }
    }
    fun updateServerUrl(value: String) {
        if (busy || state != AgentCore.State.ConsentPending) return
        serverUrl = value; engine = null; consent = null; consentEpoch++
    }
    fun acceptConsent(scope: CoroutineScope? = null) {
        if (busy) return
        if (platform == null) {
            consent = core().acceptConsent()
            message = "Consentimiento registrado. Puedes inscribir el dispositivo."
        } else if (scope != null) perform(scope) {
            it.acceptConsent()
            "Consentimiento registrado. Puedes inscribir el dispositivo."
        }
    }
    fun enroll(code: String, scope: CoroutineScope) = perform(scope, networkHint = "No se pudo confirmar la inscripción. Comprueba el centro y solicita otro código si el anterior fue consumido.") {
        it.enroll(code)
        platform?.indicator?.show()
        "Dispositivo inscrito. Puedes autorizar la captura real o generar un evento de prueba."
    }
    fun sendSimulatedEvent(scope: CoroutineScope) = perform(scope) {
        val result = it.captureAndSend { platform?.metadata() ?: mapOf("appEventCode" to "agent_started", "networkState" to "online") }
        if (it.stats().pending == 0) "Evento enviado: ${result.accepted} aceptado(s), ${result.duplicates} duplicado(s)."
        else "Evento guardado localmente. Pendientes: ${it.stats().pending}."
    }
    fun revoke(scope: CoroutineScope) {
        platform?.stopWhatsAppSharing()
        platform?.stopScreenSharing()
        if (revoking) return
        engine?.requestWithdrawal()
        platform?.gate?.withdrawalRequested = true
        platform?.realEvents?.stop()
        revoking = true
        perform(platform?.applicationScope ?: scope, privacy = true) {
            val result = it.revokeLocally()
            if (result == AgentCore.RevokeOutcome.NOTIFIED) "Consentimiento revocado y servidor notificado. No se enviarán más eventos."
            else "Retirada local guardada. No se confirmó el aviso al servidor; reintenta o consulta el centro."
        }
    }
    fun retryPrivacy(scope: CoroutineScope) = perform(platform?.applicationScope ?: scope, privacy = true) {
        val result = it.retryRevocation()
        if (result == AgentCore.RevokeOutcome.NOTIFIED) "Retirada confirmada y cola eliminada."
        else "Retirada local conservada. El aviso al servidor sigue sin confirmarse."
    }
    fun restartConsent(scope: CoroutineScope) = perform(scope, success = {
        consentEpoch++; consent = null; engine = null
    }) {
        it.resetAfterRevocation()
        "Lee y acepta de nuevo el consentimiento. Necesitas un código nuevo."
    }
    fun resume(scope: CoroutineScope) = perform(scope) {
        check(it.state is AgentCore.State.Enrolled) { "Primero debes inscribir este dispositivo." }
        if (platform != null && !platform.indicator.show()) throw MonitoringPaused()
        "Indicador habilitado. La sincronización puede continuar."
    }
    fun pause(scope: CoroutineScope) {
        platform?.stopWhatsAppSharing()
        perform(scope) {
        platform?.stopScreenSharing()
        platform?.realEvents?.stop()
        platform?.indicator?.hide()
        "Monitoreo pausado. Los pendientes se conservan; no se generan ni envían eventos."
        }
    }
    var numberConsent by mutableStateOf(false)
        private set
    fun updateNumberConsent(value: Boolean) { if(!realMonitoring) numberConsent=value }
    var smsTextConsent by mutableStateOf(false); private set
    fun updateSmsTextConsent(value: Boolean) { if(!realMonitoring) smsTextConsent=value }
    fun startRealMonitoring(scope: CoroutineScope) = perform(scope) {
        check(platform != null) { "La captura real necesita un dispositivo Android." }
        platform.realEvents.start(numberConsent,smsTextConsent)
        "Captura solicitada. ${platform.realEvents.permissionsText()}"
    }
    fun stopRealMonitoring(scope: CoroutineScope) = perform(scope) {
        platform?.realEvents?.stop()
        "Captura real detenida. No se consultan nuevos registros del teléfono."
    }
    fun refreshDeviceStatus(scope: CoroutineScope) = perform(scope) {
        check(platform!=null) { "Esta función necesita el teléfono del Agente." }
        platform.realEvents.refreshNow()
        "Actualización solicitada. El Agente leerá la batería y enviará el estado al servidor."
    }
    fun stopScreenSharing() { platform?.stopScreenSharing() }
    fun stopWhatsAppSharing() { platform?.stopWhatsAppSharing() }
    private fun core(): AgentCore = engine ?: (platform?.core(serverUrl) ?: AgentCore(apiFactory(serverUrl), store))
        .also { engine = it }
    private data class Snapshot(val state: AgentCore.State, val consent: ConsentContext?, val stats: QueueStats,
                                val pending: Boolean, val visible: Boolean, val url: String,
                                val events: List<LocalEvent>, val deviceStatus: EventDraft?)
    private fun snapshot(): Snapshot = platform?.let { synchronized(it.gate) { readSnapshot() } } ?: readSnapshot()
    private fun readSnapshot(): Snapshot {
        val current = core()
        // Read only the existing consent-bound local queue; never query phone providers from the UI.
        val state = current.refresh()
        val active = state is AgentCore.State.Enrolled
        val rows = if (active) platform?.database?.dao()?.recent(100).orEmpty() else emptyList()
        val deviceStatus = if (active) platform?.database?.dao()?.lastDeviceStatus()?.let { EventCodec.decode(it.payloadJson) } else null
        return Snapshot(state, current.acceptedConsent, current.stats(), current.revocationPending,
            platform?.indicator?.isVisible() ?: true, platform?.target(serverUrl) ?: serverUrl,
            rows.map { LocalEvent(EventCodec.decode(it.payloadJson), it.delivery, it.errorCode) }, deviceStatus)
    }
    private fun publish(value: Snapshot) {
        if(value.state !is AgentCore.State.Enrolled) { numberConsent=false;smsTextConsent=false }
        state = value.state; consent = value.consent; stats = value.stats; revocationPending = value.pending
        visibleAvailable = value.visible; serverUrl = value.url
        ready = true; recentEvents = value.events; lastDeviceStatus = value.deviceStatus
        realMonitoring = platform?.realEvents?.requested == true
        realCaptureRunning = platform?.realEvents?.running == true
        realStatus = platform?.realEvents?.let { if (it.requested) it.message else "Captura real detenida. ${it.permissionsText()}" }
            ?: "Captura real no disponible en esta prueba."
    }
    private fun perform(scope: CoroutineScope, privacy: Boolean = false, notify: Boolean = true,
                        success: () -> Unit = {}, networkHint: String? = null, action: (AgentCore) -> String?) {
        if (busy && !privacy) return
        operationCount++; busy = true
        scope.launch {
            try {
                operations.withLock {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            try { action(core()) }
                            finally { if (notify) platform?.after(core()) }
                        }
                        success()
                        if (result != null) { message = result; log = (listOf(result) + log).take(20) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: ApiException) {
                        message = when {
                            e.isDeviceRevoked -> "El centro de control revocó este dispositivo. No se envían más eventos."
                            e.code == "TLS_ERROR" -> "El certificado del servicio fue rechazado. No se transmiten datos."
                            e.isRetryable -> networkHint ?: "Sin confirmación del servicio. El evento permanece pendiente para reintento."
                            else -> "La operación fue rechazada (${e.code}). Revisa el estado y reintenta."
                        }
                    } catch (_: StorageFailure) { message = "No se pudo confirmar el almacenamiento. Las acciones pueden estar bloqueadas; reintenta la retirada." }
                    catch (e: IllegalStateException) { message = e.message ?: "Operación bloqueada." }
                    catch (e: IllegalArgumentException) { message = e.message ?: "Revisa la dirección del servicio." }
                    catch (_: Exception) { message = "No se pudo completar la operación. No se confirma éxito." }
                    finally {
                        try { publish(withContext(Dispatchers.IO) { snapshot() }) }
                        catch (_: Exception) { state = AgentCore.State.Blocked("No se pudo leer el almacenamiento. Reintenta o revoca."); ready = true }
                        if (privacy) revoking = false
                    }
                }
            } finally { operationCount--; busy = operationCount > 0 }
        }
    }
    companion object { const val DEFAULT_URL = "http://10.0.2.2:8081" }
}
