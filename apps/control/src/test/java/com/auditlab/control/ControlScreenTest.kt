package com.auditlab.control

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auditlab.common.*
import com.auditlab.design.AuditTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.*

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlScreenTest {
    @get:Rule val compose = createAndroidComposeRule<TestHostActivity>()
    private var current: ControlSession? = null
    private fun start(session: ControlSession) {
        current = session
        compose.setContent { AuditTheme { ControlScreen(session) } }
    }
    private fun tap(text: String) {
        val clickable = hasText(text) and hasClickAction()
        val scrollable = clickable and hasAnyAncestor(hasScrollAction())
        if (compose.onAllNodes(scrollable).fetchSemanticsNodes().isNotEmpty()) compose.onNode(scrollable).performScrollTo()
        compose.onNode(clickable).performClick()
        settle()
    }
    private fun settle() {
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            current?.busy == false
        }
        compose.waitForIdle()
    }
    private fun waitFor(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        settle()
    }
    private fun signIn(role: String = "admin", real: Boolean = false): FakeApi {
        val api = FakeApi(role).apply { realEvents = real }
        start(ControlSession(InMemorySessionStore(), { api }))
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("admin")
        compose.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextInput("secreto")
        tap("INICIAR SESIÓN")
        waitFor("Tu dispositivo de un vistazo")
        return api
    }
    private fun detail() { tap("EQUIPOS"); tap("VER DISPOSITIVO") }
    private fun events() { tap("VER HISTORIAL DE EVENTOS"); waitFor("RESULTADOS"); tap("FILTRAR EVENTOS") }
    private fun eventDetail() { compose.onNodeWithContentDescription("Ver detalle →").performScrollTo().performClick(); settle() }

    @Test fun `auditor can view ephemeral previews and local stop clears text and secure flag on exit`() {
        val api=signIn("auditor")
        detail();tap("VER NOTIFICACIONES DE WHATSAPP")
        waitFor("private preview fixture")
        assertTrue(compose.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
        api.previewSnapshot=WhatsAppPreviewSnapshot(false,null,emptyList())
        waitFor("SESIÓN DETENIDA")
        compose.onNodeWithText("private preview fixture").assertDoesNotExist()
        tap("← Volver al dispositivo")
        assertEquals(0,compose.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test fun `preview network failure hides previously received content`() {
        val api=signIn()
        detail();tap("VER NOTIFICACIONES DE WHATSAPP")
        waitFor("private preview fixture")
        api.previewError=true
        waitFor("Sin conexión con el servicio. Se ocultaron las vistas previas.")
        compose.onNodeWithText("private preview fixture").assertDoesNotExist()
        compose.onNodeWithText("EXPORTAR CSV").assertDoesNotExist()
    }

    @Test fun `el admin ve dispositivos y puede revocar con confirmacion`() {
        val api = signIn()
        compose.onNodeWithText("Equipo device-1").assertExists()
        detail(); tap("REVOCAR DISPOSITIVO")
        assertNull(api.revoked)
        tap("Confirmar revocación")
        waitFor("Dispositivo revocado. Los nuevos lotes serán rechazados.")
        assertEquals("device-1", api.revoked)
    }
    @Test fun `el auditor consulta pero no ve administracion`() {
        signIn("auditor")
        detail()
        compose.onNodeWithText("REVOCAR DISPOSITIVO").assertDoesNotExist()
        tap("MÁS")
        compose.onNodeWithText("CREAR CÓDIGO DE INSCRIPCIÓN").assertDoesNotExist()
        compose.onNodeWithText("Rol: auditor").assertExists()
    }
    @Test fun `SMS real muestra titulo y origen`() {
        val api = signIn(real = true)
        events()
        eventDetail()
        compose.onAllNodesWithText("SMS enviado")[0].assertExists()
        compose.onNodeWithText("Evento real del dispositivo").assertExists()
        compose.onNodeWithText("Simulado").assertDoesNotExist()
        assertEquals("device-1", api.eventsFor)
    }
    @Test fun `historial simulado no se confunde con estado real`() {
        signIn()
        // The mock has a simulated event with metadata; it must not populate battery telemetry.
        assertNull(current!!.overviewReading)
        events()
        eventDetail()
        compose.onAllNodesWithText("Actividad del agente")[0].assertExists()
        compose.onNodeWithText("Evento simulado").assertExists()
    }
    @Test fun `cancelar revocacion y cerrar sesion requieren confirmacion`() {
        val api = signIn()
        detail(); tap("REVOCAR DISPOSITIVO"); tap("Cancelar")
        assertNull(api.revoked)
        tap("MÁS"); tap("CERRAR SESIÓN"); tap("Cancelar")
        assertTrue(current!!.signedIn)
        tap("CERRAR SESIÓN"); tap("Confirmar salida")
        waitFor("INICIAR SESIÓN")
        val fields = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertEquals("", fields[1].config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        assertEquals("", fields[2].config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
    }
    @Test fun `pagina detalle y auditoria conservan endpoints`() {
        val api = signIn()
        events(); tap("SIGUIENTE PÁGINA")
        assertEquals(2, current!!.eventPageNumber)
        eventDetail()
        compose.onNodeWithText("DETALLE DE EVENTO").assertExists()
        assertEquals("event-2", api.detailFor)
        tap("Cerrar detalle")
        tap("MÁS"); tap("CONSULTAR AUDITORÍA")
        compose.onNodeWithText("SIGUIENTE PÁGINA DE AUDITORÍA").assertExists()
        assertEquals(1, api.auditCalls)
    }
    @Test fun `limpiar tambien elimina cambios aun no aplicados`() {
        signIn(); events()
        compose.onNode(hasSetTextAction() and hasContentDescription("ID o código exacto")).performScrollTo().performTextInput("sms_sent")
        tap("Limpiar filtros")
        assertEquals("", compose.onNode(hasSetTextAction() and hasContentDescription("ID o código exacto")).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        assertEquals(EventQuery(), current!!.query)
    }
    @Test fun `fechas invalidas no sustituyen la consulta aplicada`() {
        signIn(); events()
        val previous = current!!.query
        tap("Editar fechas y horas exactas")
        compose.onNode(hasSetTextAction() and hasContentDescription("Desde (incluida)")).performScrollTo().performTextInput("ayer")
        tap("APLICAR FILTROS")
        compose.onNodeWithText("Revisa las fechas UTC, su orden, la categoría y el ID o código exacto.").assertExists()
        assertEquals(previous, current!!.query)
    }
    @Test fun `credenciales rechazadas muestran error y no abren el centro`() {
        val api = FakeApi("admin").apply { loginError = true }
        start(ControlSession(InMemorySessionStore(), { api }))
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("admin")
        compose.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextInput("incorrecta")
        tap("INICIAR SESIÓN")
        waitFor("Credenciales rechazadas o sesión vencida.")
        assertFalse(current!!.signedIn)
        compose.onNodeWithText("Tu dispositivo de un vistazo").assertDoesNotExist()
    }
    @Test fun `exportar con filtros modificados no escribe documento`() {
        val api = signIn()
        val session = current!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val ticket = session.exportTicket()!!
            compose.runOnIdle { session.selectDevice("device-1", scope) }; settle()
            var written = false
            compose.runOnIdle { session.export(ticket, scope) { written = true } }; settle()
            assertFalse(written); assertEquals(0, api.exports)
            assertEquals("No se pudo completar la operación. Revisa los datos o el archivo y vuelve a intentarlo.", session.message)
        } finally { scope.cancel() }
    }
    @Test fun `fallo de red al exportar y fallo al guardar no confirman exito`() {
        val api = signIn()
        val session = current!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val ticket = session.exportTicket()!!; var writes = 0
            api.exportError = true
            compose.runOnIdle { session.export(ticket, scope) { writes++ } }; settle()
            assertEquals(0, writes)
            assertEquals("Sin conexión con el servicio. Revisa la URL y la red.", session.message)
            api.exportError = false
            compose.runOnIdle { session.export(ticket, scope) { writes++; throw java.io.IOException("fixture") } }; settle()
            assertEquals(1, writes)
            assertFalse(session.message?.contains("CSV guardado") == true)
        } finally { scope.cancel() }
    }
    private class FakeApi(private val role: String) : AuditApi {
        @Volatile var previewError=false
        @Volatile var previewSnapshot=WhatsAppPreviewSnapshot(true,"sharing-1",listOf(
            WhatsAppPreview("preview-1","2026-10-29T00:00:00Z","com.whatsapp","QA contact","private preview fixture")))
        override fun whatsAppPreviews(token: String,device: String):WhatsAppPreviewSnapshot {
            if(previewError) throw ApiException(0,"NETWORK_ERROR",null,"fixture")
            return previewSnapshot
        }
        var realEvents = false
        var lastQuery: com.auditlab.common.EventQuery? = null
        var loginError = false
        var exports = 0
        var exportError = false
        override fun exportEvents(token: String, query: com.auditlab.common.EventQuery): String {
            exports++
            if (exportError) throw com.auditlab.common.ApiException(0, "NETWORK_ERROR", null, "fixture")
            return "eventId,timestamp\n"
        }
        var detailFor: String? = null
        var auditCalls = 0
        override fun eventPage(token: String, query: com.auditlab.common.EventQuery, cursor: String?, limit: Int): EventPage {
            lastQuery = query
            val page = listEvents(token, query.device, limit).let { page ->
                page.copy(items = page.items.filter { query.category == null || it.category == query.category })
            }
            return if (cursor == null) page.copy(nextCursor = "opaque-next-page")
                else page.copy(items = page.items.map { it.copy(eventId = "event-2") })
        }
        override fun eventDetail(token: String, device: String, event: String): com.auditlab.common.AuditEvent {
            detailFor = event
            return listEvents(token, device, 50).items.single().copy(eventId = event)
        }
        override fun auditPage(token: String, cursor: String?): com.auditlab.common.AuditPage {
            auditCalls++
            return com.auditlab.common.AuditPage(emptyList(), "next-audit-page")
        }
        var revoked: String? = null
        var eventsFor: String? = null

        override fun login(username: String, password: String): Session {
            if (loginError) throw com.auditlab.common.ApiException(401, "UNAUTHENTICATED", null, "fixture")
            return Session(
            accessToken = "access-1",
            refreshToken = "refresh-1",
            tokenType = "Bearer",
            expiresIn = 900,
            sessionId = "session-1",
            userId = "user-1",
            username = username,
            role = role,
        )

        }

        override fun listDevices(accessToken: String, status: String?, limit: Int): DevicePage = DevicePage(
            items = listOf(
                DeviceSummary(
                    deviceId = "device-1",
                    status = "active",
                    lastSeenAt = null,
                    enrolledAt = "2026-10-24T09:59:00Z",
                    revokedAt = null,
                    consent = ConsentContext("c-1", "mvp-1", "2026-10-24T09:58:00Z"),
                    appVersion = "0.1.0-test",
                ),
            ),
            nextCursor = null,
        )

        override fun listEvents(accessToken: String, deviceId: String?, limit: Int): EventPage {
            eventsFor = deviceId
            return EventPage(
                items = listOf(
                    com.auditlab.common.AuditEvent(
                        eventId = "event-1",
                        timestamp = "2026-10-24T10:00:00Z",
                        category = if (realEvents) "sms" else "app_lifecycle",
                        direction = if (realEvents) "outbound" else "occurred",
                        sourceDevice = "device-1",
                        consent = ConsentContext("c-1", "mvp-1", "2026-10-24T09:58:00Z"),
                        isSimulated = !realEvents,
                        metadata = mapOf("appEventCode" to if (realEvents) "sms_sent" else "agent_started"),
                        receivedAt = "2026-10-24T10:00:01Z",
                    ),
                ),
                nextCursor = null,
            )
        }

        override fun revokeDevice(accessToken: String, deviceId: String) {
            revoked = deviceId
        }

        override fun createEnrollmentCode(accessToken: String): EnrollmentCode =
            EnrollmentCode("codigo-de-un-uso-para-la-prueba-1234", 600, "2026-10-24T10:10:00Z")

        override fun refresh(refreshToken: String): Session = throw UnsupportedOperationException()
        override fun logout(accessToken: String) = throw UnsupportedOperationException()
        override fun enroll(code: String, consent: ConsentContext, appVersion: String): EnrolledDevice =
            throw UnsupportedOperationException()
        override fun sendEvents(deviceToken: String, events: List<EventDraft>): IngestResult =
            throw UnsupportedOperationException()
        override fun revokeConsent(deviceToken: String, deviceId: String) = throw UnsupportedOperationException()
    }
}
