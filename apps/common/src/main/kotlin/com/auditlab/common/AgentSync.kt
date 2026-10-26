package com.auditlab.common

enum class SyncDecision { DONE, RETRY, FAILED }

/** Event work can never use a credential retained exclusively for withdrawal. */
class AgentSync(private val journal: AgentJournal, private val gate: AgentGate, private val core: () -> AgentCore) {
    fun run(expectedDevice: String, privacyOnly: Boolean): SyncDecision = synchronized(gate) {
        try {
            val registration = journal.registration() ?: return@synchronized SyncDecision.DONE
            if (registration.deviceId != expectedDevice) return@synchronized SyncDecision.DONE
            if (privacyOnly) {
                if (registration.phase != AgentPhase.REVOKING) return@synchronized SyncDecision.DONE
                core().retryRevocation()
                if (journal.registration()?.phase == AgentPhase.REVOKING) SyncDecision.RETRY else SyncDecision.DONE
            } else {
                if (registration.phase != AgentPhase.ACTIVE) return@synchronized SyncDecision.DONE
                core().flushPending()
                if (journal.stats().pending > 0) SyncDecision.RETRY else SyncDecision.DONE
            }
        } catch (_: MonitoringPaused) { SyncDecision.DONE }
        catch (error: ApiException) { if (error.isRetryable) SyncDecision.RETRY else SyncDecision.FAILED }
        catch (_: StorageFailure) { SyncDecision.RETRY }
        catch (_: IllegalStateException) { SyncDecision.FAILED }
    }
}
