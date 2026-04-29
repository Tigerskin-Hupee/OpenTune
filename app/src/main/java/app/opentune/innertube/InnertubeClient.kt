package app.opentune.innertube

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Minimal Innertube client targeting the ANDROID_MUSIC context.
 * ANDROID_MUSIC returns pre-signed stream URLs (no signatureCipher / n-param decode needed).
 */
@Singleton
class InnertubeClient @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
    }

    // ── Data models ──────────────────────────────────────────────────────────

    @Serializable
    data class PlayerRequest(
        val context: Context,
        val videoId: String,
        val playbackContext: PlaybackContext = PlaybackContext(),
        val contentCheckOk: Boolean = true,
        val racyCheckOk: Boolean = true,
    ) {
        @Serializable
        data class Context(val client: Client)

        @Serializable
        data class Client(
            val clientName: String,
            val clientVersion: String,
            val androidSdkVersion: Int,
            val hl: String = "en",
            val gl: String = "US",
            val userAgent: String,
        )

        @Serializable
        data class PlaybackContext(
            val contentPlaybackContext: ContentPlaybackContext = ContentPlaybackContext(),
        ) {
            @Serializable
            data class ContentPlaybackContext(
                val html5Preference: String = "HTML5_PREF_WANTS",
            )
        }
    }

    @Serializable
    data class PlayerResponse(
        val streamingData: StreamingData? = null,
        val playabilityStatus: PlayabilityStatus? = null,
    ) {
        @Serializable
        data class StreamingData(
            val adaptiveFormats: List<AdaptiveFormat> = emptyList(),
            val expiresInSeconds: String? = null,
        )

        @Serializable
        data class AdaptiveFormat(
            val itag: Int,
            val url: String? = null,
            val mimeType: String,
            val bitrate: Long = 0,
            val audioSampleRate: String? = null,
            val contentLength: String? = null,
            val loudnessDb: Double? = null,
        ) {
            val isAudio: Boolean get() = mimeType.startsWith("audio/")
        }

        @Serializable
        data class PlayabilityStatus(val status: String, val reason: String? = null)
    }

    // ── API ──────────────────────────────────────────────────────────────────

    suspend fun getPlayerResponse(videoId: String): Result<PlayerResponse> = withContext(Dispatchers.IO) {
        runCatching {
            http.post(PLAYER_ENDPOINT) {
                parameter("key", API_KEY)
                header(HttpHeaders.UserAgent, USER_AGENT)
                header("X-Goog-Api-Format-Version", "1")
                contentType(ContentType.Application.Json)
                setBody(
                    PlayerRequest(
                        context = PlayerRequest.Context(
                            client = PlayerRequest.Client(
                                clientName = CLIENT_NAME,
                                clientVersion = CLIENT_VERSION,
                                androidSdkVersion = ANDROID_SDK_VERSION,
                                userAgent = USER_AGENT,
                            ),
                        ),
                        videoId = videoId,
                    )
                )
            }.body<PlayerResponse>()
        }
    }

    fun selectBestAudioFormat(response: PlayerResponse): PlayerResponse.AdaptiveFormat? =
        selectAudioFormat(response, AudioQuality.BEST)

    fun selectAudioFormat(response: PlayerResponse, quality: AudioQuality): PlayerResponse.AdaptiveFormat? {
        val audio = response.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.url != null }
            ?.takeIf { it.isNotEmpty() } ?: return null
        return when (quality) {
            AudioQuality.LOW   -> audio.minByOrNull { it.bitrate }
            AudioQuality.MEDIUM -> audio.closestBitrate(128_000L) { it.bitrate }
            AudioQuality.HIGH   -> audio.closestBitrate(256_000L) { it.bitrate }
            AudioQuality.BEST, AudioQuality.AUTO -> audio.maxByOrNull { it.bitrate }
        }
    }

    companion object {
        private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player"
        private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        private const val CLIENT_NAME = "ANDROID_MUSIC"
        private const val CLIENT_VERSION = "6.42.52"
        private const val ANDROID_SDK_VERSION = 30
        private const val USER_AGENT =
            "com.google.android.apps.youtube.music/$CLIENT_VERSION (Linux; U; Android 11) gzip"

        // URL expiry — YouTube adaptive format URLs expire in ~6 hours; cache for 5h to be safe
        const val URL_TTL_MS = 5 * 60 * 60 * 1000L
    }
}
