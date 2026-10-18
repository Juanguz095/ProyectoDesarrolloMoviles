package com.auditlab.api

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.sql.SQLException
import java.util.UUID

internal val json = ObjectMapper(JsonFactory.builder()
    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(16384).build()).build())
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

internal fun bearer(header: String?): String {
    if (header == null || !Regex("(?i:Bearer) [A-Za-z0-9_.-]{32,4096}").matches(header)) throw ApiFailure(401, "UNAUTHENTICATED")
    return header.substringAfter(' ')
}

/** Shared response envelope for auth and subsequent protected routes. Never echo exception messages. */
internal suspend fun ApplicationCall.api(block: suspend (UUID) -> Unit) {
    val correlation = UUID.randomUUID()
    response.headers.append("X-Correlation-ID", correlation.toString())
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    try {
        block(correlation)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val failure = when (error) {
            is ApiFailure -> error
            is SQLException -> ApiFailure(503, "SERVICE_UNAVAILABLE")
            else -> ApiFailure(500, "INTERNAL_ERROR")
        }
        if (failure.status == 401) response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        failure.retryAfter?.let { response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
        val title = when (failure.status) {
            400 -> "Solicitud inválida."
            401 -> "Autenticación inválida, expirada o revocada."
            403 -> "Rol sin permiso para esta operación."
            429 -> "Límite de solicitudes; respetar Retry-After."
            503 -> "Servicio temporalmente no disponible."
            else -> "Error interno."
        }
        respondText(json.writeValueAsString(mapOf("type" to "about:blank", "title" to title, "status" to failure.status,
            "code" to failure.code, "correlationId" to correlation.toString())), ContentType.parse("application/problem+json"), HttpStatusCode.fromValue(failure.status))
    }
}

private suspend fun ApplicationCall.bodyBytes(): ByteArray {
    val channel = receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(2048)
    while (true) {
        val read = channel.readAvailable(buffer)
        if (read == -1) break
        if (read == 0) continue
        if (output.size() + read > 16384) throw ApiFailure(400, "INVALID_REQUEST")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private suspend fun ApplicationCall.objectBody(fields: Set<String>): JsonNode {
    if (request.queryParameters.names().isNotEmpty() || request.headers[HttpHeaders.ContentEncoding] != null) throw ApiFailure(400, "INVALID_REQUEST")
    val type = runCatching { request.contentType() }.getOrNull()
    if (type?.withoutParameters() != ContentType.Application.Json ||
        (type.parameter("charset")?.lowercase()?.let { it != "utf-8" } == true)) throw ApiFailure(400, "INVALID_REQUEST")
    val bytes = bodyBytes()
    val node = try {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        json.readTree(text)
    } catch (_: Exception) { throw ApiFailure(400, "INVALID_JSON") }
    if (node == null || !node.isObject || node.fieldNames().asSequence().toSet() != fields || fields.any { !node[it].isTextual })
        throw ApiFailure(400, "INVALID_REQUEST")
    return node
}

fun Route.authRoutes(auth: AuthService?) {
    post("/v1/auth/login") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            withContext(Dispatchers.IO) { service.limitRequest("login", call.request.local.remoteHost) }
            val body = call.objectBody(setOf("username", "password"))
            val username = body["username"].textValue(); val password = body["password"].textValue()
            if (!Regex("[A-Za-z0-9][A-Za-z0-9_.-]{2,63}").matches(username) || password.codePointCount(0, password.length) !in 1..128)
                throw ApiFailure(400, "INVALID_REQUEST")
            val result = withContext(Dispatchers.IO) { service.login(username, password, correlation) }
            call.respondText(json.writeValueAsString(result), ContentType.Application.Json)
        }
    }
    post("/v1/auth/refresh") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            withContext(Dispatchers.IO) { service.limitRequest("refresh", call.request.local.remoteHost) }
            val body = call.objectBody(setOf("refreshToken"))
            val token = body["refreshToken"].textValue()
            if (token.codePointCount(0, token.length) !in 32..4096) throw ApiFailure(400, "INVALID_REQUEST")
            val result = withContext(Dispatchers.IO) { service.refresh(token, correlation) }
            call.respondText(json.writeValueAsString(result), ContentType.Application.Json)
        }
    }
    post("/v1/auth/logout") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            if (call.request.queryParameters.names().isNotEmpty() || call.bodyBytes().isNotEmpty()) throw ApiFailure(400, "INVALID_REQUEST")
            withContext(Dispatchers.IO) { service.logout(token, correlation) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
