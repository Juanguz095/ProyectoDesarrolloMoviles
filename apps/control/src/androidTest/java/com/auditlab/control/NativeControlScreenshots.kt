package com.auditlab.control

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auditlab.common.*
import com.auditlab.design.AuditTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** Actual production Compose screens rendered on Android, with deterministic synthetic API data. */
@RunWith(AndroidJUnit4::class)
class NativeControlScreenshots {
    @get:Rule val compose=createEmptyComposeRule()
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var session: ControlSession
    private var generation=0
    private fun waitReady() {
        compose.waitUntil(60_000) { !session.busy && (!session.signedIn || session.devicesLoaded) }
        compose.waitForIdle()
    }
    private fun render(api: CaptureApi,signed: Boolean=true) {
        val store=InMemorySessionStore()
        if(signed) store.save(api.identity)
        session=ControlSession(store,{api})
        val serial=++generation
        scenario.onActivity { activity -> activity.setContent { AuditTheme { key(serial) {
            ControlScreen(session,onExport={},workScope=scope,onOpenReport={})
        } } } }
        waitReady()
    }
    private fun tap(label: String) {
        val matcher=hasText(label,ignoreCase=true) and hasClickAction()
        val scrollable=matcher and hasAnyAncestor(hasScrollAction())
        if(compose.onAllNodes(scrollable).fetchSemanticsNodes().isNotEmpty()) compose.onAllNodes(scrollable)[0].performScrollTo()
        compose.onAllNodes(matcher)[0].performClick();waitReady()
    }
    private fun capture(name: String) {
        val close=hasText("Cerrar") and hasClickAction()
        if(compose.onAllNodes(close).fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodes(close)[0].performClick();compose.waitForIdle()
        }
        compose.waitForIdle();android.os.SystemClock.sleep(650)
        scenario.onActivity { activity -> Assert.assertEquals("Do not bypass FLAG_SECURE",0,activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) }
        val bitmap=inst.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val out=File(inst.targetContext.filesDir,"qa-control-screens");out.mkdirs()
        File(out,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun captureProductionViews() {
        Assert.assertNull("Use a dedicated QA emulator without a user session",KeystoreSessionStore(inst.targetContext).load())
        scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            val api=CaptureApi()
            render(api,false);capture("01-login")
            render(api);capture("02-inicio")
            tap("EQUIPOS");capture("03-equipos")
            tap("Ver dispositivo");capture("04-detalle-superior")
            tap("Cambiar nombre");capture("05-cambiar-nombre");tap("Cancelar")
            tap("Archivar dispositivo");capture("06-confirmar-archivo");tap("Cancelar")
            tap("Revocar dispositivo");capture("07-confirmar-revocacion");tap("Cancelar")
            tap("Información técnica");capture("08-informacion-tecnica")
            inst.runOnMainSync {session.archiveDevice("capture-a",true)}
            tap("← Volver a dispositivos")
            tap("Sin archivar");tap("Archivados");capture("09-equipos-archivados")
            tap("Ver dispositivo");tap("Restaurar dispositivo");capture("10-confirmar-restauracion");tap("Cancelar")
            render(api);tap("EVENTOS");capture("11-eventos-todo")
            tap("SMS");capture("12-eventos-sms")
            tap("Filtrar eventos");capture("13-filtros")
            tap("Ocultar filtros")
            compose.onAllNodesWithContentDescription("Ver detalle →")[0].performScrollTo().performClick();waitReady()
            capture("14-detalle-sms");tap("Cerrar detalle")
            tap("Llamadas")
            compose.onAllNodesWithContentDescription("Ver detalle →")[0].performScrollTo().performClick();waitReady()
            capture("15-detalle-llamada");tap("Cerrar detalle")
            tap("MÁS");capture("16-ajustes-admin")
            tap("Consultar auditoría");capture("17-auditoria");tap("← Volver")
            tap("Crear código de inscripción");capture("18-inscripcion");tap("Crear código de un uso");capture("19-codigo-ejemplo");tap("← Volver")
            tap("Cerrar sesión");capture("20-confirmar-cierre");tap("Cancelar")
            render(CaptureApi(role="auditor"));tap("MÁS");capture("21-ajustes-auditor")
            render(CaptureApi(empty=true));capture("22-inicio-vacio")
            render(api)
            compose.onNode(hasText("Ver historial de eventos",ignoreCase=true) and hasClickAction()).performScrollTo()
            capture("23-inicio-inferior")
            tap("EQUIPOS");tap("Ver dispositivo")
            compose.onNode(hasText("Activar alertas para este teléfono",ignoreCase=true) and hasClickAction()).performScrollTo()
            capture("24-detalle-alertas")
            render(api);tap("EVENTOS");tap("SMS");tap("Filtrar eventos")
            compose.onNode(hasText("Aplicar filtros",ignoreCase=true) and hasClickAction()).performScrollTo()
            capture("25-campos-filtros")
            tap("Ocultar filtros")
            compose.onAllNodesWithContentDescription("Ver detalle →")[0].performScrollTo()
            capture("26-tarjeta-sms")
        } finally {scenario.close();scope.cancel()}
    }
}

private class CaptureApi(private val role: String="admin",private val empty: Boolean=false): AuditApi {
    private val now=Instant.now().toString()
    private val consent=ConsentContext("consentimiento-ficticio","mvp-1",now)
    val identity=Session("fixture-access","fixture-refresh","Bearer",900,"fixture-session","fixture-user","usuario@example.test",role)
    private val devices=listOf(DeviceSummary("capture-a","active",now,now,null,consent,"0.7.1-preview"),DeviceSummary("capture-b","active",null,now,null,consent,"0.7.1-preview"))
    private val events=listOf(
        AuditEvent("sms-demo",now,"sms","inbound","capture-a",consent,false,mapOf("appEventCode" to "sms_received","phoneNumber" to "+51 900 000 001","smsText" to "Mensaje ficticio para mostrar la interfaz."),now),
        AuditEvent("call-demo",now,"call","outbound","capture-a",consent,false,mapOf("appEventCode" to "call_outgoing","phoneNumber" to "+51 900 000 002"),now),
        AuditEvent("reading-demo",now,"device_status","occurred","capture-a",consent,false,mapOf("appEventCode" to "device_status","batteryPercent" to 72,"networkConnected" to true),now))
    override fun login(username: String,password: String)=identity
    override fun refresh(refreshToken: String)=identity
    override fun logout(accessToken: String) {}
    override fun listDevices(accessToken: String,status: String?,limit: Int)=DevicePage(if(empty) emptyList() else devices.filter {status==null || it.status==status},null)
    override fun listEvents(accessToken: String,deviceId: String?,limit: Int)=EventPage(events.filter {deviceId==null || it.sourceDevice==deviceId},null)
    override fun eventPage(token: String,query: EventQuery,cursor: String?,limit: Int)=EventPage(events.filter {
        (query.device==null || it.sourceDevice==query.device) && (query.category==null || query.category==it.category || query.category=="communication" && it.category in setOf("sms","call","whatsapp"))
    },null)
    override fun eventDetail(token: String,device: String,event: String)=events.single {it.eventId==event}
    override fun auditPage(token: String,cursor: String?)=AuditPage(emptyList(),null)
    override fun createEnrollmentCode(accessToken: String)=EnrollmentCode("CODIGO-FICTICIO-NO-VALIDO",600,Instant.now().plusSeconds(600).toString())
    override fun revokeDevice(accessToken: String,deviceId: String) {}
    override fun revokeConsent(deviceToken: String,deviceId: String) {}
    override fun enroll(code: String,consent: ConsentContext,appVersion: String): EnrolledDevice=error("Not used")
    override fun sendEvents(deviceToken: String,events: List<EventDraft>): IngestResult=error("Not used")
    override fun whatsAppPreviews(token: String,device: String)=WhatsAppPreviewSnapshot(false,null,emptyList())
}
