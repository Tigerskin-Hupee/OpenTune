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

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // WEB_REMIX client — for search and home feed
    private val webRemixContext = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20240101.01.00")
            put("hl", "en")
        })
    }

    private fun postYtMusic(endpoint: String, body: JSONObject): JSONObject? {
        val url = "https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false"
        val req = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Mozilla/5.0 (compatible; OpenTune/1.0)")
            .addHeader("X-YouTube-Client-Name", "67")
            .addHeader("X-YouTube-Client-Version", "1.20240101.01.00")
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
                resp.body?.string()?.let { JSONObject(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "postYtMusic $endpoint failed: ${e.message}")
            null
        }
    }

    /**
     * Resolves a video ID to a direct audio CDN URL via the YouTube ANDROID
     * client (client ID 3). This client returns pre-signed adaptive stream
     * URLs that ExoPlayer plays without signature/cipher processing.
     */
    fun getAudioStreamUrl(videoId: String): String {
        val body = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID")
                    put("clientVersion", "19.44.38")
                    put("androidSdkVersion", 30)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip")
            .addHeader("X-YouTube-Client-Name", "3")
            .addHeader("X-YouTube-Client-Version", "19.44.38")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val root = try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Player API HTTP ${resp.code}")
                resp.body?.string()?.let { JSONObject(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "getAudioStreamUrl($videoId) request failed: ${e.message}")
            null
        } ?: error("Player API null response for $videoId")

        val status = root.optJSONObject("playabilityStatus")?.optString("status") ?: ""
        if (status == "ERROR" || status == "UNPLAYABLE") {
            val reason = root.optJSONObject("playabilityStatus")?.optString("reason") ?: status
            error("Video $videoId not playable: $reason")
        }

        val adaptiveFormats = root.optJSONObject("streamingData")
            ?.optJSONArray("adaptiveFormats")
            ?: error("No streamingData for $videoId (status='$status')")

        data class Fmt(val url: String, val bitrate: Int)
        val candidates = mutableListOf<Fmt>()
        for (i in 0 until adaptiveFormats.length()) {
            val fmt = adaptiveFormats.getJSONObject(i)
            if (!fmt.optString("mimeType").startsWith("audio/")) continue
            val url = fmt.optString("url").takeIf { it.startsWith("http") } ?: continue
            candidates.add(Fmt(url, fmt.optInt("bitrate", 0)))
        }

        return candidates.maxByOrNull { it.bitrate }?.url
            ?: error("No direct audio stream for $videoId")
    }

    fun search(query: String): List<YtMusicTrack> {
        val body = JSONObject().apply {
            put("context", webRemixContext)
            put("query", query)
            put("params", "EgWKAQIIAWoKEAoQAxAEEAkQBQ==") // songs filter
        }
        val root = postYtMusic("search", body) ?: return emptyList()
        return try {
            val results = mutableListOf<YtMusicTrack>()
            val contents = root
                .optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
                ?: return emptyList()

            for (i in 0 until contents.length()) {
                val shelf = contents.getJSONObject(i)
                    .optJSONObject("musicShelfRenderer") ?: continue
                val items = shelf.optJSONArray("contents") ?: continue
                for (j in 0 until items.length()) {
                    parseTrack(items.getJSONObject(j))?.let { results.add(it) }
                }
            }
            results
        } catch (e: Exception) {
            Log.w(tag, "search parse failed: ${e.message}")
            emptyList()
        }
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
