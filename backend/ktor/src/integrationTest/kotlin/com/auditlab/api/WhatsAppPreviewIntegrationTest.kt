package com.auditlab.api

import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhatsAppPreviewIntegrationTest : ApiTestSupport() {
    @BeforeAll fun setup() {
        Bootstrap.user(owner,"wa_admin",password,"admin")
        Bootstrap.user(owner,"wa_auditor",password,"auditor")
    }
    @Test fun `optional preview scope authenticates isolates audits without content and stops permanently`() = http { _ ->
        val admin=login("wa_admin");val auditor=login("wa_auditor")
        val d=enroll(admin);val other=enroll(admin)
        val device=d["deviceId"].textValue();val token=d["deviceToken"].textValue()
        val root="/v1/devices/$device";val session=UUID.randomUUID().toString();val accepted=Instant.now()
        val start=mapOf("sessionId" to session,"acceptedAt" to accepted.toString(),"policyVersion" to "whatsapp-preview-1","consentContext" to d["consentContext"])
        suspend fun begin(auth:String=token,body:Any=start)=client.post("$root/whatsapp-sharing") { bearerAuth(auth);contentType(ContentType.Application.Json);setBody(mapper.writeValueAsString(body)) }
        val payload=mapOf("sessionId" to session,"messageId" to UUID.randomUUID().toString(),"timestamp" to accepted.plusMillis(1).toString(),"packageName" to "com.whatsapp","title" to "QA contact","text" to "private_preview_fixture_29_oct")
        suspend fun send(body:Any=payload,auth:String=token)=client.post("$root/whatsapp-previews") { bearerAuth(auth);contentType(ContentType.Application.Json);setBody(mapper.writeValueAsString(body)) }
        problem(client.get("$root/whatsapp-previews"),401)
        problem(send(),409)
        problem(begin(admin),401)
        problem(begin(other["deviceToken"].textValue()),403)
        problem(begin(body=start+("policyVersion" to "wrong")),400)
        assertEquals(HttpStatusCode.NoContent,begin().status)
        assertEquals(HttpStatusCode.NoContent,send().status)
        assertEquals(HttpStatusCode.NoContent,send().status)
        problem(send(payload+("packageName" to "other.app")),400)
        problem(send(payload+("text" to "x".repeat(2001))),400)
        problem(send(payload+("text" to "changed")),409)
        problem(send(payload+("timestamp" to accepted.minusSeconds(1).toString())),400)
        val result=client.get("$root/whatsapp-previews") { bearerAuth(auditor) }
        assertEquals(HttpStatusCode.OK,result.status);assertEquals("no-store",result.headers["Cache-Control"])
        val snapshot=json(result);assertTrue(snapshot["active"].booleanValue());assertEquals(1,snapshot["items"].size())
        assertEquals(payload["text"],snapshot["items"][0]["text"].textValue())
        val persisted=inspect { connection -> connection.prepareStatement("SELECT count(*) FROM auditlab.audit_log WHERE resource::text LIKE ? OR filters::text LIKE ?").use {
            it.setString(1,"%${payload["text"]}%");it.setString(2,"%${payload["text"]}%")
            it.executeQuery().use { row->row.next();row.getInt(1) }
        } }
        assertEquals(0,persisted)
        assertEquals(HttpStatusCode.NoContent,client.delete("$root/whatsapp-sharing/$session") { bearerAuth(token) }.status)
        val stopped=json(client.get("$root/whatsapp-previews") { bearerAuth(admin) })
        assertFalse(stopped["active"].booleanValue());assertEquals(0,stopped["items"].size())
        problem(send(),409)
        problem(client.post("$root/whatsapp-sharing/$session/heartbeat") { bearerAuth(token) },409)
        val next=start+("sessionId" to UUID.randomUUID().toString())+("acceptedAt" to accepted.plusMillis(10).toString())
        assertEquals(HttpStatusCode.NoContent,begin(body=next).status)
        client.post("$root/revoke") { bearerAuth(admin) }
        problem(client.get("$root/whatsapp-previews") { bearerAuth(admin) },409)
        problem(send(),401)
    }
}
