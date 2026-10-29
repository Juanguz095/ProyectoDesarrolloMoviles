package com.auditlab.api

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

internal fun Route.whatsAppPreviewRoutes(auth: AuthService?, hub: WhatsAppPreviewHub) {
    post("/v1/devices/{deviceId}/whatsapp-sharing") {
        call.api { correlation ->
            val service=auth ?: throw ApiFailure(503,"SERVICE_UNAVAILABLE")
            call.requireNoQuery()
            val device=call.deviceIdPath();val token=bearer(call.request.headers[HttpHeaders.Authorization])
            withContext(Dispatchers.IO) { service.authorizedDevice(token) { _,d -> if(d.deviceId!=device) throw ApiFailure(403,"DEVICE_SCOPE_MISMATCH") } }
            val body=call.strictObject()
            body.requireExactKeys(setOf("sessionId","acceptedAt","policyVersion","consentContext"))
            val policy=body.requireText("policyVersion")
            if(policy !in setOf("whatsapp-preview-1","whatsapp-preview-2")) throw ApiFailure(400,"INVALID_REQUEST")
            val id=parseUuid(body.requireText("sessionId"));val accepted=parseInstant(body.requireText("acceptedAt"),"acceptedAt")
            if(accepted.isBefore(Instant.now().minusSeconds(300)) || accepted.isAfter(Instant.now().plusSeconds(300))) throw ApiFailure(400,"INVALID_REQUEST")
            val consent=parseConsent(body.requireObject("consentContext"))
            withContext(Dispatchers.IO) { service.authorizedDevice(token) { connection,d ->
                if(d.deviceId!=device) throw ApiFailure(403,"DEVICE_SCOPE_MISMATCH")
                if(consent.id!=d.consentId || consent.policyVersion!=d.policyVersion || consent.acceptedAt!=d.acceptedAt) throw ApiFailure(409,"CONSENT_MISMATCH")
                Audit.record(connection,"device",device,"whatsapp_sharing_started",json.writeValueAsString(mapOf("type" to "whatsapp_preview","deviceId" to device.toString(),"sessionId" to id.toString(),"consentId" to consent.id.toString(),"acceptedAt" to wire(accepted),"policyVersion" to policy)),correlation=correlation)
                hub.start(device,id,accepted)
            } }
            call.respond(HttpStatusCode.NoContent)
        }
    }
    post("/v1/devices/{deviceId}/whatsapp-previews") {
        call.api {
            val service=auth ?: throw ApiFailure(503,"SERVICE_UNAVAILABLE")
            call.requireNoQuery();val device=call.deviceIdPath();val token=bearer(call.request.headers[HttpHeaders.Authorization])
            withContext(Dispatchers.IO) { service.authorizedDevice(token) { _,d -> if(d.deviceId!=device) throw ApiFailure(403,"DEVICE_SCOPE_MISMATCH") } }
            val body=call.strictObject()
            body.requireExactKeys(setOf("sessionId","messageId","timestamp","packageName","title","text"))
            val pkg=body.requireText("packageName");val title=body.requireText("title");val text=body.requireText("text")
            if(pkg !in setOf("com.whatsapp","com.whatsapp.w4b") || title.length>200 || text.isBlank() || text.length>2000) throw ApiFailure(400,"INVALID_REQUEST")
            val session=parseUuid(body.requireText("sessionId"))
            val preview=WhatsAppPreviewHub.Preview(parseUuid(body.requireText("messageId")),parseInstant(body.requireText("timestamp"),"timestamp"),pkg,title,text)
            withContext(Dispatchers.IO) { service.authorizedDevice(token) { _,d ->
                if(d.deviceId!=device) throw ApiFailure(403,"DEVICE_SCOPE_MISMATCH")
                hub.publish(device,session,preview)
            } }
            call.respond(HttpStatusCode.NoContent)
        }
    }
    post("/v1/devices/{deviceId}/whatsapp-sharing/{sharingId}/heartbeat") {
        call.api {
            val service=auth ?: throw ApiFailure(503,"SERVICE_UNAVAILABLE")
            call.requireNoQuery();val device=call.deviceIdPath();val id=parseUuid(call.parameters["sharingId"].orEmpty())
            val token=bearer(call.request.headers[HttpHeaders.Authorization])
            withContext(Dispatchers.IO) { service.authorizedDevice(token) { _,d ->
                if(d.deviceId!=device) throw ApiFailure(403,"DEVICE_SCOPE_MISMATCH")
                hub.heartbeat(device,id)
            } }
            call.respond(HttpStatusCode.NoContent)
        }
    }
    delete("/v1/devices/{deviceId}/whatsapp-sharing/{sharingId}") {
        call.api { correlation ->
            val service=auth ?: throw ApiFailure(503,"SERVICE_UNAVAILABLE")
            call.requireNoQuery();val device=call.deviceIdPath();val id=parseUuid(call.parameters["sharingId"].orEmpty())
            val token=bearer(call.request.headers[HttpHeaders.Authorization])
            withContext(Dispatchers.IO) { service.authorizedDevice(token) { connection,d ->
                if(d.deviceId!=device) throw ApiFailure(403,"DEVICE_SCOPE_MISMATCH")
                Audit.record(connection,"device",device,"whatsapp_sharing_stopped",json.writeValueAsString(mapOf("type" to "whatsapp_preview","deviceId" to device.toString(),"sessionId" to id.toString())),correlation=correlation)
                hub.stop(device,id)
            } }
            call.respond(HttpStatusCode.NoContent)
        }
    }
    get("/v1/devices/{deviceId}/whatsapp-previews") {
        call.api { correlation ->
            val service=auth ?: throw ApiFailure(503,"SERVICE_UNAVAILABLE")
            call.requireNoQuery();val device=call.deviceIdPath();val token=bearer(call.request.headers[HttpHeaders.Authorization])
            val result=withContext(Dispatchers.IO) { service.authorized(token,setOf("admin","auditor")) { connection,user ->
                connection.prepareStatement("SELECT d.status,c.revoked_at FROM auditlab.devices d JOIN auditlab.consents c ON c.id=d.consent_id WHERE d.id=?").use {
                    it.setObject(1,device);it.executeQuery().use { row ->
                        if(!row.next()) throw ApiFailure(404,"RESOURCE_NOT_FOUND")
                        if(row.getString(1)!="active" || row.getTimestamp(2)!=null) { hub.stop(device);throw ApiFailure(409,"DEVICE_REVOKED") }
                    }
                }
                Audit.record(connection,"user",user.userId,"whatsapp_previews_queried",Audit.resource("whatsapp_preview",device),correlation=correlation)
                hub.snapshot(device)
            } }
            call.respondText(json.writeValueAsString(result),ContentType.Application.Json)
        }
    }
}
