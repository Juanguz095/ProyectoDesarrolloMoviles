package com.auditlab.domain

import java.util.UUID
import kotlin.test.*

class AcceptanceServiceTest {
    private class Fixture {
        val store = InMemoryAcceptance()
        val coordinator = SynchronizedAuditCoordinator()
        var time = 2000L
        var id = UUID(0, 2)
        var clockFailure = false
        var idFailure = false
        var clockCalls = 0
        var idCalls = 0
        val service = AcceptanceService(store, EpochClock {
            clockCalls++; if (clockFailure) error("clock") else time
        }, EventIdGenerator {
            idCalls++; if (idFailure) error("id") else id
        }, "1.0", coordinator)
    }

    @Test fun `load missing decision returns pending without creating acceptance`() {
        val f = Fixture()
        assertEquals(AuditResult.Success(Acceptance.Pending), f.service.load())
        assertEquals(0, f.store.saveAttempts); assertEquals(0, f.clockCalls); assertEquals(0, f.idCalls)
    }

    @Test fun `load recovers decision without replacing its context`() {
        val f = Fixture(); val state = Acceptance.Granted(context()); f.store.state = state
        assertEquals(AuditResult.Success(state), f.service.load())
        assertEquals(0, f.store.saveAttempts)
    }

    @Test fun `load read failure cannot appear as acceptance`() {
        val f = Fixture(); f.store.failRead = true
        assertEquals(AuditResult.Rejected(AuditError.ACCEPTANCE_READ_FAILED), f.service.load())
        assertEquals(0, f.store.saveAttempts)
    }

    @Test fun `load stale policy rejects but preserves stored decision`() {
        val f = Fixture(); val old = Acceptance.Granted(context(policy = "old")); f.store.state = old
        assertEquals(AuditResult.Rejected(AuditError.POLICY_CHANGED), f.service.load())
        assertEquals(old, f.store.state)
    }

    @Test fun `revoked state loads as withdrawn whether deletion is pending or complete`() {
        for (pending in listOf(true, false)) {
            val f = Fixture(); val state = Acceptance.Revoked(null, pending); f.store.state = state
            assertEquals(AuditResult.Success(state), f.service.load()); assertEquals(0, f.store.saveAttempts)
        }
    }

    @Test fun `explicit accept generates context and returns only after saved`() {
        val f = Fixture()
        val expected = ConsentContext(f.id, "1.0", f.time)
        assertEquals(AuditResult.Success(expected), f.service.accept())
        assertEquals(Acceptance.Granted(expected), f.store.state); assertEquals(1, f.store.saveAttempts)
    }

    @Test fun `repeated accept for current policy is idempotent without generating ids`() {
        val f = Fixture(); val state = Acceptance.Granted(context()); f.store.state = state
        assertEquals(AuditResult.Success(state.context), f.service.accept())
        assertEquals(0, f.store.saveAttempts); assertEquals(0, f.clockCalls); assertEquals(0, f.idCalls)
    }

    @Test fun `pending cleanup blocks new acceptance without side effects`() {
        val f = Fixture(); val withdrawn = Acceptance.Revoked(1500, true); f.store.state = withdrawn
        assertEquals(AuditResult.Rejected(AuditError.CLEANUP_REQUIRED), f.service.accept())
        assertEquals(withdrawn, f.store.state); assertEquals(0, f.store.saveAttempts); assertEquals(0, f.idCalls)
    }

    @Test fun `completed cleanup permits explicit acceptance with a new context`() {
        val f = Fixture(); f.store.state = Acceptance.Revoked(1500, false)
        val granted = assertIs<AuditResult.Success<ConsentContext>>(f.service.accept()).value
        assertEquals(f.id, granted.consentId); assertEquals(Acceptance.Granted(granted), f.store.state)
    }

    @Test fun `new policy requires explicit action and replaces context with new id`() {
        val f = Fixture(); val old = context(policy = "old"); f.store.state = Acceptance.Granted(old)
        val granted = assertIs<AuditResult.Success<ConsentContext>>(f.service.accept()).value
        assertEquals("1.0", granted.policyVersion); assertNotEquals(old.consentId, granted.consentId)
    }

    @Test fun `failed acceptance write is not success and does not silently enable generation`() {
        val f = Fixture(); f.store.failSaves += 1
        assertEquals(AuditResult.Rejected(AuditError.ACCEPTANCE_WRITE_FAILED), f.service.accept())
        assertEquals(Acceptance.Pending, f.store.state)
    }

    @Test fun `read failure blocks explicit accept before obtaining clock or id`() {
        val f = Fixture(); f.store.failRead = true
        assertEquals(AuditResult.Rejected(AuditError.ACCEPTANCE_READ_FAILED), f.service.accept())
        assertEquals(0, f.clockCalls); assertEquals(0, f.idCalls); assertEquals(0, f.store.saveAttempts)
    }

    @Test fun `invalid clock never saves acceptance`() {
        for (previous in listOf(Acceptance.Pending, Acceptance.Revoked(3000, false), Acceptance.Granted(context(time = 3000, policy = "old")))) {
            val f = Fixture(); f.time = -1; f.store.state = previous
            assertEquals(AuditResult.Rejected(AuditError.INVALID_TIMESTAMP), f.service.accept()); assertEquals(0, f.idCalls)
        }
        val f = Fixture(); f.store.state = Acceptance.Revoked(3000, false)
        assertEquals(AuditResult.Rejected(AuditError.INVALID_TIMESTAMP), f.service.accept())
    }

    @Test fun `clock and id failures leave previous decision unchanged`() {
        val f = Fixture(); f.clockFailure = true
        assertEquals(AuditResult.Rejected(AuditError.CLOCK_UNAVAILABLE), f.service.accept())
        f.clockFailure = false; f.idFailure = true
        assertEquals(AuditResult.Rejected(AuditError.ID_GENERATION_FAILED), f.service.accept())
        assertEquals(Acceptance.Pending, f.store.state); assertEquals(0, f.store.saveAttempts)
    }

    @Test fun `reused id on policy change is rejected`() {
        val f = Fixture(); f.store.state = Acceptance.Granted(ConsentContext(f.id, "old", 1000))
        assertEquals(AuditResult.Rejected(AuditError.ID_GENERATION_FAILED), f.service.accept())
        assertEquals(0, f.store.saveAttempts)
    }

    @Test fun `invalid policy version is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            AcceptanceService(InMemoryAcceptance(), EpochClock { 2000 }, EventIdGenerator { UUID.randomUUID() }, "=bad", SynchronizedAuditCoordinator())
        }
    }

    @Test fun `acceptance alone does not generate an event`() {
        val f = Fixture(); val events = InMemoryEvents()
        val audit = AuditService(events, f.store, EpochClock { 2000 }, EventIdGenerator { UUID.randomUUID() }, "1.0", f.coordinator)
        assertEquals(AuditResult.Rejected(AuditError.ACCEPTANCE_REQUIRED), audit.record(EventKind.CALL, EventDirection.INCOMING))
        assertIs<AuditResult.Success<ConsentContext>>(f.service.accept()); assertTrue(events.snapshot.isEmpty())
        assertIs<AuditResult.Success<Event>>(audit.record(EventKind.CALL, EventDirection.INCOMING))
    }
}
