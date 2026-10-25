package com.auditlab.control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = ControlSession(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ControlScreen(session)
                }
            }
        }
    }
}

@Composable
internal fun ControlScreen(session: ControlSession) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AuditLab · Centro de control", style = MaterialTheme.typography.headlineMedium)

        if (!session.signedIn) {
            Text("Inicia sesión con una cuenta de AuditLab. Los roles admin y auditor se aplican en el servidor.",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = session.serverUrl,
                onValueChange = session::updateServerUrl,
                label = { Text("URL del servicio") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Usuario") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Contraseña") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { session.login(user, password, scope) },
                enabled = !session.busy && user.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (session.busy) "Conectando…" else "Iniciar sesión") }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(session.username, style = MaterialTheme.typography.titleMedium)
                    Text("Rol: ${session.role}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { session.logout(scope) }, enabled = !session.busy) { Text("Salir") }
            }
            Button(
                onClick = { session.refresh(scope) },
                enabled = !session.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (session.busy) "Actualizando…" else "Actualizar dispositivos") }

            if (session.isAdmin) {
                HorizontalDivider()
                Text("Inscripción de dispositivos", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { session.createEnrollmentCode(scope) },
                    enabled = !session.busy,
                ) { Text("Crear código de un uso") }
                session.enrollmentCode?.let { code ->
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Código (visible solo ahora)", style = MaterialTheme.typography.labelMedium)
                            Text(code.code, style = MaterialTheme.typography.bodyMedium)
                            Text("Vence: ${code.expiresAt}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("Dispositivos (${session.devices.size})", style = MaterialTheme.typography.titleMedium)
            if (session.devices.isEmpty()) {
                Text("No hay dispositivos visibles para este rol.", style = MaterialTheme.typography.bodySmall)
            }
            session.devices.forEach { device ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${device.status.uppercase()} · ${device.appVersion ?: "versión desconocida"}",
                            style = MaterialTheme.typography.titleSmall)
                        Text(device.deviceId, style = MaterialTheme.typography.bodySmall)
                        Text("Inscrito: ${device.enrolledAt}", style = MaterialTheme.typography.bodySmall)
                        device.revokedAt?.let { Text("Revocado: $it", style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { session.selectDevice(device.deviceId, scope) },
                                enabled = !session.busy,
                            ) { Text("Ver eventos") }
                            if (session.isAdmin && device.isActive) {
                                TextButton(
                                    onClick = { session.revoke(device.deviceId, scope) },
                                    enabled = !session.busy,
                                ) { Text("Revocar") }
                            }
                        }
                    }
                }
            }

            if (session.selectedDevice != null) {
                HorizontalDivider()
                Text("Eventos del dispositivo seleccionado (${session.events.size})",
                    style = MaterialTheme.typography.titleMedium)
                Text("La consulta queda registrada en la auditoría del servidor.",
                    style = MaterialTheme.typography.bodySmall)
                if (session.events.isEmpty()) {
                    Text("Sin eventos en la página consultada.", style = MaterialTheme.typography.bodySmall)
                }
                session.events.forEach { event ->
                    Card {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${event.category} · ${event.direction}",
                                style = MaterialTheme.typography.titleSmall)
                            Text("Simulado: ${if (event.isSimulated) "sí" else "no"} · ${event.timestamp}",
                                style = MaterialTheme.typography.bodySmall)
                            Text(event.metadata.entries.joinToString { "${it.key}=${it.value}" },
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        session.message?.let {
            Card { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
