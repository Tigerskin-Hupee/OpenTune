/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.playback

import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves YouTube stream URLs by invoking the embedded yt-dlp Python via
 * [YoutubeDL.execute]. Returns [Result.failure] if the library is not yet
 * initialised so callers can fall back to other resolvers.
 */
@Singleton
class YtDlpHelper @Inject constructor(
    private val manager: YtDlpManager,
) {
    private val tag = "YtDlpHelper"

    val isInstalled: Boolean get() = manager.isReady

    suspend fun getStreamUrl(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            check(isInstalled) { "yt-dlp library not yet initialised" }
            val ytUrl = "https://www.youtube.com/watch?v=$videoId"

            // Primary attempt: request best audio webm/opus
            val primaryRequest = YoutubeDLRequest(ytUrl).apply {
                addOption("--no-playlist")
                addOption("-f", "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio")
                addOption("--get-url")
                addOption("--no-warnings")
                addOption("--socket-timeout", "30")
                addOption("--no-check-certificates")
            }

            val result = runCatching {
                val response = YoutubeDL.getInstance().execute(primaryRequest)
                extractUrl(response.out, videoId)
            }

            if (result.isSuccess) {
                Log.d(tag, "getStreamUrl($videoId) ok (primary) ${result.getOrThrow().take(80)}…")
                return@runCatching result.getOrThrow()
            }

            Log.w(tag, "getStreamUrl($videoId) primary failed: ${result.exceptionOrNull()?.message}")

            // Fallback: let yt-dlp pick any format
            val fallbackRequest = YoutubeDLRequest(ytUrl).apply {
                addOption("--no-playlist")
                addOption("-f", "bestaudio")
                addOption("--get-url")
                addOption("--no-warnings")
                addOption("--socket-timeout", "30")
                addOption("--no-check-certificates")
            }
            val fallbackResponse = YoutubeDL.getInstance().execute(fallbackRequest)
            val url = extractUrl(fallbackResponse.out, videoId)
            Log.d(tag, "getStreamUrl($videoId) ok (fallback) ${url.take(80)}…")
            url
        }.onFailure { Log.w(tag, "getStreamUrl($videoId) fail [${it.javaClass.simpleName}]: ${it.message}") }
    }

    private fun extractUrl(out: String, videoId: String): String {
        val trimmed = out.trim()
        return trimmed.lines().lastOrNull { it.startsWith("http") }
            ?: error("yt-dlp returned no HTTP URL for $videoId:\n${trimmed.take(500)}")
    }
}
