package com.auditlab.api

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

private val random = SecureRandom()
private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

internal fun randomToken(prefix: String, bytes: Int = 32): String = prefix + urlEncoder.encodeToString(ByteArray(bytes).also(random::nextBytes))

internal data class ConsentInput(val id: UUID, val policyVersion: String, val acceptedAt: Instant)

internal fun parseConsent(node: JsonNode, clock: Clock = Clock.systemUTC()): ConsentInput {
    node.requireExactKeys(setOf("consentId", "policyVersion", "acceptedAt"))
    val id = parseUuid(node.requireText("consentId"))
    val policy = node.requireText("policyVersion")
    requirePolicyVersion(policy)
    val acceptedAt = parseInstant(node.requireText("acceptedAt"), "acceptedAt")
    if (acceptedAt.isAfter(clock.instant().plusSeconds(300))) throw ApiFailure(400, "INVALID_REQUEST")
    return ConsentInput(id, policy, acceptedAt)
}

internal fun ApplicationCall.deviceIdPath(): UUID {
    val raw = parameters["deviceId"] ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND")
    return try { UUID.fromString(raw) } catch (_: IllegalArgumentException) { throw ApiFailure(400, "INVALID_REQUEST") }
}

internal fun ApplicationCall.eventIdPath(): UUID {
    val raw = parameters["eventId"] ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND")
    return try { UUID.fromString(raw) } catch (_: IllegalArgumentException) { throw ApiFailure(400, "INVALID_REQUEST") }
}

private fun ApplicationCall.requireEmptyBody() {
    val length = request.headers[HttpHeaders.ContentLength]
    if (length != null && length != "0") throw ApiFailure(400, "INVALID_REQUEST")
}

internal fun Connection.deviceView(deviceId: UUID): Map<String, Any?>? = prepareStatement("""SELECT d.id,d.status,d.last_seen_at,d.enrolled_at,d.revoked_at,d.app_version,
        c.id AS consent_id,c.policy_version,c.accepted_at
        FROM auditlab.devices d JOIN auditlab.consents c ON c.id=d.consent_id WHERE d.id=?""").use {
    it.setObject(1, deviceId)
    it.executeQuery().use { row ->
        if (!row.next()) return null
        mapOf(
            "deviceId" to row.getObject(1, UUID::class.java).toString(),
            "status" to row.getString(2),
            "lastSeenAt" to row.getTimestamp(3)?.toInstant()?.let(::wire),
            "enrolledAt" to wire(row.getTimestamp(4).toInstant()),
            "revokedAt" to row.getTimestamp(5)?.toInstant()?.let(::wire),
            "consentContext" to mapOf(
                "consentId" to row.getObject(7, UUID::class.java).toString(),
                "policyVersion" to row.getString(8),
                "acceptedAt" to wire(row.getTimestamp(9).toInstant()),
            ),
            "appVersion" to row.getString(6),
        )
    }
}

