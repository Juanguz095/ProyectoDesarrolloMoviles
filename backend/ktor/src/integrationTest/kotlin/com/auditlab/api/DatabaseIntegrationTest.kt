package com.auditlab.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseIntegrationTest {
    private lateinit var owner: DatabaseConfig
    private lateinit var runtime: DatabaseConfig
    private lateinit var maintenance: DatabaseConfig
    private lateinit var userId: UUID
    private var initialMigrations = -1

    @BeforeAll fun setup() {
        owner = TestDatabase.owner
        runtime = TestDatabase.runtime
        maintenance = TestDatabase.maintenance
        initialMigrations = TestDatabase.initialMigrations
        userId = DatabaseTools.connection(owner) { connection ->
            connection.createStatement().executeQuery("SELECT id FROM auditlab.users WHERE username='fixture_admin'").use { it.next(); it.getObject(1, UUID::class.java) }
        }
    }

    private fun sql(connection: Connection, statement: String, vararg values: Any?): Int = connection.prepareStatement(statement).use { query ->
        values.forEachIndexed { i, value -> query.setObject(i + 1, value) }
        query.executeUpdate()
    }

    private fun hash() = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")

    private data class Device(val id: UUID, val consent: UUID, val code: UUID)

    private fun device(connection: Connection): Device {
        connection.createStatement().use { it.execute("SET CONSTRAINTS ALL DEFERRED") }
        val device = Device(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        sql(connection, "INSERT INTO auditlab.enrollment_codes(id,code_hash,created_by,expires_at) VALUES (?,?,?,now()+interval '10 minutes')", device.code, hash(), userId)
        sql(connection, "INSERT INTO auditlab.devices(id,enrollment_code_id,token_hash,consent_id) VALUES (?,?,?,?)", device.id, device.code, hash(), device.consent)
        sql(connection, "INSERT INTO auditlab.consents(id,device_id,policy_version,accepted_at) VALUES (?,?,'mvp-1',now())", device.consent, device.id)
        sql(connection, "UPDATE auditlab.enrollment_codes SET used_at=now(),consumed_device_id=? WHERE id=?", device.id, device.code)
        connection.createStatement().use { it.execute("SET CONSTRAINTS ALL IMMEDIATE") }
        return device
    }

    private fun event(connection: Connection, device: Device, id: UUID = UUID.randomUUID(), consent: UUID = device.consent, metadata: String = "{}") {
        sql(connection, "INSERT INTO auditlab.events(device_id,event_id,consent_id,occurred_at,category,direction,is_simulated,metadata,payload_hash) VALUES (?,?,?,now(),'app_lifecycle','occurred',true,?::jsonb,?)", device.id, id, consent, metadata, hash())
    }

    private fun transaction(config: DatabaseConfig = owner, action: (Connection) -> Unit) {
        DatabaseTools.connection(config) { connection ->
            connection.autoCommit = false
            try { action(connection) } finally { connection.rollback() }
        }
    }

    private fun rejected(connection: Connection, state: String, action: () -> Unit) {
        val savepoint = connection.setSavepoint()
        try {
            val error = assertThrows(SQLException::class.java) {
                action()
                connection.createStatement().use { it.execute("SET CONSTRAINTS ALL IMMEDIATE") }
            }
            assertEquals(state, error.sqlState)
        } finally {
            connection.rollback(savepoint)
        }
    }

    @Test fun `migration applies on empty PostgreSQL and repeats without modifications`() {
        assertEquals(3, initialMigrations)
        assertEquals(0, DatabaseTools.migrate(owner))
        DatabaseTools.connection(owner) { connection ->
            for (table in listOf("auth_rate_limits","users","user_sessions","refresh_tokens","enrollment_codes","devices","consents","events","audit_log")) {
                connection.createStatement().executeQuery("SELECT to_regclass('auditlab.$table') IS NOT NULL").use { it.next(); assertTrue(it.getBoolean(1)) }
            }
        }
    }

    @Test fun `bootstrap hashes passwords and never changes an existing role or password`() {
        val password = System.getenv("AUDITLAB_BOOTSTRAP_PASSWORD")
        assertFalse(Bootstrap.user(owner, "FIXTURE_ADMIN", "another-fixture-password", "admin"))
        assertThrows(IllegalStateException::class.java) { Bootstrap.user(owner, "fixture_admin", password, "auditor") }
        DatabaseTools.connection(owner) { connection ->
            connection.createStatement().executeQuery("SELECT password_hash,role FROM auditlab.users WHERE id='$userId'").use {
                it.next(); assertTrue(Passwords.verify(password, it.getString(1)))
                assertFalse(Passwords.verify("another-fixture-password", it.getString(1)))
                assertEquals("admin", it.getString(2))
            }
        }
    }

    @Test fun `bootstrap supports auditor and rejects arbitrary roles`() {
        assertTrue(Bootstrap.user(owner, "fixture_auditor", "auditor-fixture-password", "auditor"))
        assertThrows(IllegalArgumentException::class.java) { Bootstrap.user(owner, "invalid_user", "fixture-password", "root") }
    }

    @Test fun `username uniqueness is case insensitive`() = transaction { connection ->
        rejected(connection, "23505") {
            sql(connection, "INSERT INTO auditlab.users(id,username,password_hash,role) VALUES (?,'FIXTURE_ADMIN',?, 'admin')", UUID.randomUUID(), "\$argon2id\$fixture")
        }
    }

    @Test fun `idempotency primary key is per device not global`() = transaction { connection ->
        val first = device(connection); val second = device(connection); val id = UUID.randomUUID()
        event(connection, first, id)
        rejected(connection, "23505") { event(connection, first, id) }
        event(connection, second, id)
    }

    @Test fun `foreign consent cannot be attached to another device`() = transaction { connection ->
        val first = device(connection); val second = device(connection)
        rejected(connection, "23503") { event(connection, first, consent = second.consent) }
    }

    @Test fun `metadata rejects unknown fields types nulls and invalid values`() = transaction { connection ->
        val device = device(connection)
        for (metadata in listOf("null", "[]", "{\"sms\":\"forbidden\"}", "{\"batteryPercent\":true}", "{\"batteryPercent\":101}", "{\"batteryPercent\":50.5}", "{\"batteryPercent\":null}", "{\"networkState\":{}}", "{\"networkState\":null}", "{\"appEventCode\":\"read_sms\"}")) {
            rejected(connection, "23514") { event(connection, device, metadata = metadata) }
        }
        event(connection, device, metadata = "{\"batteryPercent\":50.0,\"networkState\":\"online\"}")
    }

    @Test fun `event enums and simulation flag are constrained`() = transaction { connection ->
        val device = device(connection); event(connection, device)
        rejected(connection, "23514") { sql(connection, "UPDATE auditlab.events SET category='sms' WHERE device_id=?", device.id) }
        rejected(connection, "23514") { sql(connection, "UPDATE auditlab.events SET direction='call' WHERE device_id=?", device.id) }
        rejected(connection, "23502") { sql(connection, "UPDATE auditlab.events SET is_simulated=NULL WHERE device_id=?", device.id) }
    }

    @Test fun `device and code consumption cannot disagree`() = transaction { connection ->
        val first = device(connection); val second = device(connection)
        rejected(connection, "23505") { sql(connection, "UPDATE auditlab.enrollment_codes SET consumed_device_id=? WHERE id=?", second.id, first.code) }
        rejected(connection, "23503") { sql(connection, "UPDATE auditlab.devices SET consent_id=? WHERE id=?", second.consent, first.id) }
    }

    @Test fun `deferred relations reject an enrollment without consent at commit boundary`() = transaction { connection ->
        rejected(connection, "23503") {
            val code = UUID.randomUUID(); val device = UUID.randomUUID()
            connection.createStatement().use { it.execute("SET CONSTRAINTS ALL DEFERRED") }
            sql(connection, "INSERT INTO auditlab.enrollment_codes(id,code_hash,created_by,expires_at) VALUES (?,?,?,now()+interval '10 minutes')", code, hash(), userId)
            sql(connection, "INSERT INTO auditlab.devices(id,enrollment_code_id,token_hash,consent_id) VALUES (?,?,?,?)", device, code, hash(), UUID.randomUUID())
            sql(connection, "UPDATE auditlab.enrollment_codes SET used_at=now(),consumed_device_id=? WHERE id=?", device, code)
        }
    }

    @Test fun `refresh rotation remains in its session and has only one child`() = transaction { connection ->
        val first = UUID.randomUUID(); val second = UUID.randomUUID(); val parent = UUID.randomUUID()
        for (session in listOf(first, second)) sql(connection, "INSERT INTO auditlab.user_sessions(id,user_id,expires_at) VALUES (?,?,now()+interval '7 days')", session, userId)
        fun refresh(id: UUID, session: UUID, ancestor: UUID?) = sql(connection, "INSERT INTO auditlab.refresh_tokens(id,session_id,token_hash,parent_token_id,expires_at) VALUES (?,?,?,?,now()+interval '7 days')", id, session, hash(), ancestor)
        refresh(parent, first, null)
        rejected(connection, "23503") { refresh(UUID.randomUUID(), second, parent) }
        refresh(UUID.randomUUID(), first, parent)
        rejected(connection, "23505") { refresh(UUID.randomUUID(), first, parent) }
    }

    @Test fun `runtime can append audit but cannot alter history or schema`() = transaction(runtime) { connection ->
        sql(connection, "INSERT INTO auditlab.audit_log(id,actor_type,action,resource,result,correlation_id) VALUES (?,'system','retention_executed','{\"type\":\"system\"}','success',?)", UUID.randomUUID(), UUID.randomUUID())
        for (statement in listOf("UPDATE auditlab.audit_log SET result='failure' WHERE false", "DELETE FROM auditlab.audit_log WHERE false", "UPDATE auditlab.users SET role='admin' WHERE false", "DROP TABLE auditlab.events", "SELECT * FROM auditlab.flyway_schema_history")) {
            rejected(connection, "42501") { connection.createStatement().use { it.execute(statement) } }
        }
    }

    @Test fun `maintenance can delete expired data but cannot rewrite audit`() = transaction(maintenance) { connection ->
        sql(connection, "DELETE FROM auditlab.audit_log WHERE false")
        rejected(connection, "42501") { sql(connection, "UPDATE auditlab.audit_log SET result='failure' WHERE false") }
        rejected(connection, "42501") { sql(connection, "INSERT INTO auditlab.users(id,username,password_hash,role) VALUES (?,'invalid_insert',?,'admin')", UUID.randomUUID(), "\$argon2id\$fixture") }
    }

    @Test fun `administrative and malformed roles cannot be granted runtime privileges`() {
        assertThrows(IllegalArgumentException::class.java) { DatabaseTools.grantRole(owner, "invalid\"role") }
        assertThrows(IllegalArgumentException::class.java) { DatabaseTools.grantRole(owner, owner.username) }
    }

    @Test fun `pooled runtime connection can read required schema`() {
        Database(runtime).use { assertTrue(it.ready()) }
    }
}
