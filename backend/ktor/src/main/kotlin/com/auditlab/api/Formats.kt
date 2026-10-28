package com.auditlab.api

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object TokenHash {
    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** RFC 3339 in UTC with microsecond precision, as required by the contract. */
private val wireFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

internal fun wire(instant: Instant): String = wireFormat.format(instant.truncatedTo(ChronoUnit.MICROS))

internal fun parseInstant(value: String, field: String): Instant = try {
    OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MICROS)
} catch (_: DateTimeParseException) {
    throw ApiFailure(400, "INVALID_REQUEST")
}

internal fun parseUuid(value: String): UUID = try {
    UUID.fromString(value)
} catch (_: IllegalArgumentException) {
    throw ApiFailure(400, "INVALID_REQUEST")
}

internal fun isAllowedAppEventCode(value: String): Boolean =
    value in setOf("agent_started", "sync_succeeded", "sync_failed", "consent_revoked") || value in realEventCodes

internal val realEventCodes = mapOf(
    "sms_received" to ("sms" to "inbound"), "sms_sent" to ("sms" to "outbound"),
    "call_received" to ("call" to "inbound"), "call_missed" to ("call" to "inbound"),
    "call_rejected" to ("call" to "inbound"), "call_outgoing" to ("call" to "outbound"),
    "device_status_changed" to ("device_status" to "occurred"),
)

internal fun isAllowedCategory(value: String): Boolean = value in setOf("app_lifecycle", "sync", "device_status", "sms", "call")
internal fun isAllowedDirection(value: String): Boolean = value in setOf("inbound", "outbound", "occurred")

/** Escapes a CSV cell and neutralizes values a spreadsheet could evaluate as a formula. */
internal fun csvCell(value: String): String {
    val neutral = if (value.isNotEmpty() && value[0] in charArrayOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
    return if (neutral.any { it == '"' || it == ',' || it == '\r' || it == '\n' }) {
        "\"" + neutral.replace("\"", "\"\"") + "\""
    } else neutral
}

/**
 * Opaque, HMAC-signed cursor. It is bound to the authenticated actor and the exact filters, and it
 * expires after ten minutes, so a cursor cannot be replayed by another actor or reused with other
 * filters.
 */
internal class CursorCodec(private val key: ByteArray, private val clock: java.time.Clock = java.time.Clock.systemUTC()) {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(actor: UUID, filters: String, values: List<String>): String {
        val payload = json.createObjectNode().apply {
            put("a", actor.toString())
            put("f", TokenHash.sha256(filters).take(32))
            put("e", clock.instant().plusSeconds(600).epochSecond)
            putArray("v").apply { values.forEach { add(it) } }
        }
        val bytes = json.writeValueAsBytes(payload)
        return encoder.encodeToString(bytes) + "." + encoder.encodeToString(sign(bytes))
    }

    fun decode(token: String, actor: UUID, filters: String): List<String> {
        if (token.length !in 16..2048) throw ApiFailure(400, "INVALID_CURSOR")
        val parts = token.split('.')
        if (parts.size != 2) throw ApiFailure(400, "INVALID_CURSOR")
        val bytes = try { decoder.decode(parts[0]) } catch (_: IllegalArgumentException) { throw ApiFailure(400, "INVALID_CURSOR") }
        val signature = try { decoder.decode(parts[1]) } catch (_: IllegalArgumentException) { throw ApiFailure(400, "INVALID_CURSOR") }
        if (!MessageDigest.isEqual(signature, sign(bytes))) throw ApiFailure(400, "INVALID_CURSOR")
        val node = try { json.readTree(bytes) } catch (_: Exception) { throw ApiFailure(400, "INVALID_CURSOR") }
        if (node == null || !node.isObject) throw ApiFailure(400, "INVALID_CURSOR")
        val expectedActor = node["a"]?.textValue() ?: throw ApiFailure(400, "INVALID_CURSOR")
        val expectedFilters = node["f"]?.textValue() ?: throw ApiFailure(400, "INVALID_CURSOR")
        val expires = node["e"]?.longValue() ?: throw ApiFailure(400, "INVALID_CURSOR")
        if (expectedActor != actor.toString() || expectedFilters != TokenHash.sha256(filters).take(32) ||
            expires < clock.instant().epochSecond) throw ApiFailure(400, "INVALID_CURSOR")
        val values = node["v"] as? ArrayNode ?: throw ApiFailure(400, "INVALID_CURSOR")
        if (values.size() !in 1..8) throw ApiFailure(400, "INVALID_CURSOR")
        return values.map { it.textValue() ?: throw ApiFailure(400, "INVALID_CURSOR") }
    }

    private fun sign(bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(bytes)
    }
}

internal fun ObjectNode.putNullable(name: String, value: String?) {
    if (value == null) putNull(name) else put(name, value)
}
