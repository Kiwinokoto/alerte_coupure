package com.kiwinokoto.powerwatch

import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

object WebhookClient {
    private const val CRITICAL_DELIVERY_WAKE_LOCK_MS = 90_000L
    private val executor = Executors.newSingleThreadExecutor()

    internal fun isCriticalPowerEvent(eventType: String): Boolean =
        eventType == "power_lost" || eventType == "power_restored"

    internal fun retryDelaysFor(eventType: String): LongArray =
        if (isCriticalPowerEvent(eventType)) longArrayOf(0L, 5_000L, 15_000L) else longArrayOf(0L)

    internal fun criticalEventTypeFromPayload(payload: String): String? =
        runCatching { JSONObject(payload).optString("event") }
            .getOrNull()
            ?.takeIf(::isCriticalPowerEvent)

    internal fun replayOrder(
        pending: List<PendingEvent>,
        currentEventId: String
    ): List<PendingEvent> {
        val candidates = pending.filterNot { it.eventId == currentEventId }
        val (critical, regular) = candidates.partition {
            criticalEventTypeFromPayload(it.payload) != null
        }
        return critical + regular
    }

    private fun acquireCriticalDeliveryWakeLock(context: Context): PowerManager.WakeLock {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PowerWatch:critical-delivery"
        ).apply {
            setReferenceCounted(false)
            acquire(CRITICAL_DELIVERY_WAKE_LOCK_MS)
        }
    }

    fun sendAsync(
        context: Context,
        eventType: String,
        snapshot: PowerSnapshot,
        reason: String
    ) {
        val appContext = context.applicationContext
        val eventId = UUID.randomUUID().toString()
        val eventTimestamp = Instant.now().toString()
        val payload = buildPayload(appContext, eventType, snapshot, reason, eventId, eventTimestamp)
        val persistent = eventType != "heartbeat"
        val criticalFallback = isCriticalPowerEvent(eventType)
        if (persistent) EventOutbox.enqueue(appContext, eventId, payload)

        val url = MonitorPrefs.webhookUrl(appContext)
        if (url.isBlank()) {
            if (criticalFallback) FallbackTracker.markEligible(appContext, eventId, eventType)
            MonitorPrefs.setLastDelivery(appContext, "Webhook non configuré · événement conservé localement")
            return
        }

        val deliveryWakeLock = if (criticalFallback) {
            acquireCriticalDeliveryWakeLock(appContext)
        } else {
            null
        }

        executor.execute {
            try {
                replayOutbox(appContext, url, eventId)

                val retries = retryDelaysFor(eventType)
                var lastError = "échec inconnu"

                for ((attempt, delay) in retries.withIndex()) {
                    if (delay > 0) Thread.sleep(delay)
                    try {
                        val code = post(appContext, url, payload)
                        if (code in 200..299) {
                            if (persistent) EventOutbox.remove(appContext, eventId)
                            FallbackTracker.clear(appContext, eventId)
                            MonitorPrefs.setLastDelivery(
                                appContext,
                                "${Instant.now()} · $eventType · HTTP $code"
                            )
                            return@execute
                        }
                        lastError = "HTTP $code"
                    } catch (error: Exception) {
                        lastError = error.javaClass.simpleName + ": " + (error.message ?: "")
                    }
                }

                if (criticalFallback) {
                    FallbackTracker.markEligible(appContext, eventId, eventType)
                }
                MonitorPrefs.setLastDelivery(
                    appContext,
                    "${Instant.now()} · $eventType · ÉCHEC · $lastError"
                )
            } finally {
                deliveryWakeLock?.let { wakeLock ->
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
        }
    }

    fun refreshServerStatusAsync(context: Context) {
        val appContext = context.applicationContext
        val eventsUrl = MonitorPrefs.webhookUrl(appContext)
        val statusUrl = statusEndpoint(eventsUrl)

        if (statusUrl == null) {
            MonitorPrefs.setRemoteAlertSummary(
                appContext,
                if (eventsUrl.isBlank()) {
                    "Aucune alerte distante configurée"
                } else {
                    "Canal d’alerte : endpoint personnalisé, état non disponible"
                }
            )
            return
        }

        executor.execute {
            try {
                val connection = (URL(statusUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "PowerWatch/0.1.0")
                    MonitorPrefs.webhookToken(appContext).takeIf { it.isNotBlank() }?.let {
                        setRequestProperty("X-PowerWatch-Token", it)
                    }
                }

                try {
                    val code = connection.responseCode
                    MonitorPrefs.recordBackendHttpResult(appContext, code)
                    if (code !in 200..299) {
                        MonitorPrefs.setRemoteAlertSummary(
                            appContext,
                            "Canal d’alerte : serveur joignable mais configuration inaccessible (HTTP $code)"
                        )
                        return@execute
                    }

                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    MonitorPrefs.setRemoteAlertSummary(
                        appContext,
                        json.optString(
                            "alert_summary",
                            "Canal d’alerte : réponse serveur incomplète"
                        )
                    )
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                MonitorPrefs.markBackendUnreachable(appContext)
                MonitorPrefs.setRemoteAlertSummary(
                    appContext,
                    "Canal d’alerte : serveur temporairement injoignable"
                )
            }
        }
    }

    private fun statusEndpoint(eventsEndpoint: String): String? {
        if (eventsEndpoint.isBlank()) return null
        val suffix = "/api/v1/events"
        if (!eventsEndpoint.endsWith(suffix)) return null
        return eventsEndpoint.removeSuffix(suffix) + "/api/v1/status"
    }

    private fun buildPayload(
        context: Context,
        eventType: String,
        snapshot: PowerSnapshot,
        reason: String,
        eventId: String,
        eventTimestamp: String
    ): String = JSONObject().apply {
        put("schema_version", 1)
        put("event", eventType)
        put("event_id", eventId)
        put("timestamp_utc", eventTimestamp)
        put("device_name", MonitorPrefs.deviceName(context))
        put("installation_id", MonitorPrefs.installationId(context))
        put("external_power", snapshot.externalPower ?: JSONObject.NULL)
        put("battery_percent", snapshot.batteryPercent ?: JSONObject.NULL)
        put("reason", reason)
        put("android_sdk", Build.VERSION.SDK_INT)
    }.toString()

    private fun replayOutbox(context: Context, endpoint: String, currentEventId: String) {
        for (event in replayOrder(EventOutbox.pending(context), currentEventId)) {
            val criticalEventType = criticalEventTypeFromPayload(event.payload)
            try {
                if (post(context, endpoint, event.payload) in 200..299) {
                    EventOutbox.remove(context, event.eventId)
                    FallbackTracker.clear(context, event.eventId)
                } else {
                    criticalEventType?.let {
                        FallbackTracker.markEligible(context, event.eventId, it)
                    }
                    return
                }
            } catch (_: Exception) {
                criticalEventType?.let {
                    FallbackTracker.markEligible(context, event.eventId, it)
                }
                return
            }
        }
    }

    private fun post(context: Context, endpoint: String, payload: String): Int {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PowerWatch/0.1.0")
            MonitorPrefs.webhookToken(context).takeIf { it.isNotBlank() }?.let {
                setRequestProperty("X-PowerWatch-Token", it)
            }
        }

        try {
            connection.outputStream.use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            MonitorPrefs.recordBackendHttpResult(context, code)
            return code
        } catch (error: Exception) {
            MonitorPrefs.markBackendUnreachable(context)
            throw error
        } finally {
            connection.disconnect()
        }
    }
}
