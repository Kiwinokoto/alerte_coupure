package com.kiwinokoto.powerwatch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import org.json.JSONObject

enum class SmsFallbackResult {
    DISABLED,
    NOT_CONFIGURED,
    PERMISSION_MISSING,
    ALREADY_SUBMITTED,
    SUBMITTED,
    FAILED
}

object SmsFallback {
    private const val FILE = "powerwatch-sms-fallback"
    private const val KEY_SUBMITTED_EVENT_IDS = "submitted_event_ids"

    internal fun normalizeRecipient(raw: String): String? {
        val compact = raw.trim()
            .replace(" ", "")
            .replace(".", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")

        if (compact.matches(Regex("^0[1-9][0-9]{8}$"))) {
            return "+33" + compact.drop(1)
        }
        if (compact.matches(Regex("^\\+[1-9][0-9]{7,14}$"))) {
            return compact
        }
        return null
    }

    internal fun messageFromPayload(payload: String): String {
        val json = JSONObject(payload)
        val event = json.optString("event")
        val device = json.optString("device_name", "Restaurant").take(60)
        val battery = if (json.isNull("battery_percent")) null else json.optInt("battery_percent")
        val timestamp = json.optString("timestamp_utc").take(19)

        val label = when (event) {
            "power_lost" -> "COUPURE SECTEUR"
            "power_restored" -> "COURANT RETABLI"
            else -> "ALERTE"
        }

        return buildString {
            append("PowerWatch ")
            append(label)
            append(" - ")
            append(device)
            battery?.let {
                append(" - batterie ")
                append(it)
                append("%")
            }
            if (timestamp.isNotBlank()) {
                append(" - ")
                append(timestamp)
            }
        }
    }

    internal fun handled(result: SmsFallbackResult): Boolean =
        result == SmsFallbackResult.SUBMITTED ||
            result == SmsFallbackResult.ALREADY_SUBMITTED

    internal fun statusLabel(result: SmsFallbackResult): String = when (result) {
        SmsFallbackResult.DISABLED -> "fallback SMS désactivé"
        SmsFallbackResult.NOT_CONFIGURED -> "numéro fallback non configuré"
        SmsFallbackResult.PERMISSION_MISSING -> "permission SMS manquante"
        SmsFallbackResult.ALREADY_SUBMITTED -> "SMS fallback déjà remis à Android"
        SmsFallbackResult.SUBMITTED -> "SMS fallback remis à Android"
        SmsFallbackResult.FAILED -> "échec du fallback SMS"
    }

    @Synchronized
    fun submitIfEnabled(
        context: Context,
        eventId: String,
        payload: String
    ): SmsFallbackResult {
        if (!MonitorPrefs.fallbackSmsEnabled(context)) {
            return SmsFallbackResult.DISABLED
        }

        val recipient = normalizeRecipient(MonitorPrefs.fallbackSmsNumber(context))
            ?: return SmsFallbackResult.NOT_CONFIGURED

        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return SmsFallbackResult.PERMISSION_MISSING
        }

        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val submitted = preferences.getStringSet(KEY_SUBMITTED_EVENT_IDS, emptySet())
            ?.toMutableSet()
            ?: mutableSetOf()

        if (eventId in submitted) {
            return SmsFallbackResult.ALREADY_SUBMITTED
        }

        return try {
            @Suppress("DEPRECATION")
            val manager = SmsManager.getDefault()
            val message = messageFromPayload(payload)
            val parts = manager.divideMessage(message)
            if (parts.size <= 1) {
                manager.sendTextMessage(recipient, null, message, null, null)
            } else {
                manager.sendMultipartTextMessage(recipient, null, parts, null, null)
            }

            submitted.add(eventId)
            check(
                preferences.edit()
                    .putStringSet(KEY_SUBMITTED_EVENT_IDS, submitted)
                    .commit()
            ) {
                "Unable to persist submitted PowerWatch SMS fallback"
            }
            SmsFallbackResult.SUBMITTED
        } catch (_: Exception) {
            SmsFallbackResult.FAILED
        }
    }
}
