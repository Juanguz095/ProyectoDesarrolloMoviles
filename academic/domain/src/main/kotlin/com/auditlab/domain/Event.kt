package com.auditlab.domain

import java.util.UUID

private val policyVersionPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,39}")

internal fun requirePolicyVersion(version: String) {
    require(policyVersionPattern.matches(version)) { "Policy version must contain 1 to 40 identifier characters" }
}

enum class EventKind { CALL, MESSAGE }
enum class EventDirection { INCOMING, OUTGOING }

/** Snapshot of the explicit acceptance in force when an event is generated. */
data class ConsentContext(
    val consentId: UUID,
    val policyVersion: String,
    val acceptedAtEpochMs: Long,
) {
    init {
        requirePolicyVersion(policyVersion)
        require(acceptedAtEpochMs >= 0) { "Acceptance timestamp cannot be negative" }
    }
}

/** Own application event. There are no phone numbers, message bodies or external data. */
data class Event(
    val eventId: UUID,
    val timestampEpochMs: Long,
    val kind: EventKind,
    val direction: EventDirection,
    val consent: ConsentContext,
) {
    init {
        require(timestampEpochMs >= consent.acceptedAtEpochMs) {
            "Event timestamp cannot precede acceptance"
        }
    }

    // Not a constructor argument: consumers cannot construct or copy a real event.
    val isSimulated: Boolean get() = true
}
