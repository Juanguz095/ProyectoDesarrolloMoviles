package com.auditlab.domain

import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class AuditConcurrencyTest {
    @Test fun `two services sharing coordinator cannot exceed capacity under concurrent generation`() {
        val events = InMemoryEvents()
        val acceptance = InMemoryAcceptance(Acceptance.Granted(context()))
        val coordinator = SynchronizedAuditCoordinator()
        val ids = AtomicLong()
        val services = List(2) { AuditService(events, acceptance, EpochClock { 2000 }, EventIdGenerator { UUID(0, ids.incrementAndGet()) }, "1.0", coordinator) }
        val pool = Executors.newFixedThreadPool(8); val gate = CountDownLatch(1)
        try {
            val jobs = (0 until 1100).map { i -> pool.submit(Callable {
                assertTrue(gate.await(10, TimeUnit.SECONDS))
                services[i % 2].record(EventKind.MESSAGE, EventDirection.OUTGOING)
            }) }
            gate.countDown()
            val results = jobs.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1000, results.count { it is AuditResult.Success })
            assertEquals(100, results.count { it == AuditResult.Rejected(AuditError.CAPACITY_REACHED) })
            assertEquals(1000, events.snapshot.size)
            assertEquals(1000, events.snapshot.map { it.eventId }.toSet().size)
        } finally { pool.shutdownNow() }
    }

    @Test fun `record racing revocation never leaves an event after confirmed withdrawal`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(30) {
                val f = ServiceFixture(); val peer = f.peer(); val gate = CountDownLatch(1)
                val record = pool.submit(Callable { assertTrue(gate.await(5, TimeUnit.SECONDS)); f.service.record(EventKind.CALL, EventDirection.INCOMING) })
                val revoke = pool.submit(Callable { assertTrue(gate.await(5, TimeUnit.SECONDS)); peer.revokeAcceptance() })
                gate.countDown()
                val recorded = record.get(10, TimeUnit.SECONDS)
                assertIs<AuditResult.Success<RevocationReport>>(revoke.get(10, TimeUnit.SECONDS))
                assertTrue(recorded is AuditResult.Success || recorded == AuditResult.Rejected(AuditError.ACCEPTANCE_REQUIRED))
                assertTrue(f.events.snapshot.isEmpty())
                assertIs<Acceptance.Revoked>(f.acceptance.state)
                assertEquals(AuditResult.Rejected(AuditError.ACCEPTANCE_REQUIRED), f.service.record(EventKind.CALL, EventDirection.OUTGOING))
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun `failed deletion during concurrent record and revoke still leaves acceptance withdrawn`() {
        val f = ServiceFixture(); f.events.failDelete = true
        val pool = Executors.newFixedThreadPool(2); val gate = CountDownLatch(1)
        try {
            val record = pool.submit(Callable { assertTrue(gate.await(5, TimeUnit.SECONDS)); f.service.record(EventKind.MESSAGE, EventDirection.INCOMING) })
            val revoke = pool.submit(Callable { assertTrue(gate.await(5, TimeUnit.SECONDS)); f.peer().revokeAcceptance() })
            gate.countDown(); record.get(10, TimeUnit.SECONDS)
            assertEquals(DeletionStatus.PENDING, assertIs<AuditResult.Success<RevocationReport>>(revoke.get(10, TimeUnit.SECONDS)).value.deletionStatus)
            assertTrue(assertIs<Acceptance.Revoked>(f.acceptance.state).deletionPending)
            assertEquals(AuditResult.Rejected(AuditError.ACCEPTANCE_REQUIRED), f.peer().record(EventKind.CALL, EventDirection.OUTGOING))
        } finally { pool.shutdownNow() }
    }
}
