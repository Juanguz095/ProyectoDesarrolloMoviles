package com.auditlab.agent

import android.app.Notification
import androidx.core.app.NotificationCompat
import android.service.notification.StatusBarNotification
import com.auditlab.common.*
import java.time.Instant
import java.util.UUID

/** The event contains only type/time. Text is confined to the separate ephemeral preview. */
data class WhatsAppObservation(val key: String,val timestamp: String,val direction: String,
                               val code: String,val preview: WhatsAppPreview?)

object WhatsAppNotificationParser {
    fun parse(sbn: StatusBarNotification,sessionId: String,startedAt: Long,now: Long=System.currentTimeMillis()): List<WhatsAppPreview> =
        observe(sbn,sessionId,startedAt,now).mapNotNull { it.preview }

    /** Only observations exposed by Android after this locally authorized session began. */
    fun observe(sbn: StatusBarNotification,sessionId: String,startedAt: Long,now: Long=System.currentTimeMillis()): List<WhatsAppObservation> {
        if(sbn.packageName !in WhatsAppPreviewPolicy.PACKAGES || sbn.postTime<startedAt) return emptyList()
        val n=sbn.notification
        if(n.flags and Notification.FLAG_GROUP_SUMMARY!=0) return emptyList()
        val style=NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val replyable=n.actions?.any { action -> action.remoteInputs?.any { it.allowFreeFormInput }==true }==true
        if(style==null && n.category!=Notification.CATEGORY_MESSAGE && !(n.category==null && replyable)) return emptyList()
        fun observation(time: Long,title: String,text: CharSequence?,local: Boolean): WhatsAppObservation? {
            if(time<startedAt || time>now+300000 || text.isNullOrBlank()) return null
            val timestamp=Instant.ofEpochMilli(time).toString()
            val direction=if(local) "outbound" else "inbound"
            val key=UUID.nameUUIDFromBytes("${sbn.key}|$time|$direction".toByteArray(Charsets.UTF_8)).toString()
            val preview=if(local) null else {
                val t=title.take(WhatsAppPreviewPolicy.MAX_TITLE)
                val body=text.toString().take(WhatsAppPreviewPolicy.MAX_TEXT)
                val id=UUID.nameUUIDFromBytes("$sessionId|$key|$t|$body".toByteArray(Charsets.UTF_8)).toString()
                WhatsAppPreview(id,timestamp,sbn.packageName,t,body)
            }
            return WhatsAppObservation(key,timestamp,direction,
                if(local) "whatsapp_reply_observed" else "whatsapp_notification_received",preview)
        }
        if(style!=null && style.messages.isNotEmpty()) return style.messages.mapNotNull { message ->
            val sender=message.person
            // A name match alone is ambiguous: a contact may have the same name
            // as the owner. Only a null sender or explicit matching keys identify self.
            val explicitKeys=sender?.key!=null && style.user.key!=null
            val local=sender==null || explicitKeys && sender?.key==style.user.key
            if(sender!=null && !explicitKeys && sender.name?.toString()==style.user.name?.toString()) return@mapNotNull null
            val conversation=style.conversationTitle?.toString().orEmpty()
            val title=if(conversation.isNotBlank()) "$conversation · ${sender?.name?.toString().orEmpty()}" else sender?.name?.toString().orEmpty()
            observation(message.timestamp,title,message.text,local)
        }
        val time=n.`when`.takeIf { it>0 } ?: sbn.postTime
        val text=n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)
        return listOfNotNull(observation(time,n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),text,false))
    }
}
