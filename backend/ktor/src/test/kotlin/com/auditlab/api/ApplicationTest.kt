package com.auditlab.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApplicationTest {
    @Test fun `liveness does not falsely advertise database readiness`() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        val readiness = client.get("/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, readiness.status)
        assertEquals("""{"status":"unavailable"}""", readiness.bodyAsText())
    }
}
