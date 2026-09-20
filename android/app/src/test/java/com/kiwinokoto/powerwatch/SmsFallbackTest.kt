package com.kiwinokoto.powerwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsFallbackTest {
    @Test
    fun normalizesFrenchAndInternationalRecipients() {
        assertEquals("+33612345678", SmsFallback.normalizeRecipient("06 12 34 56 78"))
        assertEquals("+33612345678", SmsFallback.normalizeRecipient("+33 6 12 34 56 78"))
        assertNull(SmsFallback.normalizeRecipient("123"))
    }

    @Test
    fun buildsCriticalSmsFromPersistedPayload() {
        val message = SmsFallback.messageFromPayload(
            """
            {
              "event":"power_lost",
              "device_name":"Restaurant test",
              "battery_percent":72,
              "timestamp_utc":"2026-09-20T17:00:00Z"
            }
            """.trimIndent()
        )

        assertTrue(message.contains("COUPURE SECTEUR"))
        assertTrue(message.contains("Restaurant test"))
        assertTrue(message.contains("72%"))
    }

    @Test
    fun onlySubmittedResultsClearFallbackEligibility() {
        assertTrue(SmsFallback.handled(SmsFallbackResult.SUBMITTED))
        assertTrue(SmsFallback.handled(SmsFallbackResult.ALREADY_SUBMITTED))
        assertFalse(SmsFallback.handled(SmsFallbackResult.DISABLED))
        assertFalse(SmsFallback.handled(SmsFallbackResult.PERMISSION_MISSING))
        assertFalse(SmsFallback.handled(SmsFallbackResult.FAILED))
    }
}
