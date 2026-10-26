package com.auditlab.agent

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentSchedulingTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
    }
    @Test fun `no consent schedules no work and retention does not require network`() {
        val scheduler = SyncScheduler(context)
        val work = WorkManager.getInstance(context)
        assertTrue(work.getWorkInfosForUniqueWork(SyncScheduler.EVENTS).get().isEmpty())
        scheduler.active("fixture-device", false)
        val periodic = work.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC).get(10, TimeUnit.SECONDS).single()
        val retention = work.getWorkInfosForUniqueWork(SyncScheduler.RETENTION).get(10, TimeUnit.SECONDS).single()
        assertEquals(NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)
        assertEquals(NetworkType.NOT_REQUIRED, retention.constraints.requiredNetworkType)
        assertTrue(work.getWorkInfosForUniqueWork(SyncScheduler.EVENTS).get().isEmpty())
        scheduler.stopEvents()
        assertEquals(WorkInfo.State.CANCELLED, work.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC).get().single().state)
    }
    @Test fun `pause remains effective after restart even if an old notification is still present`() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val indicator = MonitoringIndicator(context)
        assertTrue(indicator.show()); indicator.hide()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(MonitoringIndicator.ID, android.app.Notification.Builder(context, MonitoringIndicator.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Synthetic stale QA indicator").build())
        assertTrue(manager.activeNotifications.any { it.id == MonitoringIndicator.ID })
        assertFalse(MonitoringIndicator(context).isVisible())
        manager.cancel(MonitoringIndicator.ID)
    }
    @Test fun `pending privacy for a previous enrollment cannot consume the new enrollment job`() {
        val scheduler = SyncScheduler(context)
        scheduler.privacy("previous-device")
        scheduler.privacy("current-device")
        scheduler.privacy("current-device")
        val requests = WorkManager.getInstance(context)
            .getWorkInfosByTag(AgentSyncWorker::class.java.name).get(10, TimeUnit.SECONDS)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.constraints.requiredNetworkType == NetworkType.CONNECTED })
    }
    @Test fun `notification permission and user dismissal pause monitoring without automatic repost`() {
        val indicator = MonitoringIndicator(context)
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(indicator.allowed()); assertFalse(indicator.show()); assertFalse(indicator.isVisible())
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(indicator.show()); assertTrue(indicator.isVisible())
        context.getSystemService(NotificationManager::class.java).cancel(MonitoringIndicator.ID)
        assertFalse(indicator.isVisible()); assertFalse(indicator.isVisible())
    }
}
