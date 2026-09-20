package com.kiwinokoto.powerwatch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PendingEvent(val eventId: String, val payload: String)

object EventOutbox {
    private const val FILE = "powerwatch-outbox"
    private const val KEY_EVENTS = "events"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun enqueue(context: Context, eventId: String, payload: String) {
        val events = read(context).toMutableList()
        if (events.any { it.eventId == eventId }) return
        events += PendingEvent(eventId, payload)
        write(context, events)
    }

    @Synchronized
    fun pending(context: Context): List<PendingEvent> = read(context)

    @Synchronized
    fun remove(context: Context, eventId: String) {
        write(context, read(context).filterNot { it.eventId == eventId })
    }

    private fun read(context: Context): List<PendingEvent> {
        val raw = prefs(context).getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(PendingEvent(item.getString("event_id"), item.getString("payload")))
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun write(context: Context, events: List<PendingEvent>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(JSONObject().put("event_id", event.eventId).put("payload", event.payload))
        }
        check(prefs(context).edit().putString(KEY_EVENTS, array.toString()).commit()) {
            "Unable to persist PowerWatch event outbox"
        }
    }
}
