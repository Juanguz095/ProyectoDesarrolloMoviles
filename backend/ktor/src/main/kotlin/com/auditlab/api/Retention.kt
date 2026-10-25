package com.auditlab.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.sql.Timestamp
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Scheduled retention, as required by P3.5. It runs with the maintenance account, never with the
 * runtime account: the runtime role cannot delete events or audit rows. Events are removed by
 * reception time and audit rows after their own longer period.
 */
object Retention {
    fun daysFromEnvironment(env: Map<String, String> = System.getenv()): Int = boundedDays(env, "AUDITLAB_RETENTION_DAYS", 30)

    fun auditDaysFromEnvironment(env: Map<String, String> = System.getenv()): Int =
        boundedDays(env, "AUDITLAB_AUDIT_RETENTION_DAYS", 90)

    private fun boundedDays(env: Map<String, String>, name: String, default: Int): Int {
        val raw = env[name]?.takeIf { it.isNotBlank() } ?: return default
        val days = raw.toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer")
        require(days in 1..3650) { "$name must be between 1 and 3650" }
        return days
    }

    /** Deletes expired events and expired audit rows, then records the pass. Returns deleted events. */
    fun purge(database: Database, days: Int, auditDays: Int = 90, clock: Clock = Clock.systemUTC()): Int {
        require(days in 1..3650) { "Retention days out of range" }
        require(auditDays in 1..3650) { "Audit retention days out of range" }
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val eventCutoff = now.minus(days.toLong(), ChronoUnit.DAYS)
        val auditCutoff = now.minus(auditDays.toLong(), ChronoUnit.DAYS)
        return database.transaction { connection ->
            val deleted = connection.prepareStatement("DELETE FROM auditlab.events WHERE received_at < ?").use {
                it.setTimestamp(1, Timestamp.from(eventCutoff))
                it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM auditlab.audit_log WHERE occurred_at < ?").use {
                it.setTimestamp(1, Timestamp.from(auditCutoff))
                it.executeUpdate()
            }
            Audit.record(connection, "system", null, "retention_executed", Audit.resource("system"),
                correlation = UUID.randomUUID())
            deleted
        }
    }

    /** Starts the independent loop. The first pass runs at once, then every [intervalSeconds]. */
    fun start(database: Database?, days: Int, intervalSeconds: Long = 3600,
              auditDays: Int = 90): CoroutineScope? {
        if (database == null) return null
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            while (isActive) {
                runCatching { purge(database, days, auditDays) }
                delay(intervalSeconds.coerceAtLeast(1) * 1000)
            }
        }
        return scope
    }
}
