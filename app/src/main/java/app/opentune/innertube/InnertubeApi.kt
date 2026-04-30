/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.innertube

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class YtMusicTrack(
    val videoId: String,
    val title: String,
    val artistName: String,
    val thumbnailUrl: String?,
    val durationText: String?,
)

@Singleton
class InnertubeApi @Inject constructor() {
    private val tag = "InnertubeApi"

    // Public YouTube Music API key (same for all clients, has been stable for years).
    private val YTM_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-NKNELL6TV"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // WEB_REMIX client — for search and home feed
    private val webRemixContext = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20250131.01.00")
            put("hl", "en")
            put("gl", "US")
        })
    }

    private fun postYtMusic(endpoint: String, body: JSONObject): JSONObject? {
        val url = "https://music.youtube.com/youtubei/v1/$endpoint?key=$YTM_KEY&prettyPrint=false"
        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .addHeader("X-YouTube-Client-Name", "67")
            .addHeader("X-YouTube-Client-Version", "1.20250131.01.00")
            .addHeader("Origin", "https://music.youtube.com")
            .addHeader("Referer", "https://music.youtube.com/")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(tag, "postYtMusic $endpoint HTTP ${resp.code}")
                    return null
                }
                val text = resp.body?.string() ?: return null
                Log.v(tag, "postYtMusic $endpoint response (${text.length}B): ${text.take(200)}")
                JSONObject(text)
            }
        } catch (e: Exception) {
            Log.w(tag, "postYtMusic $endpoint failed: ${e.message}")
            null
        }
    }

    private data class PlayerClient(
        val clientName: String,
        val clientVersion: String,
        val clientId: String,
        val userAgent: String,
        val extraContext: (JSONObject) -> Unit = {},
        val extraBody: (JSONObject) -> Unit = {},
    )

    // Clients tried in order. iOS and ANDROID_VR currently bypass PoToken
    // enforcement and return pre-signed CDN URLs without cipher processing.
    private val playerClients = listOf(
        PlayerClient(
            clientName = "IOS",
            clientVersion = "19.45.4",
            clientId = "5",
            userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            extraContext = { c ->
                c.put("deviceMake", "Apple")
                c.put("deviceModel", "iPhone16,2")
                c.put("osName", "iPhone")
                c.put("osVersion", "18.1.0.22B83")
            },
        ),
        PlayerClient(
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            extraContext = { c ->
                c.put("deviceMake", "Oculus")
                c.put("deviceModel", "Quest 3")
                c.put("osName", "Android")
                c.put("osVersion", "12L")
                c.put("androidSdkVersion", 32)
            },
        ),
        PlayerClient(
            clientName = "ANDROID_MUSIC",
            clientVersion = "6.42.52",
            clientId = "21",
            userAgent = "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 11) gzip",
            extraContext = { c ->
                c.put("deviceMake", "Google")
                c.put("deviceModel", "Pixel 5")
                c.put("osName", "Android")
                c.put("osVersion", "11")
                c.put("androidSdkVersion", 30)
            },
        ),
        PlayerClient(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            clientId = "85",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Safari/605.1.15",
            extraBody = { b ->
                b.put("thirdParty", JSONObject().apply {
                    put("embedUrl", "https://www.youtube.com/")
                })
            },
        ),
        PlayerClient(
            clientName = "ANDROID",
            clientVersion = "19.44.38",
            clientId = "3",
            userAgent = "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip",
            extraContext = { c -> c.put("androidSdkVersion", 30) },
        ),
        PlayerClient(
            clientName = "MWEB",
            clientVersion = "2.20241126.01.00",
            clientId = "2",
            userAgent = "Mozilla/5.0 (Linux; Android 10; Pixel 3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.77 Mobile Safari/537.36",
        ),
    )

    /**
     * Resolves a video ID to a direct audio CDN URL by trying multiple
     * YouTube innertube clients.
     */
    fun getAudioStreamUrl(videoId: String): String {
        val errors = mutableListOf<String>()
        for (client in playerClients) {
            try {
                val url = tryClient(videoId, client)
                Log.d(tag, "getAudioStreamUrl($videoId) ok via ${client.clientName}")
                return url
            } catch (e: Exception) {
                Log.w(tag, "getAudioStreamUrl($videoId) ${client.clientName} failed: ${e.message}")
                errors.add("${client.clientName}: ${e.message}")
            }
        }
        error("All player clients failed for $videoId — ${errors.joinToString("; ")}")
    }

    private fun tryClient(videoId: String, client: PlayerClient): String {
        val clientObj = JSONObject().apply {
            put("clientName", client.clientName)
            put("clientVersion", client.clientVersion)
            put("hl", "en")
            put("gl", "US")
            client.extraContext(this)
        }
        val body = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().apply { put("client", clientObj) })
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            client.extraBody(this)
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", client.userAgent)
            .addHeader("X-YouTube-Client-Name", client.clientId)
            .addHeader("X-YouTube-Client-Version", client.clientVersion)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val root = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body?.string()?.let { JSONObject(it) }
        } ?: error("null response")

        val playabilityStatus = root.optJSONObject("playabilityStatus")
        val status = playabilityStatus?.optString("status") ?: ""
        if (status == "ERROR" || status == "UNPLAYABLE" || status == "LOGIN_REQUIRED") {
            val reason = playabilityStatus?.optString("reason") ?: status
            error("not playable: $reason")
        }

        val streamingData = root.optJSONObject("streamingData")
            ?: error("no streamingData (playabilityStatus='$status')")

        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            ?: error("no adaptiveFormats")

        data class Fmt(val url: String, val bitrate: Int)
        val candidates = mutableListOf<Fmt>()
        for (i in 0 until adaptiveFormats.length()) {
            val fmt = adaptiveFormats.getJSONObject(i)
            if (!fmt.optString("mimeType").startsWith("audio/")) continue
            // Skip ciphered formats — we can't decode them without a JS engine
            if (fmt.has("signatureCipher")) continue
            val url = fmt.optString("url").takeIf { it.startsWith("http") } ?: continue
            candidates.add(Fmt(url, fmt.optInt("bitrate", 0)))
        }

        return candidates.maxByOrNull { it.bitrate }?.url
            ?: error("no direct audio stream (${adaptiveFormats.length()} formats, all ciphered or non-audio)")
    }

    fun search(query: String): List<YtMusicTrack> {
        // Try with songs filter first, then without filter as fallback
        val withFilter = JSONObject().apply {
            put("context", webRemixContext)
            put("query", query)
            put("params", "EgWKAQIIAWoKEAoQAxAEEAkQBQ==") // songs only
        }
        val withoutFilter = JSONObject().apply {
            put("context", webRemixContext)
            put("query", query)
        }

        for (body in listOf(withFilter, withoutFilter)) {
            val root = postYtMusic("search", body) ?: continue
            val results = parseSearchResponse(root)
            if (results.isNotEmpty()) {
                Log.d(tag, "search('$query') → ${results.size} results")
                return results
            }
        }
        Log.w(tag, "search('$query') returned no results from either filter")
        return emptyList()
    }

    private fun parseSearchResponse(root: JSONObject): List<YtMusicTrack> {
        val results = mutableListOf<YtMusicTrack>()
        try {
            // Path 1: tabbedSearchResultsRenderer (standard WEB_REMIX response)
            val tabContents = root
                .optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")

            // Path 2: sectionListRenderer directly under contents
            val directContents = root
                .optJSONObject("contents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")

            val sections: JSONArray = tabContents ?: directContents ?: run {
                Log.w(tag, "parseSearchResponse: no known structure found, keys=${root.optJSONObject("contents")?.keys()?.asSequence()?.toList()}")
                return emptyList()
            }

            for (i in 0 until sections.length()) {
                val sectionObj = sections.getJSONObject(i)
                val shelf = sectionObj.optJSONObject("musicShelfRenderer")
                    ?: sectionObj.optJSONObject("musicCardShelfRenderer")
                    ?: continue
                val items = shelf.optJSONArray("contents") ?: continue
                for (j in 0 until items.length()) {
                    parseTrack(items.getJSONObject(j))?.let { results.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "parseSearchResponse failed: ${e.message}")
        }
        return results
    }

    fun getRecommendations(): List<YtMusicTrack> {
        val body = JSONObject().apply {
            put("context", webRemixContext)
            put("browseId", "FEmusic_home")
        }
        val root = postYtMusic("browse", body) ?: return emptyList()
        return try {
            val results = mutableListOf<YtMusicTrack>()
            val shelves = root
                .optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
                ?: return emptyList()

            for (i in 0 until shelves.length()) {
                val carousel = shelves.getJSONObject(i)
                    .optJSONObject("musicCarouselShelfRenderer") ?: continue
                val items = carousel.optJSONArray("contents") ?: continue
                for (j in 0 until items.length()) {
                    parseTwoRowTrack(items.getJSONObject(j))?.let { results.add(it) }
                    if (results.size >= 30) return results
                }
            }
            results
        } catch (e: Exception) {
            Log.w(tag, "getRecommendations parse failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseTwoRowTrack(item: JSONObject): YtMusicTrack? {
        val renderer = item.optJSONObject("musicTwoRowItemRenderer") ?: return null
        val videoId = renderer
            .optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
            ?.takeIf { it.isNotBlank() }
            ?: renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val title = renderer.optJSONObject("title")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text") ?: videoId

        val artist = renderer.optJSONObject("subtitle")
            ?.optJSONArray("runs")
            ?.let { runs ->
                (0 until runs.length())
                    .map { runs.getJSONObject(it).optString("text") }
                    .firstOrNull { it != "•" && it != " • " && it.isNotBlank() }
            } ?: ""

        val thumbnail = renderer.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { thumbs ->
                (0 until thumbs.length()).map { thumbs.getJSONObject(it) }
                    .maxByOrNull { it.optInt("width", 0) }
                    ?.optString("url")
            }

        return YtMusicTrack(videoId = videoId, title = title, artistName = artist, thumbnailUrl = thumbnail, durationText = null)
    }

    private fun parseTrack(item: JSONObject): YtMusicTrack? {
        val renderer = item.optJSONObject("musicResponsiveListItemRenderer") ?: return null
        val videoId = renderer
            .optJSONObject("playlistItemData")
            ?.optString("videoId")
            ?.takeIf { it.isNotBlank() }
            ?: renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val flexCols = renderer.optJSONArray("flexColumns") ?: return null
        val title = flexCols.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text") ?: videoId

        val artist = flexCols.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text") ?: ""

        val thumbnail = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { thumbs ->
                (0 until thumbs.length()).map { thumbs.getJSONObject(it) }
                    .maxByOrNull { it.optInt("width", 0) }
                    ?.optString("url")
            }

        return YtMusicTrack(
            videoId = videoId,
            title = title,
            artistName = artist,
            thumbnailUrl = thumbnail,
            durationText = null,
        )
    }
}
