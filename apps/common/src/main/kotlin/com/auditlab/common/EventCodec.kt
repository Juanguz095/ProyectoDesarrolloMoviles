package com.auditlab.common

import org.json.JSONObject

/** Versioned local queue payload, containing only permitted simulated-event metadata. */
object EventCodec {
    fun consent(value: ConsentContext): JSONObject = JSONObject().put("consentId", value.consentId)
        .put("policyVersion", value.policyVersion).put("acceptedAt", value.acceptedAt)
    fun consent(node: JSONObject) = ConsentContext(node.getString("consentId"), node.getString("policyVersion"), node.getString("acceptedAt"))
    fun encode(event: EventDraft): String {
        require(event.category in setOf("app_lifecycle", "sync", "device_status"))
        require(event.direction in setOf("inbound", "outbound", "occurred"))
        require(event.metadata.keys.all { it in setOf("networkState", "batteryPercent", "appEventCode") })
        event.metadata.forEach { (key, value) ->
            require(when (key) {
                "networkState" -> value in setOf("online", "offline", "unknown")
                "batteryPercent" -> (value is Int || value is Long) && (value as Number).toLong() in 0..100
                "appEventCode" -> value in setOf("agent_started", "sync_succeeded", "sync_failed", "consent_revoked")
                else -> false
            }) { "Metadato no permitido" }
        }
        val metadata = JSONObject()
        event.metadata.toSortedMap().forEach { (key, value) -> metadata.put(key, value ?: JSONObject.NULL) }
        return JSONObject().put("eventId", event.eventId).put("timestamp", event.timestamp)
            .put("category", event.category).put("direction", event.direction).put("sourceDevice", event.sourceDevice)
            .put("consent", consent(event.consent)).put("metadata", metadata).toString()
    }
    fun decode(value: String): EventDraft {
        val n = JSONObject(value)
        val m = n.getJSONObject("metadata")
        val metadata = m.keys().asSequence().associateWith { key -> m.get(key).let { if (it == JSONObject.NULL) null else it } }
        return EventDraft(n.getString("eventId"), n.getString("timestamp"), n.getString("category"),
            n.getString("direction"), n.getString("sourceDevice"), consent(n.getJSONObject("consent")), metadata)
            .also { encode(it) }
    }
}
