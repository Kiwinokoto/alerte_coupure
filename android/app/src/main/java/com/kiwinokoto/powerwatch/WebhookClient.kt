package com.kiwinokoto.powerwatch

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors

object WebhookClient {
    private val executor = Executors.newSingleThreadExecutor()

    fun sendAsync(
        context: Context,
        eventType: String,
        snapshot: PowerSnapshot,
        reason: String
    ) {
        val appContext = context.applicationContext
        val url = MonitorPrefs.webhookUrl(appContext)
        if (url.isBlank()) {
            MonitorPrefs.setLastDelivery(appContext, "Webhook non configuré")
            return
        }

        executor.execute {
            val retries = if (eventType == "heartbeat") {
                longArrayOf(0L)
            } else {
                longArrayOf(0L, 5_000L, 15_000L)
            }

            var lastError = "échec inconnu"
            for (delay in retries) {
                if (delay > 0) Thread.sleep(delay)
                try {
                    val code = post(appContext, url, eventType, snapshot, reason)
                    if (code in 200..299) {
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

            MonitorPrefs.setLastDelivery(
                appContext,
                "${Instant.now()} · $eventType · ÉCHEC · $lastError"
            )
        }
    }

    private fun post(
        context: Context,
        endpoint: String,
        eventType: String,
        snapshot: PowerSnapshot,
        reason: String
    ): Int {
        val payload = JSONObject().apply {
            put("schema_version", 1)
            put("event", eventType)
            put("timestamp_utc", Instant.now().toString())
            put("device_name", MonitorPrefs.deviceName(context))
            put("installation_id", MonitorPrefs.installationId(context))
            put("external_power", snapshot.externalPower ?: JSONObject.NULL)
            put("battery_percent", snapshot.batteryPercent ?: JSONObject.NULL)
            put("reason", reason)
            put("android_sdk", Build.VERSION.SDK_INT)
        }.toString()

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
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
