package com.auditlab.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Stable category identity, independent of delivery state or selected filter. */
data class AuditTagPalette(val label: String,val ink: Color,val tint: Color)
object AuditCategoryColors {
    val all=AuditTagPalette("Todo",Color(0xFF334155),Color(0xFFE2E8F0))
    val whatsApp=AuditTagPalette("WhatsApp",Color(0xFF166534),Color(0xFFDCFCE7))
    val sms=AuditTagPalette("SMS",Color(0xFF1D4ED8),Color(0xFFDBEAFE))
    val calls=AuditTagPalette("Llamadas",Color(0xFF6D28D9),Color(0xFFEDE9FE))
    val shared=AuditTagPalette("Compartidos",Color(0xFF92400E),Color(0xFFFEF3C7))
    val device=AuditTagPalette("Batería y conexión",Color(0xFF115E59),Color(0xFFCCFBF1))
    fun category(value: String): AuditTagPalette=when(value) {
        "whatsapp"->whatsApp;"sms"->sms;"call"->calls;"manual_share"->shared
        "device_status"->device;"sync"->sms.copy(label="Sincronización")
        "app_lifecycle"->all.copy(label="Actividad de la app")
        "communication"->all
        else->all.copy(label="Evento")
    }
}

@Composable
fun AuditCategoryFilter(category: String,label: String,selected: Boolean,enabled: Boolean,onClick: ()->Unit) {
    val palette=AuditCategoryColors.category(category)
    FilterChip(selected=selected,onClick=onClick,enabled=enabled,modifier=Modifier.heightIn(min=48.dp),
        shape=RoundedCornerShape(50),
        border=BorderStroke(if(selected) 1.5.dp else 1.dp,palette.ink.copy(alpha=if(selected) 1f else .28f)),
        leadingIcon=if(selected) ({Text("✓",modifier=Modifier.clearAndSetSemantics {},fontWeight=FontWeight.Bold)}) else null,
        label={Text(label,fontWeight=if(selected) FontWeight.Bold else FontWeight.Medium)},
        colors=FilterChipDefaults.filterChipColors(containerColor=palette.tint,labelColor=palette.ink,
            selectedContainerColor=palette.ink,selectedLabelColor=Color.White,selectedLeadingIconColor=Color.White))
}

@Composable
fun AuditCategoryTag(category: String) {
    val palette=AuditCategoryColors.category(category)
    AuditPill(palette.label,palette.ink,palette.tint)
}

@Composable
fun AuditStateTag(text: String,active: Boolean=false,attention: Boolean=false,failed: Boolean=false) {
    val palette=when {
        failed->AuditTagPalette(text,Color(0xFF991B1B),Color(0xFFFEE2E2))
        attention->AuditCategoryColors.shared
        active->AuditCategoryColors.whatsApp
        else->AuditCategoryColors.all
    }
    AuditPill(text,palette.ink,palette.tint)
}

@Composable
private fun AuditPill(text: String,ink: Color,tint: Color) {
    Surface(color=tint,contentColor=ink,shape=RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal=10.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).background(ink,CircleShape))
            Text(text,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold)
        }
    }
}

@Composable
fun AuditFunctionStatus(label: String,status: String,active: Boolean=false,attention: Boolean=false) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
        AuditStateTag(status,active,attention)
    }
}
