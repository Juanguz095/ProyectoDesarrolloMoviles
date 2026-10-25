package com.auditlab.common

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Un evento que el agente quiere enviar. `isSimulated` es siempre true por diseño. */
data class EventDraft(
    val eventId: String,
    val timestamp: String,
    val category: String,
    val direction: String,
    val sourceDevice: String,
    val consent: ConsentContext,
    val metadata: Map<String, Any?>,
)

/** Superficie usada por las apps; permite probar la lógica sin red. */
interface AuditApi {
    fun login(username: String, password: String): Session
    fun refresh(refreshToken: String): Session
    fun logout(accessToken: String)
    fun createEnrollmentCode(accessToken: String): EnrollmentCode
    fun enroll(code: String, consent: ConsentContext, appVersion: String): EnrolledDevice
    fun sendEvents(deviceToken: String, events: List<EventDraft>): IngestResult
    fun listDevices(accessToken: String, status: String? = null, limit: Int = 50): DevicePage
    fun listEvents(accessToken: String, deviceId: String? = null, limit: Int = 50): EventPage
    fun revokeDevice(accessToken: String, deviceId: String)
    fun revokeConsent(deviceToken: String, deviceId: String)
}

/**
 * Cliente HTTP de la API definitiva. Usa `HttpURLConnection` porque existe tanto en Android como
 * en la JVM, lo que permite probar el mismo código fuera del dispositivo.
 *
 * Fuera de loopback solo se admite HTTPS: la app no debe hablar HTTP en claro con un servidor real.
 */
