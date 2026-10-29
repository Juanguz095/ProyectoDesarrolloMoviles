package com.auditlab.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.util.UUID

private val auditActions = setOf(
    "login_succeeded", "login_failed", "session_refreshed", "session_revoked", "enrollment_code_created",
    "device_enrolled", "devices_listed", "device_queried", "events_received", "events_queried", "event_queried",
    "report_exported", "device_revoked", "consent_revoked", "audit_log_queried", "retention_executed",
    "bootstrap_user_created", "screen_frame_queried", "whatsapp_sharing_started", "whatsapp_sharing_stopped", "whatsapp_previews_queried",
)

fun Route.auditRoutes(auth: AuthService?) {
    get("/v1/audit-log") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            val query = call.queryMap()
            call.requireNoQuery("actorId", "action", "result", "from", "to", "limit", "cursor")
            val actorId = query["actorId"]?.let(::parseUuid)
            val action = query["action"]?.also { if (it !in auditActions) throw ApiFailure(400, "INVALID_REQUEST") }
            val result = query["result"]?.also { if (it !in setOf("success", "denied", "failure")) throw ApiFailure(400, "INVALID_REQUEST") }
            val from = query["from"]?.let { parseInstant(it, "from") }
            val to = query["to"]?.let { parseInstant(it, "to") }
            if (from != null && to != null && !from.isBefore(to)) throw ApiFailure(400, "INVALID_RANGE")
            val limit = queryLimit(query["limit"])
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            val page = withContext(Dispatchers.IO) {
                service.authorized(token, setOf("admin", "auditor")) { connection, user ->
                    val filtersKey = "audit|${actorId?.toString().orEmpty()}|${action.orEmpty()}|${result.orEmpty()}|${from?.let(::wire).orEmpty()}|${to?.let(::wire).orEmpty()}"
                    val cursor = query["cursor"]?.let { service.cursorCodec().decode(it, user.userId, filtersKey) }
                    val where = StringBuilder()
                    val values = mutableListOf<Any>()
                    actorId?.let { where.append(" AND actor_id=?"); values += it }
                    action?.let { where.append(" AND action=?"); values += it }
                    result?.let { where.append(" AND result=?"); values += it }
                    from?.let { where.append(" AND occurred_at>=?"); values += Timestamp.from(it) }
                    to?.let { where.append(" AND occurred_at<?"); values += Timestamp.from(it) }
                    if (cursor != null) {
                        where.append(" AND (occurred_at,id) < (?,?)")
                        values += Timestamp.from(parseInstant(cursor[0], "cursor"))
                        values += parseUuid(cursor[1])
                    }
                    val rows = connection.prepareStatement("""SELECT id,actor_type,actor_id,action,resource,filters,result,occurred_at,correlation_id
                        FROM auditlab.audit_log WHERE true$where ORDER BY occurred_at DESC,id DESC LIMIT ?""").use {
                        values.forEachIndexed { index, value -> it.setObject(index + 1, value) }
                        it.setInt(values.size + 1, limit + 1)
                        it.executeQuery().use { resultSet ->
                            buildList {
                                while (resultSet.next()) add(
                                    mapOf(
                                        "auditId" to resultSet.getObject(1, UUID::class.java).toString(),
                                        "actor" to mapOf(
                                            "type" to resultSet.getString(2),
                                            "id" to resultSet.getObject(3, UUID::class.java)?.toString(),
                                        ),
                                        "action" to resultSet.getString(4),
                                        "resource" to json.readTree(resultSet.getString(5)),
                                        "filters" to json.readTree(resultSet.getString(6)),
                                        "result" to resultSet.getString(7),
                                        "occurredAt" to wire(resultSet.getTimestamp(8).toInstant()),
                                        "correlationId" to resultSet.getObject(9, UUID::class.java).toString(),
                                    )
                                )
                            }
                        }
                    }
                    val items = rows.take(limit)
                    val next = if (rows.size > limit) {
                        val last = items.last()
                        service.cursorCodec().encode(user.userId, filtersKey, listOf(last["occurredAt"] as String, last["auditId"] as String))
                    } else null
                    Audit.record(connection, "user", user.userId, "audit_log_queried", Audit.resource("audit"),
                        Audit.filters(actorId = actorId?.toString(), action = action, result = result,
                            from = from?.let(::wire), to = to?.let(::wire), limit = limit), correlation = correlation)
                    mapOf("items" to items, "nextCursor" to next)
                }
            }
            call.respondText(json.writeValueAsString(page), ContentType.Application.Json)
        }
    }
}
