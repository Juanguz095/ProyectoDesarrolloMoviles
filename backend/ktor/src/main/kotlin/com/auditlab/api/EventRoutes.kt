package com.auditlab.api

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private data class NormalizedEvent(
    val eventId: UUID,
    val timestamp: Instant,
    val category: String,
    val direction: String,
    val sourceDevice: UUID,
    val consent: ConsentInput,
    val metadataJson: String,
    val payloadHash: String,
)

private data class EventFilters(val device: UUID?, val category: String?, val from: Instant?, val to: Instant?, val q: String?) {
    val key: String get() = "events|${device?.toString().orEmpty()}|${category.orEmpty()}|${from?.let(::wire).orEmpty()}|${to?.let(::wire).orEmpty()}|${q.orEmpty()}"
}

private fun parseMetadata(node: JsonNode?): String {
    if (node == null || node.isNull) return "{}"
    if (!node.isObject) throw ApiFailure(400, "INVALID_REQUEST")
    val allowed = setOf("networkState", "batteryPercent", "appEventCode")
    if (!allowed.containsAll(node.fieldNames().asSequence().toSet())) throw ApiFailure(400, "INVALID_REQUEST")
    val canonical = json.createObjectNode()
    node["networkState"]?.let {
        if (!it.isTextual || it.textValue() !in setOf("online", "offline", "unknown")) throw ApiFailure(400, "INVALID_REQUEST")
        canonical.put("networkState", it.textValue())
    }
    node["batteryPercent"]?.let {
        if (!it.isIntegralNumber || !it.canConvertToInt() || it.intValue() !in 0..100) throw ApiFailure(400, "INVALID_REQUEST")
        canonical.put("batteryPercent", it.intValue())
    }
    node["appEventCode"]?.let {
        if (!it.isTextual || !isAllowedAppEventCode(it.textValue())) throw ApiFailure(400, "INVALID_REQUEST")
        canonical.put("appEventCode", it.textValue())
    }
    return canonical.toString()
}

private fun parseEvent(node: JsonNode, clock: Clock): NormalizedEvent {
    node.requireExactKeys(
        setOf("eventId", "timestamp", "category", "direction", "sourceDevice", "consentContext", "isSimulated"),
        setOf("metadata"),
    )
    val eventId = parseUuid(node.requireText("eventId"))
    val timestamp = parseInstant(node.requireText("timestamp"), "timestamp")
    val category = node.requireText("category").also { if (!isAllowedCategory(it)) throw ApiFailure(400, "INVALID_REQUEST") }
    val direction = node.requireText("direction").also { if (!isAllowedDirection(it)) throw ApiFailure(400, "INVALID_REQUEST") }
    val sourceDevice = parseUuid(node.requireText("sourceDevice"))
    val consent = parseConsent(node.requireObject("consentContext"), clock)
    val simulated = node["isSimulated"]
    if (simulated == null || !simulated.isBoolean || !simulated.booleanValue()) throw ApiFailure(400, "INVALID_REQUEST")
    if (timestamp.isAfter(clock.instant().plusSeconds(300))) throw ApiFailure(400, "INVALID_REQUEST")
    if (timestamp.isBefore(consent.acceptedAt)) throw ApiFailure(400, "INVALID_REQUEST")
    val metadata = parseMetadata(node["metadata"])
    val canonical = json.createObjectNode().apply {
        put("eventId", eventId.toString())
        put("timestamp", wire(timestamp))
        put("category", category)
        put("direction", direction)
        put("sourceDevice", sourceDevice.toString())
        put("consentId", consent.id.toString())
        put("policyVersion", consent.policyVersion)
        put("acceptedAt", wire(consent.acceptedAt))
        put("isSimulated", true)
        set<JsonNode>("metadata", json.readTree(metadata))
    }.toString()
    return NormalizedEvent(eventId, timestamp, category, direction, sourceDevice, consent, metadata, TokenHash.sha256(canonical))
}

private fun parseEventFilters(query: Map<String, String>): EventFilters {
    val device = query["device"]?.let(::parseUuid)
    val category = query["category"]?.also { if (!isAllowedCategory(it)) throw ApiFailure(400, "INVALID_REQUEST") }
    val from = query["from"]?.let { parseInstant(it, "from") }
    val to = query["to"]?.let { parseInstant(it, "to") }
    if (from != null && to != null && !from.isBefore(to)) throw ApiFailure(400, "INVALID_RANGE")
    val q = query["q"]?.also {
        if (!Regex("[A-Za-z0-9_-]{1,64}").matches(it)) throw ApiFailure(400, "INVALID_REQUEST")
        if (it.count { c -> c == '-' } == 4) parseUuid(it)
        else if (!isAllowedAppEventCode(it)) throw ApiFailure(400, "INVALID_REQUEST")
    }
    return EventFilters(device, category, from, to, q)
}

