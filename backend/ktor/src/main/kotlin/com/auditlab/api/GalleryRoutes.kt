package com.auditlab.api

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Explicit gallery batches (plan 0.9, M2). The device uploads only the elements the user
 * selected and previewed; the server keeps metadata in PostgreSQL and files on disk (D11).
 * Deletion is manual, admin only and audited (D12); nothing expires automatically.
 */
private val galleryMediaTypes = setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic")
private val galleryExtensions = setOf("jpg", "jpeg", "png", "webp", "heif", "heic")
private val galleryItemPattern = Regex("^[A-Za-z0-9_-]{1,64}$")
private const val GALLERY_MAX_ITEMS = 20
private const val GALLERY_MAX_ITEM_BYTES = 15L * 1024 * 1024
private const val GALLERY_MAX_UPLOAD = 15 * 1024 * 1024 + 1
private const val GALLERY_MAX_TOTAL_BYTES = 150L * 1024 * 1024

private data class GalleryItemInput(val itemId: String, val fileName: String, val mediaType: String, val sizeBytes: Long, val sha256: String)

private fun parseGalleryItem(node: JsonNode): GalleryItemInput {
    node.requireExactKeys(setOf("itemId", "fileName", "mediaType", "sizeBytes", "sha256"))
    val itemId = node.requireText("itemId")
    val fileName = node.requireText("fileName")
    val mediaType = node.requireText("mediaType")
    val sha256 = node.requireText("sha256")
    val sizeNode = node["sizeBytes"] ?: throw ApiFailure(400, "INVALID_REQUEST")
    if (!sizeNode.isIntegralNumber || sizeNode.longValue() !in 1..GALLERY_MAX_ITEM_BYTES) throw ApiFailure(400, "INVALID_REQUEST")
    if (!galleryItemPattern.matches(itemId)) throw ApiFailure(400, "INVALID_REQUEST")
    if (fileName.length !in 1..120 || fileName.contains(Regex("[\\\\/\\r\\n]"))) throw ApiFailure(400, "INVALID_REQUEST")
    if (fileName.substringAfterLast('.', "").lowercase() !in galleryExtensions) throw ApiFailure(400, "INVALID_REQUEST")
    if (mediaType !in galleryMediaTypes) throw ApiFailure(400, "INVALID_REQUEST")
    if (!sha256.matches(Regex("[0-9a-f]{64}"))) throw ApiFailure(400, "INVALID_REQUEST")
    return GalleryItemInput(itemId, fileName, mediaType, sizeNode.longValue(), sha256)
}

private fun parseGalleryItems(node: JsonNode): List<GalleryItemInput> {
    if (!node.isArray || node.size() !in 1..GALLERY_MAX_ITEMS) throw ApiFailure(400, "INVALID_REQUEST")
    val items = node.map(::parseGalleryItem)
    if (items.sumOf { it.sizeBytes } > GALLERY_MAX_TOTAL_BYTES) throw ApiFailure(400, "INVALID_REQUEST")
    return items
}

private fun io.ktor.server.application.ApplicationCall.batchPath(name: String): UUID {
    val raw = parameters[name] ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND")
    return try { UUID.fromString(raw) } catch (_: IllegalArgumentException) { throw ApiFailure(400, "INVALID_REQUEST") }
}

private fun io.ktor.server.application.ApplicationCall.itemPath(): String {
    val raw = parameters["itemId"] ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND")
    if (!galleryItemPattern.matches(raw)) throw ApiFailure(400, "INVALID_REQUEST")
    return raw
}

private fun io.ktor.server.application.ApplicationCall.requireNoBody() {
    val length = request.headers[io.ktor.http.HttpHeaders.ContentLength]
    if (length != null && length != "0") throw ApiFailure(400, "INVALID_REQUEST")
}

