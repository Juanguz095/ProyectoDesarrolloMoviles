package com.auditlab.common

/** Optional notification content. Never enqueue in the event outbox or log this object's text. */
data class WhatsAppPreview(val messageId: String, val timestamp: String, val packageName: String,
                           val title: String, val text: String) {
    override fun toString() = "WhatsAppPreview(messageId=$messageId, content=redacted)"
}
data class WhatsAppPreviewSnapshot(val active: Boolean, val sessionId: String?, val items: List<WhatsAppPreview>)

object WhatsAppPreviewPolicy {
    const val VERSION = "whatsapp-preview-1"
    val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    const val MAX_TEXT = 2000
    const val MAX_TITLE = 200
}
