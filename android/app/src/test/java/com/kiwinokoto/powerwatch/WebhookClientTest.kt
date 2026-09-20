package com.kiwinokoto.powerwatch

import org.junit.Assert.assertArrayEquals
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
class WebhookClientTest {
    @Test
    fun onlyPowerTransitionsUseExtendedRetries() {
        assertTrue(WebhookClient.isCriticalPowerEvent("power_lost"))
        assertTrue(WebhookClient.isCriticalPowerEvent("power_restored"))
        assertFalse(WebhookClient.isCriticalPowerEvent("monitoring_started"))
        assertFalse(WebhookClient.isCriticalPowerEvent("heartbeat"))

        assertArrayEquals(
            longArrayOf(0L, 5_000L, 15_000L),
            WebhookClient.retryDelaysFor("power_lost")
        )
        assertArrayEquals(
            longArrayOf(0L, 5_000L, 15_000L),
            WebhookClient.retryDelaysFor("power_restored")
        )
        assertArrayEquals(longArrayOf(0L), WebhookClient.retryDelaysFor("monitoring_started"))
        assertArrayEquals(longArrayOf(0L), WebhookClient.retryDelaysFor("heartbeat"))
        assertArrayEquals(longArrayOf(0L), WebhookClient.retryDelaysFor("test"))
    }

    @Test
    fun replayPrioritizesCriticalEventsWithoutReorderingThem() {
        val pending = listOf(
            PendingEvent("started", """{"event":"monitoring_started"}"""),
            PendingEvent("loss", """{"event":"power_lost"}"""),
            PendingEvent("test", """{"event":"test"}"""),
            PendingEvent("restored", """{"event":"power_restored"}"""),
            PendingEvent("current", """{"event":"power_lost"}""")
        )

        assertEquals(
            listOf("loss", "restored", "started", "test"),
            WebhookClient.replayOrder(pending, "current").map { it.eventId }
        )
    }

    @Test
    fun criticalEventTypeIsRecoveredFromPersistedPayload() {
        assertEquals(
            "power_lost",
            WebhookClient.criticalEventTypeFromPayload("""{"event":"power_lost"}""")
        )
        assertEquals(
            "power_restored",
            WebhookClient.criticalEventTypeFromPayload("""{"event":"power_restored"}""")
        )
        assertNull(WebhookClient.criticalEventTypeFromPayload("""{"event":"heartbeat"}"""))
        assertNull(WebhookClient.criticalEventTypeFromPayload("not-json"))
    }
}
