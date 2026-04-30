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
 *   1. In-memory cache (4h TTL).
 *   2. yt-dlp via embedded Python — handles PoToken, signature ciphers, and
 *      whatever else YouTube introduces; updated daily by [YtDlpDownloadWorker].
 *   3. Innertube player API (IOS / ANDROID_VR / TVHTML5 / ANDROID) — fallback
 *      while yt-dlp is still initialising on first launch.
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

    /** Drop a cached URL; called after a 403/410 from the CDN. */
    suspend fun invalidate(videoId: String) {
        cacheMutex.withLock { urlCache.remove(videoId) }
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

        // 2. yt-dlp (primary — handles PoToken & signature ciphers).
        if (ytDlpHelper.isInstalled) {
            Log.d(tag, "resolve($videoId) trying yt-dlp")
            val result = ytDlpHelper.getStreamUrl(videoId)
            if (result.isSuccess) {
                cacheUrl(videoId, result.getOrThrow())
                Log.d(tag, "resolve($videoId) yt-dlp ok")
                return result
            }
            Log.w(tag, "resolve($videoId) yt-dlp failed: ${result.exceptionOrNull()?.message}")
        } else {
            Log.d(tag, "resolve($videoId) yt-dlp not yet initialised — using innertube fallback")
        }

        // 3. Innertube player API fallback (multi-client).
        Log.d(tag, "resolve($videoId) trying innertube player API")
        try {
            val url = withContext(Dispatchers.IO) { innertube.getAudioStreamUrl(videoId) }
            cacheUrl(videoId, url)
            Log.d(tag, "resolve($videoId) innertube ok")
            return Result.success(url)
        } catch (e: Exception) {
            Log.w(tag, "resolve($videoId) innertube failed: ${e.message}")
        }

        return Result.failure(StreamResolutionException(videoId))
    }

    private suspend fun cacheUrl(videoId: String, url: String) {
        cacheMutex.withLock {
            urlCache[videoId] = url to (System.currentTimeMillis() + URL_TTL_MS)
        }
    }

    companion object {
        private const val URL_TTL_MS = 4L * 60 * 60 * 1000  // 4h (YouTube CDN URLs expire ~6h)
    }
}

class StreamResolutionException(videoId: String) :
    Exception("Failed to resolve stream for $videoId (player API and yt-dlp both failed)")
