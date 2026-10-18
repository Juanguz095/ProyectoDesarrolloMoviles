package com.auditlab.control

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
                        Text("AuditLab · Centro de control", style = MaterialTheme.typography.headlineMedium)
                        Text("Consulta los dispositivos y eventos autorizados de tu grupo.")
                        Text("Estado: sesión no iniciada", style = MaterialTheme.typography.titleMedium)
                        Text("El acceso requiere una cuenta de administrador o auditor y queda registrado.")
                    }
                }
            }
        }
    }
}
