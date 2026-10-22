package com.auditlab.domain

import java.util.UUID
import kotlin.test.*

internal class ServiceFixture {
    val journal = mutableListOf<String>()
    val consent = context()
    val events = InMemoryEvents(journal)
    val acceptance = InMemoryAcceptance(Acceptance.Granted(consent), journal)
    val coordinator = SynchronizedAuditCoordinator()
    var timestamp = 2000L
    var clockCalls = 0
    var idCalls = 0
    var failClock = false
    var failIds = false
    var fixedId: UUID? = null
    val clock = EpochClock {
        clockCalls++
        if (failClock) error("fixture clock unavailable")
        timestamp
    }
    val ids = EventIdGenerator {
        idCalls++
        if (failIds) error("fixture id unavailable")
        fixedId ?: UUID(0, idCalls.toLong())
    }
    val service = peer()
    fun peer(repository: EventRepository = events) = AuditService(repository, acceptance, clock, ids, "1.0", coordinator)
    fun seed(count: Int) {
        repeat(count) { i -> events.insertIfBelowCapacity(event(UUID(0, 10_000L + i), 2000L + i, consent), 1000) }
        journal.clear()
    }
}

private fun <T> AuditResult<T>.value(): T = assertIs<AuditResult.Success<T>>(this).value
private fun AuditResult<*>.rejects(error: AuditError) { assertEquals(AuditResult.Rejected(error), this) }
private fun AuditService.recordCall() = record(EventKind.CALL, EventDirection.INCOMING)

class AuditServiceTest {
    @Test fun `records each of four combinations with injected values`() {
        val f = ServiceFixture()
        for (kind in EventKind.entries) for (direction in EventDirection.entries) {
            val generated = f.service.record(kind, direction).value()
            assertEquals(kind, generated.kind); assertEquals(direction, generated.direction)
            assertEquals(f.timestamp, generated.timestampEpochMs)
            assertEquals(f.consent, generated.consent); assertTrue(generated.isSimulated)
        }
        assertEquals(4, f.events.snapshot.size)
        assertEquals(4, f.events.snapshot.map { it.eventId }.toSet().size)
        assertEquals(1000, f.events.requestedCapacity)
    }

    @Test fun `missing acceptance rejects without touching clock ids or repository`() {
        val f = ServiceFixture(); f.acceptance.state = Acceptance.Pending
        f.service.recordCall().rejects(AuditError.ACCEPTANCE_REQUIRED)
        f.service.history().rejects(AuditError.ACCEPTANCE_REQUIRED)
        assertEquals(0, f.clockCalls); assertEquals(0, f.idCalls)
        assertTrue(f.events.snapshot.isEmpty()); assertTrue(f.journal.none { it.startsWith("events.") })
    }

    @Test fun `revoked acceptance rejects even when cleanup is already complete`() {
        for (pending in listOf(true, false)) {
            val f = ServiceFixture(); f.acceptance.state = Acceptance.Revoked(1000, pending)
            f.service.recordCall().rejects(AuditError.ACCEPTANCE_REQUIRED)
            f.service.history().rejects(AuditError.ACCEPTANCE_REQUIRED)
            assertTrue(f.events.snapshot.isEmpty())
        }
    }

    @Test fun `stale policy cannot generate or read protected history`() {
        val f = ServiceFixture(); f.acceptance.state = Acceptance.Granted(context(policy = "old"))
        f.service.recordCall().rejects(AuditError.POLICY_CHANGED)
        f.service.history().rejects(AuditError.POLICY_CHANGED)
        assertEquals(0, f.clockCalls); assertTrue(f.events.snapshot.isEmpty())
    }

    @Test fun `acceptance read failure never grants access`() {
        val f = ServiceFixture(); f.acceptance.failRead = true
        f.service.recordCall().rejects(AuditError.ACCEPTANCE_READ_FAILED)
        f.service.history().rejects(AuditError.ACCEPTANCE_READ_FAILED)
        assertEquals(0, f.idCalls); assertTrue(f.events.snapshot.isEmpty())
    }

    @Test fun `negative or pre acceptance clock rejects without consuming ids or writing`() {
        for (time in listOf(-1L, 999L)) {
            val f = ServiceFixture(); f.timestamp = time
            f.service.recordCall().rejects(AuditError.INVALID_TIMESTAMP)
            assertEquals(0, f.idCalls); assertTrue(f.events.snapshot.isEmpty())
        }
    }

    @Test fun `clock failure and id failure are reported without insertions`() {
        val f = ServiceFixture(); f.failClock = true
        f.service.recordCall().rejects(AuditError.CLOCK_UNAVAILABLE)
        assertEquals(0, f.idCalls)
        f.failClock = false; f.failIds = true
        f.service.recordCall().rejects(AuditError.ID_GENERATION_FAILED)
        assertTrue(f.events.snapshot.isEmpty())
    }

    @Test fun `duplicate generated id is rejected without replacing its event`() {
        val f = ServiceFixture(); f.fixedId = UUID.randomUUID()
        val first = f.service.recordCall().value()
        f.service.record(EventKind.MESSAGE, EventDirection.OUTGOING).rejects(AuditError.DUPLICATE_EVENT_ID)
        assertEquals(listOf(first), f.events.snapshot)
    }

