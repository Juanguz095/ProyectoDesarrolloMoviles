package com.auditlab.agent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.auditlab.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class MonitoringIndicator(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = context.getSharedPreferences("auditlab_visibility", Context.MODE_PRIVATE)
    @Volatile private var paused = runCatching { preferences.getBoolean("paused", true) }.getOrDefault(true)
    init { manager.createNotificationChannel(NotificationChannel(CHANNEL, "Monitoreo autorizado", NotificationManager.IMPORTANCE_LOW)) }
    fun allowed(): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun isVisible(): Boolean = !paused && allowed() && manager.activeNotifications.any { it.id == ID }
    fun show(): Boolean {
        if (!allowed()) return false
        if (!preferences.edit().putBoolean("paused", false).commit()) throw StorageFailure()
        paused = false
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            manager.notify(ID, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(com.auditlab.agent.R.drawable.ic_auditlab)
                .setContentTitle("AuditLab · Monitoreo autorizado")
                .setContentText("Eventos simulados. Abre para pausar o revocar el consentimiento.")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE).build())
            val until = SystemClock.elapsedRealtime() + 1_000
            while (!isVisible() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20)
            isVisible()
        } catch (_: SecurityException) { false }
    }
    fun hide() {
        // Block producers immediately, before waiting for the asynchronous OS cancellation.
        paused = true
        try {
            if (!preferences.edit().putBoolean("paused", true).commit()) throw StorageFailure()
        } finally {
            manager.cancel(ID)
        }
    }
    companion object { const val CHANNEL = "auditlab_monitoring"; const val ID = 5101 }
}

class SyncScheduler(context: Context) {
    private val work = WorkManager.getInstance(context.applicationContext)
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private fun data(id: String, privacy: Boolean) = workDataOf("device" to id, "privacy" to privacy)
    fun active(id: String, pending: Boolean) {
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AgentSyncWorker>(15, TimeUnit.MINUTES).setConstraints(connected)
                .setInputData(data(id, false)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        work.enqueueUniquePeriodicWork(RETENTION, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AgentRetentionWorker>(15, TimeUnit.MINUTES).build())
        if (pending) work.enqueueUniqueWork(EVENTS, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AgentSyncWorker>().setConstraints(connected).setInputData(data(id, false))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun privacy(id: String) {
        // KEEP preserves this enrollment's bounded backoff, without letting an old
        // enrollment's delayed worker consume the next enrollment's privacy request.
        work.enqueueUniqueWork("$PRIVACY-$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AgentSyncWorker>().setConstraints(connected).setInputData(data(id, true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun stopEvents() { work.cancelUniqueWork(EVENTS); work.cancelUniqueWork(PERIODIC) }
    fun retired() { stopEvents(); work.cancelUniqueWork(RETENTION) }
    companion object {
        const val EVENTS = "auditlab-events"
        const val PERIODIC = "auditlab-periodic-sync"
        const val PRIVACY = "auditlab-privacy"
        const val RETENTION = "auditlab-local-retention"
    }
}

/** One process owns the same gate and Room database for UI, workers and privacy. */
class AgentRuntime private constructor(private val context: android.app.Application) {
    val credentials = KeystoreCredentialStore(context)
    val database = AgentDatabase.open(context)
    val journal: AgentJournal = RoomAgentJournal(database)
    val gate = AgentGate()
    val indicator = MonitoringIndicator(context)
    val scheduler = SyncScheduler(context)
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    fun target(preferred: String = AgentSession.DEFAULT_URL): String =
        runCatching { credentials.load()?.serverUrl }.getOrNull()
            ?: runCatching { journal.registration()?.serverUrl }.getOrNull() ?: preferred
    fun core(preferred: String = AgentSession.DEFAULT_URL): AgentCore {
        val url = target(preferred)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.2.0-rc1"
        return AgentCore(ApiClient(url), credentials, journal = journal, gate = gate,
            visible = indicator::isVisible, serverUrl = url, appVersion = version)
    }
    fun after(core: AgentCore) = synchronized(gate) {
        val state = core.refresh()
        val row = runCatching { journal.registration() }.getOrNull()
        if (state is AgentCore.State.Enrolled && row != null) {
            scheduler.active(row.deviceId, indicator.isVisible() && journal.stats().pending > 0)
        } else {
            indicator.hide(); scheduler.stopEvents()
            if (row?.phase == AgentPhase.REVOKING) scheduler.privacy(row.deviceId)
            else if (row?.phase in setOf(AgentPhase.REVOKED, AgentPhase.LOCAL_ONLY)) scheduler.retired()
        }
        changes.tryEmit(Unit)
    }
    fun sync(device: String, privacy: Boolean): SyncDecision = synchronized(gate) {
        val result = AgentSync(journal, gate) { core() }.run(device, privacy)
        runCatching { after(core()) }
        result
    }
    fun metadata(): Map<String, Any?> = buildMap {
        put("appEventCode", "agent_started")
        val connected = runCatching { context.getSystemService(ConnectivityManager::class.java).activeNetwork != null }.getOrNull()
        put("networkState", when (connected) { true -> "online"; false -> "offline"; null -> "unknown" })
        val battery = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (battery in 0..100) put("batteryPercent", battery)
    }
    companion object {
        @Volatile private var instance: AgentRuntime? = null
        fun get(context: Context): AgentRuntime = instance ?: synchronized(this) {
            instance ?: AgentRuntime(context.applicationContext as android.app.Application).also { instance = it }
        }
    }
}

class AgentSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val device = inputData.getString("device") ?: return@withContext Result.failure()
        val outcome = runCatching { AgentRuntime.get(applicationContext).sync(device, inputData.getBoolean("privacy", false)) }
            .getOrDefault(SyncDecision.RETRY)
        when (outcome) {
            SyncDecision.DONE -> Result.success()
            SyncDecision.RETRY -> if (runAttemptCount < 7) Result.retry() else Result.failure()
            SyncDecision.FAILED -> Result.failure()
        }
    }
}
class AgentRetentionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val runtime = AgentRuntime.get(applicationContext)
            synchronized(runtime.gate) { runtime.journal.prune(Instant.now().minus(7, ChronoUnit.DAYS)) }
            runtime.changes.tryEmit(Unit)
            Result.success()
        } catch (_: Exception) { if (runAttemptCount < 7) Result.retry() else Result.failure() }
    }
}
