/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.playback

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a YouTube video ID to a playable audio URL.
 *
 * Strategy:
 *   1. In-memory cache (5 hour TTL).
 *   2. yt-dlp binary — auto-updated by [YtDlpManager], so YouTube cipher / nsig
 *      changes are handled by upstream yt-dlp without requiring an APK release.
 *
 * Per-video [Mutex] prevents stampede when multiple coroutines request the
 * same ID concurrently (e.g. on queue setup).
 */
@Singleton
class StreamResolver @Inject constructor(
    private val ytDlpHelper: YtDlpHelper,
) {
    private val tag = "StreamResolver"
    private val urlCache = HashMap<String, Pair<String, Long>>()
    private val cacheMutex = Mutex()
    private val locks = HashMap<String, Mutex>()
    private val locksMutex = Mutex()

    suspend fun getStreamUrl(videoId: String): Result<String> {
        val mutex = locksMutex.withLock { locks.getOrPut(videoId) { Mutex() } }
        return mutex.withLock { resolve(videoId) }
    }

    private suspend fun resolve(videoId: String): Result<String> {
        cacheMutex.withLock {
            urlCache[videoId]?.let { (url, expireAt) ->
                if (expireAt > System.currentTimeMillis()) {
                    Log.d(tag, "resolve($videoId) cache hit")
                    return Result.success(url)
                } else {
                    urlCache.remove(videoId)
                }
            }
        }

        Log.d(tag, "resolve($videoId) trying yt-dlp (ready=${ytDlpHelper.isInstalled})")
        val result = ytDlpHelper.getStreamUrl(videoId)
        if (result.isSuccess) {
            val url = result.getOrThrow()
            cacheMutex.withLock {
                urlCache[videoId] = url to (System.currentTimeMillis() + URL_TTL_MS)
            }
            Log.d(tag, "resolve($videoId) yt-dlp ok")
            return result
        }

        val err = result.exceptionOrNull()?.message ?: "unknown"
        Log.e(tag, "resolve($videoId) failed: $err")
        return Result.failure(
            StreamResolutionException(videoId, ytDlpError = err)
        )
    }

    companion object {
        private const val URL_TTL_MS = 5L * 60 * 60 * 1000  // 5 hours
    }
}

class StreamResolutionException(
    videoId: String,
    ytDlpError: String,
) : Exception(
    "Failed to resolve stream for $videoId. yt-dlp: $ytDlpError"
)
