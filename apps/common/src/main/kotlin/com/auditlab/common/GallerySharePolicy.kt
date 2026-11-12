package com.auditlab.common

/**
 * One deliberate photo batch selected explicitly by the user (plan 0.9, M2).
 * The picker never scans the gallery: only the elements the user confirms are read,
 * previewed before sending and copied to a temporary location that is erased on
 * cancel or after upload. Limits follow decision D9.
 */
object GallerySharePolicy {
    const val VERSION = "gallery-share-1"
    const val MAX_ITEMS = 20
    const val MAX_BYTES_PER_ITEM = 15L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 150L * 1024 * 1024
    val mediaTypes = setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic")
    val extensions = setOf("jpg", "jpeg", "png", "webp", "heif", "heic")

    data class Item(
        val itemId: String,
        val fileName: String,
        val mediaType: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    fun validateItem(item: Item) {
        require(item.itemId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Identificador de elemento no válido." }
        require(item.fileName.length in 1..120 && !item.fileName.contains(Regex("[\\\\/\\r\\n]"))) { "Nombre de archivo no permitido." }
        require(item.fileName.substringAfterLast('.', "").lowercase() in extensions) { "Extensión no permitida." }
        require(item.mediaType in mediaTypes) { "Formato no permitido." }
        require(item.sizeBytes in 1..MAX_BYTES_PER_ITEM) { "El archivo supera el límite de 15 MB." }
        require(item.sha256.matches(Regex("[0-9a-f]{64}"))) { "Huella del archivo no válida." }
    }

    fun validate(items: List<Item>) {
        require(items.isNotEmpty() && items.size <= MAX_ITEMS) { "Selecciona entre 1 y $MAX_ITEMS elementos." }
        require(items.map { it.itemId }.toSet().size == items.size) { "Elementos duplicados." }
        require(items.sumOf { it.sizeBytes } <= MAX_TOTAL_BYTES) { "El lote supera el límite total." }
        items.forEach(::validateItem)
    }
}
