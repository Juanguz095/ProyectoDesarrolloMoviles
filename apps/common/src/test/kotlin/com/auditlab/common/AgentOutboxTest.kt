package com.auditlab.common

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AgentOutboxTest {
    private class Api : AuditApi {
        val attempts = mutableListOf<List<EventDraft>>()
        val received = mutableSetOf<String>()
        var sendError: ApiException? = null
        var revokeError: ApiException? = null
        var loseReply = false
        var wrongReply = false
        var revokes = 0
        var beforeSend: (() -> Unit)? = null
        override fun enroll(code: String, consent: ConsentContext, appVersion: String) =
            EnrolledDevice("device-qa", "fixture-only-token", consent, consent.acceptedAt)
        override fun sendEvents(deviceToken: String, events: List<EventDraft>): IngestResult {
            attempts += events.toList()
            beforeSend?.invoke()
            sendError?.let { throw it }
            val accepted = events.count { received.add(it.eventId) }
            if (loseReply) { loseReply = false; throw ApiException(0, "NETWORK_ERROR", null, "sin respuesta") }
            return if (wrongReply) IngestResult(0, 0) else IngestResult(accepted, events.size - accepted)
        }
        override fun revokeConsent(deviceToken: String, deviceId: String) { revokes++; revokeError?.let { throw it } }
        override fun login(username: String, password: String): Session = error("unused")
        override fun refresh(refreshToken: String): Session = error("unused")
        override fun logout(accessToken: String) = Unit
        override fun createEnrollmentCode(accessToken: String): EnrollmentCode = error("unused")
        override fun listDevices(accessToken: String, status: String?, limit: Int): DevicePage = error("unused")
        override fun listEvents(accessToken: String, deviceId: String?, limit: Int): EventPage = error("unused")
        override fun revokeDevice(accessToken: String, deviceId: String) = Unit
    }
    private class Fixture(capacity: Int = 10_000, decorate: (AgentJournal) -> AgentJournal = { it }) {
        var now: Instant = Instant.parse("2026-10-25T10:00:00Z")
        var visible = true
        val api = Api()
        val store = InMemoryCredentialStore()
        val journal = decorate(MemoryAgentJournal(capacity))
        val gate = AgentGate()
        fun core(url: String = "https://qa.example.invalid") = AgentCore(api, store, { now }, journal = journal,
            gate = gate, visible = { visible }, serverUrl = url)
        val core = core()
        init { core.acceptConsent(); core.enroll("x".repeat(40)) }
        fun offline() { api.sendError = ApiException(0, "NETWORK_ERROR", null, "offline") }
    }

    @Test fun `worker ignores stale device and privacy work never sends events`() {
        val f = Fixture(); f.offline()
        assertThrows(ApiException::class.java) { f.core.sendSimulatedEvent() }
        val sync = AgentSync(f.journal, f.gate) { f.core() }
        assertEquals(SyncDecision.DONE, sync.run("old-device", false))
        assertEquals(SyncDecision.DONE, sync.run("device-qa", true))
        assertEquals(1, f.api.attempts.size)
        assertEquals(SyncDecision.RETRY, sync.run("device-qa", false))
        f.api.revokeError = ApiException(0, "NETWORK_ERROR", null, "offline")
        f.core.revokeLocally(); val sends = f.api.attempts.size
        assertEquals(SyncDecision.DONE, sync.run("device-qa", false))
        assertEquals(SyncDecision.RETRY, sync.run("device-qa", true))
        assertEquals(sends, f.api.attempts.size)
        f.api.revokeError = null
        assertEquals(SyncDecision.DONE, sync.run("device-qa", true))
        assertNull(f.store.load())
    }
    @Test fun `metadata supplier is never invoked without consent or visibility`() {
        val f = Fixture(); f.visible = false
        var reads = 0
        assertThrows(MonitoringPaused::class.java) { f.core.captureAndSend { reads++; emptyMap() } }
        f.core.revokeLocally()
        assertThrows(IllegalStateException::class.java) { f.core.captureAndSend { reads++; emptyMap() } }
        assertEquals(0, reads)
    }
    @Test fun `metadata values are strictly validated before queueing`() {
        val f = Fixture()
        listOf(mapOf("batteryPercent" to -1), mapOf("batteryPercent" to 20.5), mapOf("batteryPercent" to 101),
            mapOf("appEventCode" to "private_message"), mapOf("networkState" to null)).forEach { metadata ->
            assertThrows(IllegalArgumentException::class.java) { f.core.sendSimulatedEvent(metadata) }
        }
        assertEquals(QueueStats(), f.journal.stats()); assertTrue(f.api.attempts.isEmpty())
    }
    @Test fun `removing visibility stops the next batch even during an existing sync`() {
        val f = Fixture()
        val c = f.store.load()!!
        repeat(101) { f.journal.enqueue(EventDraft(UUID.randomUUID().toString(), f.now.toString(), "sync", "occurred", c.deviceId, c.consent, emptyMap())) }
        f.api.beforeSend = { f.visible = false }
        assertThrows(MonitoringPaused::class.java) { f.core.flushPending() }
        assertEquals(1, f.api.attempts.size); assertEquals(QueueStats(1, 100, 0), f.journal.stats())
        f.api.beforeSend = null; f.visible = true
        assertEquals(IngestResult(1, 0), f.core.flushPending()); assertEquals(101, f.api.received.size)
    }
    @Test fun `offline keeps the ID across recreation and retry`() {
        val f = Fixture(); f.offline()
        assertThrows(ApiException::class.java) { f.core.sendSimulatedEvent() }
        val id = f.journal.pending(100).single().eventId
        assertEquals(1, f.journal.stats().pending)
        f.api.sendError = null
        assertEquals(IngestResult(1, 0), f.core().flushPending())
        assertEquals(id, f.api.attempts.last().single().eventId)
        assertEquals(QueueStats(0, 1, 0), f.journal.stats())
    }
    @Test fun `lost acknowledgement retries exactly the same event without duplication`() {
        val f = Fixture(); f.api.loseReply = true
        assertThrows(ApiException::class.java) { f.core.sendSimulatedEvent() }
        assertEquals(IngestResult(0, 1), f.core().flushPending())
        assertEquals(f.api.attempts[0], f.api.attempts[1]); assertEquals(1, f.api.received.size)
    }
    @Test fun `capacity never drops pending events`() {
        val f = Fixture(2); f.offline()
        repeat(2) { assertThrows(ApiException::class.java) { f.core.sendSimulatedEvent() } }
        val ids = f.journal.pending(100).map { it.eventId }
        assertThrows(QueueFull::class.java) { f.core.sendSimulatedEvent() }
        assertEquals(ids, f.journal.pending(100).map { it.eventId }); assertEquals(2, f.api.attempts.size)
    }
    @Test fun `sent history can be evicted without losing pending work`() {
        val f = Fixture(2)
        repeat(4) { f.core.sendSimulatedEvent() }
        assertEquals(QueueStats(0, 2, 0), f.journal.stats()); assertEquals(4, f.api.received.size)
    }
    @Test fun `retention drops expired events before transmission`() {
        val f = Fixture(); f.offline(); assertThrows(ApiException::class.java) { f.core.sendSimulatedEvent() }
        val expired = f.journal.pending(1).single().eventId
        f.now = f.now.plus(8, ChronoUnit.DAYS); f.api.sendError = null; f.core.sendSimulatedEvent()
        assertFalse(expired in f.api.received); assertEquals(QueueStats(0, 1, 0), f.journal.stats())
    }
    @Test fun `a permanent invalid batch is retained as failed and not resent`() {
        val f = Fixture(); f.api.sendError = ApiException(400, "INVALID_EVENT", null, "invalid")
        assertThrows(ApiException::class.java) { f.core.sendSimulatedEvent() }
        assertEquals(QueueStats(0, 0, 1), f.journal.stats()); f.api.sendError = null
        assertEquals(IngestResult(0, 0), f.core.flushPending()); assertEquals(1, f.api.attempts.size)
    }
    @Test fun `401 blocks future workers without an infinite retry`() {
        val f = Fixture(); f.api.sendError = ApiException(401, "UNAUTHENTICATED", null, "invalid")
        assertThrows(ApiException::class.java) { f.core.sendSimulatedEvent() }
        assertTrue(f.core().state is AgentCore.State.Blocked)
        assertThrows(IllegalStateException::class.java) { f.core().flushPending() }; assertEquals(1, f.api.attempts.size)
    }
    @Test fun `TLS failures and inconsistent acknowledgements require intervention`() {
        val f = Fixture(); f.api.wrongReply = true
        assertThrows(ApiException::class.java) { f.core.sendSimulatedEvent() }
        assertEquals(1, f.journal.stats().pending); assertEquals(0, f.journal.stats().sent)
        assertThrows(IllegalStateException::class.java) { f.core().flushPending() }
        assertFalse(ApiException(0, "TLS_ERROR", null, "TLS").isRetryable)
    }
    @Test fun `no notification means no new event and no transmission`() {
        val f = Fixture(); f.visible = false
        assertThrows(MonitoringPaused::class.java) { f.core.sendSimulatedEvent() }
        assertEquals(QueueStats(), f.journal.stats()); assertTrue(f.api.attempts.isEmpty())
    }
    @Test fun `notification removal preserves an existing pending event`() {
        val f = Fixture(); f.offline(); assertThrows(ApiException::class.java) { f.core.sendSimulatedEvent() }
        f.api.sendError = null; f.visible = false
        assertThrows(MonitoringPaused::class.java) { f.core().flushPending() }
        assertEquals(1, f.journal.stats().pending); assertEquals(1, f.api.attempts.size)
    }
    @Test fun `withdrawal survives recreation and credential is used only to retry revocation`() {
        val f = Fixture(); f.offline(); assertThrows(ApiException::class.java) { f.core.sendSimulatedEvent() }
        f.api.revokeError = ApiException(0, "NETWORK_ERROR", null, "offline")
        assertEquals(AgentCore.RevokeOutcome.LOCAL_ONLY, f.core.revokeLocally())
        assertEquals(QueueStats(), f.journal.stats()); assertNotNull(f.store.load())
        val restored = f.core(); assertTrue(restored.state is AgentCore.State.Revoked)
        assertThrows(IllegalStateException::class.java) { restored.sendSimulatedEvent() }
        assertThrows(IllegalStateException::class.java) { restored.flushPending() }
        f.api.revokeError = null; restored.retryRevocation()
        assertNull(f.store.load()); assertEquals(1, f.api.attempts.size); assertEquals(AgentPhase.REVOKED, f.journal.registration()?.phase)
    }
    @Test fun `fresh participation requires completed withdrawal and a new consent`() {
        val f = Fixture(); val old = f.store.load()!!.consent
        f.api.revokeError = ApiException(503, "SERVICE_UNAVAILABLE", null, "offline")
        f.core.revokeLocally()
        assertThrows(IllegalStateException::class.java) { f.core.resetAfterRevocation() }
        assertThrows(IllegalStateException::class.java) { f.core.acceptConsent() }
        f.api.revokeError = null; f.core.retryRevocation(); f.core.resetAfterRevocation()
        assertNotEquals(old.consentId, f.core.acceptConsent().consentId)
    }
    @Test fun `server binding prevents moving credentials to another service`() {
        val f = Fixture()
        assertTrue(f.core("https://other.example.invalid").state is AgentCore.State.Blocked)
        assertThrows(IllegalStateException::class.java) { f.core("https://other.example.invalid").flushPending() }
        assertTrue(f.api.attempts.isEmpty())
    }
    @Test fun `failed local acknowledgement preserves the event for idempotent recovery`() {
        var fail = true
        val f = Fixture(decorate = { base -> object : AgentJournal by base {
            override fun acknowledge(eventIds: List<String>) { if (fail) throw StorageFailure(); base.acknowledge(eventIds) }
        } })
        assertThrows(StorageFailure::class.java) { f.core.sendSimulatedEvent() }
        fail = false
        assertEquals(IngestResult(0, 1), f.core().flushPending()); assertEquals(1, f.api.received.size)
    }
    @Test fun `failed cleanup does not reactivate persisted withdrawal`() {
        var fail = true
        val f = Fixture(decorate = { base -> object : AgentJournal by base {
            override fun deleteEvents() { if (fail) throw StorageFailure(); base.deleteEvents() }
        } })
        f.offline(); assertThrows(ApiException::class.java) { f.core.sendSimulatedEvent() }
        assertThrows(StorageFailure::class.java) { f.core.revokeLocally() }
        assertEquals(AgentPhase.REVOKING, f.journal.registration()?.phase)
        assertThrows(IllegalStateException::class.java) { f.core().sendSimulatedEvent() }
        fail = false; f.core().retryRevocation(); assertEquals(QueueStats(), f.journal.stats()); assertNull(f.store.load())
    }
    @Test fun `withdrawal save failure blocks the process and independently clears the credential`() {
        val f = Fixture(decorate = { base -> object : AgentJournal by base {
            override fun beginRevocation(deviceId: String, serverUrl: String, remote: Boolean) { throw StorageFailure() }
        } })
        assertThrows(StorageFailure::class.java) { f.core.revokeLocally() }
        assertTrue(f.core.state is AgentCore.State.Blocked); assertNull(f.store.load())
        assertThrows(IllegalStateException::class.java) { f.core().flushPending() }; assertTrue(f.api.attempts.isEmpty())
    }
    @Test fun `foreign event context is never transmitted`() {
        val f = Fixture(decorate = { base -> object : AgentJournal by base {
            override fun pending(limit: Int) = listOf(EventDraft(UUID.randomUUID().toString(), "2026-10-25T10:00:00Z",
                "app_lifecycle", "occurred", "foreign-device", ConsentContext("foreign", "mvp-1", "2026-10-25T10:00:00Z"), emptyMap()))
        } })
        assertThrows(IllegalStateException::class.java) { f.core.flushPending() }; assertTrue(f.api.attempts.isEmpty())
    }
    @Test fun `unknown metadata is refused before it reaches storage or the API`() {
        val f = Fixture()
        assertThrows(IllegalArgumentException::class.java) { f.core.sendSimulatedEvent(mapOf("messageBody" to "not allowed")) }
        assertEquals(QueueStats(), f.journal.stats()); assertTrue(f.api.attempts.isEmpty())
    }
    @Test fun `clock earlier than consent does not enqueue`() {
        val f = Fixture(); f.now = f.now.minusSeconds(1)
        assertThrows(IllegalStateException::class.java) { f.core.sendSimulatedEvent() }; assertEquals(QueueStats(), f.journal.stats())
    }
    @Test fun `withdrawal serializes with in flight IO and blocks every subsequent producer`() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.api.beforeSend = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val sending = pool.submit<IngestResult> { f.core.sendSimulatedEvent() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val revoking = pool.submit<AgentCore.RevokeOutcome> { f.core.revokeLocally() }
            release.countDown(); sending.get(5, TimeUnit.SECONDS); revoking.get(5, TimeUnit.SECONDS)
            assertThrows(IllegalStateException::class.java) { f.core().sendSimulatedEvent() }
            assertEquals(1, f.api.attempts.size); assertEquals(QueueStats(), f.journal.stats())
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