private fun eventWhere(filters: EventFilters, cursor: List<String>?): Pair<String, List<Any>> {
    val sql = StringBuilder()
    val values = mutableListOf<Any>()
    filters.device?.let { sql.append(" AND e.device_id=?"); values += it }
    filters.category?.let { sql.append(" AND e.category=?"); values += it }
    filters.from?.let { sql.append(" AND e.occurred_at>=?"); values += java.sql.Timestamp.from(it) }
    filters.to?.let { sql.append(" AND e.occurred_at<?"); values += java.sql.Timestamp.from(it) }
    filters.q?.let {
        if (it.count { c -> c == '-' } == 4) { sql.append(" AND e.event_id=?"); values += parseUuid(it) }
        else { sql.append(" AND e.metadata->>'appEventCode'=?"); values += it }
    }
    if (cursor != null) {
        sql.append(" AND (e.occurred_at,e.event_id,e.device_id) < (?,?,?)")
        values += java.sql.Timestamp.from(parseInstant(cursor[0], "cursor"))
        values += parseUuid(cursor[1])
        values += parseUuid(cursor[2])
    }
    return sql.toString() to values
}

private fun eventView(row: java.sql.ResultSet): Map<String, Any?> = mapOf(
    "eventId" to row.getObject("event_id", UUID::class.java).toString(),
    "timestamp" to wire(row.getTimestamp("occurred_at").toInstant()),
    "category" to row.getString("category"),
    "direction" to row.getString("direction"),
    "sourceDevice" to row.getObject("device_id", UUID::class.java).toString(),
    "consentContext" to mapOf(
        "consentId" to row.getObject("consent_id", UUID::class.java).toString(),
        "policyVersion" to row.getString("policy_version"),
        "acceptedAt" to wire(row.getTimestamp("accepted_at").toInstant()),
    ),
    "isSimulated" to row.getBoolean("is_simulated"),
    "metadata" to json.readTree(row.getString("metadata")),
    "receivedAt" to wire(row.getTimestamp("received_at").toInstant()),
)

private const val EVENT_SELECT = """SELECT e.device_id,e.event_id,e.consent_id,e.occurred_at,e.received_at,e.category,
    e.direction,e.is_simulated,e.metadata,c.policy_version,c.accepted_at
    FROM auditlab.events e JOIN auditlab.consents c ON c.id=e.consent_id AND c.device_id=e.device_id"""

