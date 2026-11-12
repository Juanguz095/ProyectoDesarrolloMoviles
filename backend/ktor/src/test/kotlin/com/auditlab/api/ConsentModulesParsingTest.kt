package com.auditlab.api

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConsentModulesParsingTest {
    private val mapper = ObjectMapper()

    @Test fun `valid module input keeps module policy and state`() {
        val parsed = parseConsentModule(mapper.readTree("""{"module":"whatsapp","policyVersion":"whatsapp-preview-2","state":"PAUSED"}"""))
        assertEquals("whatsapp", parsed.module)
        assertEquals("whatsapp-preview-2", parsed.policyVersion)
        assertEquals("PAUSED", parsed.state)
        assertEquals(10, consentModulePolicies.size)
    }

    @Test fun `unknown module wrong policy wrong state or extra keys are rejected`() {
        assertThrows(ApiFailure::class.java) {
            parseConsentModule(mapper.readTree("""{"module":"camera","policyVersion":"camera-1","state":"ACCEPTED"}"""))
        }
        assertThrows(ApiFailure::class.java) {
            parseConsentModule(mapper.readTree("""{"module":"base","policyVersion":"base-2","state":"ACCEPTED"}"""))
        }
        assertThrows(ApiFailure::class.java) {
            parseConsentModule(mapper.readTree("""{"module":"base","policyVersion":"base-1","state":"PENDING"}"""))
        }
        assertThrows(ApiFailure::class.java) {
            parseConsentModule(mapper.readTree("""{"module":"base","policyVersion":"base-1","state":"ACCEPTED","extra":1}"""))
        }
    }

    @Test fun `module lists validate every element`() {
        val parsed = parseConsentModules(mapper.readTree("""[{"module":"base","policyVersion":"base-1","state":"ACCEPTED"},{"module":"sms_text","policyVersion":"sms-text-1","state":"REVOKED"}]"""))
        assertEquals(listOf("base", "sms_text"), parsed.map { it.module })
        assertThrows(ApiFailure::class.java) {
            parseConsentModules(mapper.readTree("""[{"module":"base","policyVersion":"base-1","state":"ACCEPTED"},{"module":"camera","policyVersion":"camera-1","state":"ACCEPTED"}]"""))
        }
    }
}
