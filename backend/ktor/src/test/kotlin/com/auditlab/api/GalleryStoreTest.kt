package com.auditlab.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class GalleryStoreTest {
    @Test fun `files round trip and batch deletion removes every byte`(@TempDir dir: Path) {
        val store = GalleryStore(dir)
        val device = UUID.randomUUID()
        val batch = UUID.randomUUID()
        val bytes = byteArrayOf(1, 2, 3, 4)
        store.write(device, batch, "item-1", bytes, GalleryStore.sha256(bytes))
        assertArrayEquals(bytes, store.read(device, batch, "item-1"))
        assertNull(store.read(device, batch, "missing"))
        store.deleteBatch(device, batch)
        assertNull(store.read(device, batch, "item-1"))
    }

    @Test fun `mismatching hash empty files and unsafe item ids are refused`(@TempDir dir: Path) {
        val store = GalleryStore(dir)
        val device = UUID.randomUUID()
        val batch = UUID.randomUUID()
        assertThrows(IllegalArgumentException::class.java) {
            store.write(device, batch, "item-1", byteArrayOf(1), "a".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(device, batch, "item-1", ByteArray(0), GalleryStore.sha256(ByteArray(0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(device, batch, "../escape", byteArrayOf(1), GalleryStore.sha256(byteArrayOf(1)))
        }
    }
}
