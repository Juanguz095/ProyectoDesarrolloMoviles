package com.auditlab.api

import com.fasterxml.jackson.databind.node.ObjectNode
import java.sql.Connection
import java.util.UUID

/** Transactional audit rows: callers write them inside the same transaction as the action. */
internal object Audit {
    fun resource(type: String, deviceId: UUID? = null, eventId: UUID? = null, userId: UUID? = null,
                 sessionId: UUID? = null): String = json.createObjectNode().apply {
        put("type", type)
        deviceId?.let { put("deviceId", it.toString()) }
        eventId?.let { put("eventId", it.toString()) }
        userId?.let { put("userId", it.toString()) }
        sessionId?.let { put("sessionId", it.toString()) }
    }.toString()

    fun filters(device: String? = null, category: String? = null, from: String? = null, to: String? = null,
                q: String? = null, status: String? = null, limit: Int? = null, actorId: String? = null,
                action: String? = null, result: String? = null): String = json.createObjectNode().apply {
        device?.let { put("device", it) }
        category?.let { put("category", it) }
        from?.let { put("from", it) }
        to?.let { put("to", it) }
        q?.let { put("q", it) }
        status?.let { put("status", it) }
        limit?.let { put("limit", it) }
        actorId?.let { put("actorId", it) }
        action?.let { put("action", it) }
        result?.let { put("result", it) }
    }.toString()

    fun record(connection: Connection, actorType: String, actorId: UUID?, action: String, resource: String,
               filters: String = "{}", result: String = "success", correlation: UUID) {
        connection.prepareStatement("""INSERT INTO auditlab.audit_log(id,actor_type,actor_id,action,resource,filters,result,correlation_id)
            VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?)""").use {
            it.setObject(1, UUID.randomUUID())
            it.setString(2, actorType)
            it.setObject(3, actorId)
            it.setString(4, action)
            it.setString(5, resource)
            it.setString(6, filters)
            it.setString(7, result)
            it.setObject(8, correlation)
            it.executeUpdate()
        }
    }

    fun node(value: String): ObjectNode = json.readTree(value) as ObjectNode
}
