package com.auditlab.agent

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auditlab.common.ApiException
import com.auditlab.common.AuditApi
import com.auditlab.common.ConsentContext
import com.auditlab.common.DevicePage
import com.auditlab.common.EnrolledDevice
import com.auditlab.common.EnrollmentCode
import com.auditlab.common.EventDraft
import com.auditlab.common.EventPage
import com.auditlab.common.InMemoryCredentialStore
import com.auditlab.common.IngestResult
import com.auditlab.common.Session
import com.auditlab.common.StoredCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ejecuta la pantalla real del agente en la JVM con Robolectric: composición, estado y pulsaciones.
 * La API se sustituye por un doble, así que se prueba la UI y la lógica de sesión, no la red ni
 * Android Keystore (eso requiere un dispositivo o emulador).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<TestHostActivity>()

    private val consent = ConsentContext("c-1", "mvp-1", "2026-10-24T10:00:00Z")

    private fun start(session: AgentSession) {
        compose.setContent {
            MaterialTheme {
                Surface { AgentScreen(session) }
            }
        }
    }

    /** Desplaza hasta el control y lo pulsa: el contenido está en una columna con scroll. */
    private fun tap(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `sin aceptar el consentimiento el registro esta deshabilitado`() {
        start(AgentSession(InMemoryCredentialStore(), { FakeApi() }))

        compose.onNodeWithText("Monitoreo visible: DESACTIVADO").assertExists()
        compose.onNodeWithText("Registrar consentimiento").assertIsNotEnabled()
        compose.onNodeWithText("Paso 2 · Inscripción").assertDoesNotExist()

        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Registrar consentimiento").assertIsEnabled()
        tap("Registrar consentimiento")
        compose.onNodeWithText("Paso 2 · Inscripción").assertExists()
        compose.onNodeWithText("Monitoreo visible: DESACTIVADO").assertExists()
    }

    @Test
    fun `inscribir y enviar un evento simulado desde la pantalla`() {
        val api = FakeApi()
        start(AgentSession(InMemoryCredentialStore(), { api }))

        compose.onNode(isToggleable()).performScrollTo().performClick()
        tap("Registrar consentimiento")
        // 0 = URL del servicio, 1 = código de inscripción.
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("x".repeat(40))
        tap("Inscribir dispositivo")

        waitFor("Dispositivo inscrito")
        compose.onNodeWithText("Monitoreo visible: ACTIVO").assertExists()
        compose.onNodeWithText("Identificador: device-1").assertExists()
        compose.onNodeWithText("Política aceptada: mvp-1").assertExists()

        tap("Generar evento simulado")
        waitFor("Evento enviado: 1 aceptado(s), 0 duplicado(s).")
        assertEquals(1, api.sentEvents)
        assertEquals("device-token", api.sentToken)
    }

    @Test
    fun `una revocacion remota detiene el monitoreo y permite volver a empezar`() {
        val api = FakeApi().apply { sendError = ApiException(401, "DEVICE_REVOKED", "corr-1", "revocado") }
        val store = InMemoryCredentialStore(StoredCredential("device-1", "device-token", consent))
        start(AgentSession(store, { api }))

        compose.onNodeWithText("Monitoreo visible: ACTIVO").assertExists()
        tap("Generar evento simulado")

        waitFor("Monitoreo detenido")
        compose.onNodeWithText("Monitoreo visible: DETENIDO").assertExists()
        compose.onNodeWithText("El centro de control revocó este dispositivo. No se generarán más eventos.").assertExists()
        compose.onNodeWithText("Volver a aceptar consentimiento").assertExists()
        assertNull(store.load())
    }

    @Test
    fun `la revocacion local avisa y borra la credencial`() {
        val api = FakeApi()
        val store = InMemoryCredentialStore(StoredCredential("device-1", "device-token", consent))
        start(AgentSession(store, { api }))

        tap("Revocar consentimiento")
        waitFor("Monitoreo detenido")
        compose.onNodeWithText("Monitoreo visible: DETENIDO").assertExists()
        compose.onNodeWithText("Revocaste el consentimiento en este dispositivo. No se generarán más eventos.").assertExists()
        assertEquals(1, api.revokedConsents)
        assertNull(store.load())
        // Tras revocar, la pantalla ya no ofrece enviar eventos.
        compose.onNodeWithText("Generar evento simulado").assertDoesNotExist()
    }

    @Test fun `un fallo de red al inscribir no anuncia un evento pendiente inexistente`() {
        val api = FakeApi().apply { enrollError = ApiException(0, "NETWORK_ERROR", null, "fixture offline") }
        val store = InMemoryCredentialStore()
        start(AgentSession(store, { api }))
        compose.onNode(isToggleable()).performScrollTo().performClick()
        tap("Registrar consentimiento")
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("x".repeat(40))
        tap("Inscribir dispositivo")
        waitFor("No se pudo confirmar la inscripción. Comprueba el centro y solicita otro código si el anterior fue consumido.")
        assertNull(store.load())
        compose.onNodeWithText("Generar evento simulado").assertDoesNotExist()
        assertEquals(0, api.sentEvents)
    }

    private class FakeApi : AuditApi {
        var enrollError: ApiException? = null
        var sendError: ApiException? = null
        var sentEvents = 0
        var sentToken: String? = null
        var revokedConsents = 0

        override fun enroll(code: String, consent: ConsentContext, appVersion: String): EnrolledDevice {
            enrollError?.let { throw it }
            return EnrolledDevice("device-1", "device-token", consent, "2026-10-24T10:00:01Z", "active")
        }

        override fun sendEvents(deviceToken: String, events: List<EventDraft>): IngestResult {
            sendError?.let { throw it }
            sentEvents += events.size
            sentToken = deviceToken
            return IngestResult(events.size, 0)
        }

        override fun revokeConsent(deviceToken: String, deviceId: String) {
            revokedConsents++
        }

        override fun login(username: String, password: String): Session = throw UnsupportedOperationException()
        override fun refresh(refreshToken: String): Session = throw UnsupportedOperationException()
        override fun logout(accessToken: String) = throw UnsupportedOperationException()
        override fun createEnrollmentCode(accessToken: String): EnrollmentCode = throw UnsupportedOperationException()
        override fun listDevices(accessToken: String, status: String?, limit: Int): DevicePage =
            throw UnsupportedOperationException()
        override fun listEvents(accessToken: String, deviceId: String?, limit: Int): EventPage =
            throw UnsupportedOperationException()
        override fun revokeDevice(accessToken: String, deviceId: String) = throw UnsupportedOperationException()
    }
}
