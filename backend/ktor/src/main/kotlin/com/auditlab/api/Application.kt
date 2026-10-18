package com.auditlab.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.module(database: Database? = null, authConfig: AuthConfig? = null) {
    val auth = if (database != null && authConfig != null) AuthService(database, authConfig) else null
    monitor.subscribe(ApplicationStopped) { database?.close() }
    routing {
        authRoutes(auth)
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
        when (args.firstOrNull()) {
            "migrate" -> println("Migrations applied: " + DatabaseTools.migrate(DatabaseConfig.fromEnvironment("AUDITLAB_MIGRATION")))
            "bootstrap-user" -> println("User created: " + Bootstrap.user(DatabaseConfig.fromEnvironment("AUDITLAB_MIGRATION"),
                required("AUDITLAB_BOOTSTRAP_USERNAME"), required("AUDITLAB_BOOTSTRAP_PASSWORD"), required("AUDITLAB_BOOTSTRAP_ROLE")))
            "grant-runtime", "grant-maintenance" -> {
                DatabaseTools.grantRole(DatabaseConfig.fromEnvironment("AUDITLAB_MIGRATION"), required("AUDITLAB_TARGET_DB_ROLE"), args[0] == "grant-maintenance")
                println("Database role grants applied")
            }
            null -> {
                val database = if (System.getenv().keys.any { it in setOf("AUDITLAB_DB_JDBC_URL", "AUDITLAB_DB_USER", "AUDITLAB_DB_PASSWORD") })
                    Database(DatabaseConfig.fromEnvironment("AUDITLAB_DB")) else null
                val authConfig = if (System.getenv().keys.any { it.startsWith("AUDITLAB_AUTH_") }) AuthConfig.fromEnvironment() else null
                require(authConfig == null || database != null) { "Auth requires database configuration" }
                val port = System.getenv("AUDITLAB_PORT")?.let { it.toIntOrNull() ?: throw IllegalArgumentException("AUDITLAB_PORT must be an integer") } ?: 8081
                require(port in 1..65535) { "AUDITLAB_PORT must be between 1 and 65535" }
                embeddedServer(Netty, host = "127.0.0.1", port = port) { module(database, authConfig) }.start(wait = true)
            }
            else -> throw IllegalArgumentException("Use migrate, bootstrap-user, grant-runtime, grant-maintenance or no command")
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
