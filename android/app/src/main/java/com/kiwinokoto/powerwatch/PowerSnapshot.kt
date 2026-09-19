package com.kiwinokoto.powerwatch

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class PowerSnapshot(
    val externalPower: Boolean?,
    val batteryPercent: Int?
) {
    companion object {
        fun read(context: Context): PowerSnapshot {
            val battery = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return PowerSnapshot(null, null)

            val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val external = when {
                plugged < 0 -> null
                plugged == 0 -> false
                else -> true
            }

            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) {
                ((level * 100f) / scale).toInt().coerceIn(0, 100)
            } else {
                null
            }

            return PowerSnapshot(external, percent)
        }
    }
}
