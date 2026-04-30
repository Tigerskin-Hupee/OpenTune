/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.playback

import android.util.Log
import app.opentune.innertube.InnertubeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a YouTube video ID to a playable audio URL.
 *
 * Strategy (in order):
 *   1. In-memory cache (5 hour TTL).
 *   2. YouTube ANDROID_MUSIC player API — reliable, no binary needed, works
 *      on all Android versions. Returns pre-signed CDN URLs.
 *   3. yt-dlp binary (fallback) — auto-updated by [YtDlpManager]. Used if the
 *      player API fails (geo-block, login-required, etc.).
 *
 * Per-video [Mutex] prevents stampede when multiple coroutines request the
 * same ID concurrently (e.g. on queue setup).
 */
@Singleton
class StreamResolver @Inject constructor(
    private val innertube: InnertubeApi,
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
        // 1. Cache
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

        // 2. YouTube ANDROID_MUSIC player API (primary — no binary needed)
        Log.d(tag, "resolve($videoId) trying YouTube player API")
        try {
            val url = withContext(Dispatchers.IO) { innertube.getAudioStreamUrl(videoId) }
            cacheUrl(videoId, url)
            Log.d(tag, "resolve($videoId) player API ok")
            return Result.success(url)
        } catch (e: Exception) {
            Log.w(tag, "resolve($videoId) player API failed: ${e.message}")
        }

        // 3. yt-dlp fallback (handles geo-restrictions & login-required cases)
        if (ytDlpHelper.isInstalled) {
            Log.d(tag, "resolve($videoId) trying yt-dlp fallback")
            val result = ytDlpHelper.getStreamUrl(videoId)
            if (result.isSuccess) {
                cacheUrl(videoId, result.getOrThrow())
                Log.d(tag, "resolve($videoId) yt-dlp ok")
                return result
            }
            Log.w(tag, "resolve($videoId) yt-dlp failed: ${result.exceptionOrNull()?.message}")
        }

        return Result.failure(StreamResolutionException(videoId))
    }

    private suspend fun cacheUrl(videoId: String, url: String) {
        cacheMutex.withLock {
            urlCache[videoId] = url to (System.currentTimeMillis() + URL_TTL_MS)
        }
    }

    companion object {
        private const val URL_TTL_MS = 5L * 60 * 60 * 1000  // 5 hours
    }
}

class StreamResolutionException(videoId: String) :
    Exception("Failed to resolve stream for $videoId (player API and yt-dlp both failed)")
