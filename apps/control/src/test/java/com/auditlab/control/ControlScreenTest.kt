package com.auditlab.control

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auditlab.common.AuditApi
import com.auditlab.common.ConsentContext
import com.auditlab.common.DevicePage
import com.auditlab.common.DeviceSummary
import com.auditlab.common.EnrolledDevice
import com.auditlab.common.EnrollmentCode
import com.auditlab.common.EventDraft
import com.auditlab.common.EventPage
import com.auditlab.common.InMemorySessionStore
import com.auditlab.common.IngestResult
import com.auditlab.common.Session
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ejecuta la pantalla real del centro de control en la JVM con Robolectric y una API doble.
 * Comprueba el login, el listado y que la UI respeta el rol antes de ofrecer acciones de admin.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<TestHostActivity>()

    private fun start(session: ControlSession) {
        compose.setContent {
            MaterialTheme {
                Surface { ControlScreen(session) }
            }
        }
    }

    private fun tap(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun signIn(role: String = "admin"): FakeApi {
        val api = FakeApi(role)
        start(ControlSession(InMemorySessionStore(), { api }))
        // 0 = URL, 1 = usuario, 2 = contraseña.
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("admin")
        compose.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextInput("secreto")
        tap("Iniciar sesión")
        waitFor("Rol: $role")
        return api
    }

    @Test
    fun `el admin ve dispositivos y puede revocar`() {
        val api = signIn()
        compose.onNodeWithText("Dispositivos (1)").assertExists()
        compose.onNodeWithText("ACTIVE · 0.1.0-test").assertExists()
        compose.onNodeWithText("Crear código de un uso").assertExists()

        tap("Revocar")
        assertEquals(null, api.revoked)
        compose.onNodeWithText("Confirmar revocación").performClick()
        waitFor("Dispositivo revocado. Los nuevos lotes serán rechazados.")
        assertEquals("device-1", api.revoked)
    }

    @Test
    fun `el auditor no ve acciones de administracion`() {
        signIn("auditor")
        compose.onNodeWithText("Crear código de un uso").assertDoesNotExist()
        compose.onNodeWithText("Revocar").assertDoesNotExist()
        // Aun así puede consultar los dispositivos visibles para su rol.
        compose.onNodeWithText("Dispositivos (1)").assertExists()
    }

    @Test
    fun `un SMS real muestra nombre legible y marca Real`() {
        val api = signIn()
        api.realEvents = true
        tap("Ver eventos")
        waitFor("Eventos del dispositivo seleccionado (1)")
        compose.onNodeWithText("SMS enviado").assertExists()
        compose.onNodeWithText("Real · 2026-10-24T10:00:00Z").assertExists()
        compose.onNodeWithText("Simulado · 2026-10-24T10:00:00Z").assertDoesNotExist()
    }

    @Test
    fun `ver eventos del dispositivo consulta la API`() {
        val api = signIn()
        tap("Ver eventos")
        waitFor("Eventos del dispositivo seleccionado (1)")
        assertEquals("device-1", api.eventsFor)
        compose.onNodeWithText("app_lifecycle · occurred").assertExists()
        compose.onNodeWithText("Simulado · 2026-10-24T10:00:00Z").assertExists()
    }

    @Test fun `cancelar revocacion no llama a la API y cerrar sesion pide confirmacion`() {
        val api = signIn()
        tap("Revocar"); compose.onNodeWithText("Cancelar").performClick()
        assertEquals(null, api.revoked)
        tap("Salir"); compose.onNodeWithText("Cancelar").performClick()
        compose.onNodeWithText("Rol: admin").assertExists()
        tap("Salir"); compose.onNodeWithText("Confirmar salida").performClick()
        waitFor("Iniciar sesión")
        val fields = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertEquals("", fields[1].config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        assertEquals("", fields[2].config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
    }
    @Test fun `historial pagina detalle y auditoria usan los endpoints correspondientes`() {
        val api = signIn()
        tap("Ver eventos"); waitFor("Más eventos")
        tap("Más eventos"); waitFor("Detalle event-2")
        tap("Detalle event-2"); compose.onNodeWithText("Detalle de evento").assertExists()
        compose.onNodeWithText("Cerrar detalle").performClick()
        assertEquals("event-2", api.detailFor)
        tap("Consultar auditoría"); waitFor("Más auditoría")
        assertEquals(1, api.auditCalls)
    }
    @Test fun `exportar con filtros modificados no abre ni escribe el documento`() {
        val api = FakeApi("admin")
        val session = ControlSession(InMemorySessionStore(), { api })
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        try {
            start(session)
            compose.runOnIdle { session.login("admin", "secret", scope) }
            waitFor("Rol: admin")
            val ticket = session.exportTicket()!!
            compose.runOnIdle { session.selectDevice("device-1", scope) }
            waitFor("Historial actualizado: 1 evento(s).")
            var written = false
            compose.runOnIdle { session.export(ticket, scope) { written = true } }
            waitFor("No se pudo completar la operación. Revisa los datos o el archivo y vuelve a intentarlo.")
            compose.runOnIdle { assertEquals(false, session.busy) }
            assertEquals(false, written); assertEquals(0, api.exports)
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
    @Test fun `fallo de red al exportar no toca el documento y fallo al guardar no confirma exito`() {
        val api = FakeApi("admin")
        val session = ControlSession(InMemorySessionStore(), { api })
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        try {
            start(session); compose.runOnIdle { session.login("admin", "secret", scope) }; waitFor("Rol: admin")
            val ticket = session.exportTicket()!!; var writes = 0
            api.exportError = true
            compose.runOnIdle { session.export(ticket, scope) { writes++ } }
            waitFor("Sin conexión con el servicio. Revisa la URL y la red.")
            compose.runOnIdle { assertEquals(false, session.busy) }; assertEquals(0, writes)
            api.exportError = false
            compose.runOnIdle { session.export(ticket, scope) { writes++; throw java.io.IOException("fixture") } }
            waitFor("No se pudo completar la operación. Revisa los datos o el archivo y vuelve a intentarlo.")
            compose.runOnIdle { assertEquals(false, session.busy) }; assertEquals(1, writes)
            assertEquals(false, session.message?.contains("CSV guardado"))
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
    private class FakeApi(private val role: String) : AuditApi {
        var realEvents = false
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
            val page = listEvents(token, query.device, limit)
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

        override fun login(username: String, password: String): Session = Session(
            accessToken = "access-1",
            refreshToken = "refresh-1",
            tokenType = "Bearer",
            expiresIn = 900,
            sessionId = "session-1",
            userId = "user-1",
            username = username,
            role = role,
        )

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
