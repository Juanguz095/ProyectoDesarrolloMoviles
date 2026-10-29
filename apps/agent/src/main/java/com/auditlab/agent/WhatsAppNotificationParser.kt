package com.auditlab.agent

import android.app.Notification
import androidx.core.app.NotificationCompat
import android.service.notification.StatusBarNotification
import com.auditlab.common.*
import java.time.Instant
import java.util.UUID

/** Read only new message previews supplied by Android, never notification history or attachments. */
object WhatsAppNotificationParser {
    fun parse(sbn: StatusBarNotification, sessionId: String, startedAt: Long, now: Long = System.currentTimeMillis()): List<WhatsAppPreview> {
        if (sbn.packageName !in WhatsAppPreviewPolicy.PACKAGES || sbn.postTime<startedAt) return emptyList()
        val n=sbn.notification
        if(n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return emptyList()
        val style=NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if(style==null && n.category!=Notification.CATEGORY_MESSAGE) return emptyList()
        fun preview(time: Long,title: String,text: CharSequence?): WhatsAppPreview? {
            if(time<startedAt || time>now+300000 || text.isNullOrBlank()) return null
            val t=title.take(WhatsAppPreviewPolicy.MAX_TITLE)
            val body=text.toString().take(WhatsAppPreviewPolicy.MAX_TEXT)
            val id=UUID.nameUUIDFromBytes("$sessionId|${sbn.key}|$time|$t|$body".toByteArray(Charsets.UTF_8)).toString()
            return WhatsAppPreview(id,Instant.ofEpochMilli(time).toString(),sbn.packageName,t,body)
        }
        if(style!=null) return style.messages.mapNotNull { message ->
            val sender=message.person?.name
            if(sender.isNullOrBlank()) return@mapNotNull null // null sender denotes the local user's outgoing reply.
            // Platform notifications on older Android can lose Person keys when
            // restored by AndroidX. In that case conservatively exclude the local name.
            val senderKey=message.person?.key
            if(if(senderKey!=null && style.user.key!=null) senderKey==style.user.key else sender.toString()==style.user.name.toString()) return@mapNotNull null
            val conversation=style.conversationTitle?.toString().orEmpty()
            val title=if(conversation.isNotBlank()) "$conversation · $sender" else sender.toString()
            preview(message.timestamp,title,message.text)
        }
        val time=n.`when`.takeIf { it>0 } ?: sbn.postTime
        val text=n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)
        return listOfNotNull(preview(time,n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),text))
    }
}
