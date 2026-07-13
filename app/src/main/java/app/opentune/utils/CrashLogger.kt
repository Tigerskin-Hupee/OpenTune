/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.utils

import android.content.Context
import android.os.Build
import app.opentune.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions to a local file so users can copy the crash
 * reason from Settings → About → Crash Log after the app restarts.
 *
 * The handler chains to the previously installed default handler, so normal
 * crash behaviour (process kill, system dialog) is unchanged.
 */
object CrashLogger {
    private const val FILE_NAME = "crash_log.txt"
    private const val MAX_LOG_BYTES = 64 * 1024  // keep newest crashes, cap file size

    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, FILE_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(thread, throwable)
            } catch (_: Throwable) {
                // never let the logger itself break crash handling
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        val file = logFile ?: return
        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = buildString {
            appendLine("=== CRASH $time ===")
            appendLine("App    : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device : ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.SDK_INT}")
            appendLine("Thread : ${thread.name}")
            appendLine(stackTrace)
        }
        // Newest crash first; truncate so the file never grows unbounded.
        val existing = if (file.exists()) file.readText() else ""
        file.writeText((entry + "\n" + existing).take(MAX_LOG_BYTES))
    }

    /**
     * Record a caught (non-fatal) exception to the same log, so background
     * errors that no longer crash the app remain visible in Settings → About.
     */
    fun logCaught(tag: String, throwable: Throwable) {
        try {
            val file = logFile ?: return
            val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val entry = buildString {
                appendLine("=== NON-FATAL $time ($tag) ===")
                appendLine("App    : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine(stackTrace)
            }
            val existing = if (file.exists()) file.readText() else ""
            file.writeText((entry + "\n" + existing).take(MAX_LOG_BYTES))
        } catch (_: Throwable) {
            // never let the logger itself cause problems
        }
    }

    /** Full crash log, or null if no crash has been recorded. */
    fun getReport(): String? =
        logFile?.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear() {
        logFile?.delete()
    }
}