fun Route.eventRoutes(auth: AuthService?) {
    post("/v1/events") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            val bytes = call.jsonBytes(262144)
            val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
            val result = withContext(Dispatchers.IO) {
                service.authorizedDevice(token) { connection, device ->
                    val body = parseObject(bytes)
                    body.requireExactKeys(setOf("events"))
                    val array = body["events"]
                    if (array == null || !array.isArray || array.size() !in 1..100) throw ApiFailure(400, "INVALID_REQUEST")
                    val events = array.map { parseEvent(it, Clock.systemUTC()) }
                    var accepted = 0
                    var duplicates = 0
                    for (event in events) {
                        if (event.sourceDevice != device.deviceId) throw ApiFailure(403, "DEVICE_SCOPE_MISMATCH")
                        if (event.consent.id != device.consentId || event.consent.policyVersion != device.policyVersion ||
                            event.consent.acceptedAt != device.acceptedAt) throw ApiFailure(409, "CONSENT_MISMATCH")
                        val inserted = connection.prepareStatement("""INSERT INTO auditlab.events(device_id,event_id,consent_id,occurred_at,received_at,category,direction,is_simulated,metadata,payload_hash)
                            VALUES (?,?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT (device_id,event_id) DO NOTHING""").use {
                            it.setObject(1, device.deviceId)
                            it.setObject(2, event.eventId)
                            it.setObject(3, event.consent.id)
                            it.setTimestamp(4, java.sql.Timestamp.from(event.timestamp))
                            it.setTimestamp(5, java.sql.Timestamp.from(now))
                            it.setString(6, event.category)
                            it.setString(7, event.direction)
                            it.setBoolean(8, true)
                            it.setString(9, event.metadataJson)
                            it.setString(10, event.payloadHash)
                            it.executeUpdate()
                        }
                        if (inserted == 1) {
                            accepted++
                        } else {
                            val stored = connection.prepareStatement("SELECT payload_hash FROM auditlab.events WHERE device_id=? AND event_id=?").use {
                                it.setObject(1, device.deviceId); it.setObject(2, event.eventId)
                                it.executeQuery().use { row -> if (row.next()) row.getString(1) else null }
                            } ?: throw ApiFailure(409, "EVENT_ID_CONFLICT")
                            if (stored != event.payloadHash) throw ApiFailure(409, "EVENT_ID_CONFLICT")
                            duplicates++
                        }
                    }
                    connection.prepareStatement("UPDATE auditlab.devices SET last_seen_at=? WHERE id=?").use {
                        it.setTimestamp(1, java.sql.Timestamp.from(now)); it.setObject(2, device.deviceId); it.executeUpdate()
                    }
                    Audit.record(connection, "device", device.deviceId, "events_received",
                        Audit.resource("event", deviceId = device.deviceId), correlation = correlation)
                    mapOf("accepted" to accepted, "duplicates" to duplicates)
                }
            }
            call.respondText(json.writeValueAsString(result), ContentType.Application.Json, HttpStatusCode.Accepted)
        }
    }

    get("/v1/events") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            val query = call.queryMap()
            call.requireNoQuery("device", "category", "from", "to", "q", "limit", "cursor")
            val filters = parseEventFilters(query)
            val limit = queryLimit(query["limit"])
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            val page = withContext(Dispatchers.IO) {
                service.authorized(token, setOf("admin", "auditor")) { connection, user ->
                    val cursor = query["cursor"]?.let { service.cursorCodec().decode(it, user.userId, filters.key) }
                    val ceiling = cursor?.get(3)?.let { parseInstant(it, "cursor") } ?: Instant.now().truncatedTo(ChronoUnit.MICROS)
                    val (where, values) = eventWhere(filters, cursor)
                    val rows = connection.prepareStatement("""$EVENT_SELECT WHERE e.received_at<=?$where
                        ORDER BY e.occurred_at DESC,e.event_id DESC,e.device_id DESC LIMIT ?""").use {
                        it.setTimestamp(1, java.sql.Timestamp.from(ceiling))
                        values.forEachIndexed { index, value -> it.setObject(index + 2, value) }
                        it.setInt(values.size + 2, limit + 1)
                        it.executeQuery().use { result -> buildList { while (result.next()) add(eventView(result)) } }
                    }
                    val items = rows.take(limit)
                    val next = if (rows.size > limit) {
                        val last = items.last()
                        service.cursorCodec().encode(user.userId, filters.key, listOf(
                            last["timestamp"] as String, last["eventId"] as String, last["sourceDevice"] as String, wire(ceiling)))
                    } else null
                    Audit.record(connection, "user", user.userId, "events_queried", Audit.resource("event"),
                        Audit.filters(device = filters.device?.toString(), category = filters.category,
                            from = filters.from?.let(::wire), to = filters.to?.let(::wire), q = filters.q, limit = limit),
                        correlation = correlation)
                    mapOf("items" to items, "nextCursor" to next)
                }
            }
            call.respondText(json.writeValueAsString(page), ContentType.Application.Json)
        }
    }

    get("/v1/devices/{deviceId}/events/{eventId}") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            val deviceId = call.deviceIdPath()
            val eventId = call.eventIdPath()
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            val event = withContext(Dispatchers.IO) {
                service.authorized(token, setOf("admin", "auditor")) { connection, user ->
                    val view = connection.prepareStatement("$EVENT_SELECT WHERE e.device_id=? AND e.event_id=?").use {
                        it.setObject(1, deviceId); it.setObject(2, eventId)
                        it.executeQuery().use { row -> if (row.next()) eventView(row) else null }
                    } ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND")
                    Audit.record(connection, "user", user.userId, "event_queried",
                        Audit.resource("event", deviceId = deviceId, eventId = eventId), correlation = correlation)
                    view
                }
            }
            call.respondText(json.writeValueAsString(event), ContentType.Application.Json)
        }
    }

    get("/v1/events/export.csv") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            val query = call.queryMap()
            call.requireNoQuery("device", "category", "from", "to", "q")
            val filters = parseEventFilters(query)
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            val export = withContext(Dispatchers.IO) {
                service.authorized(token, setOf("admin", "auditor")) { connection, user ->
                    val (where, values) = eventWhere(filters, null)
                    val rows = connection.prepareStatement("""$EVENT_SELECT WHERE true$where
                        ORDER BY e.occurred_at DESC,e.event_id DESC,e.device_id DESC LIMIT 10001""").use {
                        values.forEachIndexed { index, value -> it.setObject(index + 1, value) }
                        it.executeQuery().use { result ->
                            buildList {
                                while (result.next()) add(listOf(
                                    result.getObject("event_id", UUID::class.java).toString(),
                                    wire(result.getTimestamp("occurred_at").toInstant()),
                                    result.getString("category"),
                                    result.getBoolean("is_simulated").toString(),
                                    result.getString("direction"),
                                    result.getObject("device_id", UUID::class.java).toString(),
                                    result.getObject("consent_id", UUID::class.java).toString(),
                                    result.getString("policy_version"),
                                    result.getString("metadata"),
                                ))
                            }
                        }
                    }
                    val filtersJson = Audit.filters(device = filters.device?.toString(), category = filters.category,
                        from = filters.from?.let(::wire), to = filters.to?.let(::wire), q = filters.q)
                    if (rows.size > 10000) {
                        Audit.record(connection, "user", user.userId, "report_exported", Audit.resource("report"),
                            filtersJson, result = "denied", correlation = correlation)
                        null
                    } else {
                        Audit.record(connection, "user", user.userId, "report_exported", Audit.resource("report"),
                            filtersJson, correlation = correlation)
                        buildString {
                            append("eventId,timestamp,category,isSimulated,direction,sourceDevice,consentId,policyVersion,metadata\r\n")
                            rows.forEach { row -> append(row.joinToString(",") { csvCell(it) }).append("\r\n") }
                        }
                    }
                }
            } ?: throw ApiFailure(409, "EXPORT_LIMIT_EXCEEDED")
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"auditlab-events.csv\"")
            call.respondText(export, ContentType.parse("text/csv"))
        }
    }
}
