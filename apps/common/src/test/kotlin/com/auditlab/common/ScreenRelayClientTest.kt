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
            assertThrows(IllegalStateException::class.java) { client.request(UUID.randomUUID().toString(), "fixture-token") }
            assertFalse(followed)
        } finally { server.stop(0) }
    }
    @Test fun `rejects insecure origins and resource path injection`() {
        assertThrows(IllegalArgumentException::class.java) { ScreenRelayClient("http://example.com") }
        assertThrows(IllegalArgumentException::class.java) { ScreenRelayClient("http://127.0.0.1").request("../../x", "fixture") }
    }
}
