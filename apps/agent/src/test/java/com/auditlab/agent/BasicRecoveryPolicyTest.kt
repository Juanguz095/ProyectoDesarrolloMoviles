package com.auditlab.agent

import com.auditlab.common.*
import org.junit.Assert.*
import org.junit.Test

class BasicRecoveryPolicyTest {
    private val consent=ConsentContext("consent-a","mvp-2-real","2026-10-30T10:00:00Z")
    private val state=AgentCore.State.Enrolled("device-a",consent)
    private val receipt=BasicRecoveryReceipt("device-a","consent-a",true)
    private fun allowed(receipt: BasicRecoveryReceipt?=this.receipt,state: AgentCore.State=this.state,
                        paused: Boolean=false,notifications: Boolean=true,withdrawn: Boolean=false,
                        blocked: Boolean=false,automatic: Boolean=true)=
        BasicRecoveryPolicy.allows(receipt,state,paused,notifications,withdrawn,blocked,automatic)
    @Test fun bootRequiresExplicitOptInForTheSameEnrollmentAndConsent() {
        assertTrue(allowed())
        assertFalse(allowed(receipt=null))
        assertFalse(allowed(receipt=receipt.copy(autoResume=false)))
        assertFalse(allowed(receipt=receipt.copy(device="device-b")))
        assertFalse(allowed(receipt=receipt.copy(consent="consent-b")))
        assertTrue(allowed(receipt=receipt.copy(autoResume=false),automatic=false))
    }
    @Test fun pausedRevokedBlockedOrInvisibleNeverRecovers() {
        assertFalse(allowed(paused=true))
        assertFalse(allowed(notifications=false))
        assertFalse(allowed(withdrawn=true))
        assertFalse(allowed(blocked=true))
        assertFalse(allowed(state=AgentCore.State.ConsentPending))
        assertFalse(allowed(state=AgentCore.State.Blocked("invalid")))
        assertFalse(allowed(state=AgentCore.State.Revoked("device-a",AgentCore.RevokeReason.LOCAL)))
    }
}
