package com.auditlab.common

object EventPolicy {
    val categories = setOf("app_lifecycle", "sync", "device_status", "sms", "call", "whatsapp", "manual_share", "location")
    val realCodes = mapOf(
        "text_shared_manually" to ("manual_share" to "occurred"),
        "location_update" to ("location" to "occurred"),
        "sms_received" to ("sms" to "inbound"), "sms_sent" to ("sms" to "outbound"),
        "call_received" to ("call" to "inbound"), "call_missed" to ("call" to "inbound"),
        "call_rejected" to ("call" to "inbound"), "call_outgoing" to ("call" to "outbound"),
        "device_status_changed" to ("device_status" to "occurred"),
        "whatsapp_notification_received" to ("whatsapp" to "inbound"),
        "whatsapp_reply_observed" to ("whatsapp" to "outbound"),
    )
    val codes = setOf("agent_started", "sync_succeeded", "sync_failed", "consent_revoked") + realCodes.keys
    fun validate(category: String, direction: String, code: Any?, simulated: Boolean) {
        require(category in categories && direction in setOf("inbound", "outbound", "occurred"))
        if (!simulated) require(realCodes[code] == (category to direction)) { "Evento real no permitido" }
        else require(category !in setOf("sms", "call", "whatsapp", "manual_share", "location") && code !in realCodes) { "Marca de simulación incorrecta" }
    }
}
