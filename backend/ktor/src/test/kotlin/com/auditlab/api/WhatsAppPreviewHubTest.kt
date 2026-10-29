package com.auditlab.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WhatsAppPreviewHubTest {
    private var now=Instant.parse("2026-10-29T00:00:00Z")
    private val hub=WhatsAppPreviewHub { now }
    private val device=UUID.randomUUID()
    private val session=UUID.randomUUID()
    private fun preview()=WhatsAppPreviewHub.Preview(UUID.randomUUID(),now,"com.whatsapp","QA sender","private fixture text")
    @Test fun `idle sessions and stopped sessions never expose cached text`() {
        hub.start(device,session,now);hub.publish(device,session,preview())
        now=now.plusSeconds(31)
        assertEquals(false,hub.snapshot(device)["active"])
        assertEquals(emptyList<Any>(),hub.snapshot(device)["items"])
        assertThrows(ApiFailure::class.java) { hub.heartbeat(device,session) }
    }
    @Test fun `late sends and closes from the old session cannot affect a new one`() {
        hub.start(device,session,now);hub.stop(device,session)
        assertThrows(ApiFailure::class.java) { hub.publish(device,session,preview()) }
        now=now.plusSeconds(1);val next=UUID.randomUUID();hub.start(device,next,now)
        hub.stop(device,session)
        assertEquals(true,hub.snapshot(device)["active"])
        assertThrows(ApiFailure::class.java) { hub.publish(device,session,preview()) }
        assertThrows(ApiFailure::class.java) { hub.start(device,session,now.minusSeconds(1)) }
    }
    @Test fun `messages expire while heartbeat keeps the sharing session active`() {
        hub.start(device,session,now);hub.publish(device,session,preview())
        repeat(31) { now=now.plusSeconds(10);hub.heartbeat(device,session) }
        assertEquals(true,hub.snapshot(device)["active"])
        assertEquals(emptyList<Any>(),hub.snapshot(device)["items"])
    }
    @Test fun `bounded history deduplicates conflicts and isolates devices`() {
        hub.start(device,session,now)
        repeat(25) { hub.publish(device,session,preview()) }
        assertEquals(20,(hub.snapshot(device)["items"] as List<*>).size)
        val p=preview();hub.publish(device,session,p);hub.publish(device,session,p)
        assertThrows(ApiFailure::class.java) { hub.publish(device,session,p.copy(text="changed")) }
        assertEquals(emptyList<Any>(),hub.snapshot(UUID.randomUUID())["items"])
        assertFalse(p.toString().contains(p.text))
    }
}
