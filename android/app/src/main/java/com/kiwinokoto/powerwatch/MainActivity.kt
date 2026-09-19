package com.kiwinokoto.powerwatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var armedText: TextView
    private lateinit var powerText: TextView
    private lateinit var batteryText: TextView
    private lateinit var optimizationText: TextView
    private lateinit var deliveryText: TextView
    private lateinit var eventsText: TextView
    private lateinit var deviceNameInput: EditText
    private lateinit var webhookInput: EditText
    private lateinit var toggleButton: Button

    private val refreshUi = object : Runnable {
        override fun run() {
            renderState()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshUi)
        handler.post(refreshUi)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshUi)
        super.onPause()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        val title = TextView(this).apply {
            text = "PowerWatch"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)

        root.addView(TextView(this).apply {
            text = "Transforme ce téléphone en moniteur de coupure secteur."
            textSize = 16f
            setPadding(0, dp(4), 0, dp(20))
        })

        armedText = statusLine()
        powerText = statusLine()
        batteryText = statusLine()
        optimizationText = statusLine()
        deliveryText = statusLine()

        root.addView(armedText)
        root.addView(powerText)
        root.addView(batteryText)
        root.addView(optimizationText)
        root.addView(deliveryText)

        root.addView(sectionTitle("Configuration", dp(18)))

        deviceNameInput = EditText(this).apply {
            hint = "Nom du site (ex. Restaurant République)"
            setText(MonitorPrefs.deviceName(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(deviceNameInput, matchWidth())

        webhookInput = EditText(this).apply {
            hint = "Webhook HTTPS (optionnel)"
            setText(MonitorPrefs.webhookUrl(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(webhookInput, matchWidth())

        val saveButton = Button(this).apply {
            text = "Enregistrer"
            setOnClickListener {
                if (saveSettings()) {
                    toast("Configuration enregistrée.")
                }
            }
        }
        root.addView(saveButton, matchWidth())

        val batterySettingsButton = Button(this).apply {
            text = "Ouvrir les réglages d’optimisation batterie"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        root.addView(batterySettingsButton, matchWidth())

        root.addView(sectionTitle("Surveillance", dp(18)))

        toggleButton = Button(this).apply {
            setOnClickListener {
                if (MonitorPrefs.isArmed(this@MainActivity)) {
                    stopMonitor()
                } else if (saveSettings()) {
                    startMonitor()
                }
            }
        }
        root.addView(toggleButton, matchWidth())

        val testButton = Button(this).apply {
            text = "Tester le webhook"
            setOnClickListener {
                if (!saveSettings()) return@setOnClickListener
                if (MonitorPrefs.webhookUrl(this@MainActivity).isBlank()) {
                    toast("Configure d’abord un webhook HTTPS.")
                    return@setOnClickListener
                }
                WebhookClient.sendAsync(
                    this@MainActivity,
                    "test",
                    PowerSnapshot.read(this@MainActivity),
                    "manual_test"
                )
                toast("Test lancé.")
            }
        }
        root.addView(testButton, matchWidth())

        root.addView(TextView(this).apply {
            text = "La surveillance utilise un service Android de premier plan. Une notification persistante doit rester visible lorsqu’elle est armée."
            setPadding(0, dp(10), 0, dp(10))
        })

        root.addView(sectionTitle("Événements récents", dp(18)))

        eventsText = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(eventsText, matchWidth())

        val scroll = ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(scroll)
    }

    private fun statusLine(): TextView =
        TextView(this).apply {
            textSize = 16f
            setPadding(0, 5, 0, 5)
        }

    private fun sectionTitle(label: String, topPadding: Int): TextView =
        TextView(this).apply {
            text = label
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, topPadding, 0, 8)
        }

    private fun matchWidth() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun renderState() {
        val armed = MonitorPrefs.isArmed(this)
        val snapshot = PowerSnapshot.read(this)
        val powerManager = getSystemService(PowerManager::class.java)
        val unrestricted = powerManager.isIgnoringBatteryOptimizations(packageName)

        armedText.text = if (armed) {
            "Surveillance : ACTIVE"
        } else {
            "Surveillance : arrêtée"
        }

        powerText.text = "Secteur : " + when (snapshot.externalPower) {
            true -> "présent"
            false -> "ABSENT"
            null -> "inconnu"
        }
        batteryText.text = "Batterie : " + (snapshot.batteryPercent?.let { "$it %" } ?: "inconnue")
        optimizationText.text = if (unrestricted) {
            "Optimisation batterie : non restrictive"
        } else {
            "Optimisation batterie : Android peut appliquer des restrictions"
        }
        deliveryText.text = "Dernier envoi : " + MonitorPrefs.lastDelivery(this)
        eventsText.text = EventLog.recent(this)
        toggleButton.text = if (armed) "Désactiver la surveillance" else "Activer la surveillance"
    }

    private fun saveSettings(): Boolean {
        val name = deviceNameInput.text.toString().trim().ifBlank { "Restaurant" }
        val webhook = webhookInput.text.toString().trim()

        if (webhook.isNotBlank() && !webhook.startsWith("https://")) {
            toast("La V1 n’accepte que les webhooks HTTPS.")
            return false
        }

        MonitorPrefs.setDeviceName(this, name)
        MonitorPrefs.setWebhookUrl(this, webhook)
        return true
    }

    private fun startMonitor() {
        val intent = Intent(this, MonitorService::class.java)
            .setAction(MonitorService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopMonitor() {
        val intent = Intent(this, MonitorService::class.java)
            .setAction(MonitorService.ACTION_STOP)
        startService(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
