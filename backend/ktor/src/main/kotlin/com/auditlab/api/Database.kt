package com.auditlab.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager

class DatabaseConfig(val url: String, val username: String, val password: String) {
    override fun toString() = "DatabaseConfig(redacted)"

    companion object {
        fun fromEnvironment(prefix: String, env: Map<String, String> = System.getenv()): DatabaseConfig {
            fun required(suffix: String): String = env["${prefix}_$suffix"]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Required variable: ${prefix}_$suffix")
            val url = required("JDBC_URL")
            require(url.startsWith("jdbc:postgresql://")) { "PostgreSQL JDBC URL required" }
            val uri = URI(url.removePrefix("jdbc:"))
            require(uri.host != null && uri.userInfo == null && !uri.path.isNullOrBlank() && uri.path != "/") {
                "Database host/name required; credentials must use separate variables"
            }
            val queryKeys = uri.rawQuery.orEmpty().split('&').map { URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8).lowercase() }
            require(queryKeys.none { it in setOf("password", "user", "username") }) {
                "Credentials must not appear in JDBC URL"
            }
            return DatabaseConfig(url, required("USER"), required("PASSWORD"))
        }
    }
}

object DatabaseTools {
    fun migrate(config: DatabaseConfig): Int = Flyway.configure()
        .dataSource(config.url, config.username, config.password)
        .defaultSchema("auditlab")
        .schemas("auditlab")
        .locations("classpath:db/migration")
        .validateOnMigrate(true)
        .cleanDisabled(true)
        .load().migrate().migrationsExecuted

    fun <T> connection(config: DatabaseConfig, action: (Connection) -> T): T =
        DriverManager.getConnection(config.url, config.username, config.password).use(action)

    fun grantRole(config: DatabaseConfig, role: String, maintenance: Boolean = false) {
        require(Regex("[a-z_][a-z0-9_]{0,62}").matches(role)) { "Dedicated lowercase database role required" }
        connection(config) { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("SELECT rolsuper, rolcreaterole, rolcreatedb, oid FROM pg_roles WHERE rolname = ?").use { query ->
                    query.setString(1, role)
                    query.executeQuery().use { row ->
                        require(row.next() && !row.getBoolean(1) && !row.getBoolean(2) && !row.getBoolean(3) && role != config.username) {
                            "Role must exist and have no administrative attributes"
                        }
                        val oid = row.getLong(4)
                        connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_auth_members WHERE member = ?::oid) OR EXISTS(SELECT 1 FROM pg_namespace WHERE nspname = 'auditlab' AND nspowner = ?::oid) OR EXISTS(SELECT 1 FROM pg_database WHERE datname = current_database() AND datdba = ?::oid) OR EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='auditlab' AND c.relowner = ?::oid)").use { member ->
                            member.setLong(1, oid); member.setLong(2, oid); member.setLong(3, oid); member.setLong(4, oid)
                            member.executeQuery().use { result ->
                                result.next()
                                require(!result.getBoolean(1)) { "Role must be dedicated and cannot own the schema" }
                            }
                        }
                    }
                }
                connection.createStatement().use { sql ->
                    sql.execute("REVOKE ALL ON SCHEMA auditlab FROM \"$role\"")
                    sql.execute("REVOKE ALL ON ALL TABLES IN SCHEMA auditlab FROM \"$role\"")
                    sql.execute("GRANT USAGE ON SCHEMA auditlab TO \"$role\"")
                    if (maintenance) {
                        sql.execute("GRANT SELECT, DELETE ON auditlab.events, auditlab.audit_log, auditlab.devices, auditlab.consents, auditlab.enrollment_codes, auditlab.user_sessions, auditlab.refresh_tokens TO \"$role\"")
                    } else {
                        sql.execute("GRANT SELECT ON auditlab.users TO \"$role\"")
                        sql.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON auditlab.user_sessions, auditlab.refresh_tokens, auditlab.enrollment_codes TO \"$role\"")
                        sql.execute("GRANT SELECT, INSERT, UPDATE ON auditlab.devices, auditlab.consents TO \"$role\"")
                        sql.execute("GRANT SELECT, INSERT ON auditlab.events, auditlab.audit_log TO \"$role\"")
                        sql.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON auditlab.auth_rate_limits TO \"$role\"")
                    }
                }
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }
}

class Database(config: DatabaseConfig) : AutoCloseable {
    private val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.url
        username = config.username
        password = config.password
        maximumPoolSize = 5
        minimumIdle = 0
        connectionTimeout = 2000
        validationTimeout = 1000
        initializationFailTimeout = -1
        poolName = "AuditLabDb"
    })

    fun ready(): Boolean = runCatching {
        pool.connection.use { connection ->
            connection.createStatement().use { it.executeQuery("SELECT id FROM auditlab.users WHERE false").close() }
        }
    }.isSuccess

    fun <T> transaction(action: (Connection) -> T): T = pool.connection.use { connection ->
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.execute("SET LOCAL statement_timeout = '5s'"); it.execute("SET LOCAL lock_timeout = '2s'") }
            val result = action(connection)
            connection.commit()
            result
        } catch (error: Exception) {
            connection.rollback()
            throw error
        }
    }

    override fun close() = pool.close()
}
