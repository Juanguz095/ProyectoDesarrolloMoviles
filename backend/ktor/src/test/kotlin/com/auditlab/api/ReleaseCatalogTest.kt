package com.auditlab.api

import com.auditlab.common.AppRelease
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ReleaseCatalogTest {
    private fun release()=AppRelease(UUID.randomUUID().toString(),"agent","com.auditlab.agent","0.8.0-test",15,26,1000,
        "a".repeat(64),"b".repeat(64),"https://raw.githubusercontent.com/Juanguz095/ProyectoDesarrolloMoviles/refs/heads/descargas-auditlab/auditlab-agent-0.8.0-test.apk")
    @Test fun `catalog is bounded and cannot contain ambiguous release or version identifiers`() {
        val value=release()
        assertTrue(ReleaseCatalog(emptyList()).items.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {ReleaseCatalog(listOf(value,value))}
        assertThrows(IllegalArgumentException::class.java) {ReleaseCatalog(listOf(value,value.copy(releaseId=UUID.randomUUID().toString())))}
    }
    @Test fun `operator metadata round trips and rejects additional or nonintegral fields`() {
        val value=release()
        assertEquals(value,releaseFromJson(json.valueToTree(value.wire())))
        assertThrows(ApiFailure::class.java) {releaseFromJson(json.valueToTree(value.wire()+mapOf("command" to "not permitted")))}
        assertThrows(IllegalArgumentException::class.java) {releaseFromJson(json.valueToTree(value.wire()+mapOf("versionCode" to "15")))}
    }
}
