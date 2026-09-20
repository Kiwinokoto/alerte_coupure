package com.kiwinokoto.powerwatch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FallbackTrackerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("powerwatch-fallback", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun onlyCriticalPowerEventsBecomeEligible() {
        assertFalse(FallbackTracker.markEligible(context, "heartbeat-1", "heartbeat"))
        assertEquals(0, FallbackTracker.eligibleCount(context))

        assertTrue(FallbackTracker.markEligible(context, "loss-1", "power_lost"))
        assertTrue(FallbackTracker.markEligible(context, "restore-1", "power_restored"))
        assertEquals(setOf("loss-1", "restore-1"), FallbackTracker.eligibleEventIds(context))
    }

    @Test
    fun duplicateEligibilityIsIdempotent() {
        assertTrue(FallbackTracker.markEligible(context, "loss-1", "power_lost"))
        assertFalse(FallbackTracker.markEligible(context, "loss-1", "power_lost"))
        assertEquals(1, FallbackTracker.eligibleCount(context))
    }

    @Test
    fun clearOnlyRemovesDeliveredEvent() {
        FallbackTracker.markEligible(context, "loss-1", "power_lost")
        FallbackTracker.markEligible(context, "restore-1", "power_restored")

        FallbackTracker.clear(context, "loss-1")

        assertEquals(setOf("restore-1"), FallbackTracker.eligibleEventIds(context))
    }
}
