package com.auditlab.api

import com.auditlab.common.AppRelease
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppUpdateIntegrationTest : ApiTestSupport() {
    private fun release(app: String="agent",code: Long=15)=AppRelease(UUID.randomUUID().toString(),app,"com.auditlab.$app",
        "0.8.$code-test",code,26,1234,"a".repeat(64),"b".repeat(64),
        "https://raw.githubusercontent.com/Juanguz095/ProyectoDesarrolloMoviles/refs/heads/descargas-auditlab/auditlab-$app-0.8.$code-test.apk")
    private val agent=release()
    private val newer=release(code=16)
    private val control=release("control")
    private val catalog=ReleaseCatalog(listOf(agent,newer,control))
    @BeforeAll fun setup() {
        Bootstrap.user(owner,"update_admin",password,"admin")
        Bootstrap.user(owner,"update_auditor",password,"auditor")
    }
    private suspend fun ApplicationTestBuilder.create(token: String,device: JsonNode,release: AppRelease=agent)=
        client.post("/v1/devices/${device["deviceId"].asText()}/update-requests") {
            bearerAuth(token);contentType(ContentType.Application.Json);setBody(mapper.writeValueAsString(mapOf("releaseId" to release.releaseId)))
        }
    private suspend fun ApplicationTestBuilder.latest(token: String,device: JsonNode)=client.get("/v1/devices/${device["deviceId"].asText()}/update-requests") {bearerAuth(token)}
    private suspend fun ApplicationTestBuilder.result(token: String,device: JsonNode,id: String,body: Map<String,Any>)=
        client.post("/v1/devices/${device["deviceId"].asText()}/update-requests/$id/result") {
            bearerAuth(token);contentType(ContentType.Application.Json);setBody(mapper.writeValueAsString(body))
        }

    @Test fun `catalog and requests enforce roles and isolate three devices`()=http(catalog) { _ ->
        val admin=login("update_admin");val auditor=login("update_auditor")
        val devices=(1..3).map {enroll(admin)}
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/app-releases").status)
        val own=devices[0]["deviceToken"].asText()
        assertEquals(HttpStatusCode.OK,client.get("/v1/app-releases?app=agent") {bearerAuth(own)}.status)
        problem(client.get("/v1/app-releases?app=control") {bearerAuth(own)},403,"FORBIDDEN")
        problem(client.get("/v1/app-releases?app=other") {bearerAuth(admin)},400,"INVALID_REQUEST")
        problem(client.get("/v1/app-releases?app=agent&app=control") {bearerAuth(admin)},400,"INVALID_REQUEST")
        problem(create(auditor,devices[0]),403,"FORBIDDEN")
        problem(create(own,devices[0]),401,"UNAUTHENTICATED")
        problem(create(admin,devices[0],control),404,"RESOURCE_NOT_FOUND")
        devices.forEach {device -> assertEquals(HttpStatusCode.Accepted,create(admin,device).status)}
        val created=mapper.readTree(latest(own,devices[0]).bodyAsText())["request"]
        val repeated=mapper.readTree(create(admin,devices[0]).bodyAsText())
        assertEquals(created["requestId"],repeated["requestId"])
        problem(create(admin,devices[0],newer),409,"UPDATE_ALREADY_PENDING")
        problem(latest(own,devices[1]),403,"FORBIDDEN")
        assertEquals(HttpStatusCode.OK,latest(auditor,devices[0]).status)
        val other=mapper.readTree(latest(devices[1]["deviceToken"].asText(),devices[1]).bodyAsText())["request"]
        assertNotEquals(created["requestId"],other["requestId"])
        inspect {connection -> connection.prepareStatement("SELECT last_seen_at FROM auditlab.devices WHERE id=?").use {
            it.setObject(1,UUID.fromString(devices[0]["deviceId"].asText()));it.executeQuery().use {row -> assertTrue(row.next());assertNull(row.getTimestamp(1))}
        } }
    }

    @Test fun `only recipient reports matching version and final states cannot roll back`()=http(catalog) { _ ->
        val admin=login("update_admin");val device=enroll(admin);val other=enroll(admin)
        val token=device["deviceToken"].asText();val id=mapper.readTree(create(admin,device).bodyAsText())["requestId"].asText()
        problem(result(other["deviceToken"].asText(),device,id,mapOf("status" to "declined")),403,"FORBIDDEN")
        problem(result(token,device,id,mapOf("status" to "installed")),400,"INVALID_REQUEST")
        problem(result(token,device,id,mapOf("status" to "installed","versionCode" to agent.versionCode,"signerSha256" to "c".repeat(64))),400,"INVALID_REQUEST")
        assertEquals(HttpStatusCode.OK,result(token,device,id,mapOf("status" to "installer_opened")).status)
        val installed=mapOf("status" to "installed","versionCode" to agent.versionCode,"signerSha256" to agent.signerSha256)
        assertEquals(HttpStatusCode.OK,result(token,device,id,installed).status)
        assertEquals(HttpStatusCode.OK,result(token,device,id,installed).status)
        problem(result(token,device,id,mapOf("status" to "declined")),409,"UPDATE_STATE_CONFLICT")
        inspect {connection -> connection.prepareStatement("SELECT app_version FROM auditlab.devices WHERE id=?").use {
            it.setObject(1,UUID.fromString(device["deviceId"].asText()));it.executeQuery().use {row -> assertTrue(row.next());assertEquals(agent.versionName,row.getString(1))}
        } }
    }

    @Test fun `expired requests can be replaced and revocation blocks both directions`()=http(catalog) { _ ->
        val admin=login("update_admin");val device=enroll(admin);val token=device["deviceToken"].asText()
        val id=mapper.readTree(create(admin,device).bodyAsText())["requestId"].asText()
        sql("UPDATE auditlab.app_update_requests SET requested_at=now()-interval '8 days',expires_at=now()-interval '1 day' WHERE id=?",UUID.fromString(id))
        assertEquals("expired",mapper.readTree(latest(token,device).bodyAsText())["request"]["status"].asText())
        problem(result(token,device,id,mapOf("status" to "installer_opened")),409,"UPDATE_STATE_CONFLICT")
        val replacement=mapper.readTree(create(admin,device,newer).bodyAsText())
        assertNotEquals(id,replacement["requestId"].asText())
        assertEquals(HttpStatusCode.NoContent,client.post("/v1/devices/${device["deviceId"].asText()}/revoke") {bearerAuth(admin)}.status)
        problem(latest(token,device),401,"DEVICE_REVOKED")
        problem(create(admin,device),409,"DEVICE_REVOKED")
    }

    @Test fun `concurrent retries create one active request and transactional audit`()=http(catalog) { _ ->
        val admin=login("update_admin");val device=enroll(admin)
        val ids=coroutineScope {(1..8).map {async {
            val response=create(admin,device);assertEquals(HttpStatusCode.Accepted,response.status)
            mapper.readTree(response.bodyAsText())["requestId"].asText()
        }}.awaitAll()}
        assertEquals(1,ids.distinct().size)
        inspect {connection -> connection.prepareStatement("SELECT count(*) FROM auditlab.app_update_requests WHERE device_id=?").use {
            it.setObject(1,UUID.fromString(device["deviceId"].asText()));it.executeQuery().use {row ->row.next();assertEquals(1,row.getInt(1))}
        } }
    }
}
