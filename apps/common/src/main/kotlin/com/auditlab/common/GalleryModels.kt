package com.auditlab.common

/** Lote de fotos compartido por el usuario, tal como lo consulta el Centro (plan 0.9, M2). */
data class GalleryItemInfo(
    val itemId: String,
    val fileName: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val state: String,
)

data class GalleryBatchInfo(
    val batchId: String,
    val deviceId: String,
    val itemCount: Int,
    val totalBytes: Long,
    val createdAt: String,
    val completedAt: String?,
    val items: List<GalleryItemInfo> = emptyList(),
)
