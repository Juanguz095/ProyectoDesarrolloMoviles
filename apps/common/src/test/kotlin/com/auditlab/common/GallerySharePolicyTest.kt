package com.auditlab.common

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GallerySharePolicyTest {
    private fun item(id: String = "item-1", name: String = "foto.jpg", type: String = "image/jpeg",
                     size: Long = 1024, sha: String = "a".repeat(64)) =
        GallerySharePolicy.Item(id, name, type, size, sha)

    @Test fun `a valid batch is accepted`() {
        GallerySharePolicy.validate(listOf(item(), item(id = "item-2", name = "foto.png", type = "image/png")))
        assertEquals("gallery-share-1", GallerySharePolicy.VERSION)
        assertEquals(20, GallerySharePolicy.MAX_ITEMS)
    }

    @Test fun `empty oversized or duplicated batches are refused`() {
        assertThrows(IllegalArgumentException::class.java) { GallerySharePolicy.validate(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            GallerySharePolicy.validate((1..21).map { item(id = "item-$it") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            GallerySharePolicy.validate(listOf(item(), item()))
        }
    }

    @Test fun `per item limits and formats are enforced`() {
        assertThrows(IllegalArgumentException::class.java) {
            GallerySharePolicy.validate(listOf(item(size = GallerySharePolicy.MAX_BYTES_PER_ITEM + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GallerySharePolicy.validate(listOf(item(name = "sin-extension")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GallerySharePolicy.validate(listOf(item(type = "application/pdf", name = "doc.pdf", sha = "b".repeat(64))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GallerySharePolicy.validate(listOf(item(name = "../ruta.jpg")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GallerySharePolicy.validate(listOf(item(sha = "no-es-huella")))
        }
    }

    @Test fun `total batch size is bounded`() {
        val heavy = (1..20).map { item(id = "item-$it", name = "f$it.jpg", size = GallerySharePolicy.MAX_BYTES_PER_ITEM) }
        assertThrows(IllegalArgumentException::class.java) { GallerySharePolicy.validate(heavy) }
    }
}
