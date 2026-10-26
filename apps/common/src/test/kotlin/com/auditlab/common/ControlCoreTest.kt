package com.auditlab.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlCoreTest {
    private val api = FakeApi()
    private val store = InMemorySessionStore()
    private val core = ControlCore(api, store)

    @Test
    fun `el login guarda la sesion y expone el rol`() {
        val session = core.login("admin", "secreto")
        assertEquals("admin", session.role)
        assertTrue(core.isSignedIn)
        assertTrue(core.isAdmin)
        assertEquals(session, store.load())
    }

    @Test
    fun `un auditor no crea codigos ni revoca sin llamar a la API`() {
        api.session = api.session.copy(role = "auditor")
        core.login("auditor", "secreto")
        assertFalse(core.isAdmin)
        assertThrows(IllegalStateException::class.java) { core.createEnrollmentCode() }
        assertThrows(IllegalStateException::class.java) { core.revoke("d-1") }
        assertEquals(listOf("login"), api.calls)
    }

    @Test
    fun `un 401 renueva la sesion una sola vez y reintenta`() {
        core.login("admin", "secreto")
        api.unauthenticatedOnce = true
        val page = core.devices()
        assertEquals(1, page.items.size)
        assertEquals(listOf("login", "devices", "refresh", "devices"), api.calls)
        assertEquals("access-2", store.load()?.accessToken)
    }

    @Test
    fun `si el refresh falla se cierra la sesion`() {
        core.login("admin", "secreto")
        api.unauthenticatedOnce = true
        api.refreshError = ApiException(401, "UNAUTHENTICATED", null, "sesión vencida")
        assertThrows(ApiException::class.java) { core.devices() }
        assertNull(core.session)
        assertNull(store.load())
        assertFalse(core.isSignedIn)
    }

    @Test
    fun `revocar envia el identificador y el token de usuario`() {
        core.login("admin", "secreto")
        core.revoke("d-7")
        assertEquals("d-7", api.revokedDevice)
        assertEquals("access-1", api.revokedToken)
    }

    @Test
    fun `crear codigo devuelve el codigo de un uso`() {
        core.login("admin", "secreto")
        val code = core.createEnrollmentCode()
        assertEquals("codigo-de-un-uso", code.code)
        assertEquals(600, code.expiresIn)
    }

    @Test
    fun `logout borra la sesion local aunque la API falle`() {
        core.login("admin", "secreto")
        api.logoutError = ApiException(0, "NETWORK_ERROR", null, "sin red")
        core.logout()
        assertNull(core.session)
        assertNull(store.load())
    }

    @Test fun `network error during refresh preserves the session for a later retry`() {
        core.login("admin", "secret"); api.unauthenticatedOnce = true
        api.refreshError = ApiException(0, "NETWORK_ERROR", null, "offline")
        assertThrows(ApiException::class.java) { core.devices() }; assertTrue(core.isSignedIn)
    }
    @Test fun `refresh changing the role cannot retry an admin action`() {
        core.login("admin", "secret"); api.session = api.session.copy(role = "auditor")
        api.codeExpired = true
        assertThrows(IllegalStateException::class.java) { core.createEnrollmentCode() }
        assertEquals(1, api.calls.count { it == "code" }); assertFalse(core.isAdmin)
    }
    @Test fun `session cannot be restored against a different origin or legacy unspecified origin`() {
        store.save(api.session.copy(serverUrl = "https://one.invalid"))
        assertThrows(StorageFailure::class.java) { ControlCore(api, store, "https://two.invalid") }
        store.save(api.session)
        assertThrows(StorageFailure::class.java) { ControlCore(api, store, "https://one.invalid") }
        assertTrue(api.calls.isEmpty())
    }
    @Test fun `refresh returning a different user is never sent on the retried request`() {
        core.login("admin", "secret"); api.unauthenticatedOnce = true
        api.session = api.session.copy(userId = "other")
        assertThrows(StorageFailure::class.java) { core.devices() }
        assertNull(core.session); assertNull(store.load()); assertEquals(1, api.calls.count { it == "devices" })
    }
    @Test fun `trailing slash and surrounding whitespace do not change the protected origin`() {
        store.save(api.session.copy(serverUrl = "https://one.invalid"))
        assertTrue(ControlCore(api, store, " https://one.invalid/ ").isSignedIn)
        assertTrue(api.calls.isEmpty())
    }
    @Test fun `logout refreshes an expired access token without restoring the local session`() {
        core.login("admin", "secret"); api.logoutExpired = true
        assertEquals(ControlCore.LogoutOutcome.NOTIFIED, core.logout())
        assertEquals(listOf("login", "logout", "refresh", "logout"), api.calls)
        assertNull(store.load()); assertNull(core.session)
    }
    @Test fun `logout reports missing remote confirmation when offline`() {
        core.login("admin", "secret"); api.logoutError = ApiException(0, "NETWORK_ERROR", null, "offline")
        assertEquals(ControlCore.LogoutOutcome.LOCAL_ONLY, core.logout()); assertNull(store.load())
    }
    private class FakeApi : AuditApi {
        val calls = mutableListOf<String>()
        var session = Session("access-1", "refresh-1", "Bearer", 900, "s-1", "u-1", "admin", "admin")
        var codeExpired = false
        var unauthenticatedOnce = false
        var refreshError: ApiException? = null
        var logoutExpired = false
        var logoutError: ApiException? = null
        var revokedDevice: String? = null
        var revokedToken: String? = null

        override fun login(username: String, password: String): Session {
            calls += "login"
            return session
        }

        override fun refresh(refreshToken: String): Session {
            calls += "refresh"
            refreshError?.let { throw it }
            return session.copy(accessToken = "access-2", refreshToken = "refresh-2")
        }

        override fun logout(accessToken: String) {
            calls += "logout"
            if (logoutExpired) { logoutExpired = false; throw ApiException(401, "UNAUTHENTICATED", null, "expired") }
            logoutError?.let { throw it }
        }

        override fun createEnrollmentCode(accessToken: String): EnrollmentCode {
            calls += "code"
            if (codeExpired) { codeExpired = false; throw ApiException(401, "UNAUTHENTICATED", null, "expired") }
            return EnrollmentCode("codigo-de-un-uso", 600, "2026-10-24T10:10:00Z")
        }

        override fun enroll(code: String, consent: ConsentContext, appVersion: String): EnrolledDevice =
            throw UnsupportedOperationException()

        override fun sendEvents(deviceToken: String, events: List<EventDraft>): IngestResult =
            throw UnsupportedOperationException()

        override fun listDevices(accessToken: String, status: String?, limit: Int): DevicePage {
            calls += "devices"
            if (unauthenticatedOnce) {
                unauthenticatedOnce = false
                throw ApiException(401, "UNAUTHENTICATED", null, "token vencido")
            }
            val consent = ConsentContext("c-1", "mvp-1", "2026-10-24T09:59:00Z")
            return DevicePage(listOf(DeviceSummary("d-1", "active", null, "2026-10-24T10:00:00Z", null,
                consent, "0.1.0-test")), null)
        }

        override fun listEvents(accessToken: String, deviceId: String?, limit: Int): EventPage {
            calls += "events"
            return EventPage(emptyList(), null)
        }

        override fun revokeDevice(accessToken: String, deviceId: String) {
            calls += "revoke"
            revokedDevice = deviceId
            revokedToken = accessToken
        }

        override fun revokeConsent(deviceToken: String, deviceId: String): Unit =
            throw UnsupportedOperationException()
    }
}
