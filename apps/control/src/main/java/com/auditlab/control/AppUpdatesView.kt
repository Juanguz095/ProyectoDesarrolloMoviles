package com.auditlab.control

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.auditlab.design.*
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun ControlAppUpdates(session: ControlSession, device: String?, scope: CoroutineScope) {
    val app=if(device==null) "control" else "agent"
    var confirm by remember(device) {mutableStateOf(false)}
    val release=session.appReleases.filter {it.app==app}.firstOrNull()
    AuditPanel {
        Text(if(device==null) "Actualizar Centro" else "Actualizar Agente",style=MaterialTheme.typography.titleMedium)
        if(device!=null) {
            Text(session.deviceName(device))
            Text("Versión registrada: ${session.devices.find {it.deviceId==device}?.appVersion ?: "Sin información"}")
            if(session.updateDevice==device) session.updateRequest?.let {Text(updateStatusLabel(it.status))}
        }
        AuditButton("Consultar versiones y solicitud",{session.loadAppUpdates(app,device,scope)},!session.busy,outlined=true)
        if(device!=null && release!=null && session.isAdmin) {
            Text("Disponible: ${release.versionName}. La instalación necesita confirmación en el otro teléfono.")
            val installed=session.devices.find {it.deviceId==device}?.appVersion==release.versionName
            if(installed) Text("Esta versión ya figura instalada en el servidor.")
            AuditButton("Solicitar actualización",{confirm=true},!session.busy && !installed && session.devices.find {it.deviceId==device}?.isActive==true)
        }
    }
    if(device==null) AppUpdateInstallerCard(release)
    if(confirm && device!=null && release!=null) androidx.compose.material3.AlertDialog(onDismissRequest={confirm=false},
        title={Text("Solicitar actualización")},text={Text("Se avisará a ${session.deviceName(device)} para instalar AuditLab ${release.versionName}. La solicitud no instala ni concede permisos.")},
        confirmButton={AuditLink("Enviar solicitud",{confirm=false;session.requestAppUpdate(device,release.releaseId,scope)})},
        dismissButton={AuditLink("Cancelar",{confirm=false})})
}
