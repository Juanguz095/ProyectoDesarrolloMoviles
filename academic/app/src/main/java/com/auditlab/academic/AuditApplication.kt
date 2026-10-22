package com.auditlab.academic

import android.app.Application
import android.content.ComponentCallbacks2

open class AuditApplication : Application() {
    open val auditSession: AuditSession by lazy { AuditSession(this) }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) auditSession.closeDatabaseWhenIdle()
    }
}