    @Test fun `exactly one thousand events fit and next insertion preserves every stored id`() {
        val f = ServiceFixture()
        repeat(1000) { assertIs<AuditResult.Success<Event>>(f.service.recordCall()) }
        val before = f.events.snapshot
        f.service.recordCall().rejects(AuditError.CAPACITY_REACHED)
        assertEquals(1000, before.size); assertEquals(before, f.events.snapshot)
    }

    @Test fun `insertion storage failure is explicit and does not claim success`() {
        val f = ServiceFixture(); f.events.failInsert = true
        f.service.recordCall().rejects(AuditError.STORAGE_UNAVAILABLE)
        assertTrue(f.events.snapshot.isEmpty())
    }

    @Test fun `history shows only latest hundred and does not truncate storage`() {
        val f = ServiceFixture(); f.seed(150)
        val history = f.service.history().value()
        assertEquals(100, history.size); assertEquals(100, f.events.requestedHistoryLimit)
        assertEquals(f.events.snapshot.sortedWith(eventOrder).take(100), history)
        assertEquals(150, f.events.snapshot.size)
    }

    @Test fun `empty history succeeds after acceptance and returned list is detached`() {
        val f = ServiceFixture(); assertTrue(f.service.history().value().isEmpty())
        f.seed(3)
        val history = f.service.history().value()
        (history as MutableList<Event>).clear()
        assertEquals(3, f.events.snapshot.size)
    }

    @Test fun `history defensively sorts and bounds an oversized adapter response`() {
        val f = ServiceFixture(); f.seed(150)
        val misbehaving = object : EventRepository by f.events {
            override fun latest(limit: Int) = f.events.snapshot.reversed()
        }
        assertEquals(f.events.snapshot.sortedWith(eventOrder).take(100), f.peer(misbehaving).history().value())
    }

    @Test fun `history storage failure is not an empty successful history`() {
        val f = ServiceFixture(); f.events.failRead = true
        f.service.history().rejects(AuditError.STORAGE_UNAVAILABLE)
    }

    @Test fun `clear deletes events preserves acceptance and is idempotent`() {
        val f = ServiceFixture(); f.seed(3); val original = f.acceptance.state
        assertEquals(3, f.service.clearHistory().value()); assertEquals(0, f.service.clearHistory().value())
        assertEquals(original, f.acceptance.state); assertEquals(0, f.acceptance.saveAttempts)
        assertIs<AuditResult.Success<Event>>(f.service.recordCall())
    }

    @Test fun `clear remains available without acceptance and even if its store cannot be read`() {
        val f = ServiceFixture(); f.seed(3); f.acceptance.state = Acceptance.Pending; f.acceptance.failRead = true
        assertEquals(3, f.service.clearHistory().value())
        assertTrue(f.journal.none { it.startsWith("acceptance.") })
        assertEquals(Acceptance.Pending, f.acceptance.state)
    }

    @Test fun `failed clear never reports deletion or changes acceptance`() {
        val f = ServiceFixture(); f.seed(3); val original = f.acceptance.state
        f.events.failDelete = true
        f.service.clearHistory().rejects(AuditError.STORAGE_UNAVAILABLE)
        assertEquals(3, f.events.snapshot.size); assertEquals(original, f.acceptance.state)
    }

    @Test fun `negative adapter deletion count is not reported as successful clear`() {
        val f = ServiceFixture()
        val invalid = object : EventRepository by f.events { override fun deleteAll() = -1 }
        f.peer(invalid).clearHistory().rejects(AuditError.INVALID_STORAGE_RESPONSE)
    }

    @Test fun `revoke saves withdrawal before deletion and cleanup confirmation`() {
        val f = ServiceFixture(); f.seed(3)
        assertEquals(RevocationReport(DeletionStatus.CLEARED, 3), f.service.revokeAcceptance().value())
        assertEquals(listOf("acceptance.save", "events.delete", "acceptance.save"), f.journal)
        assertEquals(Acceptance.Revoked(2000, false), f.acceptance.state)
        assertTrue(f.events.snapshot.isEmpty())
        f.service.recordCall().rejects(AuditError.ACCEPTANCE_REQUIRED)
    }

    @Test fun `failed deletion keeps withdrawal and blocks other service over same stores`() {
        val f = ServiceFixture(); f.seed(3); f.events.failDelete = true
        assertEquals(RevocationReport(DeletionStatus.PENDING, null), f.service.revokeAcceptance().value())
        assertEquals(Acceptance.Revoked(2000, true), f.acceptance.state); assertEquals(3, f.events.snapshot.size)
        val peer = f.peer()
        peer.recordCall().rejects(AuditError.ACCEPTANCE_REQUIRED)
        peer.history().rejects(AuditError.ACCEPTANCE_REQUIRED)
    }

