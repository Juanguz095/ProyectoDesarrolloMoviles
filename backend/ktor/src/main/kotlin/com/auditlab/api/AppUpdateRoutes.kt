package com.auditlab.api

import com.auditlab.common.AppRelease
import com.auditlab.common.UpdatePolicy
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/** Explicit operator configuration; empty by default, never an arbitrary upload/command API. */
class ReleaseCatalog(val items: List<AppRelease>) {
    init {
        require(items.size <= 100 && items.map { it.releaseId }.distinct().size == items.size)
        require(items.map { it.app to it.versionCode }.distinct().size == items.size)
    }
    companion object {
        fun fromEnvironment(): ReleaseCatalog {
            val path = System.getenv("AUDITLAB_RELEASE_CATALOG")?.takeIf { it.isNotBlank() } ?: return ReleaseCatalog(emptyList())
            val file = Path.of(path)
            require(Files.size(file) <= 256 * 1024) { "Release catalog exceeds limit" }
            val node = parseObject(Files.readAllBytes(file))
            node.requireExactKeys(setOf("items"))
            require(node["items"].isArray)
            return ReleaseCatalog(node["items"].map(::releaseFromJson))
        }
    }
}

internal fun releaseFromJson(node: JsonNode): AppRelease {
    node.requireExactKeys(setOf("releaseId","app","packageName","versionName","versionCode","minSdk","bytes","sha256","signerSha256","downloadUrl"))
    require(node["versionCode"].isIntegralNumber && node["versionCode"].canConvertToLong())
    require(node["bytes"].isIntegralNumber && node["bytes"].canConvertToLong())
    require(node["minSdk"].isIntegralNumber && node["minSdk"].canConvertToInt())
    return AppRelease(node.requireText("releaseId"),node.requireText("app"),node.requireText("packageName"),
        node.requireText("versionName"),node["versionCode"].longValue(),node["minSdk"].intValue(),node["bytes"].longValue(),
        node.requireText("sha256"),node.requireText("signerSha256"),node.requireText("downloadUrl"))
}

internal fun AppRelease.wire() = mapOf("releaseId" to releaseId,"app" to app,"packageName" to packageName,
    "versionName" to versionName,"versionCode" to versionCode,"minSdk" to minSdk,"bytes" to bytes,
    "sha256" to sha256,"signerSha256" to signerSha256,"downloadUrl" to downloadUrl)

private fun Connection.expireUpdates(device: UUID) {
    prepareStatement("UPDATE auditlab.app_update_requests SET status='expired',updated_at=now() WHERE device_id=? AND status IN ('pending','installer_opened') AND expires_at<=now()").use {
        it.setObject(1,device);it.executeUpdate()
    }
}

private fun Connection.updateView(device: UUID, id: UUID? = null): Map<String,Any?>? = prepareStatement("""SELECT id,release_snapshot,status,requested_at,expires_at,updated_at,error_code
    FROM auditlab.app_update_requests WHERE device_id=? ${if(id!=null) "AND id=?" else ""} ORDER BY (status IN ('pending','installer_opened')) DESC,requested_at DESC,id DESC LIMIT 1""").use { query ->
    query.setObject(1,device);if(id!=null) query.setObject(2,id)
    query.executeQuery().use { row -> if(!row.next()) null else mapOf("requestId" to row.getObject(1).toString(),
        "deviceId" to device.toString(),"release" to releaseFromJson(json.readTree(row.getString(2))).wire(),"status" to row.getString(3),
        "requestedAt" to wire(row.getTimestamp(4).toInstant()),"expiresAt" to wire(row.getTimestamp(5).toInstant()),
        "updatedAt" to wire(row.getTimestamp(6).toInstant()),"errorCode" to row.getString(7)) }
}

