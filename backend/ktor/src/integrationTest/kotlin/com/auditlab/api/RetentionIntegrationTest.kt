package com.auditlab.api

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionIntegrationTest : ApiTestSupport() {
    @BeforeAll
    fun setup() {
        Bootstrap.user(owner, "retention_admin", password, "admin")
    }

    private fun insertEvent(device: JsonNode, receivedAt: Instant): UUID {
        val eventId = UUID.randomUUID()
        sql("""INSERT INTO auditlab.events(device_id,event_id,consent_id,occurred_at,received_at,category,direction,is_simulated,metadata,payload_hash)
            VALUES (?,?,?,?,?,'sync','inbound',true,'{}',repeat('c',64))""",
            UUID.fromString(device["deviceId"].textValue()), eventId,
            UUID.fromString(device["consentContext"]["consentId"].textValue()), java.sql.Timestamp.from(receivedAt),
            java.sql.Timestamp.from(receivedAt))
        return eventId
    }

    private fun insertOldAudit(occurredAt: Instant): UUID {
        val id = UUID.randomUUID()
        sql("""INSERT INTO auditlab.audit_log(id,actor_type,actor_id,action,resource,filters,result,occurred_at,correlation_id)
            VALUES (?,'system',NULL,'retention_executed','{"type":"system"}','{}','success',?,?)""",
            id, java.sql.Timestamp.from(occurredAt), UUID.randomUUID())
        return id
    }

    private fun eventExists(eventId: UUID): Boolean = inspect { connection ->
        connection.prepareStatement("SELECT 1 FROM auditlab.events WHERE event_id=?").use {
            it.setObject(1, eventId)
            it.executeQuery().use { row -> row.next() }
        }
    }

    private fun auditExists(id: UUID): Boolean = inspect { connection ->
        connection.prepareStatement("SELECT 1 FROM auditlab.audit_log WHERE id=?").use {
            it.setObject(1, id)
            it.executeQuery().use { row -> row.next() }
        }
    }

    @Test
    fun `purge removes only expired rows and records the pass as system`() = http { _ ->
        val admin = login("retention_admin")
        val device = enroll(admin)
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val expiredEvent = insertEvent(device, now.minus(31, ChronoUnit.DAYS))
        val currentEvent = insertEvent(device, now.minus(1, ChronoUnit.DAYS))
        val expiredAudit = insertOldAudit(now.minus(91, ChronoUnit.DAYS))

        val database = Database(TestDatabase.maintenance)
        val deleted = try {
            Retention.purge(database, days = 30, auditDays = 90)
        } finally {
            database.close()
        }
        assertTrue(deleted >= 1)
        assertTrue(!eventExists(expiredEvent))
        assertTrue(eventExists(currentEvent))
        assertTrue(!auditExists(expiredAudit))
        inspect { connection ->
            connection.prepareStatement("SELECT actor_type,actor_id,result FROM auditlab.audit_log WHERE action='retention_executed' ORDER BY occurred_at DESC LIMIT 1").use {
                it.executeQuery().use { row ->
                    assertTrue(row.next())
                    assertEquals("system", row.getString(1))
                    assertNull(row.getObject(2))
                    assertEquals("success", row.getString(3))
                }
            }
        }
    }

    @Test
    fun `scheduled loop purges without any query traffic`() = http { _ ->
        val admin = login("retention_admin")
        val device = enroll(admin)
        val expired = insertEvent(device, Instant.now().minus(40, ChronoUnit.DAYS))
        assertTrue(eventExists(expired))
        val database = Database(TestDatabase.maintenance)
        val scope = Retention.start(database, days = 30, intervalSeconds = 1, auditDays = 90)
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (eventExists(expired) && System.nanoTime() < deadline) Thread.sleep(200)
            assertTrue(!eventExists(expired), "Scheduled retention did not delete the expired event")
        } finally {
            scope?.cancel()
            database.close()
        }
    }

    @Test
    fun `retention environment values are bounded`() {
        assertEquals(30, Retention.daysFromEnvironment(emptyMap()))
        assertEquals(90, Retention.auditDaysFromEnvironment(emptyMap()))
        assertEquals(7, Retention.daysFromEnvironment(mapOf("AUDITLAB_RETENTION_DAYS" to "7")))
        assertEquals(365, Retention.auditDaysFromEnvironment(mapOf("AUDITLAB_AUDIT_RETENTION_DAYS" to "365")))
        for (bad in listOf("0", "3651", "abc", "-1")) {
            assertThrows(IllegalArgumentException::class.java) {
                Retention.daysFromEnvironment(mapOf("AUDITLAB_RETENTION_DAYS" to bad))
            }
            assertThrows(IllegalArgumentException::class.java) {
                Retention.auditDaysFromEnvironment(mapOf("AUDITLAB_AUDIT_RETENTION_DAYS" to bad))
            }
        }
    }
}
