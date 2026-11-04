package com.auditlab.agent

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.auditlab.common.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Seed a running-service double; exercise the real session snapshot after a new UI is created.
 * This verifies UI state, not Android's ability to keep an actual FGS alive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ActiveCaptureSessionTest {
    @Test fun `stop cancels an activation waiting on Android permission before any service starts`() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().build())
        val runtime=AgentRuntime.get(context)
        val old=runtime.beginActivation()
        runtime.cancelActivation()
        assertFalse(runtime.activationValid(old))
        assertThrows(IllegalStateException::class.java) {runtime.activateMonitoring(MonitoringSelection(communications=true,whatsApp=true,screen=true),old)}
        assertFalse(runtime.realEvents.requested)
        assertNull(shadowOf(context).nextStartedService)
        val next=runtime.beginActivation()
        assertTrue(runtime.activationValid(next));assertFalse(runtime.activationValid(old))
    }

    @Test fun `new UI reflects the live SMS authorizations without requesting permissions again`() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().build())
        shadowOf(context).grantPermissions(Manifest.permission.READ_SMS)
        val runtime=AgentRuntime.get(context)
        ReflectionHelpers.setField(runtime.realEvents,"requested",true)
        ReflectionHelpers.setField(runtime.realEvents,"running",true)
        ReflectionHelpers.setField(runtime.realEvents,"includeNumbers",true)
        ReflectionHelpers.setField(runtime.realEvents,"includeSmsText",true)
        val consent=ConsentContext("fixture-consent","mvp-2-real","2026-11-04T00:00:00Z")
        val credentials=InMemoryCredentialStore().also {it.save(StoredCredential("fixture-device","fixture-only",consent))}
        val api=ApiClient("http://127.0.0.1:1")
        repeat(2) {
            val session=AgentSession(credentials,{api},runtime)
            ReflectionHelpers.setField(session,"engine",AgentCore(api,credentials))
            runBlocking { session.refresh(this) }
            assertTrue(session.realMonitoring)
            assertTrue(session.realCaptureRunning)
            assertTrue(session.numberConsent)
            assertTrue(session.smsTextConsent)
            assertTrue(runtime.realEvents.hasPermission(Manifest.permission.READ_SMS))
        }
        runtime.realEvents.stop("Fixture stopped")
        assertEquals("Fixture stopped",runtime.realEvents.message)
        runtime.realEvents.stop("A later lifecycle callback")
        assertEquals("Fixture stopped",runtime.realEvents.message)
        assertTrue(runtime.realEvents.hasPermission(Manifest.permission.READ_SMS))
    }
}