class ApiClient(
    baseUrl: String,
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 10_000,
    private val userAgent: String = "AuditLab-Android/0.1",
) : AuditApi {

    val baseUrl: String = baseUrl.trim().trimEnd('/')

    init {
        require(this.baseUrl.isNotEmpty()) { "La URL base es obligatoria" }
        val scheme = this.baseUrl.substringBefore("://", "").lowercase()
        require(scheme == "https" || (scheme == "http" && isLoopback(this.baseUrl))) {
            "Solo HTTPS fuera de loopback (127.0.0.1, localhost o 10.0.2.2)"
        }
    }

    override fun login(username: String, password: String): Session =
        session(JSONObject().put("username", username).put("password", password), "/v1/auth/login")

    override fun refresh(refreshToken: String): Session =
        session(JSONObject().put("refreshToken", refreshToken), "/v1/auth/refresh")

    private fun session(body: JSONObject, path: String): Session {
        val node = JSONObject(request("POST", path, body = body.toString()))
        return Session(
            accessToken = node.getString("accessToken"),
            refreshToken = node.getString("refreshToken"),
            tokenType = node.optString("tokenType", "Bearer"),
            expiresIn = node.optLong("expiresIn"),
            sessionId = node.getString("sessionId"),
            userId = node.getJSONObject("user").getString("userId"),
            username = node.getJSONObject("user").getString("username"),
            role = node.getJSONObject("user").getString("role"),
        )
    }

    override fun logout(accessToken: String) {
        request("POST", "/v1/auth/logout", token = accessToken)
    }

    override fun createEnrollmentCode(accessToken: String): EnrollmentCode {
        val node = JSONObject(request("POST", "/v1/devices/enrollment-code", token = accessToken))
        return EnrollmentCode(
            code = node.getString("code"),
            expiresIn = node.optLong("expiresIn"),
            expiresAt = node.getString("expiresAt"),
        )
    }

    override fun enroll(code: String, consent: ConsentContext, appVersion: String): EnrolledDevice {
        require(code.length in 32..4096) { "El código de inscripción no tiene un tamaño válido" }
        val body = JSONObject()
            .put("consentAcknowledged", true)
            .put("consent", consent.toJson())
            .put("appVersion", appVersion)
        val node = JSONObject(
            request("POST", "/v1/devices/enroll", body = body.toString(), headers = mapOf("X-Enrollment-Code" to code)),
        )
        return EnrolledDevice(
            deviceId = node.getString("deviceId"),
            deviceToken = node.getString("deviceToken"),
            consent = node.getJSONObject("consentContext").toConsent(),
            enrolledAt = node.getString("enrolledAt"),
            status = node.optString("status", "active"),
        )
    }

    override fun sendEvents(deviceToken: String, events: List<EventDraft>): IngestResult {
        require(events.isNotEmpty()) { "El lote no puede estar vacío" }
        val array = JSONArray()
        events.forEach { array.put(it.toJson()) }
        val node = JSONObject(request("POST", "/v1/events", token = deviceToken,
            body = JSONObject().put("events", array).toString()))
        return IngestResult(accepted = node.optInt("accepted"), duplicates = node.optInt("duplicates"))
    }

    override fun listDevices(accessToken: String, status: String?, limit: Int): DevicePage {
        val query = buildString {
            append("?limit=").append(limit.coerceIn(1, 100))
            if (status != null) append("&status=").append(encode(status))
        }
        val node = JSONObject(request("GET", "/v1/devices$query", token = accessToken))
        return DevicePage(node.getJSONArray("items").toDevices(), node.optStringOrNull("nextCursor"))
    }

    override fun listEvents(accessToken: String, deviceId: String?, limit: Int): EventPage {
        val query = buildString {
            append("?limit=").append(limit.coerceIn(1, 100))
            if (deviceId != null) append("&device=").append(encode(deviceId))
        }
        val node = JSONObject(request("GET", "/v1/events$query", token = accessToken))
        return EventPage(node.getJSONArray("items").toEvents(), node.optStringOrNull("nextCursor"))
    }

    override fun revokeDevice(accessToken: String, deviceId: String) {
        request("POST", "/v1/devices/${encode(deviceId)}/revoke", token = accessToken)
    }

    override fun revokeConsent(deviceToken: String, deviceId: String) {
        request("POST", "/v1/devices/${encode(deviceId)}/consent-revoke", token = deviceToken)
    }

    private fun request(
        method: String,
        path: String,
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): String {
        val connection = try {
            URI.create(baseUrl + path).toURL().openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw ApiException(0, "NETWORK_ERROR", null, "No se pudo abrir la conexión")
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", userAgent)
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val text = (if (status >= 400) connection.errorStream else connection.inputStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status >= 400) throw problem(status, text)
            return text
        } catch (error: ApiException) {
            throw error
        } catch (error: IOException) {
            throw ApiException(0, "NETWORK_ERROR", null, "Sin respuesta del servicio")
        } finally {
            connection.disconnect()
        }
    }

    private fun problem(status: Int, text: String): ApiException {
        val node = runCatching { JSONObject(text) }.getOrNull()
        return ApiException(
            status = status,
            code = node?.optString("code")?.takeIf { it.isNotEmpty() } ?: "HTTP_$status",
            correlationId = node?.optStringOrNull("correlationId"),
            message = node?.optString("title")?.takeIf { it.isNotEmpty() } ?: "Solicitud rechazada",
        )
    }

    private fun ConsentContext.toJson(): JSONObject = JSONObject()
        .put("consentId", consentId)
        .put("policyVersion", policyVersion)
        .put("acceptedAt", acceptedAt)

    private fun EventDraft.toJson(): JSONObject = JSONObject()
        .put("eventId", eventId)
        .put("timestamp", timestamp)
        .put("category", category)
        .put("direction", direction)
        .put("sourceDevice", sourceDevice)
        .put("consentContext", consent.toJson())
        .put("isSimulated", true)
        .put("metadata", JSONObject(metadata))

    companion object {
        private val LOOPBACK = setOf("127.0.0.1", "localhost", "10.0.2.2", "::1", "[::1]")

        fun isLoopback(url: String): Boolean = try {
            URI.create(url).host?.lowercase()?.let { it in LOOPBACK } == true
        } catch (_: Exception) {
            false
        }

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}

private fun JSONObject.toConsent(): ConsentContext = ConsentContext(
    consentId = getString("consentId"),
    policyVersion = getString("policyVersion"),
    acceptedAt = getString("acceptedAt"),
)

private fun JSONObject.optStringOrNull(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun JSONArray.toDevices(): List<DeviceSummary> = (0 until length()).map { index ->
    val node = getJSONObject(index)
    DeviceSummary(
        deviceId = node.getString("deviceId"),
        status = node.getString("status"),
        lastSeenAt = node.optStringOrNull("lastSeenAt"),
        enrolledAt = node.getString("enrolledAt"),
        revokedAt = node.optStringOrNull("revokedAt"),
        consent = node.getJSONObject("consentContext").toConsent(),
        appVersion = node.optStringOrNull("appVersion"),
    )
}

private fun JSONArray.toEvents(): List<AuditEvent> = (0 until length()).map { index ->
    val node = getJSONObject(index)
    AuditEvent(
        eventId = node.getString("eventId"),
        timestamp = node.getString("timestamp"),
        category = node.getString("category"),
        direction = node.getString("direction"),
        sourceDevice = node.getString("sourceDevice"),
        consent = node.getJSONObject("consentContext").toConsent(),
        isSimulated = node.getBoolean("isSimulated"),
        metadata = node.optJSONObject("metadata")?.toPlainMap().orEmpty(),
        receivedAt = node.getString("receivedAt"),
    )
}

private fun JSONObject.toPlainMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
    when (val value = get(key)) {
        is JSONObject -> value.toPlainMap()
        is JSONArray -> (0 until value.length()).map { value.get(it) }
        JSONObject.NULL -> null
        else -> value
    }
}
