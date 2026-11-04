package com.auditlab.api

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManualShareIntegrationTest : ApiTestSupport() {
    @BeforeAll fun setup() {
        Bootstrap.user(owner,"manual_admin",password,"admin")
        Bootstrap.user(owner,"manual_auditor",password,"auditor")
    }
    @Test fun `manual text persists queries exports retries and respects consent and device scope`()=http { _ ->
        val admin=login("manual_admin");val auditor=login("manual_auditor")
        val device=enroll(admin);val other=enroll(admin);val token=device["deviceToken"].asText()
        val metadata=mapOf("appEventCode" to "text_shared_manually","sharedText" to "Texto ficticio elegido","shareConsentVersion" to "manual-share-1")
        val valid=event(device,category="manual_share",direction="occurred",metadata=metadata)+("isSimulated" to false)
        assertEquals(HttpStatusCode.Accepted,sendEvents(token,valid).status)
        assertEquals(1,json(sendEvents(token,valid))["duplicates"].asInt())
        problem(sendEvents(token,valid+("metadata" to (metadata+("sharedText" to "Diferente")))),409,"EVENT_ID_CONFLICT")
        problem(sendEvents(other["deviceToken"].asText(),valid),403,"DEVICE_SCOPE_MISMATCH")
        listOf(metadata-"sharedText",metadata-"shareConsentVersion",metadata+("sharedText" to " "),
            metadata+("sharedText" to "x".repeat(2001)),metadata+("phoneNumber" to "123")).forEach {
            problem(sendEvents(token,valid+("metadata" to it)),400,"INVALID_REQUEST")
        }
        problem(sendEvents(token,valid+("isSimulated" to true)),400,"INVALID_REQUEST")
        problem(sendEvents(token,valid+("category" to "whatsapp")),400,"INVALID_REQUEST")
        inspect { connection ->
            connection.autoCommit=false
            try {
                for (bad in listOf(metadata-"sharedText", metadata-"shareConsentVersion",
                    metadata+("sharedText" to ""), metadata+("sharedText" to "x".repeat(2001)),
                    metadata+("phoneNumber" to "123"))) {
                    val savepoint=connection.setSavepoint()
                    val error=assertThrows(java.sql.SQLException::class.java) {
                        connection.prepareStatement("UPDATE auditlab.events SET metadata=?::jsonb WHERE device_id=?").use {
                            it.setString(1,mapper.writeValueAsString(bad))
                            it.setObject(2,java.util.UUID.fromString(device["deviceId"].asText()))
                            it.executeUpdate()
                        }
                    }
                    assertEquals("23514",error.sqlState)
                    connection.rollback(savepoint)
                }
            } finally { connection.rollback() }
        }
        val path="/v1/events?device=${device["deviceId"].asText()}&category=manual_share"
        val page=json(client.get(path){bearerAuth(auditor)})
        assertEquals("Texto ficticio elegido",page["items"][0]["metadata"]["sharedText"].asText())
        val csv=client.get("/v1/events/export.csv?device=${device["deviceId"].asText()}&category=manual_share"){bearerAuth(auditor)}
        assertEquals(HttpStatusCode.OK,csv.status);assertTrue(csv.bodyAsText().contains("Texto ficticio elegido"))
        assertEquals(HttpStatusCode.NoContent,client.post("/v1/devices/${device["deviceId"].asText()}/consent-revoke"){bearerAuth(token)}.status)
        problem(sendEvents(token,valid),401,"DEVICE_REVOKED")
    }
}
