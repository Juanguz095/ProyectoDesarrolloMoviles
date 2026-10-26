package com.auditlab.control

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportDestinationTest {
    @Test fun `only a content provider can receive an exported report`() {
        assertTrue(ExportDestination.isAllowed(Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fqa.csv")))
        listOf("file:///data/user/0/com.auditlab.control/shared_prefs/auditlab_control.xml", "file:///sdcard/qa.csv",
            "https://example.invalid/report", "content:/missing-authority", "", "data:text/csv,secret").forEach {
            assertFalse(ExportDestination.isAllowed(Uri.parse(it)))
        }
    }
}
