package com.auditlab.domain

import java.util.UUID
import kotlin.test.*

class RepositoryContractTest {
    @Test fun `in memory adapter atomically rejects the insertion beyond its capacity`() {
        val repository = InMemoryEvents()
        assertEquals(InsertResult.INSERTED, repository.insertIfBelowCapacity(event(), 1))
        val before = repository.snapshot
        assertEquals(InsertResult.CAPACITY_REACHED, repository.insertIfBelowCapacity(event(), 1))
        assertEquals(before, repository.snapshot)
    }

    @Test fun `duplicate id never overwrites the existing record`() {
        val repository = InMemoryEvents(); val original = event()
        repository.insertIfBelowCapacity(original, 1000)
        assertEquals(InsertResult.DUPLICATE_ID, repository.insertIfBelowCapacity(original.copy(direction = EventDirection.OUTGOING), 1000))
        assertEquals(listOf(original), repository.snapshot)
    }

    @Test fun `latest returns bounded and stable order even when timestamps tie`() {
        val repository = InMemoryEvents(); val consent = context()
        val older = event(UUID(0, 1), 1000, consent)
        val firstTie = event(UUID(0, 2), 3000, consent)
        val secondTie = event(UUID(0, 3), 3000, consent)
        for (entry in listOf(older, secondTie, firstTie)) repository.insertIfBelowCapacity(entry, 1000)
        assertEquals(listOf(firstTie, secondTie), repository.latest(2))
        assertEquals(listOf(firstTie, secondTie, older), repository.latest(100))
        assertFailsWith<IllegalArgumentException> { repository.latest(0) }
    }

    @Test fun `deletion count is accurate and repeating deletion is harmless`() {
        val repository = InMemoryEvents()
        repeat(3) { repository.insertIfBelowCapacity(event(), 1000) }
        assertEquals(3, repository.deleteAll()); assertEquals(0, repository.deleteAll())
        assertTrue(repository.snapshot.isEmpty())
    }

    @Test fun `test decision adapter defaults to pending and retains withdrawn decision`() {
        val store = InMemoryAcceptance()
        assertEquals(Acceptance.Pending, store.read())
        store.save(Acceptance.Granted(context()))
        store.save(Acceptance.Revoked(2000))
        assertEquals(Acceptance.Revoked(2000), store.read())
        assertFailsWith<IllegalStateException> { store.save(Acceptance.Granted(context())) }
        store.save(Acceptance.Revoked(2000, deletionPending = false))
        store.save(Acceptance.Granted(context()))
        assertIs<Acceptance.Granted>(store.read())
    }

    @Test fun `adapter storage failures do not report a grant or partial deletion`() {
        val store = InMemoryAcceptance(); store.failRead = true
        assertFailsWith<StorageException> { store.read() }
        val repository = InMemoryEvents(); val original = event()
        repository.insertIfBelowCapacity(original, 1000); repository.failDelete = true
        assertFailsWith<StorageException> { repository.deleteAll() }
        assertEquals(listOf(original), repository.snapshot)
    }
}
