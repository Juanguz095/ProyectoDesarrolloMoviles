package com.auditlab.common

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import javax.net.ssl.SSLException

/** Eventos propios, simulados o metadatos autorizados; nunca contenido de comunicaciones. */
data class EventDraft(
    val eventId: String,
    val timestamp: String,
    val category: String,
    val direction: String,
    val sourceDevice: String,
    val consent: ConsentContext,
    val metadata: Map<String, Any?>,
    val isSimulated: Boolean = true,
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
    fun devicePage(token: String, status: String?, cursor: String?, limit: Int = 50): DevicePage {
        check(cursor == null); return listDevices(token, status, limit)
    }
    fun eventPage(token: String, query: EventQuery, cursor: String?, limit: Int = 50): EventPage {
        check(cursor == null && query.copy(device = null) == EventQuery()); return listEvents(token, query.device, limit)
    }
    fun eventDetail(token: String, device: String, event: String): AuditEvent = throw UnsupportedOperationException()
    fun exportEvents(token: String, query: EventQuery): String = throw UnsupportedOperationException()
    fun auditPage(token: String, cursor: String? = null): AuditPage = throw UnsupportedOperationException()
    fun revokeDevice(accessToken: String, deviceId: String)
    fun revokeConsent(deviceToken: String, deviceId: String)
    fun beginWhatsApp(token: String, device: String, session: String, acceptedAt: String, consent: ConsentContext) { throw UnsupportedOperationException() }
    fun sendWhatsAppPreview(token: String, device: String, session: String, preview: WhatsAppPreview) { throw UnsupportedOperationException() }
    fun heartbeatWhatsApp(token: String, device: String, session: String) { throw UnsupportedOperationException() }
    fun endWhatsApp(token: String, device: String, session: String) { throw UnsupportedOperationException() }
    fun whatsAppPreviews(token: String, device: String): WhatsAppPreviewSnapshot = throw UnsupportedOperationException()
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
        val origin = URI.create(this.baseUrl)
        require(origin.host != null && origin.rawUserInfo == null && origin.rawQuery == null && origin.rawFragment == null &&
            (origin.rawPath.isNullOrEmpty() || origin.rawPath == "/")) { "Usa una URL de origen sin usuario, ruta, consulta ni fragmento." }
        val scheme = origin.scheme?.lowercase()
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
            serverUrl = baseUrl,
        )
    }

    override fun logout(accessToken: String) {
        request("POST", "/v1/auth/logout", token = accessToken)
    }
    override fun beginWhatsApp(token: String, device: String, session: String, acceptedAt: String, consent: ConsentContext) {
        request("POST", "/v1/devices/${encode(device)}/whatsapp-sharing", token, body=JSONObject()
            .put("sessionId", session).put("acceptedAt", acceptedAt).put("policyVersion", WhatsAppPreviewPolicy.VERSION)
            .put("consentContext", consent.toJson()).toString())
    }
    override fun sendWhatsAppPreview(token: String, device: String, session: String, preview: WhatsAppPreview) {
        request("POST", "/v1/devices/${encode(device)}/whatsapp-previews", token, body=JSONObject()
            .put("sessionId", session).put("messageId", preview.messageId).put("timestamp", preview.timestamp)
            .put("packageName", preview.packageName).put("title", preview.title).put("text", preview.text).toString())
    }
    override fun heartbeatWhatsApp(token: String, device: String, session: String) {
        request("POST", "/v1/devices/${encode(device)}/whatsapp-sharing/${encode(session)}/heartbeat", token)
    }
    override fun endWhatsApp(token: String, device: String, session: String) {
        request("DELETE", "/v1/devices/${encode(device)}/whatsapp-sharing/${encode(session)}", token)
    }
    override fun whatsAppPreviews(token: String, device: String): WhatsAppPreviewSnapshot {
        val value = JSONObject(request("GET", "/v1/devices/${encode(device)}/whatsapp-previews", token))
        val items = value.getJSONArray("items")
        return WhatsAppPreviewSnapshot(value.getBoolean("active"), value.optStringOrNull("sessionId"),
            (0 until items.length()).map { i -> items.getJSONObject(i).let { node ->
                WhatsAppPreview(node.getString("messageId"),node.getString("timestamp"),node.getString("packageName"),node.getString("title"),node.getString("text"))
            } })
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

    override fun devicePage(token: String, status: String?, cursor: String?, limit: Int): DevicePage {
        val node = JSONObject(request("GET", "/v1/devices" + queryString(mapOf("status" to status, "cursor" to cursor, "limit" to limit.toString())), token))
        return DevicePage(node.getJSONArray("items").toDevices(), node.optStringOrNull("nextCursor"))
    }
    override fun eventPage(token: String, query: EventQuery, cursor: String?, limit: Int): EventPage {
        val node = JSONObject(request("GET", "/v1/events" + queryString(filters(query) + mapOf("cursor" to cursor, "limit" to limit.toString())), token))
        return EventPage(node.getJSONArray("items").toEvents(), node.optStringOrNull("nextCursor"))
    }
    override fun eventDetail(token: String, device: String, event: String): AuditEvent =
        JSONArray().put(JSONObject(request("GET", "/v1/devices/${encode(device)}/events/${encode(event)}", token))).toEvents().single()
    override fun exportEvents(token: String, query: EventQuery): String =
        request("GET", "/v1/events/export.csv" + queryString(filters(query)), token, accept = "text/csv")
    override fun auditPage(token: String, cursor: String?): AuditPage {
        val node = JSONObject(request("GET", "/v1/audit-log" + queryString(mapOf("limit" to "50", "cursor" to cursor)), token))
        val items = node.getJSONArray("items")
        return AuditPage((0 until items.length()).map { i -> val n = items.getJSONObject(i)
            AuditRecord(n.getString("auditId"), n.getString("occurredAt"), n.getJSONObject("actor").toString(),
                n.getString("action"), n.getJSONObject("resource").toString(), n.getString("result"),
                n.getJSONObject("filters").toString(), n.getString("correlationId"))
        }, node.optStringOrNull("nextCursor"))
    }
    private fun filters(q: EventQuery) = mapOf("device" to q.device, "category" to q.category, "from" to q.from, "to" to q.to, "q" to q.q)
    private fun queryString(values: Map<String, String?>): String = values.filterValues { it != null }.entries
        .joinToString("&", prefix = "?") { (key, value) -> "$key=${encode(value!!)}" }

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
        accept: String = "application/json",
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
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", userAgent)
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { input ->
                val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer); if (size < 0) break
                    if (bytes.size() + size > 8 * 1024 * 1024) throw ApiException(0, "RESPONSE_TOO_LARGE", null, "Respuesta superior al límite local.")
                    bytes.write(buffer, 0, size)
                }
                bytes.toString("UTF-8")
            }.orEmpty()
            if (status !in 200..299) throw problem(status, text)
            if (accept == "application/json" && text.isNotBlank()) {
                try { JSONObject(text) } catch (_: Exception) { throw ApiException(0, "INVALID_RESPONSE", null, "Respuesta JSON inválida.") }
            }
            return text
        } catch (error: ApiException) {
            throw error
        } catch (error: SSLException) {
            throw ApiException(0, "TLS_ERROR", null, "El certificado o la conexión TLS fueron rechazados")
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
        .put("isSimulated", isSimulated)
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
