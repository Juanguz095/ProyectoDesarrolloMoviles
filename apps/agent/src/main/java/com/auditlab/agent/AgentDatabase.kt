package com.auditlab.agent

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.auditlab.common.*
import org.json.JSONObject
import java.time.Instant

@Entity(tableName = "agent_registration")
data class RegistrationRow(
    @PrimaryKey val singleton: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "server_url") val serverUrl: String,
    @ColumnInfo(name = "consent_json") val consentJson: String?,
    val phase: String,
    @ColumnInfo(defaultValue = "0") val remote: Long = 0,
)
@Entity(tableName = "agent_outbox", indices = [Index(value = ["delivery", "timestamp_ms", "event_id"])])
data class OutboxRow(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    val delivery: String,
    @ColumnInfo(defaultValue = "0") val attempts: Long = 0,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
)
@Dao
interface AgentDao {
    @Query("SELECT * FROM agent_registration WHERE singleton=1") fun registration(): RegistrationRow?
    @Query("SELECT COUNT(*) FROM agent_registration") fun registrations(): Int
    @Query("DELETE FROM agent_registration") fun clearRegistration()
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertRegistration(row: RegistrationRow)
    @Query("SELECT COUNT(*) FROM agent_registration WHERE singleton<>1 OR typeof(singleton)<>'integer' OR typeof(remote)<>'integer' OR remote NOT IN (0,1) OR phase NOT IN ('ACTIVE','REVOKING','REVOKED','LOCAL_ONLY','REJECTED')") fun invalidRegistration(): Int
    @Query("SELECT COUNT(*) FROM agent_outbox WHERE typeof(timestamp_ms)<>'integer' OR timestamp_ms<0 OR typeof(attempts)<>'integer' OR attempts<0 OR delivery NOT IN ('PENDING','SENT','FAILED')") fun invalidOutbox(): Int
    @Query("SELECT COUNT(*) FROM agent_outbox") fun total(): Int
    @Query("SELECT COUNT(*) FROM agent_outbox WHERE delivery=:delivery") fun count(delivery: String): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) fun insertEvent(row: OutboxRow)
    @Query("SELECT * FROM agent_outbox WHERE delivery='PENDING' ORDER BY timestamp_ms,event_id LIMIT :limit") fun pending(limit: Int): List<OutboxRow>
    @Query("UPDATE agent_outbox SET delivery='SENT', attempts=attempts+1,error_code=NULL WHERE event_id IN (:ids) AND delivery='PENDING'") fun acknowledge(ids: List<String>)
    @Query("UPDATE agent_outbox SET delivery='FAILED', attempts=attempts+1,error_code=:code WHERE event_id IN (:ids) AND delivery='PENDING'") fun reject(ids: List<String>, code: String)
    @Query("UPDATE agent_registration SET phase='REJECTED' WHERE singleton=1") fun rejectCredential()
    @Query("UPDATE agent_registration SET phase=:phase,consent_json=NULL WHERE singleton=1") fun finishWithdrawal(phase: String)
    @Query("DELETE FROM agent_outbox WHERE event_id IN (SELECT event_id FROM agent_outbox WHERE delivery='SENT' ORDER BY timestamp_ms,event_id LIMIT 1)") fun evictSent(): Int
    @Query("DELETE FROM agent_outbox WHERE timestamp_ms<:before") fun prune(before: Long): Int
    @Query("DELETE FROM agent_outbox") fun deleteEvents()
}

@Database(entities = [RegistrationRow::class, OutboxRow::class], version = 2, exportSchema = true)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun dao(): AgentDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_outbox ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE agent_outbox ADD COLUMN error_code TEXT")
            }
        }
        fun open(context: Context, name: String = "auditlab-outbox.db"): AgentDatabase =
            Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2).build()
    }
}

/** Every policy check and insertion is atomic across SQLite connections. Tokens never enter Room. */
class RoomAgentJournal(private val database: AgentDatabase, private val capacity: Int = 10_000) : AgentJournal {
    private val dao get() = database.dao()
    private inline fun <T> operation(action: () -> T): T = try { action() }
    catch (e: QueueFull) { throw e }
    catch (_: Exception) { throw StorageFailure() }
    private fun registrationValue(): AgentRegistration? {
        check(dao.invalidRegistration() == 0)
        return dao.registration()?.let { row ->
            val phase = AgentPhase.valueOf(row.phase)
            val consent = row.consentJson?.let { EventCodec.consent(JSONObject(it)) }
            if (phase == AgentPhase.ACTIVE) check(consent != null)
            consent?.let { Instant.parse(it.acceptedAt) }
            AgentRegistration(row.deviceId, row.serverUrl, consent, phase, row.remote == 1L)
        }
    }
    override fun registration(): AgentRegistration? = operation { registrationValue() }
    override fun activate(credential: StoredCredential, serverUrl: String) = operation {
        database.runInTransaction {
            check(dao.registrations() == 0 && dao.total() == 0)
            dao.insertRegistration(RegistrationRow(deviceId = credential.deviceId, serverUrl = serverUrl,
                consentJson = EventCodec.consent(credential.consent).toString(), phase = AgentPhase.ACTIVE.name))
        }
    }
    override fun enqueue(event: EventDraft) = operation {
        database.runInTransaction {
            val row = registrationValue() ?: throw StorageFailure()
            check(row.phase == AgentPhase.ACTIVE && row.deviceId == event.sourceDevice && row.consent == event.consent)
            check(dao.invalidOutbox() == 0)
            if (dao.total() >= capacity && dao.evictSent() == 0) throw QueueFull()
            dao.insertEvent(OutboxRow(event.eventId, EventCodec.encode(event), Instant.parse(event.timestamp).toEpochMilli(), DeliveryState.PENDING.name))
        }
    }
    override fun pending(limit: Int): List<EventDraft> = operation {
        require(limit in 1..100)
        check(dao.invalidOutbox() == 0)
        dao.pending(limit).map { row ->
            EventCodec.decode(row.payloadJson).also { check(it.eventId == row.eventId && Instant.parse(it.timestamp).toEpochMilli() == row.timestampMs) }
        }
    }
    override fun acknowledge(eventIds: List<String>) = operation { dao.acknowledge(eventIds) }
    override fun reject(eventIds: List<String>, code: String) = operation {
        dao.reject(eventIds, code.take(80).replace(Regex("[^A-Z0-9_]"), "_"))
    }
    override fun rejectCredential() = operation { dao.rejectCredential() }
    override fun beginRevocation(deviceId: String, serverUrl: String, remote: Boolean) = operation {
        database.runInTransaction {
            dao.clearRegistration()
            dao.insertRegistration(RegistrationRow(deviceId = deviceId, serverUrl = serverUrl,
                consentJson = null, phase = AgentPhase.REVOKING.name, remote = if (remote) 1 else 0))
        }
    }
    override fun completeRevocation(confirmed: Boolean) = operation { dao.finishWithdrawal(if (confirmed) "REVOKED" else "LOCAL_ONLY") }
    override fun deleteEvents() = operation { dao.deleteEvents() }
    override fun reset() = operation {
        database.runInTransaction {
            check(registrationValue()?.phase in setOf(AgentPhase.REVOKED, AgentPhase.LOCAL_ONLY))
            dao.deleteEvents(); dao.clearRegistration()
        }
    }
    override fun prune(before: Instant): Int = operation { dao.prune(before.toEpochMilli()) }
    override fun stats(): QueueStats = operation {
        check(dao.invalidOutbox() == 0)
        QueueStats(dao.count("PENDING"), dao.count("SENT"), dao.count("FAILED"))
    }
}
