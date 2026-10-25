package com.auditlab.agent

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.auditlab.common.AgentCore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = AgentSession(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentScreen(session)
                }
            }
        }
    }
}

@Composable
internal fun AgentScreen(session: AgentSession) {
    val scope = rememberCoroutineScope()
    var accepted by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("AuditLab · Agente", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Solo se envían eventos propios y simulados de AuditLab y metadatos permitidos. " +
                "No se leen SMS, llamadas, chats ni contenido de otras aplicaciones.",
            style = MaterialTheme.typography.bodyMedium,
        )

        // Indicador de monitoreo siempre visible.
        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (session.state) {
                        is AgentCore.State.Enrolled -> "Monitoreo visible: ACTIVO"
                        is AgentCore.State.Revoked -> "Monitoreo visible: DETENIDO"
                        AgentCore.State.ConsentPending -> "Monitoreo visible: DESACTIVADO"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("Puedes revocar el consentimiento desde esta pantalla en cualquier momento.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        OutlinedTextField(
            value = session.serverUrl,
            onValueChange = session::updateServerUrl,
            label = { Text("URL del servicio") },
            singleLine = true,
            enabled = session.state is AgentCore.State.ConsentPending,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("En el emulador, 10.0.2.2 apunta al equipo anfitrión. Fuera de loopback solo se admite HTTPS.",
            style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()

        when (val state = session.state) {
            AgentCore.State.ConsentPending -> {
                Text("Paso 1 · Consentimiento", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Al aceptar permites que AuditLab reciba eventos simulados generados por esta app, " +
                        "su identificador de dispositivo y el contexto de consentimiento. La retención " +
                        "de los eventos está limitada por la política del servicio.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                    Text("He leído la información y acepto el monitoreo.")
                }
                Button(
                    onClick = { session.acceptConsent() },
                    enabled = accepted && !session.busy,
                ) { Text("Registrar consentimiento") }

                if (session.consent != null) {
                    Text("Paso 2 · Inscripción", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Código de inscripción") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { session.enroll(code, scope) },
                        enabled = code.length >= 32 && !session.busy,
                    ) { Text(if (session.busy) "Inscribiendo…" else "Inscribir dispositivo") }
                }
            }

            is AgentCore.State.Enrolled -> {
                Text("Dispositivo inscrito", style = MaterialTheme.typography.titleMedium)
                Text("Identificador: ${state.deviceId}", style = MaterialTheme.typography.bodySmall)
                Text("Política aceptada: ${state.consent.policyVersion}", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { session.sendSimulatedEvent(scope) },
                    enabled = !session.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (session.busy) "Enviando…" else "Generar evento simulado") }
                OutlinedButton(
                    onClick = { session.revoke(scope) },
                    enabled = !session.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Revocar consentimiento") }
            }

            is AgentCore.State.Revoked -> {
                Text("Monitoreo detenido", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (state.reason) {
                        AgentCore.RevokeReason.LOCAL ->
                            "Revocaste el consentimiento en este dispositivo. No se generarán más eventos."
                        AgentCore.RevokeReason.REMOTE ->
                            "El centro de control revocó este dispositivo. No se generarán más eventos."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Para volver a participar, el centro debe emitir un código nuevo y debes aceptar de nuevo.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { session.restartConsent() },
                    enabled = !session.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Volver a aceptar consentimiento") }
            }
        }

        session.message?.let {
            Card {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (session.log.isNotEmpty()) {
            HorizontalDivider()
            Text("Actividad reciente", style = MaterialTheme.typography.titleSmall)
            session.log.forEach { entry ->
                Text(entry, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
