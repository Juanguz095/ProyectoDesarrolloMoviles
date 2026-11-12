package com.auditlab.api

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/**
 * Filesystem store for explicit gallery batches (plan 0.9, D11): PostgreSQL keeps only
 * metadata and references. Each file lives under mediaDir/<device>/<batch>/<item> and is
 * accepted only when its SHA-256 matches the declared value. Deletion is manual and
 * removes the bytes (D12); the audit trail keeps the record.
 */
class GalleryStore(private val mediaDir: Path = Path.of(System.getenv("AUDITLAB_MEDIA_DIR") ?: "auditlab-media")) {
    companion object {
        val shared = GalleryStore()
        private val safeItem = Regex("^[A-Za-z0-9_-]{1,64}$")
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun itemPath(deviceId: UUID, batchId: UUID, itemId: String): Path {
        require(safeItem.matches(itemId)) { "Identificador de elemento no válido." }
        return mediaDir.resolve(deviceId.toString()).resolve(batchId.toString()).resolve(itemId)
    }

    fun write(deviceId: UUID, batchId: UUID, itemId: String, bytes: ByteArray, expectedSha256: String) {
        require(bytes.isNotEmpty()) { "Archivo vacío." }
        require(sha256(bytes) == expectedSha256.lowercase()) { "La huella del archivo no coincide." }
        val target = itemPath(deviceId, batchId, itemId)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
    }

    fun read(deviceId: UUID, batchId: UUID, itemId: String): ByteArray? {
        val target = itemPath(deviceId, batchId, itemId)
        return if (Files.isRegularFile(target)) Files.readAllBytes(target) else null
    }

    fun deleteBatch(deviceId: UUID, batchId: UUID) {
        val dir = mediaDir.resolve(deviceId.toString()).resolve(batchId.toString())
        if (Files.isDirectory(dir)) {
            Files.list(dir).use { paths -> paths.forEach(Files::delete) }
            Files.deleteIfExists(dir)
        }
    }
}
