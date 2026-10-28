package com.auditlab.agent

import com.auditlab.design.AuditTheme
import androidx.compose.ui.test.*
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
            AuditTheme {
                Surface { AgentScreen(session) }
            }
        }
    }

    /** Desplaza hasta el control y lo pulsa: el contenido está en una columna con scroll. */
    private fun tap(text: String) {
        val clickable = hasText(text) and hasClickAction()
        val scrollable = clickable and hasAnyAncestor(hasScrollAction())
        if (compose.onAllNodes(scrollable).fetchSemanticsNodes().isNotEmpty()) compose.onNode(scrollable).performScrollTo()
        compose.onNode(clickable).performClick()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `sin aceptar el consentimiento no hay captura ni inscripcion`() {
        val api = FakeApi()
        val store = InMemoryCredentialStore()
        val session = AgentSession(store, { api })
        start(session)
        compose.onNodeWithText("ACEPTAR CONSENTIMIENTO").assertIsEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("MONITOREO").assertDoesNotExist()
        assertNull(session.consent); assertNull(store.load()); assertEquals(0, api.sentEvents)
        tap("ACEPTAR CONSENTIMIENTO")
        compose.onNode(hasSetTextAction() and hasContentDescription("Código de inscripción")).assertExists()
        compose.onNodeWithText("CONSENTIMIENTO REGISTRADO").assertExists()
        assertEquals(0, api.sentEvents)
    }

    @Test
    fun `inscribir y enviar un evento simulado desde la pantalla`() {
        val api = FakeApi()
        start(AgentSession(InMemoryCredentialStore(), { api }))
        tap("ACEPTAR CONSENTIMIENTO")
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("x".repeat(40))
        tap("INSCRIBIR DISPOSITIVO")
        waitFor("MONITOREO")
        compose.onNodeWithText("LISTO PARA ACTIVAR").assertExists()
        compose.onNodeWithText("ACTIVO").assertDoesNotExist()
        tap("GENERAR EVENTO SIMULADO")
        waitFor("Evento enviado: 1 aceptado(s), 0 duplicado(s).")
        assertEquals(1, api.sentEvents); assertEquals("device-token", api.sentToken)
    }

    @Test
    fun `una revocacion remota detiene el monitoreo y permite volver a empezar`() {
        val api = FakeApi().apply { sendError = ApiException(401, "DEVICE_REVOKED", "corr-1", "revocado") }
        val store = InMemoryCredentialStore(StoredCredential("device-1", "device-token", consent))
        start(AgentSession(store, { api }))
        tap("GENERAR EVENTO SIMULADO")
        waitFor("DISPOSITIVO REVOCADO")
        compose.onNodeWithText("La captura local se detuvo.").assertExists()
        compose.onNodeWithText("GENERAR EVENTO SIMULADO").assertDoesNotExist()
        assertNull(store.load())
        tap("Volver a aceptar consentimiento")
        waitFor("ACEPTAR CONSENTIMIENTO")
    }

    @Test
    fun `retirar pide confirmacion y cancelarlo conserva la credencial`() {
        val api = FakeApi()
        val store = InMemoryCredentialStore(StoredCredential("device-1", "device-token", consent))
        start(AgentSession(store, { api }))
        tap("RETIRAR CONSENTIMIENTO")
        compose.onNodeWithText("Cancelar").performClick()
        assertEquals(0, api.revokedConsents)
        org.junit.Assert.assertNotNull(store.load())
        tap("RETIRAR CONSENTIMIENTO")
        compose.onNodeWithText("Confirmar retirada").performClick()
        waitFor("CONSENTIMIENTO RETIRADO")
        assertEquals(1, api.revokedConsents); assertNull(store.load())
        compose.onNodeWithText("GENERAR EVENTO SIMULADO").assertDoesNotExist()
    }

    @Test fun `un fallo de red al inscribir no anuncia un evento pendiente inexistente`() {
        val api = FakeApi().apply { enrollError = ApiException(0, "NETWORK_ERROR", null, "fixture offline") }
        val store = InMemoryCredentialStore()
        start(AgentSession(store, { api }))
        tap("ACEPTAR CONSENTIMIENTO")
        compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("x".repeat(40))
        tap("INSCRIBIR DISPOSITIVO")
        waitFor("No se pudo confirmar la inscripción. Comprueba el centro y solicita otro código si el anterior fue consumido.")
        assertNull(store.load()); assertEquals(0, api.sentEvents)
        compose.onNodeWithText("GENERAR EVENTO SIMULADO").assertDoesNotExist()
    }

    @Test fun `cambiar servidor valida la direccion y exige aceptar de nuevo`() {
        val session = AgentSession(InMemoryCredentialStore(), { FakeApi() })
        start(session)
        tap("ACEPTAR CONSENTIMIENTO")
        compose.runOnIdle { session.updateServerUrl("https://auditlab.example") }
        waitFor("ACEPTAR CONSENTIMIENTO")
        assertEquals("https://auditlab.example", session.serverUrl); assertNull(session.consent)
    }

    @Test fun `politica y estado vacio no presentan lecturas inventadas`() {
        start(AgentSession(InMemoryCredentialStore(), { FakeApi() }))
        tap("VER POLÍTICA DE DATOS")
        compose.onNodeWithText("POLÍTICA DE DATOS").assertExists()
        tap("VOLVER")
        compose.onNodeWithText("ACEPTAR CONSENTIMIENTO").assertExists()
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
