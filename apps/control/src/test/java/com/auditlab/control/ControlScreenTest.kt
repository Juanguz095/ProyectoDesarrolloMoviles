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
    fun `ver eventos del dispositivo consulta la API`() {
        val api = signIn()
        tap("Ver eventos")
        waitFor("Eventos del dispositivo seleccionado (1)")
        assertEquals("device-1", api.eventsFor)
        compose.onNodeWithText("app_lifecycle · occurred").assertExists()
        compose.onNodeWithText("Simulado: sí · 2026-10-24T10:00:00Z").assertExists()
    }

    private class FakeApi(private val role: String) : AuditApi {
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
                        category = "app_lifecycle",
                        direction = "occurred",
                        sourceDevice = "device-1",
                        consent = ConsentContext("c-1", "mvp-1", "2026-10-24T09:58:00Z"),
                        isSimulated = true,
                        metadata = mapOf("appEventCode" to "agent_started"),
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
