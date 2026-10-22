package com.auditlab.academic.tests

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.auditlab.academic.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only in the instrumentation APK. UI tests never open/delete the real application's DB. */
class TestApplication : AuditApplication() {
    private var current: AuditSession? = null
    var databaseName = "auditlab-ui-${UUID.randomUUID()}.db"
        private set
    @Volatile var foreground: MainActivity? = null
        private set
    override val auditSession: AuditSession get() = current ?: AuditSession(this, databaseName).also { current = it }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { if (activity is MainActivity) foreground = activity }
            override fun onActivityPaused(activity: Activity) { if (foreground === activity) foreground = null }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun resetFixture() {
        check(foreground == null)
        current?.let { session ->
            val done = CountDownLatch(1); session.dispose { done.countDown() }
            check(done.await(15, TimeUnit.SECONDS))
        }
        if (getDatabasePath(databaseName).exists()) check(deleteDatabase(databaseName))
        current = null
        databaseName = "auditlab-ui-${UUID.randomUUID()}.db"
    }
}
