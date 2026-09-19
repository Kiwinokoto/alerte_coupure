package com.kiwinokoto.powerwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class MonitorService : Service() {
    companion object {
        const val ACTION_START = "com.kiwinokoto.powerwatch.START"
        const val ACTION_STOP = "com.kiwinokoto.powerwatch.STOP"

        private const val CHANNEL_ID = "powerwatch-monitor"
        private const val NOTIFICATION_ID = 1001
        private const val DEBOUNCE_MS = 15_000L
        private const val STATE_CHECK_MS = 30_000L
        private const val HEARTBEAT_MS = 120_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var stablePower: Boolean? = null
    private var pendingCandidate: Boolean? = null
    private var pendingTransition: Runnable? = null
    private var receiverRegistered = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val candidate = when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> true
                Intent.ACTION_POWER_DISCONNECTED -> false
                else -> return
            }
            acquireTransitionWakeLock()
            considerPowerCandidate(candidate, "broadcast:${intent.action}")
        }
    }

    private val stateCheck = object : Runnable {
        override fun run() {
            if (!MonitorPrefs.isArmed(this@MonitorService)) return
            val snapshot = PowerSnapshot.read(this@MonitorService)
            snapshot.externalPower?.let {
                considerPowerCandidate(it, "periodic_check")
            }
            refreshNotification(snapshot)
            handler.postDelayed(this, STATE_CHECK_MS)
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!MonitorPrefs.isArmed(this@MonitorService)) return
            val snapshot = PowerSnapshot.read(this@MonitorService)
            WebhookClient.sendAsync(
                this@MonitorService,
                "heartbeat",
                snapshot,
                "foreground_service"
            )
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(powerReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        if (intent == null && !MonitorPrefs.isArmed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        MonitorPrefs.setArmed(this, true)
        val snapshot = PowerSnapshot.read(this)

        stablePower = if (intent?.action == ACTION_START) {
            snapshot.externalPower
        } else {
            MonitorPrefs.lastExternalPower(this) ?: snapshot.externalPower
        }
        stablePower?.let { MonitorPrefs.setLastExternalPower(this, it) }

        startInForeground(snapshot)

        handler.removeCallbacks(stateCheck)
        handler.removeCallbacks(heartbeat)
        handler.post(stateCheck)
        handler.postDelayed(heartbeat, 5_000L)

        if (intent?.action == ACTION_START) {
            EventLog.append(
                this,
                "MONITORING_STARTED",
                "Surveillance armée · secteur=${snapshot.externalPower} · batterie=${snapshot.batteryPercent}"
            )
            WebhookClient.sendAsync(this, "monitoring_started", snapshot, "user_or_boot")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        pendingTransition?.let(handler::removeCallbacks)
        handler.removeCallbacks(stateCheck)
        handler.removeCallbacks(heartbeat)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(powerReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun considerPowerCandidate(candidate: Boolean, reason: String) {
        pendingTransition?.let(handler::removeCallbacks)
        pendingTransition = null
        pendingCandidate = null

        if (stablePower == candidate) {
            return
        }

        pendingCandidate = candidate
        val runnable = Runnable {
            val expected = pendingCandidate ?: return@Runnable
            val confirmed = PowerSnapshot.read(this).externalPower
            pendingTransition = null
            pendingCandidate = null

            if (confirmed != expected || stablePower == expected) {
                return@Runnable
            }

            stablePower = expected
            MonitorPrefs.setLastExternalPower(this, expected)
            val snapshot = PowerSnapshot.read(this)
            val type = if (expected) "power_restored" else "power_lost"
            val label = if (expected) "COURANT RÉTABLI" else "COUPURE SECTEUR"

            EventLog.append(
                this,
                label,
                "batterie=${snapshot.batteryPercent} · source=$reason · confirmé après ${DEBOUNCE_MS / 1000}s"
            )
            WebhookClient.sendAsync(this, type, snapshot, reason)
            refreshNotification(snapshot)
        }

        pendingTransition = runnable
        handler.postDelayed(runnable, DEBOUNCE_MS)
    }

    private fun stopMonitoring() {
        val snapshot = PowerSnapshot.read(this)
        MonitorPrefs.setArmed(this, false)
        EventLog.append(this, "MONITORING_STOPPED", "Surveillance désarmée")
        WebhookClient.sendAsync(this, "monitoring_stopped", snapshot, "user")
        handler.removeCallbacks(stateCheck)
        handler.removeCallbacks(heartbeat)
        pendingTransition?.let(handler::removeCallbacks)
        pendingTransition = null
        pendingCandidate = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireTransitionWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PowerWatch:transition"
        )
        wakeLock.acquire(45_000L)
    }

    private fun startInForeground(snapshot: PowerSnapshot) {
        val notification = buildNotification(snapshot)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshNotification(snapshot: PowerSnapshot) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    private fun buildNotification(snapshot: PowerSnapshot): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val powerText = when (snapshot.externalPower) {
            true -> "Secteur présent"
            false -> "Sur batterie — secteur absent"
            null -> "État secteur inconnu"
        }
        val batteryText = snapshot.batteryPercent?.let { " · batterie $it %" } ?: ""

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("PowerWatch · surveillance active")
            .setContentText(powerText + batteryText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Désactiver",
                stopIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Surveillance secteur",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Indique que PowerWatch surveille activement l'alimentation secteur."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
