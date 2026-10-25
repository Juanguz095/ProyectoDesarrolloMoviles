package com.auditlab.control

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.auditlab.common.ApiClient
import com.auditlab.common.ApiException
import com.auditlab.common.AuditApi
import com.auditlab.common.AuditEvent
import com.auditlab.common.ControlCore
import com.auditlab.common.DeviceSummary
import com.auditlab.common.EnrollmentCode
import com.auditlab.common.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Estado observable del centro de control. Las llamadas de red se ejecutan en IO y la UI recibe
 * resultados resumidos. El código de inscripción se muestra una sola vez y no se registra.
 */
class ControlSession internal constructor(
    private val store: SessionStore,
    private val apiFactory: (String) -> AuditApi,
) {
    constructor(context: Context) : this(KeystoreSessionStore(context), { url -> ApiClient(url) })

    private var engine: ControlCore? = null

    var serverUrl by mutableStateOf(DEFAULT_URL)
        private set
    var signedIn by mutableStateOf(store.load() != null)
        private set
    var username by mutableStateOf(store.load()?.username.orEmpty())
        private set
    var role by mutableStateOf(store.load()?.role.orEmpty())
        private set
    var isAdmin by mutableStateOf(store.load()?.role == "admin")
        private set
    var devices by mutableStateOf<List<DeviceSummary>>(emptyList())
        private set
    var events by mutableStateOf<List<AuditEvent>>(emptyList())
        private set
    var selectedDevice by mutableStateOf<String?>(null)
        private set
    var enrollmentCode by mutableStateOf<EnrollmentCode?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun updateServerUrl(value: String) {
        if (signedIn) return
        serverUrl = value
        engine = null
    }

    fun login(user: String, password: String, scope: CoroutineScope) = run(scope) {
        val session = core().login(user, password)
        signedIn = true
        username = session.username
        role = session.role
        isAdmin = session.role == "admin"
        message = "Sesión iniciada como ${session.username} (${session.role})."
        devices = core().devices().items
    }

    fun logout(scope: CoroutineScope) = run(scope) {
        core().logout()
        signedIn = false
        username = ""
        role = ""
        isAdmin = false
        devices = emptyList()
        events = emptyList()
        selectedDevice = null
        enrollmentCode = null
        message = "Sesión cerrada."
    }

    fun refresh(scope: CoroutineScope) = run(scope) {
        devices = core().devices().items
        val device = selectedDevice
        if (device != null) events = core().events(device).items
        message = "Actualizado: ${devices.size} dispositivo(s)."
    }

    fun selectDevice(deviceId: String, scope: CoroutineScope) = run(scope) {
        selectedDevice = deviceId
        events = core().events(deviceId).items
        message = "Eventos del dispositivo ${deviceId.take(8)}…: ${events.size}."
    }

    fun createEnrollmentCode(scope: CoroutineScope) = run(scope) {
        enrollmentCode = core().createEnrollmentCode()
        message = "Código creado. Es de un solo uso y vence en 10 minutos."
    }

    fun revoke(deviceId: String, scope: CoroutineScope) = run(scope) {
        core().revoke(deviceId)
        devices = core().devices().items
        message = "Dispositivo revocado. Los nuevos lotes serán rechazados."
    }

    private fun core(): ControlCore = engine ?: ControlCore(apiFactory(serverUrl), store).also { engine = it }

    private fun run(scope: CoroutineScope, block: () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (error: ApiException) {
                message = describe(error)
                if (error.isUnauthenticated) {
                    // La sesión ya no es válida (refresh fallido o revocada): volver al login.
                    signedIn = false
                    username = ""
                    role = ""
                    isAdmin = false
                    devices = emptyList()
                    events = emptyList()
                    selectedDevice = null
                }
            } catch (error: IllegalStateException) {
                message = error.message ?: "Operación no permitida."
            } finally {
                busy = false
            }
        }
    }

    private fun describe(error: ApiException): String = when {
        error.isNetwork -> "Sin conexión con el servicio. Revisa la URL y la red."
        error.status == 401 -> "Credenciales rechazadas o sesión vencida."
        error.status == 403 -> "Tu rol no permite esta operación."
        error.isRetryable -> "Servicio no disponible (${error.code}). Inténtalo más tarde."
        else -> "${error.code}: ${error.message}"
    }

    companion object {
        const val DEFAULT_URL = "http://10.0.2.2:8081"
    }
}
