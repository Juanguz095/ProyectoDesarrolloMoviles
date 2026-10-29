package com.auditlab.api

import java.time.Instant
import java.util.UUID

/** Bounded ephemeral content; only scope/consent/access metadata is written to SQL audit. */
internal class WhatsAppPreviewHub(private val now: () -> Instant = Instant::now) {
    data class Preview(val messageId: UUID, val timestamp: Instant, val packageName: String,
                       val title: String, val text: String) {
        override fun toString() = "Preview(content=redacted)"
    }
    private data class Entry(val preview: Preview, val received: Instant)
    private data class Session(val id: UUID, val acceptedAt: Instant, var seen: Instant,
                               var active: Boolean = true, val items: LinkedHashMap<UUID,Entry> = linkedMapOf())
    private val sessions = linkedMapOf<UUID,Session>()
    @Synchronized fun purgeExpired() {
        val current = now()
        sessions.values.forEach { session ->
            if (current.isAfter(session.seen.plusSeconds(30))) { session.active=false; session.items.clear() }
            session.items.entries.removeIf { current.isAfter(it.value.received.plusSeconds(300)) }
        }
        sessions.entries.removeIf { !it.value.active && current.isAfter(it.value.seen.plusSeconds(300)) }
    }
    @Synchronized fun start(device: UUID, id: UUID, acceptedAt: Instant) {
        purgeExpired()
        val old = sessions[device]
        if (old?.id == id) {
            if (!old.active || old.acceptedAt != acceptedAt) throw ApiFailure(409,"SHARING_SESSION_ENDED")
            return
        }
        if (old != null && !acceptedAt.isAfter(old.acceptedAt)) throw ApiFailure(409,"STALE_SHARING_SESSION")
        if (sessions.values.count { it.active } >= 16 && old?.active != true) throw ApiFailure(429,"SHARING_CAPACITY")
        if (sessions.size >= 32 && device !in sessions) sessions.entries.firstOrNull { !it.value.active }?.let { sessions.remove(it.key) }
        sessions[device]=Session(id,acceptedAt,now())
    }
    private fun active(device: UUID,id: UUID): Session {
        purgeExpired()
        return sessions[device]?.takeIf { it.active && it.id==id } ?: throw ApiFailure(409,"SHARING_SESSION_ENDED")
    }
    @Synchronized fun heartbeat(device: UUID,id: UUID) { active(device,id).seen=now() }
    @Synchronized fun publish(device: UUID,id: UUID,preview: Preview) {
        val session=active(device,id)
        if (preview.timestamp.isBefore(session.acceptedAt) || preview.timestamp.isAfter(now().plusSeconds(300))) throw ApiFailure(400,"INVALID_PREVIEW_TIME")
        val old=session.items[preview.messageId]
        if (old!=null && old.preview!=preview) throw ApiFailure(409,"PREVIEW_CONFLICT")
        if (old==null) session.items[preview.messageId]=Entry(preview,now())
        while (session.items.size>20) session.items.remove(session.items.keys.first())
        session.seen=now()
    }
    @Synchronized fun stop(device: UUID,id: UUID? = null) {
        sessions[device]?.takeIf { id==null || it.id==id }?.let { it.active=false;it.items.clear();it.seen=now() }
    }
    @Synchronized fun snapshot(device: UUID): Map<String,Any?> {
        purgeExpired()
        val session=sessions[device]?.takeIf { it.active }
        return mapOf("active" to (session!=null),"sessionId" to session?.id?.toString(),"items" to session?.items?.values?.reversed()?.map {
            val p=it.preview
            mapOf("messageId" to p.messageId.toString(),"timestamp" to wire(p.timestamp),"packageName" to p.packageName,"title" to p.title,"text" to p.text)
        }.orEmpty())
    }
    @Synchronized fun clear() { sessions.clear() }
}
