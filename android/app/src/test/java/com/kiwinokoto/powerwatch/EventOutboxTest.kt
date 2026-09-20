package com.kiwinokoto.powerwatch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventOutboxTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("powerwatch-outbox", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun enqueuePersistsAndDeduplicatesByEventId() {
        EventOutbox.enqueue(context, "event-1", "{\"value\":1}")
        EventOutbox.enqueue(context, "event-1", "{\"value\":2}")

        assertEquals(listOf(PendingEvent("event-1", "{\"value\":1}")), EventOutbox.pending(context))
    }

    @Test
    fun removeKeepsOtherPendingEvents() {
        EventOutbox.enqueue(context, "event-1", "one")
        EventOutbox.enqueue(context, "event-2", "two")

        EventOutbox.remove(context, "event-1")

        assertEquals(listOf(PendingEvent("event-2", "two")), EventOutbox.pending(context))
    }

    @Test
    fun malformedPrimaryRecoversLastValidSnapshot() {
        EventOutbox.enqueue(context, "event-1", "one")
        EventOutbox.enqueue(context, "event-2", "two")
        val prefs = context.getSharedPreferences("powerwatch-outbox", Context.MODE_PRIVATE)
        prefs.edit().putString("events", "not-json").commit()

        assertEquals(listOf(PendingEvent("event-1", "one")), EventOutbox.pending(context))
        assertEquals(
            "[{\"event_id\":\"event-1\",\"payload\":\"one\"}]",
            prefs.getString("events", null)
        )
    }
    @Test
    fun retentionDropsOldestNonCriticalEventsButKeepsPowerTransitions() {
        EventOutbox.enqueue(context, "critical-loss", "{\"event\":\"power_lost\"}")
        for (index in 0..100) {
            EventOutbox.enqueue(context, "test-$index", "{\"event\":\"test\"}")
        }
        EventOutbox.enqueue(context, "critical-restored", "{\"event\":\"power_restored\"}")

        val pending = EventOutbox.pending(context)
        assertEquals(102, pending.size)
        assertEquals(true, pending.any { it.eventId == "critical-loss" })
        assertEquals(true, pending.any { it.eventId == "critical-restored" })
        assertEquals(false, pending.any { it.eventId == "test-0" })
        assertEquals(true, pending.any { it.eventId == "test-100" })
    }

}
