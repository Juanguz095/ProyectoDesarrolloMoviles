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
) {
    var session: Session? = sessions.load()
        private set

    val isAdmin: Boolean get() = session?.role == "admin"
    val isSignedIn: Boolean get() = session != null

    fun login(username: String, password: String): Session {
        val result = api.login(username, password)
        sessions.save(result)
        session = result
        return result
    }

    fun logout() {
        val current = session
        sessions.clear()
        session = null
        if (current != null) runCatching { api.logout(current.accessToken) }
    }

    fun devices(status: String? = null): DevicePage = authorized { api.listDevices(it, status) }

    fun events(deviceId: String? = null): EventPage = authorized { api.listEvents(it, deviceId) }

    fun createEnrollmentCode(): EnrollmentCode {
        requireAdmin("crear códigos de inscripción")
        return authorized { api.createEnrollmentCode(it) }
    }

    fun revoke(deviceId: String) {
        requireAdmin("revocar dispositivos")
        authorized { api.revokeDevice(it, deviceId) }
    }

    private fun requireAdmin(action: String) {
        if (!isAdmin) throw IllegalStateException("Se requiere rol admin para $action")
    }

    /** Ejecuta con el access token y, ante 401, refresca una vez y reintenta. */
    private fun <T> authorized(block: (String) -> T): T {
        val current = session ?: throw IllegalStateException("No hay sesión iniciada")
        return try {
            block(current.accessToken)
        } catch (error: ApiException) {
            if (!error.isUnauthenticated) throw error
            val renewed = try {
                api.refresh(current.refreshToken)
            } catch (refreshError: ApiException) {
                sessions.clear()
                session = null
                throw refreshError
            }
            sessions.save(renewed)
            session = renewed
            block(renewed.accessToken)
        }
    }
}
