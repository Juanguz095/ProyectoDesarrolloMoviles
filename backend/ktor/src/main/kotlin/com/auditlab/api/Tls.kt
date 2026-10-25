package com.auditlab.api

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * Material TLS para servir HTTPS sin depender de un archivo de KeyStore previo.
 *
 * Acepta un certificado X.509 en PEM y una clave privada **PKCS#8 sin cifrar** (`BEGIN PRIVATE KEY`),
 * los formatos que produce `openssl req -newkey ... -nodes`. La clave se carga en un PKCS#12 en
 * memoria con una contraseña aleatoria de un solo proceso; no se escribe en disco ni se registra.
 * No se implementa criptografía propia: solo se usan `KeyFactory`, `CertificateFactory` y `KeyStore`.
 */
object Tls {
    data class Material(val keyStore: KeyStore, val alias: String, val password: CharArray)

    fun load(certificate: Path, privateKey: Path): Material {
        require(Files.isReadable(certificate)) { "AUDITLAB_TLS_CERTIFICATE is not readable" }
        require(Files.isReadable(privateKey)) { "AUDITLAB_TLS_PRIVATE_KEY is not readable" }
        val certificateValue = CertificateFactory.getInstance("X.509")
            .generateCertificate(Files.newInputStream(certificate))
        val encoded = pemBody(Files.readString(privateKey), "PRIVATE KEY")
        val spec = PKCS8EncodedKeySpec(encoded)
        val key = listOf("RSA", "EC", "Ed25519").firstNotNullOfOrNull { algorithm ->
            runCatching { KeyFactory.getInstance(algorithm).generatePrivate(spec) }.getOrNull()
        } ?: throw IllegalArgumentException("AUDITLAB_TLS_PRIVATE_KEY is not an unencrypted PKCS#8 RSA, EC or Ed25519 key")

        val password = UUID.randomUUID().toString().replace("-", "").toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        keyStore.setKeyEntry(ALIAS, key, password, arrayOf(certificateValue))
        return Material(keyStore, ALIAS, password)
    }

    /** Extrae el DER de un PEM, rechazando claves cifradas con contraseña. */
    private fun pemBody(pem: String, label: String): ByteArray {
        val begin = "-----BEGIN $label-----"
        val end = "-----END $label-----"
        require(pem.contains(begin) && pem.contains(end)) {
            "AUDITLAB_TLS_PRIVATE_KEY must be an unencrypted PEM block ($label)"
        }
        val body = pem.substringAfter(begin).substringBefore(end)
        return Base64.getMimeDecoder().decode(body.filterNot(Char::isWhitespace))
    }

    private const val ALIAS = "auditlab"
}
