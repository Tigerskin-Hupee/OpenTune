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
 * Resolves a YouTube video ID to a playable audio URL via NewPipeExtractor.
 *
 * - In-memory cache (4h TTL; YouTube CDN URLs expire ~6h).
 * - Per-video [Mutex] prevents stampede on concurrent requests for the same id.
 */
@Singleton
class StreamResolver @Inject constructor(
    private val innertube: InnertubeApi,
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

        return try {
            val url = withContext(Dispatchers.IO) { innertube.getAudioStreamUrl(videoId) }
            cacheUrl(videoId, url)
            Log.d(tag, "resolve($videoId) ok")
            Result.success(url)
        } catch (e: Exception) {
            Log.w(tag, "resolve($videoId) failed [${e.javaClass.simpleName}]: ${e.message}")
            Result.failure(StreamResolutionException(videoId, e))
        }
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

class StreamResolutionException(videoId: String, cause: Throwable? = null) :
    Exception("Failed to resolve stream for $videoId", cause)
