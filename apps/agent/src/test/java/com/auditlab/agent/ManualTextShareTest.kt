package com.auditlab.agent

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ManualTextShareTest {
    @Test fun `opening a share consumes the external payload without starting services`() {
        val intent=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"Texto ficticio")
        val controller=Robolectric.buildActivity(ManualTextShareActivity::class.java,intent).setup()
        val activity=controller.get()
        assertFalse(activity.intent.hasExtra(Intent.EXTRA_TEXT))
        assertNull(shadowOf(activity.application).nextStartedService)
        controller.pause().stop().destroy()
    }
    @Test fun `rotation never restores the external payload or starts monitoring`() {
        val intent=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"Texto ficticio")
        val controller=Robolectric.buildActivity(ManualTextShareActivity::class.java,intent).setup().recreate()
        assertFalse(controller.get().intent.hasExtra(Intent.EXTRA_TEXT))
        assertNull(shadowOf(controller.get().application).nextStartedService)
        controller.pause().stop().destroy()
    }
}
