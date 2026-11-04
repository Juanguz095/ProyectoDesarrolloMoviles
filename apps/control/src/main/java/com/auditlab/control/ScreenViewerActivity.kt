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
import com.auditlab.common.ApiException
import com.auditlab.design.*
import kotlinx.coroutines.*

internal const val WAITING_SCREEN_FRAME="Sin imágenes recientes de este equipo."

class ScreenViewerActivity : ComponentActivity() {
    override fun onStop() { super.onStop(); if (!isChangingConfigurations) finish() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val device = intent.getStringExtra("device") ?: run { finish(); return }
        val label=intent.getStringExtra("deviceLabel") ?: "Equipo"
        setContent {
            AuditTheme {
                var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                var status by remember { mutableStateOf("Conectando con el servidor…") }
                LaunchedEffect(device) {
                    while (isActive) {
                        try {
                            val bytes = withContext(Dispatchers.IO) {
                                val session = KeystoreSessionStore(this@ScreenViewerActivity).load() ?: error("Inicia sesión de nuevo")
                                ScreenRelayClient(session.serverUrl ?: error("Falta servidor")).request(device, session.accessToken)
                            }
                            frame = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            status = when {
                                bytes==null -> WAITING_SCREEN_FRAME
                                frame==null -> "El servidor respondió, pero no se pudo abrir la imagen recibida."
                                else -> "Imagen recibida · actualización aproximada cada segundo"
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            frame = null
                            status = when {
                                error is ApiException && error.code=="INVALID_RESPONSE" -> "El servidor no devolvió una imagen válida. Comprueba la URL de AuditLab."
                                error is ApiException && error.status==401 -> "La sesión del Centro venció o fue rechazada. Vuelve a iniciar sesión."
                                error is ApiException && error.status==403 -> "Tu cuenta no tiene acceso a la pantalla de este equipo."
                                error is ApiException && error.status==404 -> "No se encontró este equipo o la ruta de pantalla. Revisa equipo y servidor."
                                error is ApiException && error.status==409 -> "El equipo está revocado. No se pueden consultar imágenes."
                                error is ApiException && error.code=="TLS_ERROR" -> "No se pudo verificar el certificado HTTPS."
                                error is ApiException && error.isNetwork -> "Sin conexión con el servidor. Comprueba Tailscale."
                                else -> "No se pudo consultar la pantalla. Revisa conexión y sesión."
                            }
                        }
                        delay(1000)
                    }
                }
                AuditPage(key = "screen-viewer") {
                    AuditHeader("PANTALLA EN VIVO", "SESIÓN AUTORIZADA DESDE EL AGENTE")
                    Text("$label · ${device.take(8)}",style=androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    Text(status)
                    frame?.let { Image(it.asImageBitmap(), "Pantalla compartida por el usuario", Modifier.fillMaxWidth().heightIn(max = 560.dp), contentScale = ContentScale.Fit) }
                    if(frame==null) Text("En el Agente revisa Pantalla y el último envío en Detalles. Comprueba que el código de equipo coincida.")
                    AuditDisclosure("Privacidad") {Text("La persona del dispositivo inicia y detiene la captura. Sin grabación de video ni audio.")}
                    AuditButton("Cerrar visor", { finish() })
                }
            }
        }
    }
}
