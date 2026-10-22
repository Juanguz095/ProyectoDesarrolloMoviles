package com.auditlab.domain

sealed interface Acceptance {
    data object Pending : Acceptance
    data class Granted(val context: ConsentContext) : Acceptance

    /** A pending cleanup must survive a failed event deletion; it never grants acceptance. */
    data class Revoked(
        val revokedAtEpochMs: Long?,
        val deletionPending: Boolean = true,
    ) : Acceptance {
        init {
            require(revokedAtEpochMs == null || revokedAtEpochMs >= 0) {
                "Revocation timestamp cannot be negative"
            }
        }
    }
}