fun Route.galleryRoutes(auth: AuthService?, store: GalleryStore = GalleryStore.shared) {
    post("/v1/devices/{deviceId}/gallery-batches") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            val deviceId = call.deviceIdPath()
            val body = call.strictObject()
            body.requireExactKeys(setOf("batchId", "consentVersion", "items"))
            val batchId = parseUuid(body.requireText("batchId"))
            if (body.requireText("consentVersion") != "gallery-share-1") throw ApiFailure(400, "INVALID_REQUEST")
            val items = parseGalleryItems(body["items"] ?: throw ApiFailure(400, "INVALID_REQUEST"))
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            val now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS))
            withContext(Dispatchers.IO) {
                service.authorizedDevice(token) { connection, device ->
                    if (device.deviceId != deviceId) throw ApiFailure(403, "DEVICE_SCOPE_MISMATCH")
                    val existing = connection.prepareStatement("SELECT item_count,total_bytes,state FROM auditlab.gallery_batches WHERE id=? AND device_id=?").use {
                        it.setObject(1, batchId); it.setObject(2, deviceId)
                        it.executeQuery().use { row -> if (row.next()) Triple(row.getInt(1), row.getLong(2), row.getString(3)) else null }
                    }
                    if (existing != null) {
                        // Idempotent retry of the same batch; different content is a conflict.
                        if (existing.first == items.size && existing.second == items.sumOf { it.sizeBytes } && existing.third != "DELETED") return@authorizedDevice
                        throw ApiFailure(409, "CONFLICT")
                    }
                    connection.prepareStatement("""INSERT INTO auditlab.gallery_batches(id,device_id,consent_version,item_count,total_bytes,state,created_at)
                        VALUES (?,?,?,?,?,'UPLOADING',?)""").use {
                        it.setObject(1, batchId); it.setObject(2, deviceId); it.setString(3, "gallery-share-1")
                        it.setInt(4, items.size); it.setLong(5, items.sumOf { it.sizeBytes }); it.setTimestamp(6, now)
                        it.executeUpdate()
                    }
                    items.forEach { item ->
                        connection.prepareStatement("""INSERT INTO auditlab.gallery_items(batch_id,item_id,file_name,media_type,size_bytes,sha256,state)
                            VALUES (?,?,?,?,?,?,'PENDING')""").use {
                            it.setObject(1, batchId); it.setString(2, item.itemId); it.setString(3, item.fileName)
                            it.setString(4, item.mediaType); it.setLong(5, item.sizeBytes); it.setString(6, item.sha256)
                            it.executeUpdate()
                        }
                    }
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    put("/v1/devices/{deviceId}/gallery-batches/{batchId}/items/{itemId}") {
        call.api {
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            val deviceId = call.deviceIdPath()
            val batchId = call.batchPath("batchId")
            val itemId = call.itemPath()
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            val expected = withContext(Dispatchers.IO) {
                service.authorizedDevice(token) { connection, device ->
                    if (device.deviceId != deviceId) throw ApiFailure(403, "DEVICE_SCOPE_MISMATCH")
                    connection.prepareStatement("SELECT b.state,i.media_type,i.size_bytes,i.sha256,i.state FROM auditlab.gallery_batches b JOIN auditlab.gallery_items i ON i.batch_id=b.id WHERE b.id=? AND b.device_id=? AND i.item_id=?").use {
                        it.setObject(1, batchId); it.setObject(2, deviceId); it.setString(3, itemId)
                        it.executeQuery().use { row ->
                            if (!row.next()) throw ApiFailure(404, "RESOURCE_NOT_FOUND")
                            val batchState = row.getString(1)
                            if (batchState == "DELETED") throw ApiFailure(409, "CONFLICT")
                            GalleryItemInput(itemId, "", row.getString(2), row.getLong(3), row.getString(4)) to row.getString(5)
                        }
                    }
                }
            }
            if (call.request.headers[HttpHeaders.ContentType]?.substringBefore(';') != expected.first.mediaType)
                throw ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE")
            val bytes = call.readLimitedBody(GALLERY_MAX_UPLOAD)
            if (bytes.isEmpty() || bytes.size.toLong() > expected.first.sizeBytes) throw ApiFailure(400, "INVALID_REQUEST")
            withContext(Dispatchers.IO) {
                service.authorizedDevice(token) { connection, device ->
                    if (device.deviceId != deviceId) throw ApiFailure(403, "DEVICE_SCOPE_MISMATCH")
                    try { store.write(deviceId, batchId, itemId, bytes, expected.first.sha256) }
                    catch (_: IllegalArgumentException) {
                        // Idempotent retry after a confirmed upload is accepted only with identical bytes.
                        if (expected.second == "UPLOADED") return@authorizedDevice
                        throw ApiFailure(400, "INVALID_REQUEST")
                    }
                    connection.prepareStatement("UPDATE auditlab.gallery_items SET state='UPLOADED' WHERE batch_id=? AND item_id=?").use {
                        it.setObject(1, batchId); it.setString(2, itemId); it.executeUpdate()
                    }
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    post("/v1/devices/{deviceId}/gallery-batches/{batchId}/complete") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            val deviceId = call.deviceIdPath()
            val batchId = call.batchPath("batchId")
            call.strictObject().requireExactKeys(emptySet())
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            val now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS))
            withContext(Dispatchers.IO) {
                service.authorizedDevice(token) { connection, device ->
                    if (device.deviceId != deviceId) throw ApiFailure(403, "DEVICE_SCOPE_MISMATCH")
                    val pending = connection.prepareStatement("SELECT COUNT(*) FROM auditlab.gallery_items WHERE batch_id=? AND state<>'UPLOADED'").use {
                        it.setObject(1, batchId); it.executeQuery().use { row -> if (row.next()) row.getInt(1) else -1 }
                    }
                    val updated = connection.prepareStatement("UPDATE auditlab.gallery_batches SET state='READY', completed_at=COALESCE(completed_at,?) WHERE id=? AND device_id=? AND state='UPLOADING' AND NOT EXISTS (SELECT 1 FROM auditlab.gallery_items WHERE batch_id=? AND state<>'UPLOADED')").use {
                        it.setTimestamp(1, now); it.setObject(2, batchId); it.setObject(3, deviceId); it.setObject(4, batchId)
                        it.executeUpdate()
                    }
                    if (updated == 0) throw ApiFailure(409, if (pending > 0) "INVALID_REQUEST" else "CONFLICT")
                    Audit.record(connection, "device", deviceId, "gallery_batch_shared",
                        Audit.resource("device", deviceId = deviceId), correlation = correlation)
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    get("/v1/gallery-batches") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            val filter = call.request.queryParameters["device"]?.let(::parseUuid)
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            val items = withContext(Dispatchers.IO) {
                service.authorized(token, setOf("admin", "auditor")) { connection, user ->
                    Audit.record(connection, "user", user.userId, "gallery_batches_queried",
                        Audit.resource("audit"), correlation = correlation,
                        filters = Audit.filters(device = filter?.toString()))
                    val where = if (filter != null) " AND b.device_id=?" else ""
                    connection.prepareStatement("""SELECT b.id,b.device_id,b.item_count,b.total_bytes,b.created_at,b.completed_at
                        FROM auditlab.gallery_batches b WHERE b.state='READY'$where ORDER BY b.created_at DESC,b.id DESC LIMIT 100""").use {
                        if (filter != null) it.setObject(1, filter)
                        it.executeQuery().use { row ->
                            buildList {
                                while (row.next()) add(mapOf(
                                    "batchId" to row.getObject(1, UUID::class.java).toString(),
                                    "deviceId" to row.getObject(2, UUID::class.java).toString(),
                                    "itemCount" to row.getInt(3),
                                    "totalBytes" to row.getLong(4),
                                    "createdAt" to wire(row.getTimestamp(5).toInstant()),
                                    "completedAt" to row.getTimestamp(6)?.toInstant()?.let(::wire),
                                ))
                            }
                        }
                    }
                }
            }
            call.respond(mapOf("items" to items))
        }
    }

    get("/v1/gallery-batches/{batchId}") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            val batchId = call.batchPath("batchId")
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            val detail = withContext(Dispatchers.IO) {
                service.authorized(token, setOf("admin", "auditor")) { connection, user ->
                    val batch = connection.prepareStatement("SELECT device_id,item_count,total_bytes,created_at,completed_at,state FROM auditlab.gallery_batches WHERE id=? AND state<>'DELETED'").use {
                        it.setObject(1, batchId)
                        it.executeQuery().use { row ->
                            if (!row.next()) throw ApiFailure(404, "RESOURCE_NOT_FOUND")
                            mapOf(
                                "batchId" to batchId.toString(),
                                "deviceId" to row.getObject(1, UUID::class.java).toString(),
                                "itemCount" to row.getInt(2),
                                "totalBytes" to row.getLong(3),
                                "createdAt" to wire(row.getTimestamp(4).toInstant()),
                                "completedAt" to row.getTimestamp(5)?.toInstant()?.let(::wire),
                                "state" to row.getString(6),
                            )
                        }
                    }
                    val items = connection.prepareStatement("SELECT item_id,file_name,media_type,size_bytes,sha256,state FROM auditlab.gallery_items WHERE batch_id=? ORDER BY item_id").use {
                        it.setObject(1, batchId)
                        it.executeQuery().use { row ->
                            buildList {
                                while (row.next()) add(mapOf(
                                    "itemId" to row.getString(1),
                                    "fileName" to row.getString(2),
                                    "mediaType" to row.getString(3),
                                    "sizeBytes" to row.getLong(4),
                                    "sha256" to row.getString(5),
                                    "state" to row.getString(6),
                                ))
                            }
                        }
                    }
                    batch + ("items" to items)
                }
            }
            call.respond(detail)
        }
    }

    get("/v1/gallery-batches/{batchId}/items/{itemId}/content") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            val batchId = call.batchPath("batchId")
            val itemId = call.itemPath()
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            val content = withContext(Dispatchers.IO) {
                service.authorized(token, setOf("admin", "auditor")) { connection, user ->
                    val media = connection.prepareStatement("SELECT b.device_id,i.media_type,b.state FROM auditlab.gallery_batches b JOIN auditlab.gallery_items i ON i.batch_id=b.id WHERE b.id=? AND i.item_id=?").use {
                        it.setObject(1, batchId); it.setString(2, itemId)
                        it.executeQuery().use { row ->
                            if (!row.next()) throw ApiFailure(404, "RESOURCE_NOT_FOUND")
                            Triple(row.getObject(1, UUID::class.java), row.getString(2), row.getString(3))
                        }
                    }
                    if (media.third != "READY") throw ApiFailure(409, "CONFLICT")
                    val bytes = store.read(media.first, batchId, itemId) ?: throw ApiFailure(404, "RESOURCE_NOT_FOUND")
                    Audit.record(connection, "user", user.userId, "gallery_item_downloaded",
                        Audit.resource("device", deviceId = media.first), correlation = correlation,
                        filters = Audit.filters(device = media.first.toString()))
                    bytes to media.second
                }
            }
            call.respondBytes(content.first, io.ktor.http.ContentType.parse(content.second))
        }
    }

    delete("/v1/gallery-batches/{batchId}") {
        call.api { correlation ->
            val service = auth ?: throw ApiFailure(503, "SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            call.requireNoBody()
            val batchId = call.batchPath("batchId")
            val token = bearer(call.request.headers[HttpHeaders.Authorization])
            withContext(Dispatchers.IO) {
                service.authorized(token, setOf("admin")) { connection, user ->
                    val deviceId = connection.prepareStatement("SELECT device_id FROM auditlab.gallery_batches WHERE id=? AND state<>'DELETED'").use {
                        it.setObject(1, batchId)
                        it.executeQuery().use { row -> if (row.next()) row.getObject(1, UUID::class.java) else throw ApiFailure(404, "RESOURCE_NOT_FOUND") }
                    }
                    store.deleteBatch(deviceId, batchId)
                    connection.prepareStatement("DELETE FROM auditlab.gallery_items WHERE batch_id=?").use {
                        it.setObject(1, batchId); it.executeUpdate()
                    }
                    connection.prepareStatement("UPDATE auditlab.gallery_batches SET state='DELETED', completed_at=NULL WHERE id=?").use {
                        it.setObject(1, batchId); it.executeUpdate()
                    }
                    Audit.record(connection, "user", user.userId, "gallery_batch_deleted",
                        Audit.resource("device", deviceId = deviceId), correlation = correlation)
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
