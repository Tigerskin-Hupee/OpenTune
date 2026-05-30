/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * Backed by NewPipeExtractor — pure-JVM library that handles PoToken,
 * signature ciphers and the n-parameter throttling. Same library used by
 * NewPipe, InnerTune and OuterTune.
 */
package app.opentune.innertube

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SearchResult<T>(val items: List<T>, val nextPage: Page? = null)

data class YtMusicTrack(
    val videoId: String,
    val title: String,
    val artistName: String,
    val thumbnailUrl: String?,
    val durationText: String?,
)

data class YtMusicArtist(
    val channelId: String,
    val name: String,
    val thumbnailUrl: String?,
    val subscriberCount: Long,
)

data class YtMusicAlbum(
    val playlistId: String,
    val title: String,
    val artistName: String,
    val thumbnailUrl: String?,
    val streamCount: Long,
    val url: String,
)

@Singleton
class InnertubeApi @Inject constructor() {
    private val tag = "InnertubeApi"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Resolve a video id to a direct audio CDN URL (highest-bitrate audio stream). */
    fun getAudioStreamUrl(videoId: String): String {
        val start = System.currentTimeMillis()
        return try {
            // NewPipeExtractor StreamInfo is broken on 2026 YouTube (audioStreams always empty).
            // Use the Innertube player API directly instead.
            val url = fetchAudioStreamViaPlayerApi(videoId)
            val elapsed = System.currentTimeMillis() - start
            Log.d(tag, "getAudioStreamUrl($videoId) ok ${elapsed}ms")
            app.opentune.utils.DiagnosticsLogger.logStream(videoId, true, elapsed)
            url
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            Log.w(tag, "getAudioStreamUrl($videoId) FAILED ${elapsed}ms: ${e.message}")
            app.opentune.utils.DiagnosticsLogger.logStream(videoId, false, elapsed, e.javaClass.simpleName + ": " + e.message?.take(120))
            throw e
        }
    }

