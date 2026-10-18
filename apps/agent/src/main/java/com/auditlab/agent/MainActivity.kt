package com.auditlab.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("AuditLab · Agente", style = MaterialTheme.typography.headlineMedium)
                        Text("El monitoreo de este dispositivo requiere tu consentimiento explícito.")
                        Text("Estado: monitoreo desactivado", style = MaterialTheme.typography.titleMedium)
                        Text("AuditLab utiliza eventos propios de la aplicación y metadatos permitidos. Puedes revocar tu consentimiento.")
                    }
                }
            }
        }
    }
}
