/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.playback

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around the yt-dlp binary managed by [YtDlpManager].
 * Returns [Result.failure] when the binary is not yet installed so callers
 * can fall back to other resolvers without special-casing.
 */
@Singleton
class YtDlpHelper @Inject constructor(
    private val manager: YtDlpManager,
) {
    private val tag = "YtDlpHelper"
    private val bin: File get() = manager.binFile

    val isInstalled: Boolean get() = manager.isReady

    suspend fun getStreamUrl(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            check(isInstalled) { "yt-dlp not ready (still downloading or not installed)" }
            Log.d(tag, "getStreamUrl($videoId) — bin=${bin.absolutePath}")
            val process = ProcessBuilder(
                bin.absolutePath,
                "--no-playlist",
                "-f", "bestaudio[ext=webm]/bestaudio",
                "--get-url",
                "https://www.youtube.com/watch?v=$videoId",
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exit = process.waitFor()
            check(exit == 0) { "yt-dlp exit=$exit out=${output.take(200)}" }
            val url = output.lines().lastOrNull { it.startsWith("http") }
                ?: error("No HTTP URL in yt-dlp output for $videoId:\n${output.take(500)}")
            Log.d(tag, "getStreamUrl($videoId) ok ${url.take(80)}…")
            url
        }.onFailure { Log.w(tag, "getStreamUrl($videoId) fail: ${it.message}") }
    }
}
