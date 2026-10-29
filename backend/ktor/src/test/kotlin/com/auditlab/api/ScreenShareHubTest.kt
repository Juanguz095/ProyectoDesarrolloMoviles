package com.auditlab.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ScreenShareHubTest {
    @Test fun `frames are copied and expire after five seconds`() {
        var now = Instant.parse("2026-10-28T00:00:00Z")
        val hub = ScreenShareHub({ now })
        val device = UUID.randomUUID()
        val input = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2)
        hub.publish(device, input)
        input[2] = 9
        assertEquals(1, hub.latest(device)!!.bytes[2])
        now = now.plusSeconds(6)
        assertNull(hub.latest(device))
    }

    @Test fun `only bounded jpeg frames are accepted`() {
        val hub = ScreenShareHub(maxFrameBytes = 4)
        val device = UUID.randomUUID()
        assertThrows(IllegalArgumentException::class.java) { hub.publish(device, byteArrayOf(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { hub.publish(device, byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3)) }
        assertNull(hub.latest(device))
    }

    @Test fun `stopping clears latest frame`() {
        val hub = ScreenShareHub()
        val device = UUID.randomUUID()
        hub.publish(device, byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        hub.stop(device)
        assertNull(hub.latest(device))
    }
}
