package com.auditlab.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.server.routing.routing
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.security.SecureRandom
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthIntegrationTest {
    private val mapper = ObjectMapper()
    private val key = ByteArray(32).also(SecureRandom()::nextBytes)
    private val config = AuthConfig(key, accountLimit = 100, clientLimit = 1000, globalLimit = 10000, refreshClientLimit = 1000, refreshGlobalLimit = 10000)
    private val password get() = System.getenv("AUDITLAB_BOOTSTRAP_PASSWORD")
    private val owner get() = TestDatabase.owner
    private val runtime get() = TestDatabase.runtime

    @BeforeAll fun setup() {
        Bootstrap.user(owner, "auth_admin", password, "admin")
        Bootstrap.user(owner, "auth_auditor", password, "auditor")
    }
    private fun sql(statement: String, vararg values: Any?) = DatabaseTools.connection(owner) { connection ->
        connection.prepareStatement(statement).use { query -> values.forEachIndexed { i, value -> query.setObject(i+1, value) }; query.executeUpdate() }
    }
    private fun <T> inspect(action: (Connection) -> T) = DatabaseTools.connection(owner, action)
    private fun http(block: suspend ApplicationTestBuilder.(AuthService) -> Unit) = testApplication {
        val database = Database(runtime)
        val service = AuthService(database, config)
        application { module(database, config) }
        try { block(service) } finally { database.close() }
    }
    private suspend fun ApplicationTestBuilder.postJson(path: String, body: Any): HttpResponse = client.post("/v1/auth/$path") {
        contentType(ContentType.Application.Json); setBody(mapper.writeValueAsString(body))
    }
    private suspend fun ApplicationTestBuilder.login(username: String = "auth_admin"): JsonNode {
        val response = postJson("login", mapOf("username" to username, "password" to password))
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertNotNull(UUID.fromString(response.headers["X-Correlation-ID"]))
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
        return mapper.readTree(response.bodyAsText())
    }
    private suspend fun ApplicationTestBuilder.rotate(token: String) = postJson("refresh", mapOf("refreshToken" to token))
    private suspend fun ApplicationTestBuilder.logout(token: String) = client.post("/v1/auth/logout") { bearerAuth(token) }
    private suspend fun problem(response: HttpResponse, status: Int, code: String? = null): JsonNode {
        assertEquals(status, response.status.value)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/problem+json"))
        val data = mapper.readTree(response.bodyAsText())
        assertEquals(status, data["status"].intValue())
        assertEquals(response.headers["X-Correlation-ID"], data["correlationId"].textValue())
        assertNotNull(UUID.fromString(data["correlationId"].textValue()))
        if (code != null) assertEquals(code, data["code"].textValue())
        if (status == 401) assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
        return data
    }

    @Test fun `login returns bounded credentials for both roles and logout is bodyless`() = http { _ ->
        for ((name, role) in listOf("AUTH_ADMIN" to "admin", "auth_auditor" to "auditor")) {
            val data = login(name)
            assertEquals(role, data["user"]["role"].textValue())
            assertEquals("Bearer", data["tokenType"].textValue())
            assertEquals(900, data["expiresIn"].intValue()); assertEquals(604800, data["refreshExpiresIn"].intValue())
            assertNotNull(UUID.fromString(data["sessionId"].textValue()))
            val response = logout(data["accessToken"].textValue())
            assertEquals(HttpStatusCode.NoContent, response.status); assertEquals("", response.bodyAsText())
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }
    }

    @Test fun `unknown wrong password and disabled users have identical login errors`() = http { _ ->
        val responses = listOf("unknown_account" to password, "auth_admin" to "wrong-password", "auth_disabled" to password)
        Bootstrap.user(owner, "auth_disabled", password, "auditor")
        sql("UPDATE auditlab.users SET status='disabled' WHERE username='auth_disabled'")
        val bodies = responses.map { (name, secret) ->
            val response = postJson("login", mapOf("username" to name, "password" to secret))
            val data = problem(response, 401, "UNAUTHENTICATED").deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            data.remove("correlationId"); data
        }
        assertEquals(bodies[0], bodies[1]); assertEquals(bodies[0], bodies[2])
    }

    @Test fun `strict input rejects unknown fields types duplicate fields trailing JSON and oversized bodies`() = http { _ ->
        for (body in listOf("{}", "[]", "null", "{\"username\":true,\"password\":\"x\"}",
            "{\"username\":\"auth_admin\",\"password\":\"x\",\"role\":\"admin\"}",
            "{\"username\":\"x\",\"password\":\"x\"}", "{\"username\":\"auth_admin\",\"password\":null}",
            mapper.writeValueAsString(mapOf("username" to "auth_admin", "password" to "x".repeat(129))))) {
            problem(client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(body) }, 400, "INVALID_REQUEST")
        }
        for (body in listOf("{", "{\"username\":\"auth_admin\",\"username\":\"other\",\"password\":\"x\"}",
            "{\"username\":\"auth_admin\",\"password\":\"x\"} {}")) {
            problem(client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(body) }, 400, "INVALID_JSON")
        }
        problem(client.post("/v1/auth/login") { setBody("x") }, 400, "INVALID_REQUEST")
        problem(client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("x".repeat(16385)) }, 400, "INVALID_REQUEST")
        problem(client.post("/v1/auth/login?password=forbidden") { contentType(ContentType.Application.Json); setBody("{}") }, 400, "INVALID_REQUEST")
        problem(postJson("refresh", mapOf("refreshToken" to 123)), 400, "INVALID_REQUEST")
        problem(postJson("refresh", mapOf("refreshToken" to "short")), 400, "INVALID_REQUEST")
    }

    @Test fun `rotation retains absolute expiry and persists only token hashes`() = http { _ ->
        val first = login(); val session = UUID.fromString(first["sessionId"].textValue())
        val response = rotate(first["refreshToken"].textValue()); assertEquals(200, response.status.value)
        val next = mapper.readTree(response.bodyAsText())
        assertEquals(session.toString(), next["sessionId"].textValue())
        assertNotEquals(first["refreshToken"], next["refreshToken"])
        assertTrue(next["refreshExpiresIn"].intValue() <= first["refreshExpiresIn"].intValue())
        inspect { connection ->
            connection.prepareStatement("SELECT token_hash,used_at,expires_at FROM auditlab.refresh_tokens WHERE session_id=? ORDER BY created_at,id").use {
                it.setObject(1, session); it.executeQuery().use { rows ->
                    val hashes = mutableListOf<String>(); val expiries = mutableSetOf<Instant>(); var consumed = 0
                    while (rows.next()) { hashes += rows.getString(1); expiries += rows.getTimestamp(3).toInstant(); if (rows.getTimestamp(2) != null) consumed++ }
                    assertEquals(2, hashes.size); assertEquals(1, consumed); assertEquals(1, expiries.size)
                    assertTrue(hashes.all { value -> Regex("[0-9a-f]{64}").matches(value) })
                    assertFalse(hashes.contains(first["refreshToken"].textValue())); assertFalse(hashes.contains(next["refreshToken"].textValue()))
                }
            }
        }
    }

    @Test fun `refresh replay commits family revocation and invalidates every access and refresh`() = http { _ ->
        val first = login(); val next = mapper.readTree(rotate(first["refreshToken"].textValue()).bodyAsText())
        problem(rotate(first["refreshToken"].textValue()), 401, "REFRESH_REUSED")
        problem(rotate(next["refreshToken"].textValue()), 401, "SESSION_REVOKED")
        problem(logout(first["accessToken"].textValue()), 401, "SESSION_REVOKED")
        problem(logout(next["accessToken"].textValue()), 401, "SESSION_REVOKED")
    }

    @Test fun `simultaneous refresh allows one rotation then revokes the family`() = http { _ ->
        val first = login(); val token = first["refreshToken"].textValue()
        val results = coroutineScope { val a = async { rotate(token) }; val b = async { rotate(token) }; listOf(a.await(), b.await()) }
        assertEquals(listOf(200, 401), results.map { it.status.value }.sorted())
        val next = mapper.readTree(results.single { it.status.value == 200 }.bodyAsText())
        problem(results.single { it.status.value == 401 }, 401, "REFRESH_REUSED")
        problem(rotate(next["refreshToken"].textValue()), 401, "SESSION_REVOKED")
    }

    @Test fun `logout revokes only its own session and persists across service recreation`() = http { _ ->
        val first = login(); val other = login()
        assertEquals(204, logout(first["accessToken"].textValue()).status.value)
        Database(runtime).use { db ->
            val recreated = AuthService(db, config)
            val failure = assertThrows(ApiFailure::class.java) { recreated.refresh(first["refreshToken"].textValue(), UUID.randomUUID()) }
            assertEquals("SESSION_REVOKED", failure.code)
            assertNotNull(recreated.refresh(other["refreshToken"].textValue(), UUID.randomUUID())["accessToken"])
        }
    }

    @Test fun `logout racing refresh cannot leave usable descendants`() = http { _ ->
        val first = login()
        val (refreshed, loggedOut) = coroutineScope {
            val a = async { rotate(first["refreshToken"].textValue()) }; val b = async { logout(first["accessToken"].textValue()) }
            a.await() to b.await()
        }
        assertEquals(204, loggedOut.status.value)
        assertTrue(refreshed.status.value in setOf(200, 401))
        if (refreshed.status.value == 200) {
            val child = mapper.readTree(refreshed.bodyAsText())
            problem(rotate(child["refreshToken"].textValue()), 401, "SESSION_REVOKED")
            problem(logout(child["accessToken"].textValue()), 401, "SESSION_REVOKED")
        } else problem(refreshed, 401, "SESSION_REVOKED")
    }

    @Test fun `opaque refresh access tampering and expired JWT fail protected HTTP`() = http { _ ->
        val first = login(); val access = first["accessToken"].textValue()
        problem(logout(first["refreshToken"].textValue()), 401, "UNAUTHENTICATED")
        problem(logout(access.dropLast(8) + "AAAAAAAA"), 401, "UNAUTHENTICATED")
        problem(rotate(access), 401, "UNAUTHENTICATED")
        val now = Instant.now()
        val expired = JWT.create().withHeader(mapOf("typ" to "at+jwt")).withIssuer(config.issuer).withAudience(config.audience)
            .withSubject(first["user"]["userId"].textValue()).withClaim("sid", first["sessionId"].textValue()).withClaim("role", "admin")
            .withClaim("token_use", "access").withJWTId(UUID.randomUUID().toString()).withIssuedAt(Date.from(now.minusSeconds(1000)))
            .withExpiresAt(Date.from(now.minusSeconds(100))).sign(Algorithm.HMAC256(key))
        problem(logout(expired), 401, "UNAUTHENTICATED")
        problem(client.post("/v1/auth/logout"), 401, "UNAUTHENTICATED")
    }

    @Test fun `current DB role and user status are checked on every authorized action`() = http { service ->
        val admin = login(); val auditor = login("auth_auditor")
        assertEquals("admin", service.authorized(admin["accessToken"].textValue(), setOf("admin")) { _, user -> user.role })
        assertEquals("auditor", service.authorized(auditor["accessToken"].textValue(), setOf("admin", "auditor")) { _, user -> user.role })
        assertEquals(403, assertThrows(ApiFailure::class.java) { service.authorized(auditor["accessToken"].textValue(), setOf("admin")) { _, _ -> fail("Auditor accepted") } }.status)
        try {
            sql("UPDATE auditlab.users SET role='auditor' WHERE username='auth_admin'")
            assertEquals(401, assertThrows(ApiFailure::class.java) { service.authorized(admin["accessToken"].textValue(), setOf("admin")) { _, _ -> fail("Stale role accepted") } }.status)
            val updated = mapper.readTree(rotate(admin["refreshToken"].textValue()).bodyAsText())
            assertEquals("auditor", updated["user"]["role"].textValue())
            sql("UPDATE auditlab.users SET status='disabled' WHERE username='auth_admin'")
            problem(logout(updated["accessToken"].textValue()), 401, "UNAUTHENTICATED")
            problem(rotate(updated["refreshToken"].textValue()), 401, "UNAUTHENTICATED")
        } finally { sql("UPDATE auditlab.users SET role='admin',status='active' WHERE username='auth_admin'") }
    }

    @Test fun `expired absolute session denies access and refresh and caps last access lifetime`() = http { _ ->
        val first = login(); val id = UUID.fromString(first["sessionId"].textValue())
        sql("UPDATE auditlab.user_sessions SET created_at=now()-interval '8 days',expires_at=now()-interval '1 second' WHERE id=?", id)
        problem(rotate(first["refreshToken"].textValue()), 401, "SESSION_EXPIRED")
        problem(logout(first["accessToken"].textValue()), 401, "SESSION_EXPIRED")
        val near = login(); val nearId = UUID.fromString(near["sessionId"].textValue())
        sql("UPDATE auditlab.user_sessions SET expires_at=now()+interval '60 seconds' WHERE id=?", nearId)
        val rotated = mapper.readTree(rotate(near["refreshToken"].textValue()).bodyAsText())
        assertTrue(rotated["expiresIn"].intValue() in 1..60)
        assertEquals(rotated["expiresIn"], rotated["refreshExpiresIn"])
    }

    @Test fun `auth audit commits matching correlations without credentials`() = http { _ ->
        val first = login(); val token = first["refreshToken"].textValue()
        val rotated = rotate(token); assertEquals(200, rotated.status.value)
        val correlation = UUID.fromString(rotated.headers["X-Correlation-ID"])
        inspect { connection ->
            connection.prepareStatement("SELECT actor_type,actor_id,action,resource::text,result FROM auditlab.audit_log WHERE correlation_id=?").use {
                it.setObject(1, correlation); it.executeQuery().use { row ->
                    assertTrue(row.next()); assertEquals("user", row.getString(1)); assertEquals(first["user"]["userId"].textValue(), row.getString(2))
                    assertEquals("session_refreshed", row.getString(3)); assertTrue(row.getString(4).contains(first["sessionId"].textValue()))
                    assertFalse(row.getString(4).contains(token)); assertFalse(row.getString(4).contains(password)); assertEquals("success", row.getString(5)); assertFalse(row.next())
                }
            }
        }
    }

    @Test fun `audit failure rolls back session and token creation before success`() = http { _ ->
        sql("CREATE FUNCTION auditlab.fixture_reject_login() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.action = ''login_succeeded'' THEN RAISE EXCEPTION ''fixture audit unavailable''; END IF; RETURN NEW; END;'")
        sql("CREATE TRIGGER fixture_reject_login BEFORE INSERT ON auditlab.audit_log FOR EACH ROW EXECUTE FUNCTION auditlab.fixture_reject_login()")
        fun sessions() = inspect { c -> c.createStatement().executeQuery("SELECT count(*) FROM auditlab.user_sessions").use { it.next(); it.getInt(1) } }
        val before = sessions()
        try {
            val response = postJson("login", mapOf("username" to "auth_admin", "password" to password))
            problem(response, 503, "SERVICE_UNAVAILABLE"); assertEquals(before, sessions())
        } finally {
            sql("DROP TRIGGER fixture_reject_login ON auditlab.audit_log"); sql("DROP FUNCTION auditlab.fixture_reject_login()")
        }
    }

    @Test fun `account rate limit is case insensitive persistent and resets at the configured window`() {
        class MutableClock(var instant: Instant) : Clock() {
            override fun instant() = instant
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
        }
        val clock = MutableClock(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES))
        val limited = AuthConfig(ByteArray(32).also(SecureRandom()::nextBytes), accountLimit = 2)
        Database(runtime).use { db ->
            val a = AuthService(db, limited, clock); val b = AuthService(db, limited, clock)
            for (name in listOf("auth_admin", "AUTH_ADMIN")) assertEquals(401, assertThrows(ApiFailure::class.java) { a.login(name, "wrong", UUID.randomUUID()) }.status)
            val failure = assertThrows(ApiFailure::class.java) { b.login("Auth_Admin", password, UUID.randomUUID()) }
            assertEquals(429, failure.status); assertEquals(60, failure.retryAfter)
            clock.instant = clock.instant.plusSeconds(60)
            assertNotNull(b.login("AUTH_ADMIN", password, UUID.randomUUID())["accessToken"])
        }
    }

    @Test fun `concurrent shared client rate limit cannot be bypassed across service instances`() {
        val limited = AuthConfig(ByteArray(32).also(SecureRandom()::nextBytes), clientLimit = 2, globalLimit = 100)
        Database(runtime).use { db ->
            val clock = Clock.fixed(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES), ZoneOffset.UTC)
            val services = listOf(AuthService(db, limited, clock), AuthService(db, limited, clock)); val gate = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(6)
            try {
                val results = (0..5).map { index -> pool.submit(Callable {
                    gate.await(); try { services[index % 2].limitRequest("login", "fixture-peer"); 200 } catch (error: ApiFailure) { error.status }
                }) }
                gate.countDown()
                val statuses = results.map { it.get(15, TimeUnit.SECONDS) }
                assertEquals(2, statuses.count { it == 200 }); assertEquals(4, statuses.count { it == 429 })
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun `HTTP rate limits provide Retry-After and ignore spoofed forwarded peers`() = testApplication {
        val db = Database(runtime)
        val limited = AuthConfig(ByteArray(32).also(SecureRandom()::nextBytes), clientLimit = 1, refreshClientLimit = 1)
        val clock = Clock.fixed(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES), ZoneOffset.UTC)
        application { routing { authRoutes(AuthService(db, limited, clock)) } }
        try {
            problem(client.post("/v1/auth/login") { header("X-Forwarded-For", "192.0.2.1"); contentType(ContentType.Application.Json); setBody("{}") }, 400)
            val blocked = client.post("/v1/auth/login") { header("X-Forwarded-For", "192.0.2.2"); contentType(ContentType.Application.Json); setBody("{}") }
            problem(blocked, 429, "RATE_LIMITED"); assertTrue(blocked.headers["Retry-After"]!!.toInt() in 1..60)
            problem(postJson("refresh", mapOf("refreshToken" to "x".repeat(43))), 401)
            val refreshBlocked = postJson("refresh", mapOf("refreshToken" to "y".repeat(43)))
            problem(refreshBlocked, 429, "RATE_LIMITED"); assertTrue(refreshBlocked.headers["Retry-After"]!!.toInt() in 1..60)
        } finally { db.close() }
    }
}
