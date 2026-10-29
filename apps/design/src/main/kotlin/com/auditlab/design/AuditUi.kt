package com.auditlab.design

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Shared Android source, compiled by both apps without a new module or dependency. */
object AuditColors {
    val Paper = Color(0xFFDAD7C0)
    val Panel = Color(0xFFF0EDDB)
    val Ink = Color(0xFF10110C)
    val Muted = Color(0xFF66675A)
    val Warning = Color(0xFF806000)
    val Red = Color(0xFF9A3027)
    val Green = Color(0xFF287336)
}

private fun mono(size: Int, line: Int, bold: Boolean = false) = TextStyle(
    fontFamily = FontFamily.Monospace, fontSize = size.sp, lineHeight = line.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
)

@Composable
fun AuditTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            @Suppress("DEPRECATION")
            window.statusBarColor = 0xFFDAD7C0.toInt()
            @Suppress("DEPRECATION")
            window.navigationBarColor = 0xFFDAD7C0.toInt()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        }
    }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = AuditColors.Ink, onPrimary = AuditColors.Panel,
            secondary = AuditColors.Ink, onSecondary = AuditColors.Panel,
            background = AuditColors.Paper, onBackground = AuditColors.Ink,
            surface = AuditColors.Panel, onSurface = AuditColors.Ink,
            surfaceVariant = AuditColors.Paper, onSurfaceVariant = AuditColors.Muted,
            outline = AuditColors.Muted, error = AuditColors.Red,
        ),
        typography = Typography(
            headlineLarge = mono(26, 32, true), headlineMedium = mono(23, 29, true),
            titleLarge = mono(19, 25, true), titleMedium = mono(16, 22, true),
            titleSmall = mono(14, 20, true), bodyLarge = mono(14, 21),
            bodyMedium = mono(12, 18), bodySmall = mono(11, 17),
            labelLarge = mono(11, 16, true), labelMedium = mono(10, 15), labelSmall = mono(10, 15),
        ),
        shapes = Shapes(RoundedCornerShape(0.dp), RoundedCornerShape(0.dp), RoundedCornerShape(0.dp), RoundedCornerShape(0.dp), RoundedCornerShape(0.dp)),
        content = content,
    )
}

@Composable
fun AuditPage(key: Any? = Unit, feedback: String? = null, working: Boolean = false,
    bottom: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = AuditColors.Paper, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            key(key) {
                Column(
                    Modifier.weight(1f).widthIn(max = 620.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp), content = content,
                )
            }
            if (working) LinearProgressIndicator(Modifier.widthIn(max = 620.dp).fillMaxWidth(), color = AuditColors.Ink, trackColor = AuditColors.Panel)
            feedback?.let { AuditFeedback(it) }
            bottom?.let { Box(Modifier.widthIn(max = 620.dp).fillMaxWidth()) { it() } }
        }
    }
}

@Composable
private fun AuditFeedback(message: String) {
    var dismissed by remember(message) { mutableStateOf(false) }
    var expanded by remember(message) { mutableStateOf(false) }
    if (!dismissed) {
        Row(Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .background(AuditColors.Panel).border(0.7.dp, AuditColors.Ink).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { expanded = true }.padding(vertical = 8.dp)) {
                SectionLabel("Aviso · toca para ver completo")
                Text(message, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            TextButton({ dismissed = true }, contentPadding = PaddingValues(8.dp)) { Text("Cerrar", style = MaterialTheme.typography.labelMedium) }
        }
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text("AVISO") },
        text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ expanded = false }) { Text("Entendido") } })
}

@Composable
fun AuditHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
fun SectionLabel(text: String) = Text(text.uppercase(), color = AuditColors.Muted, style = MaterialTheme.typography.labelMedium)

@Composable
fun AuditRule() = HorizontalDivider(color = AuditColors.Ink, thickness = 0.7.dp)

