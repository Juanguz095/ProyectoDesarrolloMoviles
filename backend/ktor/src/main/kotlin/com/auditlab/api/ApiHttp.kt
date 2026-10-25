package com.auditlab.api

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Reads a bounded body. Oversized batches report 413; smaller envelopes report 400. */
internal suspend fun ApplicationCall.readLimitedBody(limit: Int): ByteArray {
    val channel = receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val read = channel.readAvailable(buffer)
        if (read == -1) break
        if (read == 0) continue
        if (output.size() + read > limit) throw ApiFailure(if (limit > 16384) 413 else 400, if (limit > 16384) "PAYLOAD_TOO_LARGE" else "INVALID_REQUEST")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun ApplicationCall.requireJsonContentType() {
    val type = runCatching { request.contentType() }.getOrNull()
    if (type?.withoutParameters() != ContentType.Application.Json ||
        (type.parameter("charset")?.lowercase()?.let { it != "utf-8" } == true)) throw ApiFailure(400, "INVALID_REQUEST")
}

/** Strict JSON object: valid UTF-8, no duplicate keys, no trailing content, bounded nesting and strings. */
internal suspend fun ApplicationCall.strictObject(limit: Int = 16384): JsonNode = parseObject(jsonBytes(limit))

/** Validates the JSON envelope and reads it, so authentication can happen before parsing. */
internal suspend fun ApplicationCall.jsonBytes(limit: Int = 16384): ByteArray {
    if (request.queryParameters.names().isNotEmpty() || request.headers[HttpHeaders.ContentEncoding] != null) throw ApiFailure(400, "INVALID_REQUEST")
    requireJsonContentType()
    return readLimitedBody(limit)
}

internal fun parseObject(bytes: ByteArray): JsonNode {
    val node = try {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        json.readTree(text)
    } catch (_: Exception) { throw ApiFailure(400, "INVALID_JSON") }
    if (node == null || !node.isObject) throw ApiFailure(400, "INVALID_REQUEST")
    return node
}

internal fun ApplicationCall.requireNoQuery(vararg allowed: String) {
    val unexpected = request.queryParameters.names().filter { it !in allowed }
    if (unexpected.isNotEmpty()) throw ApiFailure(400, "INVALID_REQUEST")
}

/** Rejects repeated query parameters so one request cannot carry two different filters. */
internal fun ApplicationCall.queryMap(): Map<String, String> {
    val parameters = request.queryParameters
    val values = HashMap<String, String>()
    for (name in parameters.names()) {
        val all = parameters.getAll(name) ?: continue
        if (all.size != 1) throw ApiFailure(400, "INVALID_REQUEST")
        values[name] = all.single()
    }
    return values
}

internal fun queryLimit(raw: String?, default: Int = 50): Int {
    if (raw == null) return default
    if (!Regex("[0-9]{1,3}").matches(raw)) throw ApiFailure(400, "INVALID_REQUEST")
    val limit = raw.toInt()
    if (limit !in 1..100) throw ApiFailure(400, "INVALID_REQUEST")
    return limit
}

internal fun JsonNode.requireText(name: String): String {
    val field = this[name] ?: throw ApiFailure(400, "INVALID_REQUEST")
    if (!field.isTextual) throw ApiFailure(400, "INVALID_REQUEST")
    return field.textValue()
}

internal fun JsonNode.requireObject(name: String): JsonNode {
    val field = this[name] ?: throw ApiFailure(400, "INVALID_REQUEST")
    if (!field.isObject) throw ApiFailure(400, "INVALID_REQUEST")
    return field
}

internal fun JsonNode.requireExactKeys(required: Set<String>, optional: Set<String> = emptySet()) {
    if (!isObject) throw ApiFailure(400, "INVALID_REQUEST")
    val names = fieldNames().asSequence().toSet()
    if (!names.containsAll(required) || !required.plus(optional).containsAll(names)) throw ApiFailure(400, "INVALID_REQUEST")
}

internal fun requirePolicyVersion(value: String) {
    if (!Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,39}").matches(value)) throw ApiFailure(400, "INVALID_REQUEST")
}
