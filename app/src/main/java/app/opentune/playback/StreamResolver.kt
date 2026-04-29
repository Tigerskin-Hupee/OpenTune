package app.opentune.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.opentune.db.MusicRepository
import app.opentune.db.entities.FormatEntity
import app.opentune.innertube.AudioQuality
import app.opentune.innertube.InnertubeClient
import app.opentune.prefs.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a YouTube video ID to a playable audio stream URL.
 *
 * Strategy:
 *   1. Return cached URL if still valid (< 5 h old)
 *   2. Try yt-dlp (fastest, most reliable, no cipher issues)
 *   3. On yt-dlp failure, fall back to Innertube ANDROID_MUSIC player API
 *   4. If both fail, propagate the combined error
 *
 * UI callers only receive a [Result<String>] — they are unaware of which
 * source was used.
 */
@Singleton
class StreamResolver @Inject constructor(
    private val ytDlpHelper: YtDlpHelper,
    private val innertubeClient: InnertubeClient,
    private val repository: MusicRepository,
    private val dataStore: DataStore<Preferences>,
) {
    // Per-video mutex prevents stampede when multiple coroutines request the same ID
    private val locks = HashMap<String, Mutex>()
    private val locksMutex = Mutex()

    suspend fun getStreamUrl(videoId: String): Result<String> {
        val mutex = locksMutex.withLock { locks.getOrPut(videoId) { Mutex() } }
        return mutex.withLock { resolve(videoId) }
    }

    private suspend fun resolve(videoId: String): Result<String> {
        // 1. Cache hit
        repository.getFormat(videoId)?.let { cached ->
            val url = cached.playbackUrl
            val expired = cached.expiredAt?.let { it < System.currentTimeMillis() } ?: true
            if (url != null && !expired) {
                return Result.success(url)
            }
        }

        // 2. yt-dlp (primary)
        val ytDlpResult = ytDlpHelper.getStreamUrl(videoId)
        if (ytDlpResult.isSuccess) {
            val url = ytDlpResult.getOrThrow()
            cacheUrl(
                videoId = videoId,
                url = url,
                itag = 0,
                mimeType = "audio/webm",
                codecs = "opus",
                bitrate = 0,
                source = "ytdlp",
            )
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
        val qualityLabel = dataStore.data.first()[AppPreferences.AUDIO_QUALITY] ?: "Best"
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
                ?: (InnertubeClient.URL_TTL_MS / 1000)
            val expiredAt = System.currentTimeMillis() + (expiresIn * 1000)

            cacheUrl(
                videoId = videoId,
                url = url,
                itag = format.itag,
                mimeType = format.mimeType,
                codecs = extractCodecs(format.mimeType),
                bitrate = format.bitrate,
                sampleRate = format.audioSampleRate?.toIntOrNull(),
                contentLength = format.contentLength?.toLongOrNull(),
                loudnessDb = format.loudnessDb,
                expiredAt = expiredAt,
                source = "innertube",
            )
            url
        }
    }

    private suspend fun cacheUrl(
        videoId: String,
        url: String,
        itag: Int,
        mimeType: String,
        codecs: String,
        bitrate: Long,
        sampleRate: Int? = null,
        contentLength: Long? = null,
        loudnessDb: Double? = null,
        expiredAt: Long = System.currentTimeMillis() + InnertubeClient.URL_TTL_MS,
        @Suppress("UNUSED_PARAMETER") source: String = "unknown",
    ) {
        repository.saveFormat(
            FormatEntity(
                id = videoId,
                itag = itag,
                mimeType = mimeType,
                codecs = codecs,
                bitrate = bitrate,
                sampleRate = sampleRate,
                contentLength = contentLength,
                loudnessDb = loudnessDb,
                playbackUrl = url,
                expiredAt = expiredAt,
            )
        )
    }

    private fun extractCodecs(mimeType: String): String =
        Regex("""codecs="([^"]+)"""").find(mimeType)?.groupValues?.getOrNull(1) ?: ""
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
