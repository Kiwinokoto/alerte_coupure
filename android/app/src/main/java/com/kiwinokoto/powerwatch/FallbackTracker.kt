package com.kiwinokoto.powerwatch

import android.content.Context

object FallbackTracker {
    private const val FILE = "powerwatch-fallback"
    private const val KEY_ELIGIBLE_EVENT_IDS = "eligible_event_ids"
    private val criticalEvents = setOf("power_lost", "power_restored")

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun markEligible(context: Context, eventId: String, eventType: String): Boolean {
        if (eventType !in criticalEvents) return false
        val ids = eligibleEventIds(context).toMutableSet()
        if (!ids.add(eventId)) return false
        check(prefs(context).edit().putStringSet(KEY_ELIGIBLE_EVENT_IDS, ids).commit()) {
            "Unable to persist PowerWatch fallback eligibility"
        }
        return true
    }

    @Synchronized
    fun clear(context: Context, eventId: String) {
        val ids = eligibleEventIds(context).toMutableSet()
        if (!ids.remove(eventId)) return
        check(prefs(context).edit().putStringSet(KEY_ELIGIBLE_EVENT_IDS, ids).commit()) {
            "Unable to persist PowerWatch fallback eligibility"
        }
    }

    @Synchronized
    fun eligibleEventIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ELIGIBLE_EVENT_IDS, emptySet())
            ?.toSet()
            ?: emptySet()

    fun eligibleCount(context: Context): Int = eligibleEventIds(context).size
}
