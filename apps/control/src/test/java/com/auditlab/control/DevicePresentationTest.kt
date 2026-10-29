package com.auditlab.control

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.auditlab.common.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DevicePresentationTest {
    private fun session(prefs: android.content.SharedPreferences,url: String): ControlSession {
        val store=InMemorySessionStore()
        store.save(Session("access","refresh","Bearer",900,"session","user","admin","admin",url))
        return ControlSession(store,{ error("No network expected") },true,prefs)
    }
    @Test fun namesAndArchiveSurviveRecreationAndRemainIsolatedByServer() {
        val prefs=ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("device-test",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val a=session(prefs,"https://one.example")
        assertTrue(a.renameDevice("device-a","Teléfono anterior"))
        assertTrue(a.archiveDevice("device-a",true))
        val restored=session(prefs,"https://one.example/")
        assertEquals("Teléfono anterior",restored.deviceName("device-a"))
        assertTrue(restored.isArchived("device-a"))
        assertFalse(restored.isArchived("device-b"))
        val other=session(prefs,"https://two.example")
        assertFalse(other.isArchived("device-a"))
        assertNotEquals("Teléfono anterior",other.deviceName("device-a"))
        assertTrue(restored.archiveDevice("device-a",false))
        assertFalse(session(prefs,"https://one.example").isArchived("device-a"))
        assertFalse(restored.renameDevice("device-a","\n"))
        assertFalse(restored.renameDevice("device-a","x".repeat(61)))
        assertEquals("Teléfono anterior",restored.deviceName("device-a"))
    }
    @Test fun contactAgeIsIndependentFromEnrollmentStatus() {
        val now=Instant.parse("2026-10-29T12:00:00Z")
        val d=DeviceSummary("a","active",now.minusSeconds(120).toString(),now.toString(),null,ConsentContext("c","v","2026-10-29T10:00:00Z"),null)
        assertEquals("Contacto reciente · Inscripción activa",deviceContactStatus(d,now))
        assertEquals("Sin contacto reciente · Inscripción activa",deviceContactStatus(d.copy(lastSeenAt=now.minusSeconds(121).toString()),now))
        assertEquals("Sin contacto registrado",deviceContactStatus(d.copy(lastSeenAt=null),now))
        assertEquals("Revocado",deviceContactStatus(d.copy(status="revoked"),now))
        assertEquals("Sin contacto reciente · Inscripción activa",deviceContactStatus(d.copy(lastSeenAt=now.plusSeconds(1).toString()),now))
    }
}
