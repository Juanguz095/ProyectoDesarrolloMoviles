package com.auditlab.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.auditlab.common.AgentCore
import com.auditlab.common.ApiClient
import com.auditlab.common.ApiException
import com.auditlab.common.AuditApi
import com.auditlab.common.ConsentContext
import com.auditlab.common.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Estado observable de la pantalla del agente. Toda la red ocurre fuera del hilo principal;
 * la UI solo recibe resultados ya resumidos y nunca muestra tokens.
 */
class AgentSession internal constructor(
    private val store: CredentialStore,
    private val apiFactory: (String) -> AuditApi,
) {
    constructor(context: Context) : this(KeystoreCredentialStore(context), { url -> ApiClient(url) })

    private var engine: AgentCore? = null

    var serverUrl by mutableStateOf(DEFAULT_URL)
        private set
    var consent by mutableStateOf<ConsentContext?>(null)
        private set
    var state by mutableStateOf(initialState())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var log by mutableStateOf<List<String>>(emptyList())
        private set

    private fun initialState(): AgentCore.State = store.load()?.let {
        AgentCore.State.Enrolled(it.deviceId, it.consent)
    } ?: AgentCore.State.ConsentPending

    fun updateServerUrl(value: String) {
        if (state is AgentCore.State.Enrolled) return
        serverUrl = value
        engine = null
        consent = null
    }

    fun acceptConsent() {
        consent = core().acceptConsent()
        message = "Consentimiento registrado el ${consent?.acceptedAt}. Puedes inscribir el dispositivo."
    }

    fun enroll(code: String, scope: CoroutineScope) = run(scope) {
        val enrolled = core().enroll(code)
        state = enrolled
        message = "Dispositivo inscrito. El monitoreo permanece visible y limitado a eventos simulados."
        append("Inscripción confirmada · ${enrolled.deviceId}")
    }

    fun sendSimulatedEvent(scope: CoroutineScope) = run(scope) {
        val result = core().sendSimulatedEvent()
        message = "Evento enviado: ${result.accepted} aceptado(s), ${result.duplicates} duplicado(s)."
        append("Evento simulado · aceptados=${result.accepted} duplicados=${result.duplicates}")
    }

    fun revoke(scope: CoroutineScope) = run(scope) {
        val outcome = core().revokeLocally()
        message = if (outcome == AgentCore.RevokeOutcome.NOTIFIED) {
            "Consentimiento revocado y servidor notificado. No se enviarán más eventos."
        } else {
            "Consentimiento revocado en este dispositivo. El servidor no respondió; no se enviarán más eventos."
        }
        append("Revocación local · ${if (outcome == AgentCore.RevokeOutcome.NOTIFIED) "servidor notificado" else "solo local"}")
    }

    /** Permite volver a empezar el flujo con un código nuevo tras una revocación. */
    fun restartConsent() {
        store.clear()
        engine = null
        consent = null
        state = AgentCore.State.ConsentPending
        message = "Puedes aceptar de nuevo el consentimiento y usar un código nuevo."
    }

    private fun core(): AgentCore = engine ?: AgentCore(apiFactory(serverUrl), store).also { engine = it }

    private fun append(entry: String) {
        val stamp = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.now())
        log = (listOf("$stamp UTC · $entry") + log).take(20)
    }

    private fun run(scope: CoroutineScope, block: () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (error: ApiException) {
                message = describe(error)
                append("Error ${error.code}")
            } catch (error: IllegalStateException) {
                message = error.message ?: "Operación no permitida en este estado."
            } finally {
                state = engine?.state ?: initialState()
                busy = false
            }
        }
    }

    private fun describe(error: ApiException): String = when {
        error.isDeviceRevoked -> "El centro de control revocó este dispositivo. No se envían más eventos."
        error.isNetwork -> "Sin conexión con el servicio. Revisa la URL y la red."
        error.status == 401 -> "Credencial rechazada (${error.code})."
        error.isRetryable -> "Servicio no disponible (${error.code}). Inténtalo más tarde."
        else -> "${error.code}: ${error.message}"
    }

    companion object {
        // 10.0.2.2 es el host del equipo anfitrión visto desde el emulador Android.
        const val DEFAULT_URL = "http://10.0.2.2:8081"
    }
}
