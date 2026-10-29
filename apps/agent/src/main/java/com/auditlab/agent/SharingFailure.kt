package com.auditlab.agent

import com.auditlab.common.ApiException
import kotlinx.coroutines.ensureActive

/** Actionable diagnosis without exposing server bodies, tokens or message text. */
internal fun sharingFailure(error: Exception): String = when {
    error is ApiException && error.isDeviceRevoked -> "El servidor revocó este dispositivo. Contacta al administrador."
    error is ApiException && error.code=="TLS_ERROR" -> "No se pudo verificar el certificado HTTPS. Comprueba la URL y la fecha/hora del teléfono."
    error is ApiException && error.isNetwork -> "No se pudo conectar al servidor. Comprueba Tailscale y abre la URL del servidor terminada en /ready desde este teléfono."
    error is ApiException && error.status==401 -> "El servidor rechazó la credencial del Agente (401). Comprueba que ambas apps usan el mismo servidor y que conservaste su base de datos."
    error is ApiException && error.status==403 -> "El servidor no autorizó el acceso de este dispositivo (403)."
    error is ApiException && error.status==404 -> "El servidor no encontró la ruta o sesión (404). Comprueba que usas el servidor actualizado y vuelve a iniciar la sesión desde el Agente."
    error is ApiException && error.status==409 -> "La sesión o su consentimiento ya no coincide con el servidor (409). Vuelve a iniciar la sesión local y comprueba que el dispositivo sigue inscrito."
    error is ApiException && error.status==400 -> "El servidor rechazó los datos (400). Comprueba fecha/hora automáticas y versiones compatibles de las apps y el servidor."
    error is ApiException && error.status==429 -> "El servidor limitó las solicitudes (429). Espera antes de iniciar otra sesión."
    error is ApiException && error.status>=500 -> "El servidor falló (${error.status}). Revisa su ventana de Windows antes de reintentar."
    error is java.io.IOException -> "Se interrumpió la conexión. Comprueba la red y Tailscale en este teléfono."
    else -> "No se pudo completar el envío. Comprueba conexión, permisos y versiones; vuelve a iniciar la sesión desde el Agente."
}


/** Retry only the existing authorized session; never restart capture or replay disk content. */
internal suspend fun sharingRequest(action: () -> Unit) {
    repeat(3) { attempt ->
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        try { action(); return }
        catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch(error: ApiException) {
            if(!error.isRetryable || attempt==2) throw error
            kotlinx.coroutines.delay((attempt+1)*1000L)
        }
    }
}
