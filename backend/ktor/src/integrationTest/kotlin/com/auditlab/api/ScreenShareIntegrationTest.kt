package com.auditlab.api

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScreenShareIntegrationTest : ApiTestSupport() {
    @BeforeAll fun setup() { Bootstrap.user(owner, "screen_admin", password, "admin") }
    @Test fun `frame relay isolates devices requires authentication and stops on revoke`() = http { _ ->
        val admin = login("screen_admin")
        val device = enroll(admin)
        val other = enroll(admin)
        val id = device["deviceId"].textValue()
        val token = device["deviceToken"].textValue()
        val path = "/v1/screen-share/$id/frame"
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2)
        problem(client.get(path), 401)
        assertEquals(HttpStatusCode.NoContent, client.get(path) { bearerAuth(admin) }.status)
        problem(client.post(path) { bearerAuth(other["deviceToken"].textValue()); contentType(ContentType.Image.JPEG); setBody(jpeg) }, 403)
        assertEquals(HttpStatusCode.NoContent, client.post(path) { bearerAuth(token); contentType(ContentType.Image.JPEG); setBody(jpeg) }.status)
        val frame = client.get(path) { bearerAuth(admin) }
        assertEquals(HttpStatusCode.OK, frame.status)
        assertEquals("no-store", frame.headers["Cache-Control"])
        assertArrayEquals(jpeg, frame.bodyAsBytes())
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/screen-share/$id") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NoContent, client.get(path) { bearerAuth(admin) }.status)
        client.post("/v1/devices/$id/revoke") { bearerAuth(admin) }
        problem(client.post(path) { bearerAuth(token); contentType(ContentType.Image.JPEG); setBody(jpeg) }, 401)
        problem(client.get(path) { bearerAuth(admin) }, 409)
    }
}
