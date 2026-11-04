package com.auditlab.common

import java.net.HttpURLConnection
import java.net.URI
import java.io.IOException
import javax.net.ssl.SSLException
import org.json.JSONObject

/** Ephemeral frames only; no retry queue, redirects or disk cache for screen contents. */
class ScreenRelayClient(origin: String) {
    private val origin = ApiClient(origin).baseUrl
    fun request(device: String, token: String, method: String = "GET", frame: ByteArray? = null): ByteArray? {
        require(device.matches(Regex("[a-fA-F0-9-]{36}")))
        require(method in setOf("GET","POST","DELETE"))
        require((method=="POST") == (frame!=null))
        frame?.let {require(it.isNotEmpty() && it.size<=MAX_FRAME_BYTES)}
        val suffix = if (method == "DELETE") "" else "/frame"
        val connection = URI.create("$origin/v1/screen-share/$device$suffix").toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("Accept", "image/jpeg, application/problem+json")
            if (frame != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "image/jpeg")
                connection.setFixedLengthStreamingMode(frame.size)
                connection.outputStream.use { it.write(frame) }
            }
            val status = connection.responseCode
            if(status>=300) {
                val error=connection.errorStream?.use {input -> input.readNBytesCompat(16*1024).toString(Charsets.UTF_8)}.orEmpty()
                val problem=runCatching {JSONObject(error)}.getOrNull()
                throw ApiException(status,problem?.optString("code")?.takeIf {it.isNotBlank()} ?: "SCREEN_HTTP_$status",
                    connection.getHeaderField("X-Correlation-ID"),"Servicio de pantalla: HTTP $status")
            }
            if (status == 204) return null // POST/DELETE ack, or GET with no current frame.
            if(method!="GET" || status!=200 || connection.contentType?.substringBefore(';')?.trim()?.equals("image/jpeg",ignoreCase=true)!=true)
                throw ApiException(status,"INVALID_RESPONSE",null,"Respuesta de pantalla no válida")
            return connection.inputStream.use { stream ->
                val bytes=stream.readNBytesCompat(MAX_FRAME_BYTES)
                if(bytes.size<2 || bytes[0]!=0xff.toByte() || bytes[1]!=0xd8.toByte())
                    throw ApiException(status,"INVALID_RESPONSE",null,"Imagen JPEG no válida")
                bytes
            }
        } catch(error: ApiException) {throw error}
        catch(_: SSLException) {throw ApiException(0,"TLS_ERROR",null,"Certificado de pantalla rechazado")}
        catch(_: IOException) {throw ApiException(0,"NETWORK_ERROR",null,"No se pudo conectar al servicio de pantalla")}
        finally { connection.disconnect() }
    }
    companion object {const val MAX_FRAME_BYTES=512*1024}
}

/** Android API26-compatible bounded read; never retains unbounded error/frame bodies. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output=java.io.ByteArrayOutputStream()
    val buffer=ByteArray(8192)
    while(true) {
        val count=read(buffer);if(count<0) break
        if(output.size()+count>limit) throw ApiException(0,"RESPONSE_TOO_LARGE",null,"Respuesta de pantalla demasiado grande")
        output.write(buffer,0,count)
    }
    return output.toByteArray()
}
