package com.auditlab.control

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.auditlab.common.ScreenRelayClient
import com.auditlab.design.*
import kotlinx.coroutines.*

class ScreenViewerActivity : ComponentActivity() {
    override fun onStop() { super.onStop(); if (!isChangingConfigurations) finish() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val device = intent.getStringExtra("device") ?: run { finish(); return }
        setContent {
            AuditTheme {
                var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                var status by remember { mutableStateOf("Esperando una sesión iniciada en el Agente…") }
                LaunchedEffect(device) {
                    while (isActive) {
                        try {
                            val bytes = withContext(Dispatchers.IO) {
                                val session = KeystoreSessionStore(this@ScreenViewerActivity).load() ?: error("Inicia sesión de nuevo")
                                ScreenRelayClient(session.serverUrl ?: error("Falta servidor")).request(device, session.accessToken)
                            }
                            frame = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            status = if (frame == null) "No hay una sesión de pantalla activa." else "Pantalla compartida · actualización aproximada cada segundo"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            frame = null; status = "No se pudo consultar la pantalla. Comprueba conexión y sesión e inténtalo de nuevo."
                        }
                        delay(1000)
                    }
                }
                AuditPage(key = "screen-viewer") {
                    AuditHeader("PANTALLA EN VIVO", "SESIÓN AUTORIZADA DESDE EL AGENTE")
                    Text(status)
                    frame?.let { Image(it.asImageBitmap(), "Pantalla compartida por el usuario", Modifier.fillMaxWidth().heightIn(max = 560.dp), contentScale = ContentScale.Fit) }
                    Text("La persona del dispositivo controla cuándo empieza y termina la sesión. No se graba video ni audio.")
                    AuditButton("Cerrar visor", { finish() })
                }
            }
        }
    }
}
