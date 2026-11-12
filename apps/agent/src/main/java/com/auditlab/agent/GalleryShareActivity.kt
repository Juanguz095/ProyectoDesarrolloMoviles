package com.auditlab.agent

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.auditlab.common.*
import com.auditlab.design.*
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Explicit gallery sharing (plan 0.9, M2): the system document picker opens only on a
 * local action, the user previews every selected element before anything is read into
 * the temporary folder, and each batch is confirmed individually. Cancel erases the
 * temporaries; nothing is sent without acceptance and nothing scans the gallery.
 */
class GalleryShareActivity : ComponentActivity() {
    private data class Selected(
        val itemId: String, val file: File, val fileName: String, val mediaType: String,
        val sizeBytes: Long, val sha256: String, val preview: android.graphics.Bitmap?,
    )

    private val temporary: File get() = File(cacheDir, "gallery-share")
    private val selectionState = androidx.compose.runtime.mutableStateOf<Pair<List<Selected>, String?>?>(null)
    @Volatile private var completed = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) loadSelection(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var selected by remember { mutableStateOf<List<Selected>>(emptyList()) }
            var agreed by remember { mutableStateOf(false) }
            var working by remember { mutableStateOf(false) }
            var feedback by remember { mutableStateOf<String?>(null) }
            var finished by remember { mutableStateOf(false) }
            val pendingSelection by selectionState
            LaunchedEffect(pendingSelection) {
                pendingSelection?.let { (items, error) ->
                    selected = items; if (error != null) feedback = error; selectionState.value = null
                }
            }
            AuditPage(feedback = feedback, working = working) {
                AuditHeader("COMPARTIR FOTOS", "SELECCIÓN EXPLÍCITA")
                if (finished) {
                    Text("Fotos compartidas con el destino indicado.")
                    AuditButton("Cerrar", { finish() })
                    return@AuditPage
                }
                Text("Elige hasta ${GallerySharePolicy.MAX_ITEMS} imágenes. Solo se leen las que selecciones y confirmes; nada se envía sin tu aceptación.")
                Text("Límites: 15 MB por archivo, formatos JPEG, PNG, WebP o HEIC.", style = MaterialTheme.typography.bodySmall, color = AuditColors.Muted)
                AuditButton("Elegir fotos", { picker.launch(arrayOf("image/*")) }, !working)
                if (selected.isNotEmpty()) {
                    SectionLabel("Vista previa de la selección")
                    selected.forEach { item ->
                        AuditPanel {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                item.preview?.let { Image(it.asImageBitmap(), "Vista previa de ${item.fileName}", Modifier.size(72.dp)) }
                                Column(Modifier.weight(1f)) {
                                    Text(item.fileName, style = MaterialTheme.typography.titleSmall)
                                    Text("${item.sizeBytes / 1024} KB · ${item.mediaType}", style = MaterialTheme.typography.bodySmall, color = AuditColors.Muted)
                                }
                            }
                        }
                    }
                    Row { Checkbox(checked = agreed, onCheckedChange = { agreed = it }, enabled = !working && !finished)
                        Text("Acepto compartir estos ${selected.size} elementos con el destino indicado.", Modifier.weight(1f)) }
                    AuditButton(if (working) "Enviando…" else "Confirmar envío", {
                        val batch = selected
                        working = true
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) { sendBatch(batch) }
                                completed = true; finished = true
                                feedback = "Lote confirmado por el servidor."
                                clearTemporary()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                feedback = "No se confirmó el envío. Reintenta o cancela; los archivos temporales se eliminan al cerrar."
                            } finally { working = false }
                        }
                    }, agreed && !working && !finished)
                }
                AuditButton(if (finished) "Cerrar" else "Cancelar y cerrar", {
                    clearTemporary(); finish()
                }, !working, outlined = true)
                Text("Puedes retirar o pausar esta función en cualquier momento desde Detalles y ajustes.",
                    style = MaterialTheme.typography.bodySmall, color = AuditColors.Muted)
            }
        }
    }

    private fun loadSelection(uris: List<Uri>) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                clearTemporary()
                temporary.mkdirs()
                val picked = mutableListOf<Selected>()
                var error: String? = null
                uris.forEachIndexed { index, uri ->
                    if (error != null) return@forEachIndexed
                    try {
                        val name = displayName(uri)
                        val bytes = readLimited(uri)
                        val mediaType = contentResolver.getType(uri) ?: "image/jpeg"
                        if (bytes == null || bytes.isEmpty()) { error = "Un archivo supera el límite de 15 MB o no se pudo leer."; return@forEachIndexed }
                        if (mediaType !in GallerySharePolicy.mediaTypes) { error = "Solo se admiten imágenes JPEG, PNG, WebP o HEIC."; return@forEachIndexed }
                        val file = File(temporary, "g$index-${UUID.randomUUID().toString().take(8)}")
                        file.writeBytes(bytes)
                        val preview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 8 })
                        picked += Selected("g$index-${UUID.randomUUID().toString().take(8)}", file, name, mediaType,
                            bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, preview)
                    } catch (_: Exception) { error = "No se pudo preparar la selección." }
                }
                if (error != null) { clearTemporary(); emptyList<Selected>() to error }
                else {
                    val items = picked.map { GallerySharePolicy.Item(it.itemId, it.fileName, it.mediaType, it.sizeBytes, it.sha256) }
                    try { GallerySharePolicy.validate(items); picked to null }
                    catch (e: IllegalArgumentException) { clearTemporary(); emptyList<Selected>() to e.message }
                }
            }
            // Publish on the main thread through the composition state.
            selectionState.value = result
        }
    }

    private fun readLimited(uri: Uri): ByteArray? =
        contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                total += size
                if (total > GallerySharePolicy.MAX_BYTES_PER_ITEM) return null
                out.write(buffer, 0, size)
            }
            out.toByteArray()
        }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.let { return it.take(120) }
            }
        }
        return "foto-${UUID.randomUUID().toString().take(8)}.jpg"
    }

    private fun sendBatch(items: List<Selected>) {
        val runtime = AgentRuntime.get(this)
        val credential = runtime.credentials.load() ?: throw IllegalStateException("No hay credencial de dispositivo.")
        val modules = RoomAgentJournal(runtime.database).modules()
        check(modules.any { it.module == ConsentModulesPolicy.GALLERY && it.state == ModuleState.ACCEPTED }) {
            "El módulo de galería no está aceptado o fue pausado/revocado. Gestiónalo en Detalles y ajustes."
        }
        val api = ApiClient(runtime.target())
        val batchId = UUID.randomUUID().toString()
        api.createGalleryBatch(credential.deviceToken, credential.deviceId, batchId,
            items.map { GallerySharePolicy.Item(it.itemId, it.fileName, it.mediaType, it.sizeBytes, it.sha256) })
        items.forEach { item -> api.uploadGalleryItem(credential.deviceToken, credential.deviceId, batchId, item.itemId, item.mediaType, item.file.readBytes()) }
        api.completeGalleryBatch(credential.deviceToken, credential.deviceId, batchId)
    }

    private fun clearTemporary() {
        runCatching {
            temporary.listFiles()?.forEach { it.delete() }
            temporary.delete()
        }
    }

    override fun onDestroy() {
        if (isFinishing && !completed) clearTemporary()
        super.onDestroy()
    }
}
