package com.auditlab.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.security.SecureRandom
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Semaphore
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ApiFailure(val status: Int, val code: String, val retryAfter: Int? = null) : RuntimeException(code)

class AuthConfig(key: ByteArray, val issuer: String = "auditlab-api", val audience: String = "auditlab-control",
                 val accountLimit: Int = 5, val clientLimit: Int = 30, val globalLimit: Int = 100,
                 val refreshClientLimit: Int = 60, val refreshGlobalLimit: Int = 300) {
    internal val signingKey = key.copyOf()
    init {
        require(key.size >= 32) { "Auth signing key must contain at least 32 random bytes" }
        require(listOf(issuer, audience).all { Regex("[A-Za-z0-9:/_.-]{1,128}").matches(it) }) { "Invalid auth issuer/audience" }
        require(accountLimit in 1..100 && clientLimit in 1..1000 && globalLimit in 1..10000 &&
            refreshClientLimit in 1..1000 && refreshGlobalLimit in 1..10000) { "Invalid auth request limits" }
    }
    override fun toString() = "AuthConfig(redacted)"
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AuthConfig {
            val encoded = env["AUDITLAB_AUTH_SIGNING_KEY_BASE64"]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Required variable: AUDITLAB_AUTH_SIGNING_KEY_BASE64")
            val key = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Auth signing key must be valid Base64")
            }
            fun limit(name: String, default: Int) = env[name]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid auth limit: $name") } ?: default
            return AuthConfig(key, env["AUDITLAB_AUTH_ISSUER"] ?: "auditlab-api", env["AUDITLAB_AUTH_AUDIENCE"] ?: "auditlab-control",
                limit("AUDITLAB_AUTH_LOGIN_ACCOUNT_LIMIT", 5), limit("AUDITLAB_AUTH_LOGIN_CLIENT_LIMIT", 30),
                limit("AUDITLAB_AUTH_LOGIN_GLOBAL_LIMIT", 100), limit("AUDITLAB_AUTH_REFRESH_CLIENT_LIMIT", 60),
                limit("AUDITLAB_AUTH_REFRESH_GLOBAL_LIMIT", 300))
        }
    }
}

data class UserIdentity(val userId: UUID, val username: String, val role: String, val sessionId: UUID)

/** Authenticated agent device. The consent row is read under the same device lock as ingestion. */
data class DeviceIdentity(val deviceId: UUID, val consentId: UUID, val policyVersion: String,
                          val acceptedAt: java.time.Instant, val revoked: Boolean)

class AccessTokens(private val config: AuthConfig, private val clock: Clock = Clock.systemUTC()) {
    private val algorithm = Algorithm.HMAC256(config.signingKey)
    private val verifier = JWT.require(algorithm).withIssuer(config.issuer).withAudience(config.audience)
        .withClaim("token_use", "access").withClaimPresence("exp").withClaimPresence("iat")
        .withClaimPresence("jti").build()

    fun issue(user: UserIdentity, now: Instant, expiry: Instant): String = JWT.create()
        .withHeader(mapOf("typ" to "at+jwt")).withIssuer(config.issuer).withAudience(config.audience)
        .withSubject(user.userId.toString()).withClaim("sid", user.sessionId.toString()).withClaim("role", user.role)
        .withClaim("token_use", "access").withJWTId(UUID.randomUUID().toString())
        .withIssuedAt(Date.from(now)).withExpiresAt(Date.from(expiry)).sign(algorithm)

    internal fun verify(token: String): Triple<UUID, UUID, String> {
        try {
            if (token.length !in 32..4096) throw IllegalArgumentException()
            val decoded = verifier.verify(token)
            val now = clock.instant()
            val issued = decoded.issuedAt.toInstant()
            val expiry = decoded.expiresAt.toInstant()
            require(decoded.type == "at+jwt" && expiry.isAfter(now) && !issued.isAfter(now) &&
                expiry.isAfter(issued) && !expiry.isAfter(issued.plusSeconds(900)))
            val role = decoded.getClaim("role").asString()
            require(role in setOf("admin", "auditor"))
            UUID.fromString(decoded.id)
            return Triple(UUID.fromString(decoded.subject), UUID.fromString(decoded.getClaim("sid").asString()), role)
        } catch (_: JWTVerificationException) {
            throw ApiFailure(401, "UNAUTHENTICATED")
        } catch (_: IllegalArgumentException) {
            throw ApiFailure(401, "UNAUTHENTICATED")
        } catch (_: NullPointerException) {
            throw ApiFailure(401, "UNAUTHENTICATED")
        }
    }
}