    @Test fun `retry finishes pending deletion without accepting and is idempotent`() {
        val f = ServiceFixture(); f.seed(3); f.events.failDelete = true
        f.service.revokeAcceptance()
        f.events.failDelete = false
        assertEquals(RevocationReport(DeletionStatus.CLEARED, 3), f.peer().retryRevokedDeletion().value())
        assertEquals(RevocationReport(DeletionStatus.CLEARED, 0), f.service.retryRevokedDeletion().value())
        assertFalse(assertIs<Acceptance.Revoked>(f.acceptance.state).deletionPending)
        f.service.recordCall().rejects(AuditError.ACCEPTANCE_REQUIRED)
    }

    @Test fun `repeated deletion failure leaves cleanup pending without changing withdrawal`() {
        val f = ServiceFixture(); f.seed(2); f.events.failDelete = true
        f.service.revokeAcceptance()
        assertEquals(DeletionStatus.PENDING, f.service.retryRevokedDeletion().value().deletionStatus)
        assertIs<Acceptance.Revoked>(f.acceptance.state); assertEquals(2, f.events.snapshot.size)
    }

    @Test fun `failed withdrawal save does not delete and fails closed in current service`() {
        val f = ServiceFixture(); f.seed(3); f.acceptance.failSaves += 1
        f.service.revokeAcceptance().rejects(AuditError.ACCEPTANCE_WRITE_FAILED)
        assertEquals(3, f.events.snapshot.size); assertTrue(f.journal.none { it == "events.delete" })
        assertIs<Acceptance.Granted>(f.acceptance.state)
        f.service.recordCall().rejects(AuditError.REVOCATION_NOT_CONFIRMED)
        f.service.history().rejects(AuditError.REVOCATION_NOT_CONFIRMED)
        f.service.retryRevokedDeletion().rejects(AuditError.REVOCATION_NOT_CONFIRMED)
    }

    @Test fun `explicit retry of withdrawal saves it and clears data after write failure`() {
        val f = ServiceFixture(); f.seed(2); f.acceptance.failSaves += 1
        f.service.revokeAcceptance()
        assertEquals(RevocationReport(DeletionStatus.CLEARED, 2), f.service.revokeAcceptance().value())
        assertIs<Acceptance.Revoked>(f.acceptance.state)
        f.service.recordCall().rejects(AuditError.ACCEPTANCE_REQUIRED)
    }

    @Test fun `cleanup flag save failure preserves withdrawal and can be retried`() {
        val f = ServiceFixture(); f.seed(3); f.acceptance.failSaves += 2
        assertEquals(RevocationReport(DeletionStatus.CLEARED_CONFIRMATION_PENDING, 3), f.service.revokeAcceptance().value())
        assertTrue(f.events.snapshot.isEmpty()); assertTrue(assertIs<Acceptance.Revoked>(f.acceptance.state).deletionPending)
        f.service.recordCall().rejects(AuditError.ACCEPTANCE_REQUIRED)
        assertEquals(RevocationReport(DeletionStatus.CLEARED, 0), f.peer().retryRevokedDeletion().value())
    }

    @Test fun `clock failure or invalid time cannot prevent revocation`() {
        for (broken in listOf(true, false)) {
            val f = ServiceFixture(); f.failClock = broken; f.timestamp = -1
            assertEquals(DeletionStatus.CLEARED, f.service.revokeAcceptance().value().deletionStatus)
            assertNull(assertIs<Acceptance.Revoked>(f.acceptance.state).revokedAtEpochMs)
        }
    }

    @Test fun `acceptance read failure cannot prevent a new withdrawal save`() {
        val f = ServiceFixture(); f.acceptance.failRead = true
        assertEquals(DeletionStatus.CLEARED, f.service.revokeAcceptance().value().deletionStatus)
        assertIs<Acceptance.Revoked>(f.acceptance.state)
        assertTrue(f.journal.none { it == "acceptance.read" })
    }

    @Test fun `retry read failure and missing withdrawn state do not perform deletion`() {
        val f = ServiceFixture(); f.acceptance.failRead = true
        f.service.retryRevokedDeletion().rejects(AuditError.ACCEPTANCE_READ_FAILED)
        f.acceptance.failRead = false
        for (state in listOf(Acceptance.Pending, Acceptance.Granted(f.consent))) {
            f.acceptance.state = state
            f.service.retryRevokedDeletion().rejects(AuditError.NO_REVOKED_ACCEPTANCE)
        }
        assertTrue(f.journal.none { it == "events.delete" })
    }

    @Test fun `unconfirmed adapter deletion leaves acceptance withdrawn and cleanup pending`() {
        val f = ServiceFixture()
        val invalid = object : EventRepository by f.events { override fun deleteAll() = -1 }
        assertEquals(RevocationReport(DeletionStatus.PENDING, null), f.peer(invalid).revokeAcceptance().value())
        assertTrue(assertIs<Acceptance.Revoked>(f.acceptance.state).deletionPending)
    }

    @Test fun `service current policy is validated by the model rule`() {
        val f = ServiceFixture()
        assertFailsWith<IllegalArgumentException> { AuditService(f.events, f.acceptance, f.clock, f.ids, " ", f.coordinator) }
    }
}
