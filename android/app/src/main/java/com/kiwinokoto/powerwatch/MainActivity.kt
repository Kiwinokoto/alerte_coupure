package com.kiwinokoto.powerwatch

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private enum class StatusTone { OK, WARNING, DANGER, NEUTRAL }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var armedText: TextView
    private lateinit var powerText: TextView
    private lateinit var batteryText: TextView
    private lateinit var optimizationText: TextView
    private lateinit var networkText: TextView
    private lateinit var backendText: TextView
    private lateinit var backendContactText: TextView
    private lateinit var communicationText: TextView
    private lateinit var deliveryText: TextView
    private lateinit var eventsText: TextView
    private lateinit var deviceNameInput: EditText
    private lateinit var webhookInput: EditText
    private lateinit var webhookTokenInput: EditText
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
        WebhookClient.refreshServerStatusAsync(this)
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

        root.addView(TextView(this).apply {
            text = "PowerWatch"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Transforme ce téléphone en moniteur de coupure secteur."
            textSize = 16f
            setPadding(0, dp(4), 0, dp(20))
        })

        armedText = statusLine().apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        powerText = statusLine()
        batteryText = statusLine()
        optimizationText = statusLine()

        root.addView(armedText)
        root.addView(powerText)
        root.addView(batteryText)
        root.addView(optimizationText)

        root.addView(sectionTitle("Alertes", dp(18)))

        networkText = statusLine()
        backendText = statusLine()
        backendContactText = statusLine()
        communicationText = statusLine()
        deliveryText = statusLine()
        root.addView(networkText)
        root.addView(backendText)
        root.addView(backendContactText)
        root.addView(communicationText)
        root.addView(deliveryText)

        root.addView(Button(this).apply {
            text = "Actualiser le canal d’alerte"
            setOnClickListener {
                if (!saveSettings()) return@setOnClickListener
                WebhookClient.refreshServerStatusAsync(this@MainActivity)
                toast("Vérification du canal d’alerte lancée.")
            }
        }, matchWidth())

        root.addView(TextView(this).apply {
            text = "Le téléphone peut consulter le canal configuré, mais ne peut pas modifier les destinataires du serveur dans cette V1."
            setPadding(0, dp(4), 0, dp(10))
        })

        root.addView(sectionTitle("Configuration technique", dp(18)))

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

        webhookTokenInput = EditText(this).apply {
            hint = "Token du serveur (optionnel)"
            setText(MonitorPrefs.webhookToken(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(webhookTokenInput, matchWidth())

        root.addView(Button(this).apply {
            text = "Enregistrer la configuration"
            setOnClickListener {
                if (saveSettings()) {
                    WebhookClient.refreshServerStatusAsync(this@MainActivity)
                    toast("Configuration enregistrée.")
                }
            }
        }, matchWidth())

        root.addView(Button(this).apply {
            text = "Ouvrir les réglages d’optimisation batterie"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }, matchWidth())

        if (BuildConfig.DEBUG) {
            root.addView(sectionTitle("Diagnostic Android", dp(18)))
            root.addView(TextView(this).apply {
                text = "Outils de diagnostic présents uniquement dans les builds debug."
                setPadding(0, 0, 0, dp(6))
            })
            root.addView(Button(this).apply {
                text = "Ouvrir les options développeur"
                setOnClickListener {
                    openDeveloperSettings()
                }
            }, matchWidth())
        }

        root.addView(sectionTitle("Surveillance", dp(18)))

        toggleButton = Button(this).apply {
            textSize = 18f
            setOnClickListener {
                if (MonitorPrefs.isArmed(this@MainActivity)) {
                    confirmDisarmAndQuit()
                } else if (saveSettings()) {
                    startMonitor()
                    toast("Surveillance activée. La notification PowerWatch doit rester visible.")
                }
            }
        }
        root.addView(toggleButton, matchWidth())

        root.addView(Button(this).apply {
            text = "Tester l’alerte serveur"
            setOnClickListener {
                if (!saveSettings()) return@setOnClickListener
                if (MonitorPrefs.webhookUrl(this@MainActivity).isBlank()) {
                    toast("Configure d’abord le serveur PowerWatch.")
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
        }, matchWidth())

        root.addView(TextView(this).apply {
            text = "Quand la surveillance est active, une notification persistante confirme que PowerWatch fonctionne même écran éteint."
            setPadding(0, dp(10), 0, dp(10))
        })

        root.addView(sectionTitle("Événements récents", dp(18)))

        eventsText = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(eventsText, matchWidth())

        setContentView(ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        })
    }

    private fun statusLine(): TextView =
        TextView(this).apply {
            val horizontal = (10 * resources.displayMetrics.density).toInt()
            val vertical = (6 * resources.displayMetrics.density).toInt()
            textSize = 16f
            setPadding(horizontal, vertical, horizontal, vertical)
        }

    private fun applyStatusTone(view: TextView, tone: StatusTone) {
        val background = when (tone) {
            StatusTone.OK -> Color.rgb(226, 242, 226)
            StatusTone.WARNING -> Color.rgb(255, 244, 204)
            StatusTone.DANGER -> Color.rgb(255, 224, 224)
            StatusTone.NEUTRAL -> Color.rgb(238, 238, 238)
        }
        view.setBackgroundColor(background)
        view.setTextColor(Color.rgb(32, 32, 32))
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
            val since = formatArmedSince(MonitorPrefs.armedSince(this))
            if (since == null) {
                "✓ SURVEILLANCE ACTIVE"
            } else {
                "✓ SURVEILLANCE ACTIVE depuis $since"
            }
        } else {
            "Surveillance arrêtée"
        }
        applyStatusTone(armedText, if (armed) StatusTone.OK else StatusTone.NEUTRAL)

        powerText.text = "Secteur : " + when (snapshot.externalPower) {
            true -> "présent"
            false -> "ABSENT"
            null -> "inconnu"
        }
        applyStatusTone(
            powerText,
            when (snapshot.externalPower) {
                true -> StatusTone.OK
                false -> StatusTone.DANGER
                null -> StatusTone.NEUTRAL
            }
        )

        val battery = snapshot.batteryPercent
        batteryText.text = "Batterie : " + (battery?.let { "$it %" } ?: "inconnue")
        applyStatusTone(
            batteryText,
            when {
                battery == null -> StatusTone.NEUTRAL
                battery < 15 -> StatusTone.DANGER
                battery < 30 -> StatusTone.WARNING
                else -> StatusTone.OK
            }
        )

        optimizationText.text = if (unrestricted) {
            "Optimisation batterie : non restrictive"
        } else {
            "Optimisation batterie : Android peut appliquer des restrictions"
        }
        applyStatusTone(optimizationText, if (unrestricted) StatusTone.OK else StatusTone.WARNING)

        val network = networkStatus()
        networkText.text = network.first
        applyStatusTone(networkText, network.second)

        val backendHealth = MonitorPrefs.backendHealth(this)
        backendText.text = backendStatus(backendHealth)
        applyStatusTone(
            backendText,
            when (backendHealth) {
                BackendHealth.OK -> StatusTone.OK
                BackendHealth.ERROR, BackendHealth.UNREACHABLE -> StatusTone.WARNING
                BackendHealth.UNKNOWN -> StatusTone.NEUTRAL
            }
        )

        val backendContact = formatBackendContact(MonitorPrefs.backendLastContact(this))
        backendContactText.text = "Dernier contact backend : " + (backendContact ?: "jamais")
        applyStatusTone(
            backendContactText,
            if (backendContact != null && backendHealth == BackendHealth.OK) {
                StatusTone.OK
            } else {
                StatusTone.NEUTRAL
            }
        )

        communicationText.text = MonitorPrefs.remoteAlertSummary(this)
        applyStatusTone(communicationText, StatusTone.NEUTRAL)
        deliveryText.text = "Dernier envoi : " + MonitorPrefs.lastDelivery(this)
        applyStatusTone(deliveryText, StatusTone.NEUTRAL)
        eventsText.text = EventLog.recent(this)
        toggleButton.text = if (armed) {
            "Désarmer et quitter"
        } else {
            "Activer la surveillance"
        }
    }

    private fun formatArmedSince(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            Instant.parse(value)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        }.getOrNull()
    }

    private fun networkStatus(): Pair<String, StatusTone> {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
            ?: return "Réseau : indisponible" to StatusTone.WARNING
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return "Réseau : état inconnu" to StatusTone.NEUTRAL
        return when {
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                "Réseau : connecté sans accès Internet" to StatusTone.WARNING
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
                "Réseau : Internet disponible" to StatusTone.OK
            else -> "Réseau : connecté · Internet non validé" to StatusTone.WARNING
        }
    }

    private fun backendStatus(health: BackendHealth): String {
        if (MonitorPrefs.webhookUrl(this).isBlank()) return "Backend/VPS : non configuré"
        return when (health) {
            BackendHealth.UNKNOWN -> "Backend/VPS : état inconnu"
            BackendHealth.OK -> "Backend/VPS : joignable"
            BackendHealth.ERROR -> "Backend/VPS : joignable mais en erreur"
            BackendHealth.UNREACHABLE -> "Backend/VPS : INJOIGNABLE"
        }
    }

    private fun formatBackendContact(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            Instant.parse(value)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd/MM HH:mm:ss"))
        }.getOrNull()
    }

    private fun saveSettings(): Boolean {
        val name = deviceNameInput.text.toString().trim().ifBlank { "Restaurant" }
        val webhook = webhookInput.text.toString().trim()
        val token = webhookTokenInput.text.toString().trim()

        if (webhook.isNotBlank() && !webhook.startsWith("https://")) {
            toast("La V1 n’accepte que les webhooks HTTPS.")
            return false
        }

        MonitorPrefs.setDeviceName(this, name)
        MonitorPrefs.setWebhookUrl(this, webhook)
        MonitorPrefs.setWebhookToken(this, token)
        return true
    }

    private fun confirmDisarmAndQuit() {
        AlertDialog.Builder(this)
            .setTitle("Désarmer PowerWatch ?")
            .setMessage(
                "La surveillance des coupures s’arrêtera complètement. " +
                    "Fermer ou balayer simplement l’application ne l’arrête pas."
            )
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Désarmer et quitter") { _, _ ->
                stopMonitor()
                toast("Surveillance désactivée.")
                finishAndRemoveTask()
            }
            .show()
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
        startService(
            Intent(this, MonitorService::class.java)
                .setAction(MonitorService.ACTION_STOP)
        )
    }

    private fun openDeveloperSettings() {
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }.isSuccess

        if (!opened) {
            toast("Android refuse d’ouvrir directement les options développeur.")
        }
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