private data class Outcome<T>(val value: T? = null, val error: ApiFailure? = null) {
    fun get(): T { error?.let { throw it }; return checkNotNull(value) }
}

class AuthService(private val database: Database, private val config: AuthConfig, private val clock: Clock = Clock.systemUTC()) {
    private val access = AccessTokens(config, clock)
    private val random = SecureRandom()
    private val hashingSlots = Semaphore(4)
    // Equal-cost verification for unknown users. Generated once, never a usable account.
    private val dummyHash = Passwords.hash(opaque())
    private fun now() = clock.instant().truncatedTo(ChronoUnit.SECONDS)
    private fun opaque(): String = "ar_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun hash(token: String) = TokenHash.sha256(token)
    private fun bucketKey(text: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(config.signingKey, "HmacSHA256")); doFinal(("rate:" + text).toByteArray(Charsets.UTF_8))
    }.joinToString("") { "%02x".format(it) }

    /** Reserve before parsing/hashing. Uses the socket peer; forwarded headers are deliberately ignored. */
    fun limitRequest(kind: String, peer: String) {
        require(kind in setOf("login", "refresh"))
        reserve(listOf("$kind:global" to (if (kind == "login") config.globalLimit else config.refreshGlobalLimit),
            "$kind:client:$peer" to (if (kind == "login") config.clientLimit else config.refreshClientLimit)))
    }

    private fun reserve(buckets: List<Pair<String, Int>>) {
        val instant = now()
        val window = Instant.ofEpochSecond(instant.epochSecond / 60 * 60)
        val retry = (60 - instant.epochSecond % 60).toInt()
        val permitted = database.transaction { connection ->
            connection.prepareStatement("DELETE FROM auditlab.auth_rate_limits WHERE window_started < ?").use {
                it.setObject(1, java.sql.Timestamp.from(window.minusSeconds(600))); it.executeUpdate()
            }
            for ((key, limit) in buckets) {
                val count = connection.prepareStatement("""INSERT INTO auditlab.auth_rate_limits(bucket_key,window_started,attempt_count)
                    VALUES (?,?,1) ON CONFLICT (bucket_key) DO UPDATE SET window_started=EXCLUDED.window_started,
                    attempt_count=CASE WHEN auth_rate_limits.window_started=EXCLUDED.window_started THEN LEAST(auth_rate_limits.attempt_count+1,10001) ELSE 1 END
                    RETURNING attempt_count""").use {
                    it.setString(1, bucketKey(key)); it.setTimestamp(2, java.sql.Timestamp.from(window))
                    it.executeQuery().use { row -> row.next(); row.getInt(1) }
                }
                if (count > limit) return@transaction false
            }
            true
        }
        if (!permitted) throw ApiFailure(429, "RATE_LIMITED", retry)
    }

    fun login(username: String, password: String, correlation: UUID): Map<String, Any> {
        reserve(listOf("login:account:${username.lowercase(Locale.ROOT)}" to config.accountLimit))
        data class Candidate(val id: UUID, val hash: String, val active: Boolean)
        val candidate = database.transaction { connection ->
            connection.prepareStatement("SELECT id,password_hash,status FROM auditlab.users WHERE lower(username)=lower(?)").use {
                it.setString(1, username)
                it.executeQuery().use { row -> if (row.next()) Candidate(row.getObject(1, UUID::class.java), row.getString(2), row.getString(3) == "active") else null }
            }
        }
        if (!hashingSlots.tryAcquire()) throw ApiFailure(503, "SERVICE_UNAVAILABLE")
        val verified = try { Passwords.verify(password, candidate?.hash ?: dummyHash) } finally { hashingSlots.release() }
        return database.transaction { connection ->
            val user = candidate?.let { c -> connection.prepareStatement("SELECT username,role,status,password_hash FROM auditlab.users WHERE id=?").use {
                it.setObject(1, c.id)
                it.executeQuery().use { row ->
                    if (row.next() && row.getString(3) == "active" && row.getString(4) == c.hash)
                        UserIdentity(c.id, row.getString(1), row.getString(2), UUID.randomUUID()) else null
                }
            } }
            if (!verified || candidate?.active != true || user == null) {
                audit(connection, null, "login_failed", null, correlation, "denied")
                Outcome<Map<String, Any>>(error = ApiFailure(401, "UNAUTHENTICATED"))
            } else {
                val now = now(); val expiry = now.plusSeconds(604800)
                connection.prepareStatement("INSERT INTO auditlab.user_sessions(id,user_id,created_at,expires_at) VALUES (?,?,?,?)").use {
                    it.setObject(1, user.sessionId); it.setObject(2, user.userId); it.setTimestamp(3, java.sql.Timestamp.from(now)); it.setTimestamp(4, java.sql.Timestamp.from(expiry)); it.executeUpdate()
                }
                val refresh = opaque()
                insertRefresh(connection, user.sessionId, refresh, null, now, expiry)
                audit(connection, user, "login_succeeded", user.sessionId, correlation)
                Outcome(tokens(user, refresh, now, expiry))
            }
        }.get()
    }

    fun refresh(token: String, correlation: UUID): Map<String, Any> = database.transaction { connection ->
        val tokenHash = hash(token)
        val session = connection.prepareStatement("SELECT session_id FROM auditlab.refresh_tokens WHERE token_hash=?").use {
            it.setString(1, tokenHash); it.executeQuery().use { row -> if (row.next()) row.getObject(1, UUID::class.java) else null }
        } ?: return@transaction Outcome<Map<String, Any>>(error = ApiFailure(401, "UNAUTHENTICATED"))
        // Always lock session before token; refresh and logout use the same lock ordering.
        val (user, expiry, error) = session(connection, session)
        if (error != null) return@transaction Outcome<Map<String, Any>>(error = error)
        checkNotNull(user); checkNotNull(expiry)
        data class Refresh(val id: UUID, val used: Boolean, val revoked: Boolean, val expiry: Instant)
        val old = connection.prepareStatement("SELECT id,used_at,revoked_at,expires_at FROM auditlab.refresh_tokens WHERE token_hash=? AND session_id=?").use {
            it.setString(1, tokenHash); it.setObject(2, session)
            it.executeQuery().use { row -> if (row.next()) Refresh(row.getObject(1, UUID::class.java), row.getTimestamp(2) != null, row.getTimestamp(3) != null, row.getTimestamp(4).toInstant()) else null }
        } ?: return@transaction Outcome<Map<String, Any>>(error = ApiFailure(401, "UNAUTHENTICATED"))
        val now = now()
        if (old.used) {
            revoke(connection, session, now)
            audit(connection, user, "session_revoked", session, correlation, "denied")
            Outcome<Map<String, Any>>(error = ApiFailure(401, "REFRESH_REUSED"))
        } else if (old.revoked || !old.expiry.isAfter(now)) {
            Outcome<Map<String, Any>>(error = ApiFailure(401, "UNAUTHENTICATED"))
        } else {
            connection.prepareStatement("UPDATE auditlab.refresh_tokens SET used_at=? WHERE id=?").use {
                it.setTimestamp(1, java.sql.Timestamp.from(now)); it.setObject(2, old.id); check(it.executeUpdate() == 1)
            }
            val rotated = opaque()
            insertRefresh(connection, session, rotated, old.id, now, expiry)
            audit(connection, user, "session_refreshed", session, correlation)
            Outcome(tokens(user, rotated, now, expiry))
        }
    }.get()

    /** Every protected operation rechecks current DB status and roles while holding the session lock. */
    fun <T> authorized(token: String, roles: Set<String>, action: (Connection, UserIdentity) -> T): T {
        val (userId, sessionId, role) = access.verify(token)
        return database.transaction { connection ->
            val (user, _, error) = session(connection, sessionId)
            error?.let { throw it }
            checkNotNull(user)
            if (user.userId != userId || user.role != role) throw ApiFailure(401, "UNAUTHENTICATED")
            if (user.role !in roles) throw ApiFailure(403, "FORBIDDEN")
            action(connection, user)
        }
    }

    fun logout(token: String, correlation: UUID) = authorized(token, setOf("admin", "auditor")) { connection, user ->
        revoke(connection, user.sessionId, now())
        audit(connection, user, "session_revoked", user.sessionId, correlation)
    }

    internal fun cursorCodec(): CursorCodec = CursorCodec(config.signingKey, clock)

    /** Shared transactional entry point for routes that do not act on behalf of a user session. */
    internal fun <T> transaction(action: (Connection) -> T): T = database.transaction(action)

    /**
     * Authenticates an opaque device credential and locks its row, so revocation and ingestion are
     * serialized. A revoked device or consent never authorizes a write.
     */
    fun <T> authorizedDevice(token: String, action: (Connection, DeviceIdentity) -> T): T = database.transaction { connection ->
        val identity = connection.prepareStatement("""SELECT d.id,d.status,c.id,c.policy_version,c.accepted_at,c.revoked_at
            FROM auditlab.devices d JOIN auditlab.consents c ON c.id=d.consent_id
            WHERE d.token_hash=? FOR UPDATE OF d""").use {
            it.setString(1, hash(token))
            it.executeQuery().use { row ->
                if (!row.next()) null else DeviceIdentity(
                    row.getObject(1, UUID::class.java), row.getObject(3, UUID::class.java), row.getString(4),
                    row.getTimestamp(5).toInstant().truncatedTo(ChronoUnit.MICROS), row.getTimestamp(6) != null || row.getString(2) != "active")
            }
        } ?: throw ApiFailure(401, "UNAUTHENTICATED")
        if (identity.revoked) throw ApiFailure(401, "DEVICE_REVOKED")
        action(connection, identity)
    }

    private fun session(connection: Connection, id: UUID): Triple<UserIdentity?, Instant?, ApiFailure?> =
        connection.prepareStatement("""SELECT s.user_id,u.username,u.role,u.status,s.expires_at,s.revoked_at
            FROM auditlab.user_sessions s JOIN auditlab.users u ON u.id=s.user_id WHERE s.id=? FOR UPDATE OF s""").use {
            it.setObject(1, id)
            it.executeQuery().use { row ->
                if (!row.next()) return@use Triple(null, null, ApiFailure(401, "UNAUTHENTICATED"))
                val user = UserIdentity(row.getObject(1, UUID::class.java), row.getString(2), row.getString(3), id)
                val expiry = row.getTimestamp(5).toInstant()
                val error = when {
                    row.getString(4) != "active" -> ApiFailure(401, "UNAUTHENTICATED")
                    row.getTimestamp(6) != null -> ApiFailure(401, "SESSION_REVOKED")
                    !expiry.isAfter(now()) -> ApiFailure(401, "SESSION_EXPIRED")
                    else -> null
                }
                Triple(user, expiry, error)
            }
        }

    private fun insertRefresh(connection: Connection, session: UUID, token: String, parent: UUID?, now: Instant, expiry: Instant) {
        connection.prepareStatement("INSERT INTO auditlab.refresh_tokens(id,session_id,token_hash,parent_token_id,created_at,expires_at) VALUES (?,?,?,?,?,?)").use {
            it.setObject(1, UUID.randomUUID()); it.setObject(2, session); it.setString(3, hash(token)); it.setObject(4, parent)
            it.setTimestamp(5, java.sql.Timestamp.from(now)); it.setTimestamp(6, java.sql.Timestamp.from(expiry)); it.executeUpdate()
        }
    }

    private fun tokens(user: UserIdentity, refresh: String, now: Instant, expiry: Instant): Map<String, Any> {
        val remaining = ChronoUnit.SECONDS.between(now, expiry)
        val accessTtl = minOf(900, remaining)
        return mapOf("accessToken" to access.issue(user, now, now.plusSeconds(accessTtl)), "refreshToken" to refresh,
            "tokenType" to "Bearer", "expiresIn" to accessTtl, "refreshExpiresIn" to remaining,
            "sessionId" to user.sessionId.toString(), "user" to mapOf("userId" to user.userId.toString(), "username" to user.username, "role" to user.role))
    }

    private fun revoke(connection: Connection, session: UUID, now: Instant) {
        for (sql in listOf("UPDATE auditlab.user_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE id=?",
            "UPDATE auditlab.refresh_tokens SET revoked_at=COALESCE(revoked_at,?) WHERE session_id=?")) {
            connection.prepareStatement(sql).use { it.setTimestamp(1, java.sql.Timestamp.from(now)); it.setObject(2, session); it.executeUpdate() }
        }
    }

    private fun audit(connection: Connection, user: UserIdentity?, action: String, session: UUID?, correlation: UUID, result: String = "success") {
        connection.prepareStatement("INSERT INTO auditlab.audit_log(id,actor_type,actor_id,action,resource,result,correlation_id) VALUES (?,?,?,?,?::jsonb,?,?)").use {
            it.setObject(1, UUID.randomUUID()); it.setString(2, if (user == null) "system" else "user"); it.setObject(3, user?.userId)
            it.setString(4, action); it.setString(5, if (session == null) """{"type":"session"}""" else """{"type":"session","sessionId":"$session"}""")
            it.setString(6, result); it.setObject(7, correlation); it.executeUpdate()
        }
    }
}
