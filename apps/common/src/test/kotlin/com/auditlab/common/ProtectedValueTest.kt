package com.auditlab.common

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class ProtectedValueTest {
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    private var stored: String? = null
    private var writable = true
    private fun value(aad: String = "qa:account:v1") = ProtectedValue({ stored },
        { next -> if (writable) { stored = next; true } else false }, { key }, aad)
    @Test fun `round trip and distinct IV for every write`() {
        value().save("fixture-only"); val first = stored
        assertFalse(stored!!.contains("fixture-only")); assertEquals("fixture-only", value().load())
        value().save("fixture-only"); assertNotEquals(first, stored)
    }
    @Test fun `tampered ciphertext is refused and preserved`() {
        value().save("fixture-only"); val bytes = Base64.getDecoder().decode(stored!!.substring(3))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); stored = "v1:" + Base64.getEncoder().encodeToString(bytes)
        val corrupt = stored; assertThrows(StorageFailure::class.java) { value().load() }; assertEquals(corrupt, stored)
    }
    @Test fun `another context cannot decrypt the value`() {
        value().save("fixture-only"); assertThrows(StorageFailure::class.java) { value("qa:other:v1").load() }
    }
    @Test fun `failed save and clear are not acknowledged`() {
        value().save("fixture-only"); val previous = stored; writable = false
        assertThrows(StorageFailure::class.java) { value().save("replacement") }
        assertThrows(StorageFailure::class.java) { value().clear() }; assertEquals(previous, stored)
    }
    @Test fun `legacy encrypted value migrates only after a valid decrypt`() {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
        stored = Base64.getEncoder().encodeToString(c.iv + c.doFinal("fixture-only".toByteArray()))
        assertEquals("fixture-only", value().load()); assertTrue(stored!!.startsWith("v1:"))
    }
    @Test fun `legacy migration must be persisted before returning the secret`() {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
        stored = Base64.getEncoder().encodeToString(c.iv + c.doFinal("fixture-only".toByteArray())); writable = false
        assertThrows(StorageFailure::class.java) { value().load() }
    }
    @Test fun `truncated unsupported and oversized values are refused`() {
        for (bad in listOf("a", "v2:" + "a".repeat(80), "v1:" + "a".repeat(10), "a".repeat(40_000))) {
            stored = bad; assertThrows(StorageFailure::class.java) { value().load() }; assertEquals(bad, stored)
        }
    }
    @Test fun `empty store never requests a key and unavailable key never clears the value`() {
        val protected = ProtectedValue({ stored }, { stored = it; true }, { error("no key") }, "qa")
        assertNull(protected.load()); value().save("fixture-only"); val previous = stored
        assertThrows(StorageFailure::class.java) { protected.load() }; assertEquals(previous, stored)
    }
    @Test fun `secret models have redacted string rendering`() {
        val context = ConsentContext("id", "mvp-1", "2026-10-25T00:00:00Z")
        assertFalse(StoredCredential("id", "fixture-token", context).toString().contains("fixture-token"))
        assertFalse(EnrollmentCode("fixture-code", 600, "soon").toString().contains("fixture-code"))
        assertFalse(Session("fixture-access", "fixture-refresh", "Bearer", 900, "id", "u", "qa", "admin").toString().contains("fixture-access"))
    }
}
