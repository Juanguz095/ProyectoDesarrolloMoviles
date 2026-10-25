package com.auditlab.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/** Shared helpers for the definitive API checks. Every test uses the isolated PostgreSQL fixture. */
abstract class ApiTestSupport {
    protected val mapper = ObjectMapper()
    protected val key = ByteArray(32).also(SecureRandom()::nextBytes)
    protected val config = AuthConfig(key, accountLimit = 100, clientLimit = 1000, globalLimit = 10000,
        refreshClientLimit = 1000, refreshGlobalLimit = 10000)
    protected val password: String get() = System.getenv("AUDITLAB_BOOTSTRAP_PASSWORD")
    protected val owner get() = TestDatabase.owner
    protected val runtime get() = TestDatabase.runtime

    protected fun http(block: suspend ApplicationTestBuilder.(AuthService) -> Unit) = testApplication {
        val database = Database(runtime)
        val service = AuthService(database, config)
        application { module(database, config, retentionDays = 30) }
        try { block(service) } finally { database.close() }
    }

    protected fun sql(statement: String, vararg values: Any?) = DatabaseTools.connection(owner) { connection ->
        connection.prepareStatement(statement).use { query ->
            values.forEachIndexed { index, value -> query.setObject(index + 1, value) }
            query.executeUpdate()
        }
    }

    protected fun <T> inspect(action: (Connection) -> T): T = DatabaseTools.connection(owner, action)

    protected suspend fun ApplicationTestBuilder.login(username: String): String {
        val response = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(mapper.writeValueAsString(mapOf("username" to username, "password" to password)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return mapper.readTree(response.bodyAsText())["accessToken"].textValue()
    }

    protected suspend fun ApplicationTestBuilder.problem(response: HttpResponse, status: Int, code: String? = null): JsonNode {
        assertEquals(status, response.status.value, response.bodyAsText())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/problem+json"))
        val data = mapper.readTree(response.bodyAsText())
        assertEquals(status, data["status"].intValue())
        assertEquals(response.headers["X-Correlation-ID"], data["correlationId"].textValue())
        if (code != null) assertEquals(code, data["code"].textValue())
        return data
    }

    protected suspend fun ApplicationTestBuilder.createCode(admin: String): JsonNode {
        val response = client.post("/v1/devices/enrollment-code") { bearerAuth(admin) }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        return mapper.readTree(response.bodyAsText())
    }

    protected suspend fun ApplicationTestBuilder.enroll(admin: String, appVersion: String? = "0.1.0-test",
                                                       consentId: UUID = UUID.randomUUID()): JsonNode {
        val code = createCode(admin)["code"].textValue()
        return enrollWithCode(code, appVersion, consentId)
    }

    protected suspend fun ApplicationTestBuilder.enrollWithCode(code: String, appVersion: String? = null,
                                                               consentId: UUID = UUID.randomUUID(),
                                                               policyVersion: String = "policy-1"): JsonNode {
        val body = mutableMapOf<String, Any?>(
            "consentAcknowledged" to true,
            "consent" to mapOf(
                "consentId" to consentId.toString(),
                "policyVersion" to policyVersion,
                "acceptedAt" to Instant.now().minusSeconds(1).toString(),
            ),
        )
        if (appVersion != null) body["appVersion"] = appVersion
        val response = client.post("/v1/devices/enroll") {
            header("X-Enrollment-Code", code)
            contentType(ContentType.Application.Json)
            setBody(mapper.writeValueAsString(body))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return mapper.readTree(response.bodyAsText())
    }

    protected suspend fun ApplicationTestBuilder.enrollResponse(code: String, body: Any): HttpResponse = client.post("/v1/devices/enroll") {
        header("X-Enrollment-Code", code)
        contentType(ContentType.Application.Json)
        setBody(mapper.writeValueAsString(body))
    }

    protected fun consentOf(device: JsonNode): Map<String, String> = mapOf(
        "consentId" to device["consentContext"]["consentId"].textValue(),
        "policyVersion" to device["consentContext"]["policyVersion"].textValue(),
        "acceptedAt" to device["consentContext"]["acceptedAt"].textValue(),
    )

    protected fun event(device: JsonNode, category: String = "app_lifecycle", direction: String = "occurred",
                        eventId: UUID = UUID.randomUUID(), timestamp: Instant = Instant.now(),
                        metadata: Map<String, Any?> = emptyMap(),
                        consent: Map<String, String> = consentOf(device)): Map<String, Any?> {
        val base = mutableMapOf<String, Any?>(
            "eventId" to eventId.toString(),
            "timestamp" to timestamp.toString(),
            "category" to category,
            "direction" to direction,
            "sourceDevice" to device["deviceId"].textValue(),
            "consentContext" to consent,
            "isSimulated" to true,
        )
        if (metadata.isNotEmpty()) base["metadata"] = metadata
        return base
    }

    protected suspend fun ApplicationTestBuilder.sendEvents(token: String, vararg events: Map<String, Any?>): HttpResponse =
        client.post("/v1/events") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(mapper.writeValueAsString(mapOf("events" to events.toList())))
        }

    protected suspend fun json(response: HttpResponse): JsonNode = mapper.readTree(response.bodyAsText())

    protected fun assertUuid(value: String) {
        assertNotNull(UUID.fromString(value))
    }
}
