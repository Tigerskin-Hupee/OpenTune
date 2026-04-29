package app.opentune.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.opentune.constants.AudioQualityKey
import app.opentune.innertube.AudioQuality
import app.opentune.innertube.InnertubeClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a YouTube video ID to a playable audio stream URL.
 *
 * Strategy:
 *   1. Return in-memory cached URL if still valid (< 5 h old)
 *   2. Try yt-dlp (fastest, most reliable, no cipher issues)
 *   3. On yt-dlp failure, fall back to Innertube ANDROID_MUSIC player API
 *   4. If both fail, propagate the combined error
 */
@Singleton
class StreamResolver @Inject constructor(
    private val ytDlpHelper: YtDlpHelper,
    private val innertubeClient: InnertubeClient,
    private val dataStore: DataStore<Preferences>,
) {
    // videoId → (url, expireAtMs)
    private val urlCache = HashMap<String, Pair<String, Long>>()
    private val cacheMutex = Mutex()

    // Per-video mutex prevents stampede when multiple coroutines request the same ID
    private val locks = HashMap<String, Mutex>()
    private val locksMutex = Mutex()

    suspend fun getStreamUrl(videoId: String): Result<String> {
        val mutex = locksMutex.withLock { locks.getOrPut(videoId) { Mutex() } }
        return mutex.withLock { resolve(videoId) }
    }

    private suspend fun resolve(videoId: String): Result<String> {
        // 1. In-memory cache hit
        cacheMutex.withLock {
            urlCache[videoId]?.let { (url, expireAt) ->
                if (expireAt > System.currentTimeMillis()) return Result.success(url)
                else urlCache.remove(videoId)
            }
        }

        // 2. yt-dlp (primary)
        val ytDlpResult = ytDlpHelper.getStreamUrl(videoId)
        if (ytDlpResult.isSuccess) {
            val url = ytDlpResult.getOrThrow()
            cacheMutex.withLock { urlCache[videoId] = url to (System.currentTimeMillis() + URL_TTL_MS) }
            return ytDlpResult
        }

        // 3. Innertube ANDROID_MUSIC fallback
        val innertubeResult = resolveViaInnertube(videoId)
        if (innertubeResult.isSuccess) return innertubeResult

        // 4. Both failed — compose a single error message
        val ytDlpErr = ytDlpResult.exceptionOrNull()?.message ?: "unknown"
        val innertubeErr = innertubeResult.exceptionOrNull()?.message ?: "unknown"
        return Result.failure(
            StreamResolutionException(
                videoId = videoId,
                ytDlpError = ytDlpErr,
                innertubeError = innertubeErr,
            )
        )
    }

    private suspend fun resolveViaInnertube(videoId: String): Result<String> {
        val qualityLabel = dataStore.data.first()[AudioQualityKey] ?: "Best"
        val quality = AudioQuality.fromLabel(qualityLabel)
        return innertubeClient.getPlayerResponse(videoId).mapCatching { response ->
            val status = response.playabilityStatus?.status
            check(status == "OK") {
                "Innertube playability: $status — ${response.playabilityStatus?.reason}"
            }

            val format = innertubeClient.selectAudioFormat(response, quality)
                ?: error("No audio format available in Innertube response for $videoId")

            val url = format.url ?: error("Format itag=${format.itag} has no pre-signed URL")

            val expiresIn = response.streamingData?.expiresInSeconds?.toLongOrNull()
                ?: (URL_TTL_MS / 1000)
            val expireAt = System.currentTimeMillis() + (expiresIn * 1000)
            cacheMutex.withLock { urlCache[videoId] = url to expireAt }
            url
        }
    }

    companion object {
        private const val URL_TTL_MS = 5 * 60 * 60 * 1000L // 5 hours
    }
}

class StreamResolutionException(
    videoId: String,
    ytDlpError: String,
    innertubeError: String,
) : Exception(
    "Failed to resolve stream for $videoId.\n" +
        "  yt-dlp: $ytDlpError\n" +
        "  innertube: $innertubeError"
)
