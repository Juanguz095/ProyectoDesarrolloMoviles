package com.auditlab.common

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ManualShareTest {
    private val consent=ConsentContext("consent", "mvp-2-real", "2026-11-04T00:00:00Z")
    @Test fun `manual send is independent of visibility and outbox and explicit retry is identical`() {
        val credential=StoredCredential("device","fixture",consent,"http://10.0.2.2:8081")
        val store=InMemoryCredentialStore().also {it.save(credential)}
        val journal=MemoryAgentJournal().also {it.activate(credential,credential.serverUrl!!)}
        val calls=mutableListOf<List<EventDraft>>()
        var failing=true
        val api=object : AuditApi by ApiClient("http://127.0.0.1:1") {
            override fun sendEvents(deviceToken: String, events: List<EventDraft>): IngestResult {
                calls.add(events)
                if(failing) throw ApiException(0,"NETWORK_ERROR",null,"Fixture")
                return IngestResult(0,1)
            }
        }
        val gate=AgentGate()
        val core=AgentCore(api,store,clock={Instant.parse("2026-11-04T00:01:00Z")},journal=journal,gate=gate,visible={false})
        val draft=core.prepareManualText("Texto ficticio")
        val pending=draft.copy(eventId="pending",category="app_lifecycle",metadata=mapOf("appEventCode" to "agent_started"),isSimulated=true)
        journal.enqueue(pending)
        assertThrows(ApiException::class.java) {core.sendManualText(draft)}
        assertEquals(listOf(pending),journal.pending(100))
        failing=false
        assertEquals(IngestResult(0,1),core.sendManualText(draft))
        assertEquals(calls[0],calls[1]);assertEquals(listOf(draft),calls[1])
        assertEquals(listOf(pending),journal.pending(100))
        gate.withdrawalRequested=true
        assertThrows(IllegalStateException::class.java) {core.sendManualText(draft)}
        assertEquals(2,calls.size)
    }
    @Test fun `manual payload requires text consent real flag and its exact category and direction`() {
        val good=EventDraft("event","2026-11-04T00:01:00Z","manual_share","occurred","device",consent,
            mapOf("appEventCode" to "text_shared_manually","sharedText" to "x".repeat(2000),"shareConsentVersion" to ManualSharePolicy.VERSION),false)
        assertEquals(good,EventCodec.decode(EventCodec.encode(good)))
        val invalid=listOf(good.copy(isSimulated=true),good.copy(direction="inbound"),good.copy(category="whatsapp"),
            good.copy(metadata=good.metadata-"sharedText"),good.copy(metadata=good.metadata-"shareConsentVersion"),
            good.copy(metadata=good.metadata+("sharedText" to " ")),
            good.copy(metadata=good.metadata+("sharedText" to "x".repeat(2001))),
            good.copy(metadata=good.metadata+("shareConsentVersion" to "other")),
            good.copy(metadata=good.metadata+("phoneNumber" to "123")))
        invalid.forEach {e -> assertThrows(IllegalArgumentException::class.java) {EventCodec.encode(e)}}
    }
}
