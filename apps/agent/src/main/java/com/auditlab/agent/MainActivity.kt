package com.auditlab.agent

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.auditlab.common.AgentCore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val session = AgentSession(application)
    init { session.initialize(scope) }
    override fun onCleared() { scope.cancel() }
}

class MainActivity : ComponentActivity() {
    private lateinit var model: AgentViewModel
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.session.resume(model.scope) else model.session.refresh(model.scope)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[AgentViewModel::class.java]
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentScreen(model.session, workScope = model.scope, onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else model.session.resume(model.scope)
                    }, onNotificationSettings = {
                        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    })
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.session.refresh(model.scope)
    }
}

@Composable
internal fun AgentScreen(session: AgentSession, onRequestNotifications: (() -> Unit)? = null,
                         onNotificationSettings: (() -> Unit)? = null, workScope: CoroutineScope? = null) {
    // Keep IO/publication in the ViewModel scope across recreation, like the control app.
    // A composition's frame clock must not be inherited by background synchronization.
    val composeScope = rememberCoroutineScope()
    val scope = workScope ?: composeScope
    var accepted by rememberSaveable(session.consentEpoch) { mutableStateOf(false) }
    var code by androidx.compose.runtime.remember(session.consentEpoch) { mutableStateOf("") }

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
                        is AgentCore.State.Enrolled -> if (session.visibleAvailable && !session.revoking) "Monitoreo visible: ACTIVO" else "Monitoreo visible: PAUSADO"
                        is AgentCore.State.Revoked -> "Monitoreo visible: DETENIDO"
                        is AgentCore.State.Blocked -> "Monitoreo visible: DETENIDO"
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
            enabled = session.state is AgentCore.State.ConsentPending && !session.busy,
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
                        "local es de 7 días y hasta 10000 registros. También se incluyen batería y estado " +
                        "de conexión del propio dispositivo. El servidor aplica la retención informada por tu grupo.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it }, enabled = !session.busy)
                    Text("He leído la información y acepto el monitoreo.")
                }
                Button(
                    onClick = { session.acceptConsent(scope) },
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
                Text("Pendientes: ${session.stats.pending} · Enviados: ${session.stats.sent} · Rechazados: ${session.stats.failed}")
                Button(
                    onClick = { session.sendSimulatedEvent(scope) },
                    enabled = !session.busy && session.visibleAvailable && !session.revoking,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (session.busy) "Enviando…" else "Generar evento simulado") }
                if (!session.visibleAvailable) {
                    Text("La simulación y el envío están pausados hasta habilitar la notificación visible.")
                    OutlinedButton(onClick = { onRequestNotifications?.invoke() ?: session.resume(scope) }, enabled = !session.busy) {
                        Text("Habilitar indicador y reanudar")
                    }
                    onNotificationSettings?.let { settings -> TextButton(onClick = settings) { Text("Ajustes de notificación") } }
                } else {
                    OutlinedButton(onClick = { session.pause(scope) }, enabled = !session.busy) { Text("Pausar monitoreo") }
                }
                OutlinedButton(
                    onClick = { session.revoke(scope) },
                    enabled = !session.revoking,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (session.revoking) "Retirando consentimiento…" else "Revocar consentimiento") }
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
                if (session.revocationPending) {
                    Text("Retirada local guardada. Falta confirmar el aviso o la limpieza.")
                    OutlinedButton(onClick = { session.retryPrivacy(scope) }, enabled = !session.busy) { Text("Reintentar retirada pendiente") }
                }
                OutlinedButton(
                    onClick = { session.restartConsent(scope) },
                    enabled = !session.busy && !session.revocationPending,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Volver a aceptar consentimiento") }
            }
            is AgentCore.State.Blocked -> {
                Text("Transmisiones bloqueadas", style = MaterialTheme.typography.titleMedium)
                Text(state.message)
                OutlinedButton(onClick = { session.revoke(scope) }, enabled = !session.revoking) {
                    Text("Retirar consentimiento y limpiar")
                }
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
