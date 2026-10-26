package com.auditlab.common

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Platform AES/GCM, random IV, contextual AAD and synchronous persistence acknowledgement. */
class ProtectedValue(
    private val read: () -> String?,
    private val persist: (String?) -> Boolean,
    private val key: (create: Boolean) -> SecretKey,
    context: String,
) {
    private val aad = context.toByteArray(Charsets.UTF_8)
    fun load(): String? = guarded {
        val stored = read() ?: return@guarded null
        require(stored.length in 24..32_768)
        val modern = stored.startsWith("v1:")
        require(modern || ':' !in stored)
        val bytes = Base64.getDecoder().decode(if (modern) stored.substring(3) else stored)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        if (modern) cipher.updateAAD(aad)
        val decoded = cipher.doFinal(bytes, 12, bytes.size - 12)
        val plain = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded)).toString()
        if (!modern) save(plain) // Successful legacy decrypt is upgraded, never used as a MAC-failure fallback.
        plain
    }
    fun save(value: String) = guarded {
        require(value.toByteArray(Charsets.UTF_8).size <= 20_000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(aad)
        require(cipher.iv.size == 12)
        val text = "v1:" + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
        check(persist(text))
    }
    fun clear() = guarded { check(persist(null)) }
    private inline fun <T> guarded(action: () -> T): T = try { action() }
    catch (_: Exception) { throw StorageFailure() }
}
