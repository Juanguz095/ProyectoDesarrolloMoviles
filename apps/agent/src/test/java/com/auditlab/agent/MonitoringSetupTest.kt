package com.auditlab.agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auditlab.design.AuditTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonitoringSetupTest {
    @get:Rule val compose=createAndroidComposeRule<TestHostActivity>()
    @Test fun `one confirmation requires an explicit choice for every sensitive source`() {
        var result: MonitoringSelection?=null
        compose.setContent {AuditTheme {MonitoringSetup(false,"",server="https://fixture.example",onCancel={},onActivate={result=it})}}
        assertNull(result)
        compose.onAllNodes(isToggleable()).assertCountEquals(5)
        compose.onAllNodes(isToggleable()).fetchSemanticsNodes().forEach { assertEquals(androidx.compose.ui.state.ToggleableState.Off,it.config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState]) }
        compose.onNode(hasText("SMS y llamadas") and isToggleable()).performScrollTo().performClick()
        compose.onNode(hasText("Compartir texto de SMS") and isToggleable()).performScrollTo().performClick()
        compose.onNode(hasText("Notificaciones de WhatsApp") and isToggleable()).performScrollTo().performClick()
        compose.onNode(hasText("Compartir pantalla") and isToggleable()).performScrollTo().performClick()
        assertNull(result)
        compose.onNodeWithText("ACTIVAR LO SELECCIONADO").performScrollTo().performClick()
        assertNotNull(result);assertTrue(result!!.communications);assertTrue(result!!.smsText)
        assertTrue(result!!.whatsApp);assertTrue(result!!.screen);assertFalse(result!!.numbers);assertFalse(result!!.recoverBattery)
    }
    @Test fun `deselecting communications clears text and number scopes without enabling other sources`() {
        val result=MonitoringSelection(numbers=true,smsText=true).normalized()
        assertFalse(result.communications);assertFalse(result.numbers);assertFalse(result.smsText)
        assertFalse(result.whatsApp);assertFalse(result.screen);assertFalse(result.recoverBattery)
    }
}
