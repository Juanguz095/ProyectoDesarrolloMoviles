package com.auditlab.agent

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.auditlab.common.EventDraft
import com.auditlab.design.*
import kotlinx.coroutines.*

/** The draft is never saved in a Bundle, ViewModel, Room or preferences. */
class ManualTextShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val supplied = if (savedInstanceState == null && intent.action == Intent.ACTION_SEND && intent.type == "text/plain")
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty() else ""
        intent.removeExtra(Intent.EXTRA_TEXT)
        setContent {
            AuditTheme {
                var text by remember { mutableStateOf(supplied) }
                var draft by remember { mutableStateOf<EventDraft?>(null) }
                var destination by remember { mutableStateOf("") }
                var agreed by remember { mutableStateOf(false) }
                var busy by remember { mutableStateOf(false) }
                var sent by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("") }
                Column(Modifier.fillMaxSize().background(AuditColors.Paper).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Compartir un texto", style=MaterialTheme.typography.headlineSmall)
                    if(sent) {
                        Text(message)
                        AuditButton("Cerrar", { finish() })
                        return@Column
                    }
                    Text("Hasta 2 000 caracteres. Se guarda según la política del servidor y lo consultan/exportan administradores y auditores. Sin identificar la app de origen ni el remitente.")
                    OutlinedTextField(value=text, onValueChange={text=it; agreed=false}, readOnly=draft!=null || busy,
                        label={Text("Texto (${text.length}/2000)")}, modifier=Modifier.fillMaxWidth())
                    if(draft==null) {
                        AuditButton("Pegar", {
                            val clipboard=getSystemService(ClipboardManager::class.java)
                            text=clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                            agreed=false
                        }, !busy)
                        AuditButton("Revisar destino", {
                            busy=true
                            lifecycleScope.launch {
                                try {
                                    val prepared=withContext(Dispatchers.IO) {
                                        val runtime=AgentRuntime.get(this@ManualTextShareActivity)
                                        runtime.target() to runtime.core().prepareManualText(text)
                                    }
                                    destination=prepared.first; draft=prepared.second
                                } catch (_: Exception) { message="No se pudo preparar. Comprueba la inscripción y el límite de texto." }
                                finally {busy=false}
                            }
                        }, !busy && text.isNotBlank() && text.length<=2000)
                    } else {
                        Text("Servidor: $destination")
                        Text("Dispositivo: ${draft!!.sourceDevice}")
                        Text("No reactiva el monitoreo ni envía pendientes. Sin confirmación, reintenta aquí con el mismo texto e identificador.")
                        Row { Checkbox(checked=agreed,onCheckedChange={agreed=it},enabled=!busy && !sent); Text("Acepto conservar y enviar este texto al destino indicado.") }
                        AuditButton(if(busy) "Enviando…" else "Confirmar envío / reintentar", {
                            val event=draft!!
                            busy=true
                            lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val runtime=AgentRuntime.get(this@ManualTextShareActivity)
                                        check(runtime.target()==destination)
                                        runtime.core().sendManualText(event)
                                    }
                                    sent=true; text=""; draft=null; message="Texto confirmado por el servidor."
                                } catch (_: Exception) { message="Sin confirmación: reintenta o cierra y descarta el borrador; el servidor pudo conservarlo." }
                                finally {busy=false}
                            }
                        }, agreed && !busy && !sent)
                    }
                    if(message.isNotEmpty()) Text(message)
                    AuditButton(if(sent) "Cerrar" else "Cancelar y cerrar", {text="";draft=null;finish()}, !busy, outlined=true)
                }
            }
        }
    }
}
