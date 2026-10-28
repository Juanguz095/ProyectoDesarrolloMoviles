package com.auditlab.common

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/** Prueba el cliente real contra un servidor HTTP local; no usa la red externa ni datos reales. */
class ApiClientTest {
    private lateinit var server: TestServer

    @BeforeEach
    fun start() {
        server = TestServer()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    @Test
    fun `rechaza HTTP fuera de loopback`() {
        assertThrows(IllegalArgumentException::class.java) { ApiClient("http://192.168.1.20:8081") }
        assertThrows(IllegalArgumentException::class.java) { ApiClient("ftp://127.0.0.1") }
        // Loopback y HTTPS sí están permitidos.
        ApiClient("http://127.0.0.1:8081")
        ApiClient("http://10.0.2.2:8081")
        ApiClient("https://auditlab.example.invalid")
    }

    @Test
    fun `login envia credenciales en el cuerpo y lee la sesion`() {
        server.response = """
            {"accessToken":"a.b.c","refreshToken":"r","tokenType":"Bearer","expiresIn":900,
             "sessionId":"s-1","user":{"userId":"u-1","username":"admin","role":"admin"}}
        """.trimIndent()
        val client = ApiClient(server.baseUrl)
        val session = client.login("admin", "secreto")
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/login", request.path)
        assertEquals("admin", JSONObject(request.body).getString("username"))
        assertEquals("secreto", JSONObject(request.body).getString("password"))
        assertEquals("admin", session.role)
        assertEquals(900, session.expiresIn)
        // La contraseña nunca viaja en la URL.
        assertTrue(!request.path.contains("secreto"))
    }

    @Test
    fun `la inscripcion usa la cabecera del codigo y no la URL`() {
        server.response = """
            {"deviceId":"d-1","deviceToken":"dev_token","status":"active",
             "consentContext":{"consentId":"c-1","policyVersion":"mvp-1","acceptedAt":"2026-10-24T10:00:00Z"},
             "enrolledAt":"2026-10-24T10:00:01Z"}
        """.trimIndent()
        val client = ApiClient(server.baseUrl)
        val consent = ConsentContext("c-1", "mvp-1", "2026-10-24T10:00:00Z")
        val device = client.enroll("x".repeat(40), consent, "0.1.0-test")
        val request = server.requests.single()
        assertEquals("/v1/devices/enroll", request.path)
        assertEquals("x".repeat(40), request.headers["x-enrollment-code"])
        assertTrue(!request.path.contains("x".repeat(40)))
        val body = JSONObject(request.body)
        assertTrue(body.getBoolean("consentAcknowledged"))
        assertEquals("mvp-1", body.getJSONObject("consent").getString("policyVersion"))
        assertEquals("0.1.0-test", body.getString("appVersion"))
        assertEquals("d-1", device.deviceId)
    }

    @Test
    fun `real SMS keeps false on the wire`() {
        server.response = """{"accepted":1,"duplicates":0}"""
        val event = draft().copy(category = "sms", direction = "outbound", metadata = mapOf("appEventCode" to "sms_sent"), isSimulated = false)
        ApiClient(server.baseUrl).sendEvents("fixture-only", listOf(event))
        val node = JSONObject(server.requests.single().body).getJSONArray("events").getJSONObject(0)
        assertEquals(false, node.getBoolean("isSimulated"))
        assertEquals("sms_sent", node.getJSONObject("metadata").getString("appEventCode"))
    }

    @Test
    fun `envia un lote simulado y lee accepted y duplicates`() {
        server.response = """{"accepted":1,"duplicates":0}"""
        val client = ApiClient(server.baseUrl)
        val draft = EventDraft(
            eventId = "e-1",
            timestamp = "2026-10-24T10:00:00Z",
            category = "app_lifecycle",
            direction = "occurred",
            sourceDevice = "d-1",
            consent = ConsentContext("c-1", "mvp-1", "2026-10-24T09:59:00Z"),
            metadata = mapOf("appEventCode" to "agent_started"),
        )
        val result = client.sendEvents("dev_token", listOf(draft))
        val request = server.requests.single()
        assertEquals("Bearer dev_token", request.headers["authorization"])
        val events = JSONObject(request.body).getJSONArray("events")
        assertEquals(1, events.length())
        assertTrue(events.getJSONObject(0).getBoolean("isSimulated"))
        assertEquals("agent_started", events.getJSONObject(0).getJSONObject("metadata").getString("appEventCode"))
        assertEquals(1, result.accepted)
        assertEquals(0, result.duplicates)
    }

    @Test
    fun `un problem json se convierte en ApiException con codigo y correlacion`() {
        server.status = 401
        server.contentType = "application/problem+json"
        server.response = """{"type":"about:blank","title":"No autorizado","status":401,
            "code":"DEVICE_REVOKED","correlationId":"corr-1"}"""
        val client = ApiClient(server.baseUrl)
        val error = assertThrows(ApiException::class.java) {
            client.sendEvents("dev_token", listOf(draft()))
        }
        assertEquals(401, error.status)
        assertEquals("DEVICE_REVOKED", error.code)
        assertEquals("corr-1", error.correlationId)
        assertTrue(error.isDeviceRevoked)
    }

    @Test
    fun `una respuesta sin red informa status cero`() {
        val client = ApiClient("http://127.0.0.1:1", connectTimeoutMillis = 300, readTimeoutMillis = 300)
        val error = assertThrows(ApiException::class.java) { client.login("admin", "secreto") }
        assertEquals(0, error.status)
        assertEquals("NETWORK_ERROR", error.code)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `lista dispositivos y eventos con filtros`() {
        server.response = """
            {"items":[{"deviceId":"d-1","status":"active","lastSeenAt":null,
              "enrolledAt":"2026-10-24T10:00:00Z","revokedAt":null,
              "consentContext":{"consentId":"c-1","policyVersion":"mvp-1","acceptedAt":"2026-10-24T09:59:00Z"},
              "appVersion":"0.1.0-test"}],"nextCursor":null}
        """.trimIndent()
        val client = ApiClient(server.baseUrl)
        val page = client.listDevices("token", status = "active", limit = 10)
        assertEquals("/v1/devices?limit=10&status=active", server.requests.single().path)
        assertEquals(1, page.items.size)
        assertTrue(page.items.single().isActive)
        assertNull(page.items.single().lastSeenAt)
        assertNull(page.nextCursor)
    }

    @Test
    fun `revoca el consentimiento propio sin exponer la credencial en la URL`() {
        server.status = 204
        server.response = ""
        val client = ApiClient(server.baseUrl)
        client.revokeConsent("dev_token", "d-1")
        val request = server.requests.single()
        assertEquals("/v1/devices/d-1/consent-revoke", request.path)
        assertEquals("Bearer dev_token", request.headers["authorization"])
        assertTrue(!request.path.contains("dev_token"))
    }

    private fun draft() = EventDraft(
        eventId = "e-1",
        timestamp = "2026-10-24T10:00:00Z",
        category = "app_lifecycle",
        direction = "occurred",
        sourceDevice = "d-1",
        consent = ConsentContext("c-1", "mvp-1", "2026-10-24T09:59:00Z"),
        metadata = mapOf("appEventCode" to "agent_started"),
    )

    @Test fun `base origin rejects embedded credentials paths query and fragment`() {
        listOf("https://user:secret@host.invalid", "https://host.invalid/path", "https://host.invalid?x=1", "https://host.invalid#part").forEach {
            assertThrows(IllegalArgumentException::class.java) { ApiClient(it) }
        }
    }
    @Test fun `filter and pagination query is encoded and CSV uses the same filters`() {
        val client = ApiClient(server.baseUrl)
        server.response = """{"items":[],"nextCursor":"opaque-cursor"}"""
        val query = EventQuery("d-1", "sync", "2026-10-25T10:00:00Z", "2026-10-26T10:00:00Z", "sync_failed")
        assertEquals("opaque-cursor", client.eventPage("token", query, "cursor+value").nextCursor)
        assertTrue(server.requests.last().path.contains("cursor=cursor%2Bvalue"))
        server.response = "eventId,timestamp\n"; server.contentType = "text/csv"
        assertEquals(server.response, client.exportEvents("token", query))
        val path = server.requests.last().path
        assertTrue(path.startsWith("/v1/events/export.csv?device=d-1&category=sync"))
        assertTrue(path.contains("q=sync_failed")); assertTrue(!path.contains("cursor="))
    }
    @Test fun `malformed JSON and oversized responses have permanent errors`() {
        val client = ApiClient(server.baseUrl)
        server.response = "not-json"
        assertEquals("INVALID_RESPONSE", assertThrows(ApiException::class.java) { client.listDevices("token") }.code)
        server.response = "x".repeat(8 * 1024 * 1024 + 1)
        val error = assertThrows(ApiException::class.java) { client.listDevices("token") }
        assertEquals("RESPONSE_TOO_LARGE", error.code); assertTrue(!error.isRetryable)
    }
    private class TestServer : AutoCloseable {
        data class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: String)

        var status = 200
        var contentType = "application/json"
        var response = "{}"
        val requests = mutableListOf<Recorded>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                requests += Recorded(
                    exchange.requestMethod,
                    exchange.requestURI.toString(),
                    exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                    body,
                )
                val bytes = response.toByteArray()
                exchange.responseHeaders.add("Content-Type", contentType)
                if (status == 204) exchange.sendResponseHeaders(204, -1)
                else {
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            server.start()
        }

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        override fun close() = server.stop(0)
    }
}
