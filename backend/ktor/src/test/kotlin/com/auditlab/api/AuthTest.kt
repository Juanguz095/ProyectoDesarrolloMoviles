package com.auditlab.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

class AuthTest {
    private val key = ByteArray(32).also(SecureRandom()::nextBytes)
    private val config = AuthConfig(key)
    private val tokens = AccessTokens(config)
    private val user = UserIdentity(UUID.randomUUID(), "fixture", "admin", UUID.randomUUID())

    @Test fun `configuration rejects missing malformed and weak signing keys without echoing them`() {
        for (value in listOf("", "not-base64!", Base64.getEncoder().encodeToString(ByteArray(8)))) {
            val error = assertThrows(IllegalArgumentException::class.java) { AuthConfig.fromEnvironment(mapOf("AUDITLAB_AUTH_SIGNING_KEY_BASE64" to value)) }
            if (value.isNotEmpty()) assertFalse(error.message.orEmpty().contains(value))
        }
        assertThrows(IllegalArgumentException::class.java) { AuthConfig.fromEnvironment(emptyMap()) }
        assertEquals("AuthConfig(redacted)", config.toString())
        assertThrows(IllegalArgumentException::class.java) { AuthConfig(key, accountLimit = 0) }
        assertThrows(IllegalArgumentException::class.java) { AuthConfig(key, issuer = "bad\nissuer") }
        val loaded = AuthConfig.fromEnvironment(mapOf("AUDITLAB_AUTH_SIGNING_KEY_BASE64" to Base64.getEncoder().encodeToString(key)))
        assertEquals("auditlab-api", loaded.issuer)
    }

    @Test fun `library verifies access signature issuer audience claims and expiration`() {
        val now = Instant.now().minusSeconds(1)
        val valid = tokens.issue(user, now, now.plusSeconds(900))
        assertEquals(Triple(user.userId, user.sessionId, user.role), tokens.verify(valid))
        val mutations = listOf("bad-token", valid.dropLast(8) + "AAAAAAAA", "ar_" + "x".repeat(43))
        for (token in mutations) assertEquals("UNAUTHENTICATED", assertThrows(ApiFailure::class.java) { tokens.verify(token) }.code)
        fun signed(issuer: String = config.issuer, audience: String = config.audience, use: String = "access", type: String = "at+jwt",
                   role: String = "admin", expiry: Instant = now.plusSeconds(900), sid: String = user.sessionId.toString(),
                   issued: Instant = now, algorithm: Algorithm = Algorithm.HMAC256(key), includeExpiry: Boolean = true): String {
            val builder = JWT.create().withHeader(mapOf("typ" to type)).withIssuer(issuer).withAudience(audience)
                .withSubject(user.userId.toString()).withClaim("sid", sid).withClaim("role", role).withClaim("token_use", use)
                .withJWTId(UUID.randomUUID().toString()).withIssuedAt(Date.from(issued))
            if (includeExpiry) builder.withExpiresAt(Date.from(expiry))
            return builder.sign(algorithm)
        }
        for (token in listOf(signed(issuer = "other"), signed(audience = "other"), signed(use = "refresh"), signed(type = "JWT"),
            signed(role = "root"), signed(expiry = now.minusSeconds(1)), signed(sid = "not-uuid"), signed(includeExpiry = false),
            signed(expiry = now.plusSeconds(901)), signed(issued = now.plusSeconds(300)), signed(algorithm = Algorithm.HMAC512(key)),
            signed(algorithm = Algorithm.none()))) {
            assertEquals(401, assertThrows(ApiFailure::class.java) { tokens.verify(token) }.status)
        }
    }

    @Test fun `bearer header rejects missing ambiguous or wrong credential schemes`() {
        val token = "a".repeat(43)
        assertEquals(token, bearer("bearer $token"))
        for (header in listOf(null, "", "Basic $token", "Bearer  $token", "Bearer $token,other", "Bearer short")) {
            assertEquals(401, assertThrows(ApiFailure::class.java) { bearer(header) }.status)
        }
    }
}
