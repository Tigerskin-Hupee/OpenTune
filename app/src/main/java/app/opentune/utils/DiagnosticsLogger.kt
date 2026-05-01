package app.opentune.utils

import android.content.Context
import android.os.Build
import app.opentune.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsLogger {

    data class StreamEvent(
        val timestampMs: Long,
        val videoId: String,
        val success: Boolean,
        val durationMs: Long,
        val error: String? = null,
    )

    private val events = ArrayDeque<StreamEvent>()
    private const val MAX_EVENTS = 20

    @Synchronized
    fun logStream(videoId: String, success: Boolean, durationMs: Long, error: String? = null) {
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast(StreamEvent(System.currentTimeMillis(), videoId, success, durationMs, error))
    }

    @Synchronized
    fun getReport(context: Context): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()

        sb.appendLine("=== OpenTune Diagnostics ===")
        sb.appendLine("App    : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        sb.appendLine("Device : ${Build.BRAND} ${Build.MODEL} (Android ${Build.VERSION.SDK_INT})")
        sb.appendLine("Time   : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine()

        sb.appendLine("--- Stream Resolution Log (last ${events.size}) ---")
        if (events.isEmpty()) {
            sb.appendLine("(no events yet)")
        } else {
            events.asReversed().forEach { e ->
                val status = if (e.success) "OK   " else "FAIL "
                val time = sdf.format(Date(e.timestampMs))
                sb.append("[$time] $status ${e.videoId} ${e.durationMs}ms")
                if (e.error != null) sb.append("  ← ${e.error}")
                sb.appendLine()
            }
        }

        val failCount = events.count { !it.success }
        val okCount = events.count { it.success }
        val avgMs = if (okCount > 0) events.filter { it.success }.map { it.durationMs }.average().toLong() else 0L
        sb.appendLine()
        sb.appendLine("OK: $okCount  FAIL: $failCount  avg: ${avgMs}ms")

        return sb.toString()
    }

    @Synchronized
    fun clear() = events.clear()
}