fun Route.appUpdateRoutes(auth: AuthService?, catalog: ReleaseCatalog) {
    get("/v1/app-releases") {
        call.api { correlation ->
            val service=auth ?: throw ApiFailure(503,"SERVICE_UNAVAILABLE")
            call.requireNoQuery("app")
            val app=call.queryMap()["app"] ?: "agent"
            if(app !in setOf("agent","control")) throw ApiFailure(400,"INVALID_REQUEST")
            val token=bearer(call.request.headers[HttpHeaders.Authorization])
            val result=withContext(Dispatchers.IO) {
                fun read(connection: Connection, actor: String, id: UUID): Map<String,Any> {
                    Audit.record(connection,actor,id,"app_releases_read",Audit.resource("app_release"),correlation=correlation)
                    return mapOf("items" to catalog.items.filter { it.app==app }.sortedByDescending { it.versionCode }.map { it.wire() })
                }
                if(token.startsWith("dev_")) service.authorizedDevice(token) { connection,device ->
                    if(app!="agent") throw ApiFailure(403,"FORBIDDEN")
                    read(connection,"device",device.deviceId)
                } else service.authorized(token,setOf("admin","auditor")) { connection,user -> read(connection,"user",user.userId) }
            }
            call.respondText(json.writeValueAsString(result),ContentType.Application.Json)
        }
    }
    post("/v1/devices/{deviceId}/update-requests") {
        call.api { correlation ->
            val service=auth ?: throw ApiFailure(503,"SERVICE_UNAVAILABLE")
            val device=call.deviceIdPath()
            val token=bearer(call.request.headers[HttpHeaders.Authorization])
            val body=call.strictObject();body.requireExactKeys(setOf("releaseId"))
            val releaseId=body.requireText("releaseId")
            parseUuid(releaseId)
            val result=withContext(Dispatchers.IO) {
                service.authorized(token,setOf("admin")) { connection,user ->
                    val release=catalog.items.find { it.releaseId==releaseId && it.app=="agent" } ?: throw ApiFailure(404,"RESOURCE_NOT_FOUND")
                    val active=connection.prepareStatement("SELECT status FROM auditlab.devices WHERE id=? FOR UPDATE").use {
                        it.setObject(1,device);it.executeQuery().use { row -> if(row.next()) row.getString(1) else null }
                    } ?: throw ApiFailure(404,"RESOURCE_NOT_FOUND")
                    if(active!="active") throw ApiFailure(409,"DEVICE_REVOKED")
                    connection.expireUpdates(device)
                    val existing=connection.updateView(device)
                    if(existing?.get("status") in UpdatePolicy.active) {
                        val previous=(existing!!["release"] as Map<*,*>)["releaseId"]
                        if(previous!=releaseId) throw ApiFailure(409,"UPDATE_ALREADY_PENDING")
                    } else {
                        connection.prepareStatement("INSERT INTO auditlab.app_update_requests(id,device_id,requested_by,release_id,release_snapshot,expires_at) VALUES (?,?,?,?,?::jsonb,now()+interval '7 days')").use {
                            it.setObject(1,UUID.randomUUID());it.setObject(2,device);it.setObject(3,user.userId)
                            it.setObject(4,UUID.fromString(releaseId));it.setString(5,json.writeValueAsString(release.wire()));it.executeUpdate()
                        }
                    }
                    val view=connection.updateView(device)!!
                    Audit.record(connection,"user",user.userId,"app_update_requested",Audit.resource("app_update",deviceId=device,
                        releaseId=UUID.fromString(releaseId),requestId=UUID.fromString(view["requestId"] as String)),correlation=correlation)
                    view
                }
            }
            call.respondText(json.writeValueAsString(result),ContentType.Application.Json,HttpStatusCode.Accepted)
        }
    }
    get("/v1/devices/{deviceId}/update-requests") {
        call.api { correlation ->
            val service=auth ?: throw ApiFailure(503,"SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            val device=call.deviceIdPath();val token=bearer(call.request.headers[HttpHeaders.Authorization])
            val result=withContext(Dispatchers.IO) {
                fun read(connection: Connection, actor: String, id: UUID): Map<String,Any?> {
                    connection.expireUpdates(device)
                    Audit.record(connection,actor,id,"app_update_read",Audit.resource("app_update",deviceId=device),correlation=correlation)
                    return mapOf("request" to connection.updateView(device))
                }
                if(token.startsWith("dev_")) service.authorizedDevice(token) { connection,identity ->
                    if(identity.deviceId!=device) throw ApiFailure(403,"FORBIDDEN")
                    read(connection,"device",device)
                } else service.authorized(token,setOf("admin","auditor")) { connection,user ->
                    connection.prepareStatement("SELECT id FROM auditlab.devices WHERE id=? FOR UPDATE").use {
                        it.setObject(1,device);it.executeQuery().use { row -> if(!row.next()) throw ApiFailure(404,"RESOURCE_NOT_FOUND") }
                    }
                    read(connection,"user",user.userId)
                }
            }
            call.respondText(json.writeValueAsString(result),ContentType.Application.Json)
        }
    }
    post("/v1/devices/{deviceId}/update-requests/{requestId}/result") {
        call.api { correlation ->
            val service=auth ?: throw ApiFailure(503,"SERVICE_UNAVAILABLE")
            val device=call.deviceIdPath();val token=bearer(call.request.headers[HttpHeaders.Authorization])
            val id=try { UUID.fromString(call.parameters["requestId"]) } catch(_: Exception) { throw ApiFailure(400,"INVALID_REQUEST") }
            val body=call.strictObject();body.requireExactKeys(setOf("status"),setOf("versionCode","signerSha256","errorCode"))
            val status=body.requireText("status")
            if(status !in UpdatePolicy.results) throw ApiFailure(400,"INVALID_REQUEST")
            val error=body["errorCode"]?.let { body.requireText("errorCode") }
            if(error!=null && (status!="failed" || error !in UpdatePolicy.errors)) throw ApiFailure(400,"INVALID_REQUEST")
            val result=withContext(Dispatchers.IO) {
                service.authorizedDevice(token) { connection,identity ->
                    if(identity.deviceId!=device) throw ApiFailure(403,"FORBIDDEN")
                    connection.expireUpdates(device)
                    val current=connection.updateView(device,id) ?: throw ApiFailure(404,"RESOURCE_NOT_FOUND")
                    val release=releaseFromJson(json.valueToTree(current["release"]))
                    if(!UpdatePolicy.canTransition(current["status"] as String,status)) throw ApiFailure(409,"UPDATE_STATE_CONFLICT")
                    if(status=="installed" && (body["versionCode"]?.isIntegralNumber!=true || !body["versionCode"].canConvertToLong() || body["versionCode"].longValue()!=release.versionCode ||
                            body["signerSha256"]?.asText()!=release.signerSha256)) throw ApiFailure(400,"INVALID_REQUEST")
                    if(status!="installed" && (body.has("versionCode") || body.has("signerSha256"))) throw ApiFailure(400,"INVALID_REQUEST")
                    connection.prepareStatement("UPDATE auditlab.app_update_requests SET status=?,error_code=?,updated_at=now() WHERE id=?").use {
                        it.setString(1,status);it.setString(2,error);it.setObject(3,id);it.executeUpdate()
                    }
                    if(status=="installed") connection.prepareStatement("UPDATE auditlab.devices SET app_version=? WHERE id=?").use {
                        it.setString(1,release.versionName);it.setObject(2,device);it.executeUpdate()
                    }
                    Audit.record(connection,"device",device,"app_update_result",Audit.resource("app_update",deviceId=device,requestId=id,
                        releaseId=UUID.fromString(release.releaseId)),
                        filters=json.writeValueAsString(mapOf("status" to status)),correlation=correlation)
                    connection.updateView(device,id)!!
                }
            }
            call.respondText(json.writeValueAsString(result),ContentType.Application.Json)
        }
    }
}
