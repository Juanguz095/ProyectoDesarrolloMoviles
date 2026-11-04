package com.auditlab.common

/** One deliberate submission, independent from monitoring and the durable outbox. */
object ManualSharePolicy {
    const val VERSION = "manual-share-1"
    fun validate(category: String, simulated: Boolean, metadata: Map<String, Any?>) {
        val manual = category == "manual_share"
        require(manual || metadata.keys.none { it in setOf("sharedText", "shareConsentVersion") })
        if (manual) {
            require(!simulated && metadata.keys == setOf("appEventCode", "sharedText", "shareConsentVersion"))
            val text = metadata["sharedText"]
            require(text is String && text.isNotBlank() && text.length <= 2000)
            require(metadata["shareConsentVersion"] == VERSION)
        }
    }
}
