package com.auditlab.agent

import android.app.Application
import android.content.ComponentName
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26])
class WhatsAppReconnectTest {
    @Test fun `temporary listener disconnect preserves the same RAM session and permission`() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val component=ComponentName(context,WhatsAppNotificationListener::class.java).flattenToString()
        Settings.Secure.putString(context.contentResolver,"enabled_notification_listeners",component)
        val listener=Robolectric.buildService(WhatsAppNotificationListener::class.java).create()
        listener.get().onListenerConnected()
        val session=WhatsAppSharingRuntime.Session("fixture-session",System.currentTimeMillis())
        WhatsAppSharingRuntime.activate(session)
        try {
            listener.get().onListenerDisconnected()
            assertFalse(WhatsAppSharingState.listenerConnected)
            assertTrue(WhatsAppAccess.granted(context))
            assertSame(session,WhatsAppSharingRuntime.current)
            assertTrue(WhatsAppSharingState.reconnect.withinGrace())
            listener.get().onListenerConnected()
            assertSame(session,WhatsAppSharingRuntime.current)
            assertFalse(WhatsAppSharingState.reconnect.withinGrace())
        } finally { WhatsAppSharingRuntime.clear(); listener.destroy() }
    }
    @Test fun `withdrawing notification access still stops the session immediately`() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        Settings.Secure.putString(context.contentResolver,"enabled_notification_listeners","")
        val listener=Robolectric.buildService(WhatsAppNotificationListener::class.java).create()
        listener.get().onListenerConnected()
        WhatsAppSharingRuntime.activate(WhatsAppSharingRuntime.Session("fixture",System.currentTimeMillis()))
        listener.get().onListenerDisconnected()
        assertNull(WhatsAppSharingRuntime.current)
        listener.destroy()
    }
    @Test fun `disconnect grace is bounded and repeated callbacks cannot extend it`() {
        var now=100L
        val window=ListenerReconnectWindow {now}
        assertFalse(window.withinGrace())
        window.disconnected();now+=29_999;assertTrue(window.withinGrace())
        window.disconnected();now++;assertFalse(window.withinGrace())
        window.connected();assertFalse(window.withinGrace())
    }
}
