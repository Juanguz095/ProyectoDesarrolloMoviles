package com.auditlab.agent

import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WhatsAppNotificationParserTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val started=System.currentTimeMillis()-1000
    private fun posted(n:Notification,pkg:String="com.whatsapp",post:Long=started+500)=StatusBarNotification(pkg,pkg,1,null,1000,0,0,n,UserHandle.getUserHandleForUid(1000),post)
    private fun message()=Notification.Builder(context,"qa").setSmallIcon(android.R.drawable.ic_dialog_info)
        .setCategory(Notification.CATEGORY_MESSAGE).setWhen(started+100).setContentTitle("QA sender").setContentText("private QA preview")
    @Test fun `only WhatsApp message previews posted after local authorization are accepted`() {
        val n=message().build()
        assertEquals(1,WhatsAppNotificationParser.parse(posted(n),"s",started).size)
        assertTrue(WhatsAppNotificationParser.parse(posted(n,"other.app"),"s",started).isEmpty())
        assertTrue(WhatsAppNotificationParser.parse(posted(n,post=started-1),"s",started).isEmpty())
        assertTrue(WhatsAppNotificationParser.parse(posted(message().setCategory(Notification.CATEGORY_CALL).build()),"s",started).isEmpty())
        assertTrue(WhatsAppNotificationParser.parse(posted(message().setGroup("g").setGroupSummary(true).build()),"s",started).isEmpty())
    }
    @Test fun `MessagingStyle skips old entries and local replies and deduplicates updates`() {
        val me=Person.Builder().setName("Me").setKey("self").build()
        val other=Person.Builder().setName("QA sender").setKey("other").build()
        val style=Notification.MessagingStyle(me).addMessage("old",started-1,other)
            .addMessage("incoming",started+50,other).addMessage("local reply",started+80,me)
        val n=message().setStyle(style).build()
        val rows=WhatsAppNotificationParser.parse(posted(n),"s",started)
        assertEquals(1,rows.size);assertEquals("incoming",rows[0].text)
        assertEquals(rows,WhatsAppNotificationParser.parse(posted(n,post=started+900),"s",started))
        assertFalse(rows[0].toString().contains("incoming"))
    }
    @Test fun `blank or hidden previews are not invented and content length is bounded`() {
        assertTrue(WhatsAppNotificationParser.parse(posted(message().setContentText("").build()),"s",started).isEmpty())
        val rows=WhatsAppNotificationParser.parse(posted(message().setContentText("x".repeat(5000)).build()),"s",started)
        assertEquals(2000,rows.single().text.length)
    }
}
