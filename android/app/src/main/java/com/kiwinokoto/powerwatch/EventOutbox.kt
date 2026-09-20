package com.kiwinokoto.powerwatch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PendingEvent(val eventId: String, val payload: String)

object EventOutbox {
    private const val FILE = "powerwatch-outbox"
    private const val KEY_EVENTS = "events"
    private const val KEY_EVENTS_BACKUP = "events_backup"
    private const val MAX_NON_CRITICAL_EVENTS = 100

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun enqueue(context: Context, eventId: String, payload: String) {
        val events = read(context).toMutableList()
        if (events.any { it.eventId == eventId }) return
        events += PendingEvent(eventId, payload)
        write(context, trimNonCritical(events))
    }

    @Synchronized
    fun pending(context: Context): List<PendingEvent> = read(context)

    @Synchronized
    fun remove(context: Context, eventId: String) {
        write(context, read(context).filterNot { it.eventId == eventId })
    }

    private fun read(context: Context): List<PendingEvent> {
        val preferences = prefs(context)
        val raw = preferences.getString(KEY_EVENTS, null) ?: return emptyList()
        parse(raw)?.let { return it }

        val backup = preferences.getString(KEY_EVENTS_BACKUP, null)
        val recovered = backup?.let(::parse) ?: return emptyList()
        check(preferences.edit().putString(KEY_EVENTS, backup).commit()) {
            "Unable to recover PowerWatch event outbox"
        }
        return recovered
    }

    private fun trimNonCritical(events: List<PendingEvent>): List<PendingEvent> {
        var excess = events.count { !isCritical(it) } - MAX_NON_CRITICAL_EVENTS
        if (excess <= 0) return events
        return events.filter { event ->
            if (excess > 0 && !isCritical(event)) {
                excess--
                false
            } else {
                true
            }
        }
    }

    private fun isCritical(event: PendingEvent): Boolean = runCatching {
        JSONObject(event.payload).optString("event") in setOf("power_lost", "power_restored")
    }.getOrDefault(false)

    private fun parse(raw: String): List<PendingEvent>? = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(PendingEvent(item.getString("event_id"), item.getString("payload")))
            }
        }
    }.getOrNull()

    private fun write(context: Context, events: List<PendingEvent>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(JSONObject().put("event_id", event.eventId).put("payload", event.payload))
        }
        val preferences = prefs(context)
        val previous = preferences.getString(KEY_EVENTS, null)
        val editor = preferences.edit()
        if (previous != null && parse(previous) != null) {
            editor.putString(KEY_EVENTS_BACKUP, previous)
        }
        check(editor.putString(KEY_EVENTS, array.toString()).commit()) {
            "Unable to persist PowerWatch event outbox"
        }
    }
}
