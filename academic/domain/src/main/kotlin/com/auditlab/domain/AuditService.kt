package com.auditlab.domain

/** Share one instance for all services using the same stores. Android scheduling belongs to I08. */
class SynchronizedAuditCoordinator : AuditCoordinator {
    private val lock = Any()
    override fun <T> exclusive(action: () -> T): T = synchronized(lock) { action() }
}

class AuditService(
    private val events: EventRepository,
    private val acceptance: AcceptanceStore,
    private val clock: EpochClock,
    private val ids: EventIdGenerator,
    private val currentPolicyVersion: String,
    private val coordinator: AuditCoordinator,
) {
    // Fail closed in this service when a requested revocation could not be durably saved.
    // No claim of persistence is made in that case; an explicit retry is required.
    private var revocationNotSaved = false

    init { requirePolicyVersion(currentPolicyVersion) }

    fun record(kind: EventKind, direction: EventDirection): AuditResult<Event> = coordinator.exclusive {
        withAcceptance { context ->
            val timestamp = try { clock.nowEpochMillis() } catch (_: RuntimeException) {
                return@withAcceptance AuditResult.Rejected(AuditError.CLOCK_UNAVAILABLE)
            }
            if (timestamp < context.acceptedAtEpochMs) return@withAcceptance AuditResult.Rejected(AuditError.INVALID_TIMESTAMP)
            val id = try { ids.nextId() } catch (_: RuntimeException) {
                return@withAcceptance AuditResult.Rejected(AuditError.ID_GENERATION_FAILED)
            }
            val event = Event(id, timestamp, kind, direction, context)
            try {
                when (events.insertIfBelowCapacity(event, AuditLimits.MAX_STORED_EVENTS)) {
                    InsertResult.INSERTED -> AuditResult.Success(event)
                    InsertResult.CAPACITY_REACHED -> AuditResult.Rejected(AuditError.CAPACITY_REACHED)
                    InsertResult.DUPLICATE_ID -> AuditResult.Rejected(AuditError.DUPLICATE_EVENT_ID)
                }
            } catch (_: StorageException) {
                AuditResult.Rejected(AuditError.STORAGE_UNAVAILABLE)
            }
        }
    }

    fun history(): AuditResult<List<Event>> = coordinator.exclusive {
        withAcceptance {
            try {
                val ordered = events.latest(AuditLimits.MAX_HISTORY_EVENTS)
                    .sortedWith(compareByDescending<Event> { it.timestampEpochMs }.thenBy { it.eventId.toString() })
                    .take(AuditLimits.MAX_HISTORY_EVENTS)
                AuditResult.Success(ordered)
            } catch (_: StorageException) {
                AuditResult.Rejected(AuditError.STORAGE_UNAVAILABLE)
            }
        }
    }

    /** Privacy operation: does not require or alter acceptance, even if its store is unreadable. */
    fun clearHistory(): AuditResult<Int> = coordinator.exclusive {
        try {
            val deleted = events.deleteAll()
            if (deleted < 0) AuditResult.Rejected(AuditError.INVALID_STORAGE_RESPONSE) else AuditResult.Success(deleted)
        } catch (_: StorageException) {
            AuditResult.Rejected(AuditError.STORAGE_UNAVAILABLE)
        }
    }

    /** Acceptance withdrawal is saved BEFORE deletion, in an independent durable operation. */
    fun revokeAcceptance(): AuditResult<RevocationReport> = coordinator.exclusive {
        // A broken clock must not prevent withdrawing acceptance; unknown time stays explicitly null.
        val timestamp = try { clock.nowEpochMillis().takeIf { it >= 0 } } catch (_: RuntimeException) { null }
        val withdrawn = Acceptance.Revoked(timestamp, deletionPending = true)
        try {
            acceptance.save(withdrawn)
        } catch (_: StorageException) {
            revocationNotSaved = true
            return@exclusive AuditResult.Rejected(AuditError.ACCEPTANCE_WRITE_FAILED)
        }
        revocationNotSaved = false
        cleanup(withdrawn)
    }

    /** Available without acceptance so a failed cleanup can be retried after restart. */
    fun retryRevokedDeletion(): AuditResult<RevocationReport> = coordinator.exclusive {
        if (revocationNotSaved) return@exclusive AuditResult.Rejected(AuditError.REVOCATION_NOT_CONFIRMED)
        val state = try { acceptance.read() } catch (_: StorageException) {
            return@exclusive AuditResult.Rejected(AuditError.ACCEPTANCE_READ_FAILED)
        }
        if (state !is Acceptance.Revoked) return@exclusive AuditResult.Rejected(AuditError.NO_REVOKED_ACCEPTANCE)
        if (!state.deletionPending) return@exclusive AuditResult.Success(RevocationReport(DeletionStatus.CLEARED, 0))
        cleanup(state)
    }

    private fun cleanup(withdrawn: Acceptance.Revoked): AuditResult<RevocationReport> {
        val deleted = try { events.deleteAll() } catch (_: StorageException) {
            return AuditResult.Success(RevocationReport(DeletionStatus.PENDING, null))
        }
        if (deleted < 0) return AuditResult.Success(RevocationReport(DeletionStatus.PENDING, null))
        return try {
            acceptance.save(withdrawn.copy(deletionPending = false))
            AuditResult.Success(RevocationReport(DeletionStatus.CLEARED, deleted))
        } catch (_: StorageException) {
            // Data was removed, but the pending flag was not confirmed. Keep withdrawal in force.
            AuditResult.Success(RevocationReport(DeletionStatus.CLEARED_CONFIRMATION_PENDING, deleted))
        }
    }

    private fun <T> withAcceptance(action: (ConsentContext) -> AuditResult<T>): AuditResult<T> {
        if (revocationNotSaved) return AuditResult.Rejected(AuditError.REVOCATION_NOT_CONFIRMED)
        val state = try { acceptance.read() } catch (_: StorageException) {
            return AuditResult.Rejected(AuditError.ACCEPTANCE_READ_FAILED)
        }
        if (state !is Acceptance.Granted) return AuditResult.Rejected(AuditError.ACCEPTANCE_REQUIRED)
        if (state.context.policyVersion != currentPolicyVersion) return AuditResult.Rejected(AuditError.POLICY_CHANGED)
        return action(state.context)
    }
}