@Composable
fun AuditPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(AuditColors.Panel).border(0.7.dp, AuditColors.Ink)
        .padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
fun AuditButton(text: String, onClick: () -> Unit, enabled: Boolean = true, outlined: Boolean = false,
    modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled, shape = RectangleShape,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        border = if (outlined) BorderStroke(0.7.dp, AuditColors.Ink) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (outlined) AuditColors.Panel else AuditColors.Ink,
            contentColor = if (outlined) AuditColors.Ink else AuditColors.Panel,
            disabledContainerColor = AuditColors.Ink.copy(alpha = 0.12f),
            disabledContentColor = AuditColors.Ink.copy(alpha = 0.55f),
        ), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
        Box(Modifier.fillMaxWidth()) { Text(text.uppercase(), style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
fun AuditLink(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    TextButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AuditField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true,
    secret: Boolean = false, placeholder: String = "", keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(label)
        OutlinedTextField(value, onChange, enabled = enabled, singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }, shape = RectangleShape,
            textStyle = MaterialTheme.typography.bodyMedium,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
            keyboardOptions = if (secret) keyboardOptions.copy(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else keyboardOptions,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AuditColors.Panel, unfocusedContainerColor = AuditColors.Panel,
                focusedBorderColor = AuditColors.Ink, unfocusedBorderColor = AuditColors.Muted,
            ))
    }
}

@Composable
fun AuditStatus(text: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(if (active) AuditColors.Green else AuditColors.Warning, CircleShape))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun BatteryMeter(percent: Int?) {
    Box(Modifier.width(190.dp).height(12.dp).border(0.7.dp, AuditColors.Ink).padding(2.dp)) {
        if (percent != null && percent > 0) Box(Modifier.fillMaxHeight().fillMaxWidth(percent.coerceIn(0, 100) / 100f).background(AuditColors.Ink))
    }
    Text(percent?.let { "$it %" } ?: "Sin datos recibidos", style = MaterialTheme.typography.titleSmall)
}

@Composable
fun AuditNotice(message: String) {
    AuditPanel { SectionLabel("Aviso"); Text(message) }
}

@Composable
fun AuditEmpty(title: String = "SIN DATOS RECIBIDOS", message: String = "Los eventos aparecerán cuando el servidor los entregue para esta consulta.") {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(message, color = AuditColors.Muted)
}

fun displayTime(value: String?): String = value?.let {
    runCatching { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC).format(Instant.parse(it)) }
        .getOrDefault(it)
} ?: "Sin datos recibidos"

fun displayClock(value: String?): String = value?.let {
    runCatching { DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC).format(Instant.parse(it)) }
        .getOrDefault(it)
} ?: "Sin datos recibidos"

fun networkLabel(value: Any?): String = when (value) {
    "online" -> "Conexión disponible"
    "offline" -> "Sin conexión"
    else -> "Sin datos recibidos"
}

fun metadataTitle(metadata: Map<String, Any?>, fallback: String = "Evento de la aplicación"): String = when (metadata["appEventCode"]) {
    "sms_received" -> "SMS recibido"
    "sms_sent" -> "SMS enviado"
    "call_received" -> "Llamada recibida"
    "call_outgoing" -> "Llamada saliente"
    "call_missed" -> "Llamada perdida"
    "call_rejected" -> "Llamada rechazada o bloqueada"
    "device_status_changed" -> "Estado del dispositivo"
    "agent_started" -> "Actividad del agente"
    "sync_succeeded" -> "Sincronización completada"
    "sync_failed" -> "Error de sincronización"
    "consent_revoked" -> "Consentimiento retirado"
    else -> fallback
}

fun metadataDescription(metadata: Map<String, Any?>): String = listOfNotNull(
    (metadata["batteryPercent"] as? Number)?.let { "Batería: ${it.toInt()} %" },
    metadata["networkState"]?.let { networkLabel(it) },
).joinToString(" · ")
