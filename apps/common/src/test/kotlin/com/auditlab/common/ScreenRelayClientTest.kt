package com.auditlab.common

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ScreenRelayClientTest {
    @Test fun `does not follow redirects carrying screen credentials`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var followed = false
        server.createContext("/v1") { it.responseHeaders.add("Location", "/destination"); it.sendResponseHeaders(302, -1); it.close() }
        server.createContext("/destination") { followed = true; it.sendResponseHeaders(204, -1); it.close() }
        server.start()
        try {
            val client = ScreenRelayClient("http://127.0.0.1:${server.address.port}")
            assertEquals(302, assertThrows(ApiException::class.java) { client.request(UUID.randomUUID().toString(), "fixture-token") }.status)
            assertFalse(followed)
        } finally { server.stop(0) }
    }
    @Test fun `rejects insecure origins and resource path injection`() {
        assertThrows(IllegalArgumentException::class.java) { ScreenRelayClient("http://example.com") }
        assertThrows(IllegalArgumentException::class.java) { ScreenRelayClient("http://127.0.0.1").request("../../x", "fixture") }
    }
    @Test fun `a real relay round trip distinguishes no frame upload acknowledgment and decoded bytes`() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val device=UUID.randomUUID().toString()
        var frame: ByteArray?=null
        val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),1,2,0xff.toByte(),0xd9.toByte())
        server.createContext("/v1/screen-share/$device") {exchange ->
            assertEquals("Bearer fixture-token",exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("no-store",exchange.requestHeaders.getFirst("Cache-Control"))
            when(exchange.requestMethod) {
                "POST" -> {frame=exchange.requestBody.readBytes();exchange.sendResponseHeaders(204,-1)}
                "DELETE" -> {frame=null;exchange.sendResponseHeaders(204,-1)}
                else -> if(frame==null) exchange.sendResponseHeaders(204,-1) else {
                    exchange.responseHeaders.add("Content-Type","image/jpeg")
                    exchange.sendResponseHeaders(200,frame!!.size.toLong());exchange.responseBody.use {it.write(frame!!)}
                }
            }
            exchange.close()
        }
        server.start()
        try {
            val client=ScreenRelayClient("http://127.0.0.1:${server.address.port}")
            assertNull(client.request(device,"fixture-token"))
            assertNull(client.request(device,"fixture-token","POST",jpeg))
            assertArrayEquals(jpeg,client.request(device,"fixture-token"))
            assertArrayEquals(jpeg,client.request(device,"fixture-token"))
            client.request(device,"fixture-token","DELETE")
            assertNull(client.request(device,"fixture-token"))
        } finally {server.stop(0)}
    }
    @Test fun `HTTP failures retain their code instead of becoming no active session`() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/v1") {exchange ->
            val bytes="""{"code":"DEVICE_REVOKED"}""".toByteArray()
            exchange.sendResponseHeaders(401,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)};exchange.close()
        }
        server.start()
        try {
            val client=ScreenRelayClient("http://127.0.0.1:${server.address.port}")
            val error=assertThrows(ApiException::class.java) {client.request(UUID.randomUUID().toString(),"fixture-token")}
            assertEquals(401,error.status);assertEquals("DEVICE_REVOKED",error.code)
        } finally {server.stop(0)}
    }

}
