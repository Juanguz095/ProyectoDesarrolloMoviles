package com.auditlab.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfigurationTest {
    private fun env(url: String = "jdbc:postgresql://127.0.0.1:5432/auditlab") = mapOf(
        "DB_JDBC_URL" to url, "DB_USER" to "fixture", "DB_PASSWORD" to "fixture-only-configuration-secret"
    )

    @Test fun `configuration requires each variable and never exposes password in toString`() {
        val config = DatabaseConfig.fromEnvironment("DB", env())
        assertEquals("DatabaseConfig(redacted)", config.toString())
        for (key in env().keys) {
            assertThrows(IllegalArgumentException::class.java) { DatabaseConfig.fromEnvironment("DB", env() - key) }
        }
    }

    @Test fun `JDBC credentials in URL including encoded property names are rejected`() {
        for (url in listOf("jdbc:mysql://localhost/test", "jdbc:postgresql://user:password@localhost/test",
            "jdbc:postgresql://localhost/test?password=forbidden", "jdbc:postgresql://localhost/test?%75ser=forbidden")) {
            assertThrows(Exception::class.java) { DatabaseConfig.fromEnvironment("DB", env(url)) }
        }
    }
}
