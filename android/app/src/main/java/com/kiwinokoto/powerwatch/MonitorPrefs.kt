package com.kiwinokoto.powerwatch

import android.content.Context
import java.time.Instant
import java.util.UUID

object MonitorPrefs {
    private const val FILE = "powerwatch"
    private const val KEY_ARMED = "armed"
    private const val KEY_ARMED_SINCE = "armed_since"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_WEBHOOK_URL = "webhook_url"
    private const val KEY_WEBHOOK_TOKEN = "webhook_token"
    private const val KEY_INSTALLATION_ID = "installation_id"
    private const val KEY_LAST_POWER = "last_external_power"
    private const val KEY_LAST_EVENT = "last_event"
    private const val KEY_LAST_DELIVERY = "last_delivery"
    private const val KEY_REMOTE_ALERT_SUMMARY = "remote_alert_summary"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isArmed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ARMED, false)

    fun setArmed(context: Context, value: Boolean) {
        val p = prefs(context)
        val wasArmed = p.getBoolean(KEY_ARMED, false)
        val editor = p.edit().putBoolean(KEY_ARMED, value)

        if (value && !wasArmed) {
            editor.putString(KEY_ARMED_SINCE, Instant.now().toString())
        } else if (!value) {
            editor.remove(KEY_ARMED_SINCE)
        }

        editor.apply()
    }

    fun armedSince(context: Context): String? =
        prefs(context).getString(KEY_ARMED_SINCE, null)

    fun deviceName(context: Context): String =
        prefs(context).getString(KEY_DEVICE_NAME, "Restaurant") ?: "Restaurant"

    fun setDeviceName(context: Context, value: String) {
        prefs(context).edit().putString(KEY_DEVICE_NAME, value.trim()).apply()
    }

    fun webhookUrl(context: Context): String =
        prefs(context).getString(KEY_WEBHOOK_URL, "") ?: ""

    fun setWebhookUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_WEBHOOK_URL, value.trim()).apply()
    }

    fun webhookToken(context: Context): String =
        prefs(context).getString(KEY_WEBHOOK_TOKEN, "") ?: ""

    fun setWebhookToken(context: Context, value: String) {
        prefs(context).edit().putString(KEY_WEBHOOK_TOKEN, value.trim()).apply()
    }

    fun installationId(context: Context): String {
        val current = prefs(context).getString(KEY_INSTALLATION_ID, null)
        if (!current.isNullOrBlank()) return current
        val created = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_INSTALLATION_ID, created).commit()
        return created
    }

    fun lastExternalPower(context: Context): Boolean? {
        val p = prefs(context)
        return if (p.contains(KEY_LAST_POWER)) p.getBoolean(KEY_LAST_POWER, false) else null
    }

    fun setLastExternalPower(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_LAST_POWER, value).apply()
    }

    fun lastEvent(context: Context): String =
        prefs(context).getString(KEY_LAST_EVENT, "Aucun événement") ?: "Aucun événement"

    fun setLastEvent(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST_EVENT, value).apply()
    }

    fun lastDelivery(context: Context): String =
        prefs(context).getString(KEY_LAST_DELIVERY, "Aucun envoi") ?: "Aucun envoi"

    fun setLastDelivery(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST_DELIVERY, value).apply()
    }

    fun remoteAlertSummary(context: Context): String =
        prefs(context).getString(
            KEY_REMOTE_ALERT_SUMMARY,
            "Canal d’alerte distant : état non chargé"
        ) ?: "Canal d’alerte distant : état non chargé"

    fun setRemoteAlertSummary(context: Context, value: String) {
        prefs(context).edit().putString(KEY_REMOTE_ALERT_SUMMARY, value).apply()
    }
}
