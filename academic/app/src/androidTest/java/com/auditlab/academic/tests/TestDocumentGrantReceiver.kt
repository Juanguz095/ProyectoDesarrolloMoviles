package com.auditlab.academic.tests

import android.app.Instrumentation
import android.content.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal fun grantTestDocument(instrumentation: Instrumentation, uri: Uri) {
    val done = CountDownLatch(1)
    val granted = AtomicBoolean(false)
    instrumentation.targetContext.sendOrderedBroadcast(Intent("com.auditlab.academic.test.GRANT").apply {
        component = ComponentName("com.auditlab.academic.test", TestDocumentGrantReceiver::class.java.name)
        data = uri
        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
    }, null, object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { granted.set(resultCode == android.app.Activity.RESULT_OK); done.countDown() }
    }, Handler(Looper.getMainLooper()), 0, null, null)
    check(done.await(15, TimeUnit.SECONDS)) { "Test document grant timeout" }
    check(granted.get()) { "Test document grant failed" }
}
