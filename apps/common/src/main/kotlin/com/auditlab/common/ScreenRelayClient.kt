package com.auditlab.common

import java.net.HttpURLConnection
import java.net.URI

/** Ephemeral frames only; no retry queue or disk cache for screen contents. */
class ScreenRelayClient(origin: String) {
    private val origin = ApiClient(origin).baseUrl
    fun request(device: String, token: String, method: String = "GET", frame: ByteArray? = null): ByteArray? {
        require(device.matches(Regex("[a-fA-F0-9-]{36}")))
        val suffix = if (method == "DELETE") "" else "/frame"
        val connection = URI.create("$origin/v1/screen-share/$device$suffix").toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Cache-Control", "no-store")
            if (frame != null) {
                require(frame.size <= 512 * 1024)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "image/jpeg")
                connection.setFixedLengthStreamingMode(frame.size)
                connection.outputStream.use { it.write(frame) }
            }
            val status = connection.responseCode
            if (status == 204) return null
            check(status == 200) { "Servicio de pantalla: HTTP $status" }
            return connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 512 * 1024)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
}
