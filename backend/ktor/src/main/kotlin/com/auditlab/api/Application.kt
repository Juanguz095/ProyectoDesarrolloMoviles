package com.auditlab.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

fun Application.module(database: Database? = null, authConfig: AuthConfig? = null,
                       retentionDays: Int = Retention.daysFromEnvironment(),
                       retentionIntervalSeconds: Long = 3600,
                       maintenanceDatabase: Database? = null,
                       auditRetentionDays: Int = Retention.auditDaysFromEnvironment()) {
    val auth = if (database != null && authConfig != null) AuthService(database, authConfig) else null
    val screenHub = ScreenShareHub()
    val whatsAppHub = WhatsAppPreviewHub()
    val screenCleanup = launch { while (isActive) { delay(1000); screenHub.purgeExpired(); whatsAppHub.purgeExpired() } }
    // Retention runs with the maintenance account; without it the scheduled job stays disabled.
    val retention = Retention.start(maintenanceDatabase, retentionDays, retentionIntervalSeconds, auditRetentionDays)
    monitor.subscribe(ApplicationStopped) {
        screenCleanup.cancel(); screenHub.clear(); whatsAppHub.clear()
        retention?.cancel()
        maintenanceDatabase?.close()
        database?.close()
    }
    routing {
        authRoutes(auth)
        deviceRoutes(auth)
        eventRoutes(auth)
        auditRoutes(auth)
        screenShareRoutes(auth, screenHub)
        whatsAppPreviewRoutes(auth, whatsAppHub)
        get("/health") {
            call.respondText("""{"status":"ok","service":"auditlab-api","stage":"foundation"}""", ContentType.Application.Json)
        }
        get("/ready") {
            val ready = database?.ready() == true
            call.respondText("""{"status":"${if (ready) "ready" else "unavailable"}"}""", ContentType.Application.Json,
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
        }
    }
}

fun main(args: Array<String>) {
    try {
        fun required(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Required variable: $name")
        fun configured(prefix: String) = System.getenv().keys.any { it in setOf("${prefix}_JDBC_URL", "${prefix}_USER", "${prefix}_PASSWORD") }
        when (args.firstOrNull()) {
            "migrate" -> println("Migrations applied: " + DatabaseTools.migrate(DatabaseConfig.fromEnvironment("AUDITLAB_MIGRATION")))
            "bootstrap-user" -> println("User created: " + Bootstrap.user(DatabaseConfig.fromEnvironment("AUDITLAB_MIGRATION"),
                required("AUDITLAB_BOOTSTRAP_USERNAME"), required("AUDITLAB_BOOTSTRAP_PASSWORD"), required("AUDITLAB_BOOTSTRAP_ROLE")))
            "grant-runtime", "grant-maintenance" -> {
                DatabaseTools.grantRole(DatabaseConfig.fromEnvironment("AUDITLAB_MIGRATION"), required("AUDITLAB_TARGET_DB_ROLE"), args[0] == "grant-maintenance")
                println("Database role grants applied")
            }
            "retention" -> {
                val database = Database(DatabaseConfig.fromEnvironment("AUDITLAB_MAINTENANCE"))
                try {
                    println("Retention pass deleted events: " + Retention.purge(database,
                        Retention.daysFromEnvironment(), Retention.auditDaysFromEnvironment()))
                } finally {
                    database.close()
                }
            }
            null -> {
                val database = if (configured("AUDITLAB_DB")) Database(DatabaseConfig.fromEnvironment("AUDITLAB_DB")) else null
                val maintenance = if (configured("AUDITLAB_MAINTENANCE")) Database(DatabaseConfig.fromEnvironment("AUDITLAB_MAINTENANCE")) else null
                val authConfig = if (System.getenv().keys.any { it.startsWith("AUDITLAB_AUTH_") }) AuthConfig.fromEnvironment() else null
                require(authConfig == null || database != null) { "Auth requires database configuration" }
                val retentionDays = Retention.daysFromEnvironment()
                val auditDays = Retention.auditDaysFromEnvironment()
                val interval = System.getenv("AUDITLAB_RETENTION_INTERVAL_SECONDS")?.let {
                    it.toLongOrNull()?.takeIf { seconds -> seconds in 60..86400 }
                        ?: throw IllegalArgumentException("AUDITLAB_RETENTION_INTERVAL_SECONDS must be between 60 and 86400")
                } ?: 3600
                val port = System.getenv("AUDITLAB_PORT")?.let { it.toIntOrNull() ?: throw IllegalArgumentException("AUDITLAB_PORT must be an integer") } ?: 8081
                require(port in 1..65535) { "AUDITLAB_PORT must be between 1 and 65535" }
                val certificate = System.getenv("AUDITLAB_TLS_CERTIFICATE")?.takeIf { it.isNotBlank() }
                val privateKey = System.getenv("AUDITLAB_TLS_PRIVATE_KEY")?.takeIf { it.isNotBlank() }
                require((certificate == null) == (privateKey == null)) {
                    "AUDITLAB_TLS_CERTIFICATE and AUDITLAB_TLS_PRIVATE_KEY must be configured together"
                }
                if (certificate != null && privateKey != null) {
                    // Con TLS configurado solo se abre el puerto cifrado: no queda un HTTP en claro al lado.
                    val tlsPort = System.getenv("AUDITLAB_TLS_PORT")?.let {
                        it.toIntOrNull() ?: throw IllegalArgumentException("AUDITLAB_TLS_PORT must be an integer")
                    } ?: 8443
                    require(tlsPort in 1..65535) { "AUDITLAB_TLS_PORT must be between 1 and 65535" }
                    val tls = Tls.load(java.nio.file.Path.of(certificate), java.nio.file.Path.of(privateKey))
                    embeddedServer(Netty, applicationEnvironment(), configure = {
                        sslConnector(tls.keyStore, tls.alias, { tls.password }, { tls.password }) {
                            // `this.` es necesario: el `port` local de HTTP tiene prioridad sobre el del receptor.
                            this.host = "127.0.0.1"
                            this.port = tlsPort
                        }
                    }) {
                        module(database, authConfig, retentionDays, interval, maintenance, auditDays)
                    }.start(wait = true)
                } else {
                    embeddedServer(Netty, host = "127.0.0.1", port = port) {
                        module(database, authConfig, retentionDays, interval, maintenance, auditDays)
                    }.start(wait = true)
                }
            }
            else -> throw IllegalArgumentException("Use migrate, bootstrap-user, grant-runtime, grant-maintenance, retention or no command")
        }
    } catch (error: IllegalArgumentException) {
        System.err.println("Configuration error: " + error.message)
        kotlin.system.exitProcess(2)
    } catch (error: Exception) {
        // JDBC exceptions/configuration can contain connection data; never echo them from CLI.
        System.err.println("AuditLab database/startup operation failed. Verify configuration, migration status and access.")
        kotlin.system.exitProcess(1)
    }
}
