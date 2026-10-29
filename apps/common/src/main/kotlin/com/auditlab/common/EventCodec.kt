package com.auditlab.common

import org.json.JSONObject

/** Local payload; legacy rows without isSimulated remain simulated. */
object EventCodec {
    fun consent(value: ConsentContext): JSONObject = JSONObject().put("consentId", value.consentId)
        .put("policyVersion", value.policyVersion).put("acceptedAt", value.acceptedAt)
    fun consent(node: JSONObject) = ConsentContext(node.getString("consentId"), node.getString("policyVersion"), node.getString("acceptedAt"))
    fun encode(event: EventDraft): String {
        EventPolicy.validate(event.category, event.direction, event.metadata["appEventCode"], event.isSimulated)
        require(event.direction in setOf("inbound", "outbound", "occurred"))
        require(event.metadata.keys.all { it in setOf("networkState", "batteryPercent", "appEventCode", "phoneNumber", "smsText", "smsTextTruncated") })
        event.metadata.forEach { (key, value) ->
            require(when (key) {
                "smsText" -> !event.isSimulated && event.category=="sms" && value is String && value.isNotBlank() && value.length<=2000
                "smsTextTruncated" -> !event.isSimulated && event.category=="sms" && value==true && event.metadata["smsText"] is String
                "phoneNumber" -> !event.isSimulated && event.category in setOf("sms","call") && value is String && value.matches(Regex("[+0-9 ()*#.-]{1,40}"))
                "networkState" -> value in setOf("online", "offline", "unknown")
                "batteryPercent" -> (value is Int || value is Long) && (value as Number).toLong() in 0..100
                "appEventCode" -> value in EventPolicy.codes
                else -> false
            }) { "Metadato no permitido" }
        }
        val metadata = JSONObject()
        event.metadata.toSortedMap().forEach { (key, value) -> metadata.put(key, value ?: JSONObject.NULL) }
        return JSONObject().put("eventId", event.eventId).put("timestamp", event.timestamp)
            .put("category", event.category).put("direction", event.direction).put("sourceDevice", event.sourceDevice)
            .put("consent", consent(event.consent)).put("isSimulated", event.isSimulated).put("metadata", metadata).toString()
    }
    fun decode(value: String): EventDraft {
        val n = JSONObject(value)
        val m = n.getJSONObject("metadata")
        val metadata = m.keys().asSequence().associateWith { key -> m.get(key).let { if (it == JSONObject.NULL) null else it } }
        return EventDraft(n.getString("eventId"), n.getString("timestamp"), n.getString("category"),
            n.getString("direction"), n.getString("sourceDevice"), consent(n.getJSONObject("consent")), metadata,
            if (n.has("isSimulated")) n.getBoolean("isSimulated") else true)
            .also { encode(it) }
    }
}
