package com.kiwinokoto.powerwatch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
