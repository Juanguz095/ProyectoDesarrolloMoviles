package com.auditlab.domain

/** No constructor or load operation grants acceptance. accept() requires an explicit UI action. */
class AcceptanceService(
    private val store: AcceptanceStore,
    private val clock: EpochClock,
    private val ids: EventIdGenerator,
    private val currentPolicyVersion: String,
    private val coordinator: AuditCoordinator,
) {
    init { requirePolicyVersion(currentPolicyVersion) }

    fun load(): AuditResult<Acceptance> = coordinator.exclusive {
        try {
            val state = store.read()
            if (state is Acceptance.Granted && state.context.policyVersion != currentPolicyVersion) {
                AuditResult.Rejected(AuditError.POLICY_CHANGED)
            } else AuditResult.Success(state)
        } catch (_: StorageException) {
            AuditResult.Rejected(AuditError.ACCEPTANCE_READ_FAILED)
        }
    }

    fun accept(): AuditResult<ConsentContext> = coordinator.exclusive {
        val previous = try { store.read() } catch (_: StorageException) {
            return@exclusive AuditResult.Rejected(AuditError.ACCEPTANCE_READ_FAILED)
        }
        if (previous is Acceptance.Revoked && previous.deletionPending) {
            return@exclusive AuditResult.Rejected(AuditError.CLEANUP_REQUIRED)
        }
        if (previous is Acceptance.Granted && previous.context.policyVersion == currentPolicyVersion) {
            return@exclusive AuditResult.Success(previous.context)
        }
        val timestamp = try { clock.nowEpochMillis() } catch (_: RuntimeException) {
            return@exclusive AuditResult.Rejected(AuditError.CLOCK_UNAVAILABLE)
        }
        val notBefore = when (previous) {
            is Acceptance.Granted -> previous.context.acceptedAtEpochMs
            is Acceptance.Revoked -> previous.revokedAtEpochMs ?: 0
            Acceptance.Pending -> 0
        }
        if (timestamp < notBefore) return@exclusive AuditResult.Rejected(AuditError.INVALID_TIMESTAMP)
        val id = try { ids.nextId() } catch (_: RuntimeException) {
            return@exclusive AuditResult.Rejected(AuditError.ID_GENERATION_FAILED)
        }
        if (previous is Acceptance.Granted && previous.context.consentId == id) {
            return@exclusive AuditResult.Rejected(AuditError.ID_GENERATION_FAILED)
        }
        val context = ConsentContext(id, currentPolicyVersion, timestamp)
        try {
            store.save(Acceptance.Granted(context))
            AuditResult.Success(context)
        } catch (_: StorageException) {
            AuditResult.Rejected(AuditError.ACCEPTANCE_WRITE_FAILED)
        }
    }
}
