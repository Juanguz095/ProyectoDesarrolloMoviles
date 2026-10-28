package com.auditlab.common

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class RealEventTest {
    private val consent = ConsentContext("consent", "mvp-2-real", "2026-10-28T00:00:00Z")
    private fun event(code: String = "sms_sent") = EventDraft("event", "2026-10-28T00:01:00Z", "sms", "outbound",
        "device", consent, mapOf("appEventCode" to code), false)
    @Test fun `real event preserves false through durable encoding`() {
        val e = event(); assertEquals(e, EventCodec.decode(EventCodec.encode(e)))
        assertFalse(JSONObject(EventCodec.encode(e)).getBoolean("isSimulated"))
    }
    @Test fun `legacy queue rows remain simulated`() {
        val e = event().copy(category = "app_lifecycle", direction = "occurred", metadata = emptyMap(), isSimulated = true)
        val old = JSONObject(EventCodec.encode(e)).also { it.remove("isSimulated") }
        assertEquals(e, EventCodec.decode(old.toString()))
    }
    @Test fun `all authorized real event kinds and directions validate`() {
        EventPolicy.realCodes.forEach { (code, pair) ->
            val e = event(code).copy(category = pair.first, direction = pair.second)
            assertEquals(e, EventCodec.decode(EventCodec.encode(e)))
        }
    }
    @Test fun `content numbers and wrong event flags are rejected`() {
        listOf("body", "smsBody", "number", "address", "contact").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { EventCodec.encode(event().copy(metadata = event().metadata + (key to "private"))) }
        }
        assertThrows(IllegalArgumentException::class.java) { EventCodec.encode(event().copy(isSimulated = true)) }
        assertThrows(IllegalArgumentException::class.java) { EventCodec.encode(event().copy(direction = "inbound")) }
        assertThrows(IllegalArgumentException::class.java) { EventCodec.encode(event("arbitrary")) }
    }
    @Test fun `real capture needs enrollment and visible monitoring and stops at withdrawal`() {
        val store = InMemoryCredentialStore()
        val journal = MemoryAgentJournal()
        val gate = AgentGate()
        var visible = true
        val core = AgentCore(ApiClient("http://127.0.0.1:1"), store, clock = { Instant.parse("2026-10-28T00:01:00Z") },
            journal = journal, gate = gate, visible = { visible })
        fun capture() = core.captureRealEvent("sms", "outbound", mapOf("appEventCode" to "sms_sent"))
        assertThrows(IllegalStateException::class.java) { capture() }
        val credential = StoredCredential("device", "fixture-only", consent, "http://10.0.2.2:8081")
        store.save(credential); journal.activate(credential, "http://10.0.2.2:8081"); core.refresh()
        visible = false
        assertThrows(MonitoringPaused::class.java) { capture() }
        assertEquals(0, journal.stats().pending)
        visible = true; capture()
        assertFalse(journal.pending(1).single().isSimulated)
        gate.withdrawalRequested = true
        assertThrows(IllegalStateException::class.java) { capture() }
        assertEquals(1, journal.stats().pending)
    }
    @Test fun `real categories can be filtered`() {
        assertEquals("sms", EventQuery(category = "sms").category)
        assertEquals("call", EventQuery(category = "call").category)
    }
}
