package com.auditlab.agent

import android.provider.CallLog
import android.provider.Telephony
import org.junit.Assert.*
import org.junit.Test

class PhoneMetadataTest {
    @Test fun `long calls remain visible when the provider writes their start date after completion`() {
        val activation = 1_000L
        val started = activation + 1_000
        val completed = started + 30 * 60_000
        assertTrue(started >= PhoneMetadata.captureSince(activation, completed))
        assertEquals(activation, PhoneMetadata.captureSince(activation, completed))
        assertTrue(PhoneMetadata.captureSince(activation, activation + 8 * 86_400_000L) > activation)
    }
    @Test fun `projections never request content or phone numbers`() {
        assertArrayEquals(arrayOf("_id", "date", "type"), PhoneMetadata.smsProjection)
        assertArrayEquals(arrayOf("_id", "date", "type"), PhoneMetadata.callProjection)
    }
    @Test fun `only completed SMS count as sent or received`() {
        assertEquals("sms_received", PhoneMetadata.code(true, Telephony.Sms.MESSAGE_TYPE_INBOX))
        assertEquals("sms_sent", PhoneMetadata.code(true, Telephony.Sms.MESSAGE_TYPE_SENT))
        listOf(Telephony.Sms.MESSAGE_TYPE_DRAFT, Telephony.Sms.MESSAGE_TYPE_OUTBOX,
            Telephony.Sms.MESSAGE_TYPE_FAILED, Telephony.Sms.MESSAGE_TYPE_QUEUED).forEach { assertNull(PhoneMetadata.code(true, it)) }
    }
    @Test fun `call direction and missed rejected status map correctly`() {
        assertEquals("call_received", PhoneMetadata.code(false, CallLog.Calls.INCOMING_TYPE))
        assertEquals("call_outgoing", PhoneMetadata.code(false, CallLog.Calls.OUTGOING_TYPE))
        assertEquals("call_missed", PhoneMetadata.code(false, CallLog.Calls.MISSED_TYPE))
        assertEquals("call_rejected", PhoneMetadata.code(false, CallLog.Calls.REJECTED_TYPE))
        assertNull(PhoneMetadata.code(false, -1))
    }
    @Test fun `provider retries deduplicate but new enrollment and changed type do not collide`() {
        val id = PhoneMetadata.eventId("d", "c", "sms_sent", 7, 1000)
        assertEquals(id, PhoneMetadata.eventId("d", "c", "sms_sent", 7, 1000))
        assertNotEquals(id, PhoneMetadata.eventId("d", "new-consent", "sms_sent", 7, 1000))
        assertNotEquals(id, PhoneMetadata.eventId("d", "c", "sms_received", 7, 1000))
    }
}
