package com.kiwinokoto.powerwatch

import android.content.Context
import java.time.Instant

object EventLog {
    private const val FILE = "events.log"

    @Synchronized
    fun append(context: Context, type: String, message: String) {
        val clean = message.replace("\n", " ").replace("\r", " ")
        val line = "${Instant.now()}\t$type\t$clean\n"
        context.openFileOutput(FILE, Context.MODE_APPEND).bufferedWriter().use {
            it.write(line)
        }
        MonitorPrefs.setLastEvent(context, line.trim())
    }

    @Synchronized
    fun recent(context: Context, limit: Int = 20): String {
        val file = context.getFileStreamPath(FILE)
        if (!file.isFile) return "Aucun événement."
        return file.readLines()
            .takeLast(limit.coerceAtLeast(1))
            .joinToString("\n")
            .ifBlank { "Aucun événement." }
    }
}
