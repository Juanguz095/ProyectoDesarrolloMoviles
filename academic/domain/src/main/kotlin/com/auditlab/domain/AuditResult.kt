package com.auditlab.domain

object AuditLimits {
    const val MAX_STORED_EVENTS = 1000
    const val MAX_HISTORY_EVENTS = 100
}

enum class AuditError {
    ACCEPTANCE_REQUIRED,
    POLICY_CHANGED,
    ACCEPTANCE_READ_FAILED,
    ACCEPTANCE_WRITE_FAILED,
    CAPACITY_REACHED,
    DUPLICATE_EVENT_ID,
    INVALID_TIMESTAMP,
    CLOCK_UNAVAILABLE,
    ID_GENERATION_FAILED,
    STORAGE_UNAVAILABLE,
    INVALID_STORAGE_RESPONSE,
    NO_REVOKED_ACCEPTANCE,
    REVOCATION_NOT_CONFIRMED,
}

sealed interface AuditResult<out T> {
    data class Success<T>(val value: T) : AuditResult<T>
    data class Rejected(val reason: AuditError) : AuditResult<Nothing>
}

enum class DeletionStatus {
    CLEARED,
    PENDING,
    CLEARED_CONFIRMATION_PENDING,
}

/** Successful revocation means acceptance was saved as withdrawn, even when deletion is pending. */
data class RevocationReport(val deletionStatus: DeletionStatus, val deletedEvents: Int?)
