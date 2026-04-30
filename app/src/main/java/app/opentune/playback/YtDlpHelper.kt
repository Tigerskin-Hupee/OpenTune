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
            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
                addOption("--no-playlist")
                addOption("-f", "bestaudio[ext=webm]/bestaudio")
                addOption("--get-url")
                addOption("--no-warnings")
            }
            val response = YoutubeDL.getInstance().execute(request)
            val out = response.out.trim()
            val url = out.lines().lastOrNull { it.startsWith("http") }
                ?: error("yt-dlp returned no HTTP URL:\n${out.take(500)}")
            Log.d(tag, "getStreamUrl($videoId) ok ${url.take(80)}…")
            url
        }.onFailure { Log.w(tag, "getStreamUrl($videoId) fail: ${it.message}") }
    }
}
