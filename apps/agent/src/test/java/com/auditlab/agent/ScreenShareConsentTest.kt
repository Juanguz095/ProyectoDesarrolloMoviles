package com.auditlab.agent

import android.content.DialogInterface
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenShareConsentTest {
    @Test fun `rotation before accepting keeps the disclosure and never starts capture`() {
        val controller = Robolectric.buildActivity(ScreenShareActivity::class.java).setup().recreate()
        assertFalse(controller.get().isFinishing)
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        assertNull(shadowOf(controller.get().application).nextStartedService)
        controller.pause().stop().destroy()
    }
    @Test fun `opening disclosure and cancelling never starts capture service`() {
        val controller = Robolectric.buildActivity(ScreenShareActivity::class.java).setup()
        val activity = controller.get()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertTrue(dialog.findViewById<TextView>(android.R.id.message).text.contains("WhatsApp"))
        assertNull(shadowOf(activity.application).nextStartedService)
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity.application).nextStartedService)
        controller.pause().stop().destroy()
    }
}
