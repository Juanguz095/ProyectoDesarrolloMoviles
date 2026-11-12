package com.auditlab.common

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConsentModulesTest {
    @Test fun `every module has a stable policy version`() {
        assertEquals(ConsentModulesPolicy.policies.keys.toList(), ConsentModulesPolicy.labels.keys.toList())
        assertEquals(ManualSharePolicy.VERSION, ConsentModulesPolicy.policies[ConsentModulesPolicy.MANUAL_SHARE])
        ConsentModulesPolicy.policies.forEach { (module, policy) ->
            ConsentModulesPolicy.validate(module, ModuleState.ACCEPTED, policy)
            ConsentModulesPolicy.validate(module, ModuleState.PAUSED, policy)
            ConsentModulesPolicy.validate(module, ModuleState.REVOKED, policy)
        }
    }

    @Test fun `unknown module wrong state or wrong policy are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConsentModulesPolicy.validate("camera", ModuleState.ACCEPTED, "camera-1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConsentModulesPolicy.validate(ConsentModulesPolicy.BASE, "PENDING", "base-1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConsentModulesPolicy.validate(ConsentModulesPolicy.BASE, ModuleState.ACCEPTED, "base-2")
        }
    }

    @Test fun `reserved video module is not accepted before its phase`() {
        assertTrue(ConsentModulesPolicy.available.contains(ConsentModulesPolicy.GALLERY))
        assertTrue(ConsentModulesPolicy.available.contains(ConsentModulesPolicy.LOCATION))
        assertFalse(ConsentModulesPolicy.available.contains(ConsentModulesPolicy.VIDEO))
        assertThrows(IllegalStateException::class.java) { ConsentModulesPolicy.policyOf("camera") }
    }
}
