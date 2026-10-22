package com.auditlab.domain

import java.io.IOException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ExportServiceTest {
    private class Fixture {
        val events = InMemoryEvents()
        val store = InMemoryAcceptance(Acceptance.Granted(context()))
        val coordinator = SynchronizedAuditCoordinator()
        val audit = AuditService(events, store, EpochClock { 3000 }, EventIdGenerator { UUID.randomUUID() }, "1.0", coordinator)
        var calls = 0
        var snapshotFailure = false
        var overrideSnapshot: List<Event>? = null
        val service = ExportService(audit, EventSnapshotSource {
            calls++; if (snapshotFailure) throw StorageException()
            overrideSnapshot ?: events.snapshot
        }, coordinator)
        fun permit() = assertIs<AuditResult.Success<ExportPermit>>(service.prepare()).value
        fun seed(size: Int) = repeat(size) { events.insertIfBelowCapacity(event(id = UUID(0, it.toLong())), 1000) }
    }

    @Test fun `prepare requires readable acceptance and current policy without reading events`() {
        for ((state, reason) in listOf(Acceptance.Pending to AuditError.ACCEPTANCE_REQUIRED,
            Acceptance.Revoked(null, true) to AuditError.ACCEPTANCE_REQUIRED,
            Acceptance.Granted(context(policy = "old")) to AuditError.POLICY_CHANGED)) {
            val f = Fixture(); f.store.state = state
            assertEquals(AuditResult.Rejected(reason), f.service.prepare()); assertEquals(0, f.calls)
        }
        val f = Fixture(); f.store.failRead = true
        assertEquals(AuditResult.Rejected(AuditError.ACCEPTANCE_READ_FAILED), f.service.prepare())
    }

    @Test fun `prepare does not read snapshot or open a destination`() {
        val f = Fixture(); f.permit(); assertEquals(0, f.calls)
    }

    @Test fun `empty accepted export writes only UTF8 header and reports zero`() {
        val f = Fixture(); var bytes: ByteArray? = null
        assertEquals(AuditResult.Success(ExportReport(0)), f.service.write(f.permit(), CsvDestination { bytes = it }))
        assertContentEquals((CsvReport.HEADER + "\r\n").toByteArray(Charsets.UTF_8), bytes)
    }

    @Test fun `export writes all snapshot IDs rather than the hundred visible rows`() {
        val f = Fixture(); f.seed(150); var csv = ""
        assertEquals(AuditResult.Success(ExportReport(150)), f.service.write(f.permit(), CsvDestination { csv = it.toString(Charsets.UTF_8) }))
        assertEquals(100, assertIs<AuditResult.Success<List<Event>>>(f.audit.history()).value.size)
        assertEquals(CsvReport.render(f.events.snapshot), csv); assertEquals(1, f.calls)
    }

    @Test fun `withdrawal while selector is open blocks destination before any bytes`() {
        val f = Fixture(); val permit = f.permit(); f.audit.revokeAcceptance()
        assertEquals(AuditResult.Rejected(AuditError.ACCEPTANCE_REQUIRED), f.service.write(permit, CsvDestination { fail("Must not open") }))
        assertEquals(0, f.calls)
    }

    @Test fun `new acceptance invalidates a permit with previous context`() {
        val f = Fixture(); val permit = f.permit(); f.store.state = Acceptance.Granted(context())
        assertEquals(AuditResult.Rejected(AuditError.EXPORT_EXPIRED), f.service.write(permit, CsvDestination { fail("Stale context") }))
    }

    @Test fun `changed acceptance timestamp also invalidates a permit even if UUID is reused`() {
        val f = Fixture(); val permit = f.permit(); f.store.state = Acceptance.Granted(permit.context.copy(acceptedAtEpochMs = 1500))
        assertEquals(AuditResult.Rejected(AuditError.EXPORT_EXPIRED), f.service.write(permit, CsvDestination { fail("Stale time") }))
    }

    @Test fun `policy change after prepare prevents writing`() {
        val f = Fixture(); val permit = f.permit(); f.store.state = Acceptance.Granted(context(policy = "new"))
        assertEquals(AuditResult.Rejected(AuditError.POLICY_CHANGED), f.service.write(permit, CsvDestination { fail("Stale policy") }))
    }

    @Test fun `unconfirmed revocation blocks export in the shared audit session`() {
        val f = Fixture(); val permit = f.permit(); f.store.failSaves += 1
        f.audit.revokeAcceptance()
        assertEquals(AuditResult.Rejected(AuditError.REVOCATION_NOT_CONFIRMED), f.service.prepare())
        assertEquals(AuditResult.Rejected(AuditError.REVOCATION_NOT_CONFIRMED), f.service.write(permit, CsvDestination { fail("Unconfirmed withdrawal") }))
    }

    @Test fun `snapshot failure is not a successful empty report`() {
        val f = Fixture(); val permit = f.permit(); f.snapshotFailure = true
        assertEquals(AuditResult.Rejected(AuditError.STORAGE_UNAVAILABLE), f.service.write(permit, CsvDestination { fail("No snapshot") }))
    }

    @Test fun `oversized or duplicate snapshot is rejected without truncation`() {
        for (invalid in listOf(List(1001) { event(id = UUID(0, it.toLong())) }, listOf(event()).let { it + it })) {
            val f = Fixture(); val permit = f.permit(); f.overrideSnapshot = invalid
            assertEquals(AuditResult.Rejected(AuditError.INVALID_STORAGE_RESPONSE), f.service.write(permit, CsvDestination { fail("Invalid snapshot") }))
        }
    }

    @Test fun `destination IO or permission failures are explicit`() {
        for (failure in listOf(IOException("provider"), SecurityException("permission"))) {
            val f = Fixture()
            assertEquals(AuditResult.Rejected(AuditError.EXPORT_WRITE_FAILED), f.service.write(f.permit(), CsvDestination { throw failure }))
        }
    }

    @Test fun `export keeps coordinator through destination close before revocation confirms`() {
        val f = Fixture(); f.seed(2); val permit = f.permit()
        val pool = Executors.newFixedThreadPool(2); val opened = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val export = pool.submit(Callable { f.service.write(permit, CsvDestination { opened.countDown(); check(release.await(5, TimeUnit.SECONDS)) }) })
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            val revoked = pool.submit(Callable { f.audit.revokeAcceptance() })
            assertFalse(revoked.isDone)
            release.countDown()
            assertEquals(AuditResult.Success(ExportReport(2)), export.get(5, TimeUnit.SECONDS))
            assertIs<AuditResult.Success<RevocationReport>>(revoked.get(5, TimeUnit.SECONDS))
            assertTrue(f.events.snapshot.isEmpty()); assertIs<Acceptance.Revoked>(f.store.state)
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