fun Route.deviceRoutes(auth: AuthService?) {
    post("/v1/devices/enrollment-code") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            call.requireEmptyBody()
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            val created = withContext(Dispatchers.IO) {
                service.authorized(token, setOf("admin")) { connection, user ->
                    val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
                    val expires = now.plusSeconds(600)
                    val code = randomToken("enr_")
                    connection.prepareStatement("INSERT INTO auditlab.enrollment_codes(id,code_hash,created_by,created_at,expires_at) VALUES (?,?,?,?,?)").use {
                        it.setObject(1, UUID.randomUUID())
                        it.setString(2, TokenHash.sha256(code))
                        it.setObject(3, user.userId)
                        it.setTimestamp(4, java.sql.Timestamp.from(now))
                        it.setTimestamp(5, java.sql.Timestamp.from(expires))
                        it.executeUpdate()
                    }
                    Audit.record(connection, "user", user.userId, "enrollment_code_created",
                        Audit.resource("enrollment_code"), correlation = correlation)
                    mapOf("code" to code, "expiresIn" to 600, "expiresAt" to wire(expires))
                }
            }
            call.respondText(json.writeValueAsString(created), ContentType.Application.Json, HttpStatusCode.Created)
        }
    }

    post("/v1/devices/enroll") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            val code = call.request.headers["X-Enrollment-Code"]?.takeIf { it.length in 32..4096 }
                ?: throw ApiFailure(401, "ENROLLMENT_CODE_INVALID")
            val body = call.strictObject()
            body.requireExactKeys(setOf("consentAcknowledged", "consent"), setOf("appVersion"))
            if (!body["consentAcknowledged"].isBoolean || !body["consentAcknowledged"].booleanValue()) {
                throw ApiFailure(400, "CONSENT_REQUIRED")
            }
            val consent = parseConsent(body.requireObject("consent"))
            val appVersion = body["appVersion"]?.let {
                if (!it.isTextual || it.textValue().length !in 1..40) throw ApiFailure(400, "INVALID_REQUEST")
                it.textValue()
            }
            val deviceId = UUID.randomUUID()
            val deviceToken = randomToken("dev_")
            val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
            val created = withContext(Dispatchers.IO) {
                try {
                    service.transaction { connection ->
                        val codeId = connection.prepareStatement("""UPDATE auditlab.enrollment_codes
                            SET used_at=?, consumed_device_id=? WHERE code_hash=? AND used_at IS NULL AND expires_at>?
                            RETURNING id""").use {
                            it.setTimestamp(1, java.sql.Timestamp.from(now))
                            it.setObject(2, deviceId)
                            it.setString(3, TokenHash.sha256(code))
                            it.setTimestamp(4, java.sql.Timestamp.from(now))
                            it.executeQuery().use { row -> if (row.next()) row.getObject(1, UUID::class.java) else null }
                        } ?: throw ApiFailure(401, "ENROLLMENT_CODE_INVALID")
                        connection.prepareStatement("""INSERT INTO auditlab.devices(id,enrollment_code_id,token_hash,consent_id,status,app_version,enrolled_at)
                            VALUES (?,?,?,?,'active',?,?)""").use {
                            it.setObject(1, deviceId)
                            it.setObject(2, codeId)
                            it.setString(3, TokenHash.sha256(deviceToken))
                            it.setObject(4, consent.id)
                            it.setString(5, appVersion)
                            it.setTimestamp(6, java.sql.Timestamp.from(now))
                            it.executeUpdate()
                        }
                        connection.prepareStatement("INSERT INTO auditlab.consents(id,device_id,policy_version,accepted_at,received_at) VALUES (?,?,?,?,?)").use {
                            it.setObject(1, consent.id)
                            it.setObject(2, deviceId)
                            it.setString(3, consent.policyVersion)
                            it.setTimestamp(4, java.sql.Timestamp.from(consent.acceptedAt))
                            it.setTimestamp(5, java.sql.Timestamp.from(now))
                            it.executeUpdate()
                        }
                        Audit.record(connection, "device", deviceId, "device_enrolled",
                            Audit.resource("device", deviceId = deviceId), correlation = correlation)
                        mapOf(
                            "deviceId" to deviceId.toString(),
                            "deviceToken" to deviceToken,
                            "status" to "active",
                            "consentContext" to mapOf("consentId" to consent.id.toString(),
                                "policyVersion" to consent.policyVersion, "acceptedAt" to wire(consent.acceptedAt)),
                            "enrolledAt" to wire(now),
                        )
                    }
                } catch (error: SQLException) {
                    if (error.sqlState == "23505") throw ApiFailure(409, "CONSENT_MISMATCH") else throw error
                }
            }
            call.respondText(json.writeValueAsString(created), ContentType.Application.Json, HttpStatusCode.Created)
        }
    }

    get("/v1/devices") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            val query = call.queryMap()
            call.requireNoQuery("status", "limit", "cursor")
            val status = query["status"]?.also { if (it !in setOf("active", "revoked")) throw ApiFailure(400, "INVALID_REQUEST") }
            val limit = queryLimit(query["limit"])
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            val page = withContext(Dispatchers.IO) {
                service.authorized(token, setOf("admin", "auditor")) { connection, user ->
                    val filtersKey = "devices|${status.orEmpty()}"
                    val cursor = query["cursor"]?.let { service.cursorCodec().decode(it, user.userId, filtersKey) }
                    val parameters = mutableListOf<Any>()
                    val where = StringBuilder()
                    if (status != null) { where.append(" AND d.status=?"); parameters += status }
                    if (cursor != null) {
                        where.append(" AND (d.enrolled_at,d.id) < (?,?)")
                        parameters += java.sql.Timestamp.from(parseInstant(cursor[0], "cursor"))
                        parameters += parseUuid(cursor[1])
                    }
                    val sql = """SELECT d.id,d.status,d.last_seen_at,d.enrolled_at,d.revoked_at,d.app_version,
                        c.id AS consent_id,c.policy_version,c.accepted_at
                        FROM auditlab.devices d JOIN auditlab.consents c ON c.id=d.consent_id
                        WHERE true$where ORDER BY d.enrolled_at DESC,d.id DESC LIMIT ?"""
                    val rows = connection.prepareStatement(sql).use {
                        parameters.forEachIndexed { index, value -> it.setObject(index + 1, value) }
                        it.setInt(parameters.size + 1, limit + 1)
                        it.executeQuery().use { result ->
                            buildList {
                                while (result.next()) add(
                                    mapOf(
                                        "deviceId" to result.getObject(1, UUID::class.java).toString(),
                                        "status" to result.getString(2),
                                        "lastSeenAt" to result.getTimestamp(3)?.toInstant()?.let(::wire),
                                        "enrolledAt" to wire(result.getTimestamp(4).toInstant()),
                                        "revokedAt" to result.getTimestamp(5)?.toInstant()?.let(::wire),
                                        "appVersion" to result.getString(6),
                                        "consentContext" to mapOf(
                                            "consentId" to result.getObject(7, UUID::class.java).toString(),
                                            "policyVersion" to result.getString(8),
                                            "acceptedAt" to wire(result.getTimestamp(9).toInstant()),
                                        ),
                                    )
                                )
                            }
                        }
                    }
                    val items = rows.take(limit)
                    val next = if (rows.size > limit) {
                        val last = items.last()
                        service.cursorCodec().encode(user.userId, filtersKey, listOf(last["enrolledAt"] as String,
                            last["deviceId"] as String))
                    } else null
                    Audit.record(connection, "user", user.userId, "devices_listed", Audit.resource("device"),
                        Audit.filters(status = status, limit = limit), correlation = correlation)
                    mapOf("items" to items, "nextCursor" to next)
                }
            }
            call.respondText(json.writeValueAsString(page), ContentType.Application.Json)
        }
    }

    get("/v1/devices/{deviceId}") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            val deviceId = call.deviceIdPath()
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            val device = withContext(Dispatchers.IO) {
                service.authorized(token, setOf("admin", "auditor")) { connection, user ->
                    val view = connection.deviceView(deviceId) ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND")
                    Audit.record(connection, "user", user.userId, "device_queried",
                        Audit.resource("device", deviceId = deviceId), correlation = correlation)
                    view
                }
            }
            call.respondText(json.writeValueAsString(device), ContentType.Application.Json)
        }
    }

    post("/v1/devices/{deviceId}/revoke") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            call.requireEmptyBody()
            val deviceId = call.deviceIdPath()
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            withContext(Dispatchers.IO) {
                service.authorized(token, setOf("admin")) { connection, user ->
                    val status = connection.prepareStatement("SELECT status FROM auditlab.devices WHERE id=? FOR UPDATE").use {
                        it.setObject(1, deviceId)
                        it.executeQuery().use { row -> if (row.next()) row.getString(1) else null }
                    } ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND")
                    if (status == "active") {
                        connection.prepareStatement("UPDATE auditlab.devices SET status='revoked', revoked_at=? WHERE id=?").use {
                            it.setTimestamp(1, java.sql.Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)))
                            it.setObject(2, deviceId)
                            it.executeUpdate()
                        }
                    }
                    Audit.record(connection, "user", user.userId, "device_revoked",
                        Audit.resource("device", deviceId = deviceId), correlation = correlation)
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    post("/v1/devices/{deviceId}/consent-revoke") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            call.requireEmptyBody()
            val deviceId = call.deviceIdPath()
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            withContext(Dispatchers.IO) {
                service.authorizedDevice(token) { connection, device ->
                    if (device.deviceId != deviceId) throw ApiFailure(403, "DEVICE_SCOPE_MISMATCH")
                    val now = java.sql.Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS))
                    connection.prepareStatement("UPDATE auditlab.consents SET revoked_at=COALESCE(revoked_at,?) WHERE id=?").use {
                        it.setTimestamp(1, now); it.setObject(2, device.consentId); it.executeUpdate()
                    }
                    connection.prepareStatement("UPDATE auditlab.devices SET status='revoked', revoked_at=COALESCE(revoked_at,?) WHERE id=?").use {
                        it.setTimestamp(1, now); it.setObject(2, deviceId)
                        it.executeUpdate()
                    }
                    Audit.record(connection, "device", deviceId, "consent_revoked",
                        Audit.resource("device", deviceId = deviceId), correlation = correlation)
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
