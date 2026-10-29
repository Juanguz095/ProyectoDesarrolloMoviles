package com.auditlab.control

import com.auditlab.common.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WhatsAppAlertPolicyTest {
    private val start=Instant.parse("2026-10-29T12:00:00Z")
    private fun preview(id: String,time: Instant)=WhatsAppPreview(id,time.toString(),"com.whatsapp","Contacto ficticio","Mensaje de prueba")
    @Test fun localSessionRemovalAlsoInvalidatesTheSharedCore() {
        val store=InMemorySessionStore()
        store.save(Session("access","refresh","Bearer",900,"sid","uid","admin","admin","https://example.test"))
        val core=ControlCore(ApiClient("https://example.test"),store,"https://example.test")
        assertTrue(core.isSignedIn)
        core.discardLocalSession()
        assertNull(store.load())
        assertFalse(core.isSignedIn)
        assertThrows(IllegalStateException::class.java) { core.whatsAppPreviews("device") }
    }
    @Test fun newIncomingPreviewNotifiesOnceAndExistingHistoryIsIgnored() {
        val p=WhatsAppAlertPolicy(start)
        val old=preview("old",start.minusSeconds(1));val new=preview("new",start.plusSeconds(2))
        val s=WhatsAppPreviewSnapshot(true,"session",listOf(old,new))
        assertEquals(listOf(new),p.fresh(s,start.plusSeconds(3)))
        assertTrue(p.fresh(s,start.plusSeconds(5)).isEmpty())
    }
    @Test fun stoppedExpiredMalformedAndFarFuturePreviewsNeverNotify() {
        val p=WhatsAppAlertPolicy(start)
        val new=preview("new",start.plusSeconds(1))
        assertTrue(p.fresh(WhatsAppPreviewSnapshot(false,null,listOf(new)),start.plusSeconds(2)).isEmpty())
        val invalid=listOf(new.copy(timestamp="invalid"),preview("future",start.plusSeconds(1000)))
        assertTrue(p.fresh(WhatsAppPreviewSnapshot(true,"s",invalid),start).isEmpty())
        assertTrue(p.fresh(WhatsAppPreviewSnapshot(true,"s",listOf(new)),start.plusSeconds(301)).isEmpty())
    }
}
