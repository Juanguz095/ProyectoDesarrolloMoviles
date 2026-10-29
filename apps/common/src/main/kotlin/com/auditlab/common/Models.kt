package com.auditlab.common

/**
 * Tipos del contrato 0.2 que comparten el agente y el centro de control.
 * Se mantienen como datos inmutables para que la lógica de juego/UI no dependa de JSON.
 */

data class ConsentContext(
    val consentId: String,
    val policyVersion: String,
    val acceptedAt: String,
)

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val sessionId: String,
    val userId: String,
    val username: String,
    val role: String,
    val serverUrl: String? = null,
) {
    override fun toString() = "Session(userId=$userId, role=$role, tokens=[redacted])"
}

data class EnrolledDevice(
    val deviceId: String,
    val deviceToken: String,
    val consent: ConsentContext,
    val enrolledAt: String,
    val status: String = "active",
) { override fun toString() = "EnrolledDevice(deviceId=$deviceId, token=[redacted])" }

data class EnrollmentCode(
    val code: String,
    val expiresIn: Long,
    val expiresAt: String,
) { override fun toString() = "EnrollmentCode(code=[redacted], expiresAt=$expiresAt)" }

data class DeviceSummary(
    val deviceId: String,
    val status: String,
    val lastSeenAt: String?,
    val enrolledAt: String,
    val revokedAt: String?,
    val consent: ConsentContext,
    val appVersion: String?,
) {
    val isActive: Boolean get() = status == "active"
}

data class DevicePage(val items: List<DeviceSummary>, val nextCursor: String?)

/** Un evento tal como lo devuelve la API; solo metadatos permitidos. */
data class AuditEvent(
    val eventId: String,
    val timestamp: String,
    val category: String,
    val direction: String,
    val sourceDevice: String,
    val consent: ConsentContext,
    val isSimulated: Boolean,
    val metadata: Map<String, Any?>,
    val receivedAt: String,
)

data class EventPage(val items: List<AuditEvent>, val nextCursor: String?)

/** Resultado de un lote: cuántos se aceptaron y cuántos eran reintentos idempotentes. */
data class IngestResult(val accepted: Int, val duplicates: Int)

/**
 * Error uniforme de la API (`application/problem+json`).
 * `status == 0` significa que no hubo respuesta (red, TLS o tiempo de espera).
 */
class ApiException(
    val status: Int,
    val code: String,
    val correlationId: String?,
    message: String,
) : Exception(message) {
    val isNetwork: Boolean get() = status == 0
    val isUnauthenticated: Boolean get() = status == 401 && code == "UNAUTHENTICATED"
    val isDeviceRevoked: Boolean get() = status == 401 && code == "DEVICE_REVOKED"
    val isRetryable: Boolean get() = code !in setOf("TLS_ERROR", "INVALID_RESPONSE", "RESPONSE_TOO_LARGE") &&
        (status == 429 || status >= 500 || isNetwork)
}

/** Immutable filters: changing one always starts a fresh server cursor. */
data class EventQuery(val device: String? = null, val category: String? = null,
    val from: String? = null, val to: String? = null, val q: String? = null) {
    init {
        require(category == null || category == "communication" || category in EventPolicy.categories)
        require(q == null || q.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Busca un ID o código de evento (máximo 64 caracteres)." }
        val start = from?.let(java.time.Instant::parse)
        val end = to?.let(java.time.Instant::parse)
        require(start == null || end == null || start < end) { "La fecha inicial debe ser anterior a la final." }
    }
}
data class AuditRecord(val auditId: String, val occurredAt: String, val actor: String,
    val action: String, val resource: String, val result: String, val filters: String = "{}", val correlationId: String = "")
data class AuditPage(val items: List<AuditRecord>, val nextCursor: String?)
