package app.opentune.utils

import android.content.Context
import android.os.Build
import app.opentune.BuildConfig
import app.opentune.utils.potoken.OpenTunePoTokenProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsLogger {

    data class StreamEvent(
        val timestampMs: Long,
        val videoId: String,
        val success: Boolean,
        val durationMs: Long,
        val client: String? = null,
        val urlHint: String? = null,
        val error: String? = null,
    )

    data class PlaybackEvent(
        val timestampMs: Long,
        val videoId: String,
        val errorCode: Int,
        val message: String,
    )

    private val streamEvents = ArrayDeque<StreamEvent>()
    private val playbackEvents = ArrayDeque<PlaybackEvent>()
    private const val MAX_EVENTS = 20

    @Synchronized
    fun logStream(
        videoId: String,
        success: Boolean,
        durationMs: Long,
        error: String? = null,
        client: String? = null,
        urlHint: String? = null,
    ) {
        if (streamEvents.size >= MAX_EVENTS) streamEvents.removeFirst()
        streamEvents.addLast(StreamEvent(System.currentTimeMillis(), videoId, success, durationMs, client, urlHint, error))
    }

    @Synchronized
    fun logPlayback(videoId: String, errorCode: Int, message: String) {
        if (playbackEvents.size >= MAX_EVENTS) playbackEvents.removeFirst()
        playbackEvents.addLast(PlaybackEvent(System.currentTimeMillis(), videoId, errorCode, message))
    }

    @Synchronized
    fun getReport(context: Context): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()

        sb.appendLine("=== OpenTune Diagnostics ===")
        sb.appendLine("App    : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        sb.appendLine("Device : ${Build.BRAND} ${Build.MODEL} (Android ${Build.VERSION.SDK_INT})")
        sb.appendLine("Time   : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        val poErr = OpenTunePoTokenProvider.lastError
        val keyHint = app.opentune.utils.potoken.PoTokenWebView.lastRequestKeyHint
        val playerJsId = app.opentune.utils.potoken.PoTokenWebView.lastPlayerJsId
        sb.appendLine("PoToken: ${when (poErr) { null -> "OK"; "not_called" -> "not_called"; else -> "FAIL($poErr)" }} key=$keyHint player=$playerJsId")
        sb.appendLine("nDecode: ${OpenTunePoTokenProvider.nDecodeStatus}")
        sb.appendLine()

        sb.appendLine("--- Stream Resolution (last ${streamEvents.size}) ---")
        if (streamEvents.isEmpty()) {
            sb.appendLine("(no events yet)")
        } else {
            streamEvents.asReversed().forEach { e ->
                val status = if (e.success) "OK  " else "FAIL"
                val time = sdf.format(Date(e.timestampMs))
                val clientTag = e.client?.let { " [$it]" } ?: ""
                val urlTag = e.urlHint?.let { " $it" } ?: ""
                sb.append("[$time] $status ${e.videoId} ${e.durationMs}ms$clientTag$urlTag")
                if (e.error != null) sb.append("  ← ${e.error}")
                sb.appendLine()
            }
        }

        val failCount = streamEvents.count { !it.success }
        val okCount = streamEvents.count { it.success }
        val avgMs = if (okCount > 0) streamEvents.filter { it.success }.map { it.durationMs }.average().toLong() else 0L
        sb.appendLine("OK: $okCount  FAIL: $failCount  avg: ${avgMs}ms")

        if (playbackEvents.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("--- Playback Errors (last ${playbackEvents.size}) ---")
            playbackEvents.asReversed().forEach { e ->
                val time = sdf.format(Date(e.timestampMs))
                sb.appendLine("[$time] ERR ${e.videoId} code=${e.errorCode} ${e.message.take(140)}")
            }
        }

        return sb.toString()
    }

    @Synchronized
    fun clear() {
        streamEvents.clear()
        playbackEvents.clear()
    }
}