    private fun fetchAudioStreamViaPlayerApi(videoId: String): String {
        val json = "application/json; charset=utf-8".toMediaType()
        val clientVersion = "19.09.37"
        val body = """{"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true,"context":{"client":{"clientName":"ANDROID","clientVersion":"$clientVersion","androidSdkVersion":30,"hl":"en","gl":"US"}}}"""

        val response = httpClient.newCall(
            Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?key=AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w")
                .post(body.toRequestBody(json))
                .addHeader("User-Agent", "com.google.android.youtube/$clientVersion (Linux; U; Android 11) gzip")
                .addHeader("X-YouTube-Client-Name", "3")
                .addHeader("X-YouTube-Client-Version", clientVersion)
                .addHeader("Origin", "https://www.youtube.com")
                .build()
        ).execute()

        val responseBody = response.body?.string()
        if (!response.isSuccessful || responseBody.isNullOrBlank()) {
            error("Player API HTTP ${response.code} for $videoId")
        }

        val root = JSONObject(responseBody)

        val status = root.optJSONObject("playabilityStatus")?.optString("status")
        if (status == "ERROR" || status == "LOGIN_REQUIRED" || status == "UNPLAYABLE") {
            val reason = root.optJSONObject("playabilityStatus")?.optString("reason") ?: status
            error("Video $videoId not playable: $reason")
        }

        val streamingData = root.optJSONObject("streamingData")
            ?: error("No streamingData for $videoId")

        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            ?: error("No adaptiveFormats for $videoId")

        var bestUrl: String? = null
        var bestBitrate = 0

        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.getJSONObject(i)
            if (!format.optString("mimeType", "").startsWith("audio/")) continue
            val url = format.optString("url", "").takeIf { it.isNotBlank() } ?: continue
            val bitrate = format.optInt("averageBitrate", 0).takeIf { it > 0 }
                ?: format.optInt("bitrate", 0)
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                bestUrl = url
            }
        }

        return bestUrl ?: error("No direct audio URL in adaptiveFormats for $videoId")
    }

    fun search(query: String): SearchResult<YtMusicTrack> {
        return try {
            // Use unfiltered YouTube search — music_songs filter has no nextPage token,
            // so infinite scroll never works with it.
            val info = SearchInfo.getInfo(
                ServiceList.YouTube,
                ServiceList.YouTube.searchQHFactory.fromQuery(query),
            )
            val tracks = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack() }
            Log.d(tag, "search('$query') -> ${tracks.size} tracks, hasNext=${info.nextPage != null}")
            SearchResult(tracks, info.nextPage)
        } catch (e: Exception) {
            Log.w(tag, "search('$query') failed [${e.javaClass.simpleName}]: ${e.message}")
            SearchResult(emptyList())
        }
    }

    fun searchMoreSongs(query: String, nextPage: Page): SearchResult<YtMusicTrack> {
        return try {
            val qh = ServiceList.YouTube.searchQHFactory.fromQuery(query)
            val page = SearchInfo.getMoreItems(ServiceList.YouTube, qh, nextPage)
            SearchResult(page.items.filterIsInstance<StreamInfoItem>().mapNotNull { it.toTrack() }, page.nextPage)
        } catch (e: Exception) {
            Log.w(tag, "searchMoreSongs failed: ${e.message}")
            SearchResult(emptyList())
        }
    }

    fun searchMoreArtists(query: String, nextPage: Page): SearchResult<YtMusicArtist> {
        return try {
            val qh = ServiceList.YouTube.searchQHFactory.fromQuery(query, listOf("music_artists"), "")
            val page = SearchInfo.getMoreItems(ServiceList.YouTube, qh, nextPage)
            SearchResult(page.items.filterIsInstance<ChannelInfoItem>().map { it.toArtist() }, page.nextPage)
        } catch (e: Exception) {
            Log.w(tag, "searchMoreArtists failed: ${e.message}")
            SearchResult(emptyList())
        }
    }

    fun searchMoreAlbums(query: String, nextPage: Page): SearchResult<YtMusicAlbum> {
        return try {
            val qh = ServiceList.YouTube.searchQHFactory.fromQuery(query, listOf("music_albums"), "")
            val page = SearchInfo.getMoreItems(ServiceList.YouTube, qh, nextPage)
            SearchResult(page.items.filterIsInstance<PlaylistInfoItem>().map { it.toAlbum() }, page.nextPage)
        } catch (e: Exception) {
            Log.w(tag, "searchMoreAlbums failed: ${e.message}")
            SearchResult(emptyList())
        }
    }

    fun searchMorePlaylists(query: String, nextPage: Page): SearchResult<YtMusicAlbum> {
        return try {
            val qh = ServiceList.YouTube.searchQHFactory.fromQuery(query, listOf("music_playlists"), "")
            val page = SearchInfo.getMoreItems(ServiceList.YouTube, qh, nextPage)
            SearchResult(page.items.filterIsInstance<PlaylistInfoItem>().map { it.toAlbum() }, page.nextPage)
        } catch (e: Exception) {
            Log.w(tag, "searchMorePlaylists failed: ${e.message}")
            SearchResult(emptyList())
        }
    }

    fun searchArtists(query: String): SearchResult<YtMusicArtist> {
        return try {
            val info = SearchInfo.getInfo(
                ServiceList.YouTube,
                ServiceList.YouTube.searchQHFactory.fromQuery(query, listOf("music_artists"), ""),
            )
            SearchResult(info.relatedItems.filterIsInstance<ChannelInfoItem>().map { it.toArtist() }, info.nextPage)
        } catch (e: Exception) {
            Log.w(tag, "searchArtists('$query') failed: ${e.message}")
            SearchResult(emptyList())
        }
    }

    fun searchAlbums(query: String): SearchResult<YtMusicAlbum> {
        return try {
            val info = SearchInfo.getInfo(
                ServiceList.YouTube,
                ServiceList.YouTube.searchQHFactory.fromQuery(query, listOf("music_albums"), ""),
            )
            SearchResult(info.relatedItems.filterIsInstance<PlaylistInfoItem>().map { it.toAlbum() }, info.nextPage)
        } catch (e: Exception) {
            Log.w(tag, "searchAlbums('$query') failed: ${e.message}")
            SearchResult(emptyList())
        }
    }

    fun searchPlaylists(query: String): SearchResult<YtMusicAlbum> {
        return try {
            val info = SearchInfo.getInfo(
                ServiceList.YouTube,
                ServiceList.YouTube.searchQHFactory.fromQuery(query, listOf("music_playlists"), ""),
            )
            SearchResult(info.relatedItems.filterIsInstance<PlaylistInfoItem>().map { it.toAlbum() }, info.nextPage)
        } catch (e: Exception) {
            Log.w(tag, "searchPlaylists('$query') failed: ${e.message}")
            SearchResult(emptyList())
        }
    }

    fun getPlaylistSongs(playlistId: String, originalUrl: String = ""): List<YtMusicTrack> {
        if (playlistId.isBlank()) return emptyList()
        // NewPipeExtractor v0.26.1 PlaylistInfo is broken with current YouTube
        // (RuntimeException: Field browseId_ for ux3 not found).
        // Use the Innertube browse API directly instead.
        val result = fetchPlaylistViaInnertube(playlistId)
        if (result.isEmpty()) {
            // YouTube occasionally returns an empty response on the first request;
            // a single retry after a short delay is enough to recover.
            Thread.sleep(800)
            return fetchPlaylistViaInnertube(playlistId)
        }
        return result
    }

    // Fetch playlist songs via YouTube's internal Innertube /browse API.
    // This bypasses NewPipeExtractor's broken PlaylistExtractor.
    private fun fetchPlaylistViaInnertube(playlistId: String): List<YtMusicTrack> {
        val tracks = mutableListOf<YtMusicTrack>()
        var continuation: String? = null
        var pageCount = 0
        val json = "application/json; charset=utf-8".toMediaType()

        do {
            val body = if (continuation == null) {
                """{"browseId":"VL$playlistId","context":{"client":{"clientName":"WEB","clientVersion":"2.20230101.00.00","hl":"en","gl":"US"}}}"""
            } else {
                """{"continuation":"$continuation","context":{"client":{"clientName":"WEB","clientVersion":"2.20230101.00.00","hl":"en","gl":"US"}}}"""
            }

            val response = httpClient.newCall(
                Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/browse")
                    .post(body.toRequestBody(json))
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .addHeader("X-YouTube-Client-Name", "1")
                    .addHeader("X-YouTube-Client-Version", "2.20230101.00.00")
                    .addHeader("Origin", "https://www.youtube.com")
                    .addHeader("Referer", "https://www.youtube.com/playlist?list=$playlistId")
                    .build()
            ).execute()

            val responseBody = response.body?.string()
            if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                Log.w(tag, "fetchPlaylistViaInnertube('$playlistId') HTTP ${response.code} on page $pageCount")
                break
            }

            val root = JSONObject(responseBody)
            tracks.addAll(extractPlaylistVideoRenderers(root))
            continuation = extractContinuationToken(root)
            pageCount++

        } while (continuation != null && pageCount < 20 && tracks.size < 500)

        Log.d(tag, "fetchPlaylistViaInnertube('$playlistId'): ${tracks.size} tracks in $pageCount pages")
        return tracks
    }

    // Recursively find every playlistVideoRenderer in the Innertube response.
    // This handles any nesting depth and is immune to YouTube layout changes.
    private fun extractPlaylistVideoRenderers(node: Any?): List<YtMusicTrack> {
        val tracks = mutableListOf<YtMusicTrack>()
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("playlistVideoRenderer")
                if (renderer != null) {
                    val videoId = renderer.optString("videoId").takeIf { it.isNotBlank() }
                    if (videoId != null) {
                        val title = renderer.optJSONObject("title")
                            ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                            ?: renderer.optJSONObject("title")?.optString("simpleText")
                            ?: videoId
                        val artist = renderer.optJSONObject("shortBylineText")
                            ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                        val duration = renderer.optJSONObject("lengthText")
                            ?.optString("simpleText")?.takeIf { it.isNotBlank() }
                        tracks.add(YtMusicTrack(
                            videoId = videoId,
                            title = title,
                            artistName = artist,
                            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                            durationText = duration,
                        ))
                    }
                } else {
                    for (key in node.keys()) tracks.addAll(extractPlaylistVideoRenderers(node.opt(key)))
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) tracks.addAll(extractPlaylistVideoRenderers(node.opt(i)))
            }
        }
        return tracks
    }

    // Extract the continuation token for the next page from an Innertube response.
    private fun extractContinuationToken(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                // Common locations for continuation tokens
                node.optJSONObject("nextContinuationData")?.optString("continuation")
                    ?.takeIf { it.isNotBlank() }?.let { return it }
                node.optJSONObject("continuationCommand")?.optString("token")
                    ?.takeIf { it.isNotBlank() }?.let { return it }
                for (key in node.keys()) {
                    extractContinuationToken(node.opt(key))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractContinuationToken(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * Recommendation feed. If [artistQueries] is provided, uses them as search queries
     * (personalised based on listen history). Falls back to generic popular-music queries.
     */
    fun getRecommendations(artistQueries: List<String> = emptyList()): List<YtMusicTrack> {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val fallback = listOf("top hits $year", "popular music $year", "best songs $year")
        val queries = if (artistQueries.isNotEmpty()) {
            // Mix top 3 personal artists + 1 fresh pick to keep things varied
            artistQueries.take(3) + fallback.take(1)
        } else {
            fallback
        }
        val seen = mutableSetOf<String>()
        val results = mutableListOf<YtMusicTrack>()
        for (q in queries) {
            if (results.size >= 30) break
            try {
                val page = SearchInfo.getInfo(
                    ServiceList.YouTube,
                    ServiceList.YouTube.searchQHFactory.fromQuery(q),
                )
                page.relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNull { it.toTrack() }
                    .filter { seen.add(it.videoId) }
                    .forEach { results.add(it) }
            } catch (e: Exception) {
                Log.w(tag, "getRecommendations '$q' failed: ${e.message}")
            }
        }
        Log.d(tag, "getRecommendations(${queries.take(2)}...) -> ${results.size} tracks")
        return results.take(30)
    }

    /**
     * Fetch related/recommended songs for a given videoId using StreamInfo.relatedItems.
     * Used by RadioQueue to extend the queue when it runs low.
     */
    fun getRelatedSongs(videoId: String): List<YtMusicTrack> {
        return try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val info = StreamInfo.getInfo(ServiceList.YouTube, url)
            val tracks = (info.relatedItems ?: emptyList<InfoItem>())
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toTrack() }
            Log.d(tag, "getRelatedSongs($videoId) -> ${tracks.size} tracks")
            tracks
        } catch (e: Exception) {
            Log.w(tag, "getRelatedSongs($videoId) failed: ${e.message}")
            emptyList()
        }
    }

    private fun ChannelInfoItem.toArtist(): YtMusicArtist {
        val thumb = thumbnails.maxByOrNull { it.width }?.url?.takeIf { it.isNotBlank() }
        val channelId = url?.substringAfterLast("/") ?: ""
        return YtMusicArtist(channelId = channelId, name = name ?: "", thumbnailUrl = thumb, subscriberCount = subscriberCount)
    }

    private fun PlaylistInfoItem.toAlbum(): YtMusicAlbum {
        val thumb = thumbnails.maxByOrNull { it.width }?.url?.takeIf { it.isNotBlank() }
        val rawUrl = url ?: ""
        Log.d(tag, "toAlbum: rawUrl=$rawUrl name=$name")
        val playlistId = rawUrl.let { u ->
            when {
                u.contains("list=") -> u.substringAfter("list=").substringBefore("&")
                u.contains("/playlist/") -> u.substringAfterLast("/playlist/").substringBefore("?")
                else -> u.substringAfterLast("/").substringBefore("?")
            }
        }
        Log.d(tag, "toAlbum: playlistId=$playlistId")
        return YtMusicAlbum(
            playlistId = playlistId,
            title = name ?: "",
            artistName = uploaderName ?: "",
            thumbnailUrl = thumb,
            streamCount = streamCount,
            url = rawUrl,
        )
    }

    private fun StreamInfoItem.toTrack(): YtMusicTrack? {
        val id = videoIdFromUrl(url) ?: return null
        // Prefer the highest-res thumbnail from NewPipeExtractor; fall back to
        // the standard YouTube thumbnail URL which is always available.
        val thumb = thumbnails.maxByOrNull { it.width }?.url?.takeIf { it.isNotBlank() }
            ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        val durationText = if (duration > 0) formatDuration(duration) else null
        return YtMusicTrack(
            videoId = id,
            title = name ?: id,
            artistName = uploaderName ?: "",
            thumbnailUrl = thumb,
            durationText = durationText,
        )
    }

    private fun videoIdFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        // youtube.com/watch?v=XXXX
        val watchIdx = url.indexOf("v=")
        if (watchIdx >= 0) {
            val raw = url.substring(watchIdx + 2)
            val end = raw.indexOfAny(charArrayOf('&', '#', '?'))
            return (if (end >= 0) raw.substring(0, end) else raw).takeIf { it.isNotBlank() }
        }
        // youtu.be/XXXX
        val short = "youtu.be/"
        val si = url.indexOf(short)
        if (si >= 0) {
            val raw = url.substring(si + short.length)
            val end = raw.indexOfAny(charArrayOf('&', '#', '?', '/'))
            return (if (end >= 0) raw.substring(0, end) else raw).takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun formatDuration(seconds: Long): String {
        val s = seconds % 60
        val m = (seconds / 60) % 60
        val h = seconds / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
