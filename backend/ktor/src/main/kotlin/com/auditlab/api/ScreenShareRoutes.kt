package com.auditlab.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Relay endpoints. Capture is started locally by MediaProjection; the server cannot start it. */
fun Route.screenShareRoutes(auth: AuthService?, hub: ScreenShareHub = ScreenShareHub.shared) {
    post("/v1/screen-share/{deviceId}/frame") {
        call.api {
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            if (call.request.headers[HttpHeaders.ContentType]?.substringBefore(';') != "image/jpeg")
                throw ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE")
            val device = call.deviceIdPath()
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            withContext(Dispatchers.IO) { service.authorizedDevice(token) { _, identity ->
                if (identity.deviceId != device) throw ApiFailure(403, "DEVICE_SCOPE_MISMATCH")
            } }
            val bytes = call.readLimitedBody(512 * 1024)
            service.authorizedDevice(token) { _, identity ->
                if (identity.deviceId != device) throw ApiFailure(403, "DEVICE_SCOPE_MISMATCH")
                try { hub.publish(device, bytes) } catch (_: IllegalArgumentException) { throw ApiFailure(400, "INVALID_FRAME") }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
    delete("/v1/screen-share/{deviceId}") {
        call.api {
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            val device = call.deviceIdPath()
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            service.authorizedDevice(token) { _, identity ->
                if (identity.deviceId != device) throw ApiFailure(403, "DEVICE_SCOPE_MISMATCH")
                hub.stop(device)
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
    get("/v1/screen-share/{deviceId}/frame") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            val device = call.deviceIdPath()
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            val frame = withContext(Dispatchers.IO) {
                service.authorized(token, setOf("admin", "auditor")) { connection, user ->
                    connection.prepareStatement("SELECT status FROM auditlab.devices WHERE id=?").use {
                        it.setObject(1, device)
                        it.executeQuery().use { row ->
                            if (!row.next()) throw ApiFailure(404, "RESOURCE_NOT_FOUND")
                    if (row.getString(1) != "active") { hub.stop(device); throw ApiFailure(409, "DEVICE_REVOKED") }
                        }
                    }
                    Audit.record(connection, "user", user.userId, "screen_frame_queried", Audit.resource("device", device), correlation = correlation)
                    hub.latest(device)
                }
            }
            if (frame == null) call.respond(HttpStatusCode.NoContent)
            else call.respondBytes(frame.bytes, ContentType.Image.JPEG, HttpStatusCode.OK)
        }
    }
}
