package com.auditlab.common

/** Sesión del centro de control; la implementación Android la guarda con Keystore. */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
}

class InMemorySessionStore(private var session: Session? = null) : SessionStore {
    override fun load(): Session? = session
    override fun save(session: Session) { this.session = session }
    override fun clear() { session = null }
}

class InMemoryCredentialStore(private var credential: StoredCredential? = null) : CredentialStore {
    override fun load(): StoredCredential? = credential
    override fun save(credential: StoredCredential) { this.credential = credential }
    override fun clear() { credential = null }
}

/**
 * Lógica del centro de control: login, dispositivos, eventos, códigos de inscripción y revocación.
 *
 * Renueva el access token una vez cuando el servidor responde `401 UNAUTHENTICATED`, sin repetir
 * operaciones que no son idempotentes más de una vez.
 */
class ControlCore(
    private val api: AuditApi,
    private val sessions: SessionStore = InMemorySessionStore(),
    expectedServerUrl: String? = null,
) {
    private val expectedServerUrl = expectedServerUrl?.trim()?.trimEnd('/')
    var session: Session? = sessions.load().also { value ->
        if (value != null && this.expectedServerUrl != null && value.serverUrl != this.expectedServerUrl) throw StorageFailure()
    }
        private set

    val isAdmin: Boolean get() = session?.role == "admin"
    val isSignedIn: Boolean get() = session != null

    @Synchronized fun login(username: String, password: String): Session {
        val result = api.login(username, password)
        validateSession(result)
        sessions.save(result)
        session = result
        return result
    }

    enum class LogoutOutcome { NOTIFIED, LOCAL_ONLY }
    @Synchronized fun logout(): LogoutOutcome {
        val current = session
        session = null
        sessions.clear()
        if (current == null) return LogoutOutcome.LOCAL_ONLY
        return try {
            try { api.logout(current.accessToken) }
            catch (expired: ApiException) {
                if (!expired.isUnauthenticated) throw expired
                val renewed = api.refresh(current.refreshToken)
                validateSession(renewed)
                check(renewed.userId == current.userId && renewed.sessionId == current.sessionId)
                api.logout(renewed.accessToken)
            }
            LogoutOutcome.NOTIFIED
        } catch (_: Exception) { LogoutOutcome.LOCAL_ONLY }
    }

    @Synchronized fun devices(status: String? = null): DevicePage = authorized { api.listDevices(it, status) }
    @Synchronized fun device(deviceId: String): DeviceSummary = authorized { api.deviceDetail(it, deviceId) }

    @Synchronized fun events(deviceId: String? = null): EventPage = authorized { api.listEvents(it, deviceId) }

    @Synchronized fun createEnrollmentCode(): EnrollmentCode {
        requireAdmin("crear códigos de inscripción")
        return authorized(admin = true) { api.createEnrollmentCode(it) }
    }

    @Synchronized fun revoke(deviceId: String) {
        requireAdmin("revocar dispositivos")
        authorized(admin = true) { api.revokeDevice(it, deviceId) }
    }

    private fun requireAdmin(action: String) {
        if (!isAdmin) throw IllegalStateException("Se requiere rol admin para $action")
    }

    /** Ejecuta con el access token y, ante 401, refresca una vez y reintenta. */
    @Synchronized fun devicePage(status: String?, cursor: String?) = authorized { api.devicePage(it, status, cursor) }
    @Synchronized fun eventPage(query: EventQuery, cursor: String?) = authorized { api.eventPage(it, query, cursor) }
    @Synchronized fun detail(device: String, event: String) = authorized { api.eventDetail(it, device, event) }
    @Synchronized fun export(query: EventQuery) = authorized { api.exportEvents(it, query) }
    @Synchronized fun audit(cursor: String? = null) = authorized { api.auditPage(it, cursor) }
    @Synchronized fun whatsAppPreviews(device: String) = authorized { api.whatsAppPreviews(it, device) }

    private fun validateSession(value: Session) {
        check(value.role in setOf("admin", "auditor") && value.tokenType == "Bearer")
        if (expectedServerUrl != null && value.serverUrl != expectedServerUrl) throw StorageFailure()
    }
    private fun <T> authorized(admin: Boolean = false, block: (String) -> T): T {
        val current = session ?: throw IllegalStateException("No hay sesión iniciada")
        return try {
            block(current.accessToken)
        } catch (error: ApiException) {
            if (!error.isUnauthenticated) throw error
            val renewed = try {
                api.refresh(current.refreshToken)
            } catch (refreshError: ApiException) {
                if (refreshError.status in setOf(401, 403)) { session = null; sessions.clear() }
                throw refreshError
            }
            validateSession(renewed)
            if (renewed.userId != current.userId || renewed.sessionId != current.sessionId) {
                session = null; sessions.clear(); throw StorageFailure()
            }
            try { sessions.save(renewed) } catch (error: Exception) { session = null; throw StorageFailure() }
            session = renewed
            if (admin) requireAdmin("realizar esta acción")
            try { block(renewed.accessToken) } catch (repeated: ApiException) {
                if (repeated.isUnauthenticated) { session = null; sessions.clear() }
                throw repeated
            }
        }
    }
}
