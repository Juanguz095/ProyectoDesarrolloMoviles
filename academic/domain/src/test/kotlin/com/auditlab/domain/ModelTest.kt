package com.auditlab.domain

import java.util.UUID
import kotlin.test.*

class ModelTest {
    @Test fun `two kinds and two directions represent exactly four simulated buttons`() {
        val combinations = EventKind.entries.flatMap { kind -> EventDirection.entries.map { direction -> event(kind = kind, direction = direction) } }
        assertEquals(4, combinations.size)
        assertEquals(4, combinations.map { it.kind to it.direction }.toSet().size)
        assertTrue(combinations.all { it.isSimulated })
    }

    @Test fun `event stores the injected id timestamp and acceptance snapshot`() {
        val id = UUID.randomUUID(); val consent = context()
        val event = event(id, 1234, consent)
        assertEquals(id, event.eventId)
        assertEquals(1234, event.timestampEpochMs)
        assertEquals(consent, event.consent)
    }

    @Test fun `event copy cannot change simulated into real`() {
        val original = event()
        val copied = original.copy(direction = EventDirection.OUTGOING)
        assertTrue(original.isSimulated); assertTrue(copied.isSimulated)
    }

    @Test fun `event cannot precede acceptance or use negative timestamp`() {
        assertFailsWith<IllegalArgumentException> { event(time = 999, consent = context(time = 1000)) }
        assertFailsWith<IllegalArgumentException> { event(time = -1, consent = context(time = 0)) }
    }

    @Test fun `event may be generated at the exact acceptance timestamp`() {
        assertEquals(1000, event(time = 1000, consent = context(time = 1000)).timestampEpochMs)
        assertEquals(0, event(time = 0, consent = context(time = 0)).timestampEpochMs)
    }

    @Test fun `policy rejects empty whitespace controls and oversized identifiers`() {
        for (value in listOf("", " ", "bad policy", "\n", "=formula", ".invalid", "a".repeat(41))) {
            assertFailsWith<IllegalArgumentException> { context(policy = value) }
        }
    }

    @Test fun `policy allows bounded stable identifier and acceptance time cannot be negative`() {
        for (value in listOf("1", "policy-1.0_v2", "a".repeat(40))) assertEquals(value, context(policy = value).policyVersion)
        assertFailsWith<IllegalArgumentException> { context(time = -1) }
    }

    @Test fun `revoked acceptance can record unavailable time without implying a grant`() {
        assertTrue(Acceptance.Revoked(null).deletionPending)
        assertEquals(0, Acceptance.Revoked(0).revokedAtEpochMs)
        assertFalse(Acceptance.Revoked(1000, deletionPending = false).deletionPending)
        assertFailsWith<IllegalArgumentException> { Acceptance.Revoked(-1) }
        assertNotEquals<Acceptance>(Acceptance.Pending, Acceptance.Granted(context()))
    }

    @Test fun `capacity and visible history have independent fixed limits`() {
        assertEquals(1000, AuditLimits.MAX_STORED_EVENTS)
        assertEquals(100, AuditLimits.MAX_HISTORY_EVENTS)
    }
}
