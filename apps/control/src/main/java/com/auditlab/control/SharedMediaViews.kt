package com.auditlab.control

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.auditlab.design.*
import kotlinx.coroutines.CoroutineScope

/** Álbumes compartidos por el usuario desde el Agente: solo lo que él eligió y confirmó. */
@Composable
internal fun GalleryAlbumsView(session: ControlSession, device: String?, onBack: () -> Unit, workScope: CoroutineScope) {
    AuditHeader("ÁLBUMES", "FOTOS COMPARTIDAS POR EL USUARIO")
    AuditLink("← Volver al dispositivo", onBack, !session.busy)
    if (device == null) { AuditEmpty("SIN EQUIPO", "Selecciona un dispositivo para ver sus fotos."); return }
    LaunchedEffect(device) { session.loadGallery(device, workScope) }
    Text("Solo aparecen las fotos que el usuario eligió y confirmó en su teléfono.", style = MaterialTheme.typography.bodySmall, color = AuditColors.Muted)
    if (session.galleryBatches.isEmpty()) Text("Todavía no hay fotos compartidas desde este equipo.", color = AuditColors.Muted)
    session.galleryBatches.forEach { batch ->
        AuditPanel {
            Text("${batch.itemCount} foto(s) · ${batch.totalBytes / 1024} KB", style = MaterialTheme.typography.titleSmall)
            Text("Compartido: ${displayFriendlyTime(batch.createdAt)}", style = MaterialTheme.typography.bodySmall, color = AuditColors.Muted)
            AuditButton("Ver fotos", { session.openGalleryBatch(batch.batchId, workScope) }, !session.busy, outlined = true)
        }
    }
    session.galleryDetail?.let { detail ->
        SectionLabel("Fotos compartidas")
        Text("Compartido: ${displayFriendlyTime(detail.createdAt)}", style = MaterialTheme.typography.bodySmall, color = AuditColors.Muted)
        detail.items.forEach { item ->
            session.galleryImages[item.itemId]?.let { bitmap ->
                Image(bitmap.asImageBitmap(), item.fileName, Modifier.fillMaxWidth().heightIn(max = 320.dp))
            }
            Text("${item.fileName} · ${item.sizeBytes / 1024} KB", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Mapa de ubicación aproximada reutilizable: un punto por lectura, con su fecha. */
@Composable
internal fun DeviceLocationMap(points: List<Triple<Double, Double, String>>, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            org.osmdroid.config.Configuration.getInstance().apply {
                userAgentValue = context.packageName
                osmdroidBasePath = context.cacheDir
                osmdroidTileCache = context.cacheDir
            }
            org.osmdroid.views.MapView(context).apply { setMultiTouchControls(true); controller.setZoom(14.0) }
        },
        update = { map ->
            map.overlays.clear()
            points.forEach { (lat, lon, at) ->
                map.overlays.add(org.osmdroid.views.overlay.Marker(map).apply {
                    position = org.osmdroid.util.GeoPoint(lat, lon)
                    title = displayFriendlyTime(at)
                })
            }
            points.lastOrNull()?.let { map.controller.setCenter(org.osmdroid.util.GeoPoint(it.first, it.second)) }
        },
        modifier = modifier.fillMaxWidth().height(280.dp),
    )
}

/** Ubicación aproximada del equipo sobre un mapa. Cada punto conserva su fecha. */
@Composable
internal fun LocationMapView(session: ControlSession, device: String?, label: String, onBack: () -> Unit, workScope: CoroutineScope) {
    AuditHeader("UBICACIÓN APROXIMADA", label)
    AuditLink("← Volver al dispositivo", onBack, !session.busy)
    if (device == null) { AuditEmpty("SIN EQUIPO", "Selecciona un dispositivo para ver su ubicación."); return }
    val context = androidx.compose.ui.platform.LocalContext.current
    AuditButton("Ver pantalla compartida", {
        context.startActivity(android.content.Intent(context, ScreenViewerActivity::class.java)
            .putExtra("device", device).putExtra("deviceLabel", label))
    }, !session.busy, outlined = true)
    LaunchedEffect(device) { session.loadLocations(device, workScope) }
    Text("Coordenadas aproximadas (~1 km). Cada lectura conserva su fecha; no es la ubicación actual.",
        style = MaterialTheme.typography.bodySmall, color = AuditColors.Muted)
    val points = session.locationEvents.mapNotNull { event ->
        val lat = (event.metadata["locationLat"] as? Number)?.toDouble()
        val lon = (event.metadata["locationLon"] as? Number)?.toDouble()
        if (lat != null && lon != null) Triple(lat, lon, event.timestamp) else null
    }
    if (points.isEmpty()) {
        AuditPanel { Text("Todavía no hay lecturas de ubicación de este equipo. Activa «Ubicación aproximada» en el Agente.", color = AuditColors.Muted) }
    } else {
        DeviceLocationMap(points, Modifier.fillMaxWidth().height(320.dp))
    }
    points.takeLast(10).reversed().forEach { (lat, lon, at) ->
        Text("${displayFriendlyTime(at)} · ${"%.2f".format(lat)}, ${"%.2f".format(lon)}", style = MaterialTheme.typography.bodySmall)
    }
}
