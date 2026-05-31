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

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
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
class InnertubeApi @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val tag = "InnertubeApi"

    private val cookieJar = object : okhttp3.CookieJar {
        private val store = mutableMapOf<String, List<okhttp3.Cookie>>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            store[url.host] = (store[url.host] ?: emptyList()) + cookies
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
            store[url.host] ?: emptyList()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    /** Resolve a video id to a direct audio CDN URL (highest-bitrate audio stream). */
    fun getAudioStreamUrl(videoId: String): String {
        val start = System.currentTimeMillis()
        val errors = mutableListOf<String>()

        // 1. Piped public API — server-side YouTube proxy, no user login needed.
        try {
            val url = fetchAudioStreamPiped(videoId)
            val elapsed = System.currentTimeMillis() - start
            Log.d(tag, "getAudioStreamUrl($videoId) ok via Piped ${elapsed}ms")
            app.opentune.utils.DiagnosticsLogger.logStream(videoId, true, elapsed)
            return url
        } catch (e: Exception) {
            val msg = "Piped: ${e.message?.take(120)}"
            errors += msg
            Log.w(tag, "getAudioStreamUrl($videoId) $msg")
        }

        // 2. NewPipeExtractor with WEB PoToken fed to Android client provider.
        try {
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val best = info.audioStreams.filter { it.content != null }.maxByOrNull { it.averageBitrate }
            if (best != null) {
                val elapsed = System.currentTimeMillis() - start
                Log.d(tag, "getAudioStreamUrl($videoId) ok via NPE ${elapsed}ms")
                app.opentune.utils.DiagnosticsLogger.logStream(videoId, true, elapsed)
                return best.content!!
            }
            val potErr = app.opentune.utils.potoken.OpenTunePoTokenProvider.lastError
            errors += "NPE: ${info.audioStreams.size} streams, none with URL" +
                if (potErr != null) " [pot:$potErr]" else ""
        } catch (e: Exception) {
            errors += "NPE: ${e.message?.take(100)}"
        }

        // 3. Native player API cascade (OAuth TVHTML5 → Android → iOS).
        return try {
            val nativeUrl = fetchAudioStreamNative(videoId)
            val elapsed = System.currentTimeMillis() - start
            Log.d(tag, "getAudioStreamUrl($videoId) ok via native ${elapsed}ms")
            app.opentune.utils.DiagnosticsLogger.logStream(videoId, true, elapsed)
            nativeUrl
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            errors += "native: ${e.message?.take(400)}"
            val combined = errors.joinToString(" | ")
            Log.w(tag, "getAudioStreamUrl($videoId) ALL FAILED ${elapsed}ms: $combined")
            app.opentune.utils.DiagnosticsLogger.logStream(videoId, false, elapsed, combined.take(600))
            throw Exception(combined)
        }
    }

    // Piped is a public YouTube proxy that resolves streams server-side — no user auth needed.
    // Tries multiple public instances in order; first successful response wins.
    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://piped-api.garudalinux.org",
    )

    private fun fetchAudioStreamPiped(videoId: String): String {
        var lastError = "no instances tried"
        for (instance in pipedInstances) {
            try {
                val response = httpClient.newCall(
                    Request.Builder()
                        .url("$instance/streams/$videoId")
                        .addHeader("User-Agent", "OpenTune/1.2 Android")
                        .get()
                        .build()
                ).execute()
                val rb = response.body?.string()
                if (!response.isSuccessful || rb.isNullOrBlank()) {
                    lastError = "$instance: HTTP ${response.code}"; continue
                }
                val root = JSONObject(rb)
                if (root.has("error")) {
                    lastError = "$instance: ${root.optString("message", root.optString("error"))}"; continue
                }
                val streams = root.optJSONArray("audioStreams")
                if (streams == null || streams.length() == 0) {
                    lastError = "$instance: no audioStreams"; continue
                }
                var bestUrl: String? = null; var bestBitrate = 0
                for (i in 0 until streams.length()) {
                    val s = streams.getJSONObject(i)
                    val u = s.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val br = s.optInt("bitrate", 0)
                    if (br > bestBitrate) { bestBitrate = br; bestUrl = u }
                }
                if (bestUrl != null) {
                    Log.d(tag, "fetchAudioStreamPiped($videoId) ok via $instance bitrate=$bestBitrate")
                    return bestUrl
                }
                lastError = "$instance: no URL in ${streams.length()} streams"
            } catch (e: Exception) {
                lastError = "$instance: ${e.message?.take(80)}"
            }
        }
        error("Piped failed: $lastError")
    }

    // Multi-client native player API cascade. Tries clients in order, returns first working URL.
    private data class NativeClient(
        val name: String, val version: String, val clientId: String,
        val url: String, val userAgent: String, val origin: String = "",
        val clientContextJson: String = "",  // fields appended inside context.client {}
        val contextJson: String = "",         // fields appended inside context {} alongside client
        val usePoToken: Boolean = false,
        val useVideoEmbedUrl: Boolean = false,
        val isNativeApp: Boolean = false,     // true = no web headers, use API key in URL
        val useAuth: Boolean = false,         // true = requires OAuth Bearer token
        val addApiFormatVersion: Boolean = false, // true = add X-Goog-Api-Format-Version: 2 (IOS only)
    )

    private val nativeClients = listOf(
        // TVHTML5 authenticated — requires OAuth; bypasses all bot detection.
        NativeClient(
            name = "TVHTML5", version = "7.20260101.00.00", clientId = "7",
            url = "https://www.youtube.com/youtubei/v1/player",
            userAgent = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1",
            origin = "https://www.youtube.com",
            useAuth = true,
        ),
        // ANDROID_TESTSUITE — internal test client, historically bypasses enforcement.
        NativeClient(
            name = "ANDROID_TESTSUITE", version = "1.9", clientId = "30",
            url = "https://www.youtube.com/youtubei/v1/player?key=AIzaSyA8eiZmM1FaDVjRy-df2KpynQLqgref8Xw",
            userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 14) gzip",
            clientContextJson = """"osName":"Android","osVersion":"14","androidSdkVersion":30,"platform":"MOBILE"""",
            isNativeApp = true,
        ),
        // ANDROID_MUSIC — YouTube Music Android client; music-specific endpoint.
        NativeClient(
            name = "ANDROID_MUSIC", version = "5.28.1", clientId = "21",
            url = "https://music.youtube.com/youtubei/v1/player?key=AIzaSyAOghZGza2MQSZkY_zfZ370N-PUdXEo8AI",
            userAgent = "com.google.android.apps.youtube.music/5.28.1 (Linux; U; Android 11) gzip",
            clientContextJson = """"osName":"Android","osVersion":"11","androidSdkVersion":30,"platform":"MOBILE"""",
            isNativeApp = true,
        ),
        // IOS — device-native client; must not use web headers, needs iOS device context.
        NativeClient(
            name = "IOS", version = "19.45.4", clientId = "5",
            url = "https://youtubei.googleapis.com/youtubei/v1/player?key=AIzaSyB-63vPrdThhKuerbB2N_WhIe4",
            userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 17_5 like Mac OS X;en_US) gzip",
            clientContextJson = """"osName":"iOS","osVersion":"17.5.1","deviceMake":"Apple","deviceModel":"iPhone16,2","platform":"MOBILE"""",
            isNativeApp = true,
            addApiFormatVersion = true,
        ),
        // WEB + PoToken — BotGuard WebView token with dynamically-fetched REQUEST_KEY.
        NativeClient(
            name = "WEB", version = "2.20260101.00.00", clientId = "1",
            url = "https://www.youtube.com/youtubei/v1/player",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
            origin = "https://www.youtube.com",
            usePoToken = true,
        ),
    )

    private fun fetchAudioStreamNative(videoId: String): String {
        val json = "application/json; charset=utf-8".toMediaType()
        val errors = mutableListOf<String>()

        for (client in nativeClients) {
            // OAuth client: skip if not logged in; get valid token (refreshing if needed).
            val bearerToken: String?
            if (client.useAuth) {
                val token = app.opentune.utils.YouTubeAuthManager.getValidAccessToken(context)
                if (token == null) { errors += "${client.name}: not logged in"; continue }
                bearerToken = token
            } else {
                bearerToken = null
            }

            // Fetch PoToken if this client requires it (WEB client path).
            val pot = if (client.usePoToken) {
                try { app.opentune.utils.potoken.OpenTunePoTokenProvider.getWebClientPoToken(videoId) }
                catch (e: Exception) { errors += "${client.name}(pot): ${e.message?.take(60)}"; null }
            } else null

            val visitorDataFrag = if (pot?.visitorData != null)
                ""","visitorData":"${pot.visitorData}"""" else ""
            val poTokenFrag = if (pot?.playerRequestPoToken != null)
                ""","serviceIntegrityDimensions":{"poToken":"${pot.playerRequestPoToken}"}""" else ""

            // clientContextJson fields go inside context.client; contextJson/embed go at context level.
            val clientFrag = if (client.clientContextJson.isNotBlank()) ",${client.clientContextJson}" else ""
            val contextFrag = when {
                client.useVideoEmbedUrl -> ""","thirdParty":{"embedUrl":"https://www.youtube.com/watch?v=$videoId"}"""
                client.contextJson.isNotBlank() -> ",${client.contextJson}"
                else -> ""
            }
            // html5Preference only makes sense for web clients; native apps don't use it.
            val playbackCtx = if (!client.isNativeApp)
                ""","playbackContext":{"contentPlaybackContext":{"html5Preference":"HTML5_PREF_WANTS"}}""" else ""

            val body = """{"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true,"context":{"client":{"clientName":"${client.name}","clientVersion":"${client.version}","hl":"en","gl":"US","timeZone":"UTC","utcOffsetMinutes":0$visitorDataFrag$clientFrag}$contextFrag}$poTokenFrag$playbackCtx}"""
            try {
                val reqBuilder = Request.Builder()
                    .url(client.url)
                    .post(body.toRequestBody(json))
                    .addHeader("User-Agent", client.userAgent)
                    .addHeader("Content-Type", "application/json")
                if (client.isNativeApp) {
                    // Native app clients authenticate via API key in URL.
                    // Web-style headers (X-YouTube-Client-*, Origin, Cookie) cause HTTP 400.
                    // X-Goog-Api-Format-Version: 2 is IOS-only; sending it to Android clients
                    // causes LOGIN_REQUIRED responses.
                    if (client.addApiFormatVersion) {
                        reqBuilder.addHeader("X-Goog-Api-Format-Version", "2")
                    }
                } else {
                    reqBuilder.addHeader("X-YouTube-Client-Name", client.clientId)
                    reqBuilder.addHeader("X-YouTube-Client-Version", client.version)
                    if (client.origin.isNotBlank()) {
                        reqBuilder.addHeader("Origin", client.origin)
                        reqBuilder.addHeader("Referer", "${client.origin}/watch?v=$videoId")
                    }
                    if (bearerToken != null) {
                        reqBuilder.addHeader("Authorization", "Bearer $bearerToken")
                        reqBuilder.addHeader("X-Goog-AuthUser", "0")
                    } else {
                        // SOCS=CAI= bypasses cookie-consent check for unauthenticated requests.
                        reqBuilder.addHeader("Cookie", "SOCS=CAI=")
                    }
                }
                if (pot?.visitorData != null) {
                    reqBuilder.addHeader("X-Goog-Visitor-Id", pot.visitorData)
                }
                val response = httpClient.newCall(reqBuilder.build()).execute()

                val rb = response.body?.string()
                if (!response.isSuccessful || rb.isNullOrBlank()) {
                    errors += "${client.name}: HTTP ${response.code} ${rb?.take(100)}"
                    continue
                }

                val root = JSONObject(rb)
                val playability = root.optJSONObject("playabilityStatus")
                val status = playability?.optString("status") ?: "?"
                if (status != "OK") {
                    val reason = playability?.optString("reason") ?: ""
                    val messages = playability?.optJSONArray("messages")?.let {
                        (0 until it.length()).map { i -> it.optString(i) }.joinToString(";")
                    } ?: ""
                    errors += "${client.name}: $status $reason${if (messages.isNotBlank()) " [$messages]" else ""}"
                    continue
                }

                val formats = root.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
                if (formats == null) { errors += "${client.name}: no adaptiveFormats"; continue }

                var bestUrl: String? = null; var bestBitrate = 0
                for (i in 0 until formats.length()) {
                    val fmt = formats.getJSONObject(i)
                    if (!fmt.optString("mimeType", "").startsWith("audio/")) continue
                    val u = fmt.optString("url", "").takeIf { it.isNotBlank() } ?: continue
                    val br = fmt.optInt("averageBitrate", 0).takeIf { it > 0 } ?: fmt.optInt("bitrate", 0)
                    if (br > bestBitrate) { bestBitrate = br; bestUrl = u }
                }
                if (bestUrl != null) {
                    Log.d(tag, "fetchAudioStreamNative($videoId) ok via ${client.name}")
                    return bestUrl
                }
                errors += "${client.name}: no direct audio URL in ${formats.length()} formats"

            } catch (e: Exception) {
                errors += "${client.name}: ${e.javaClass.simpleName} ${e.message?.take(80)}"
            }
        }
        error(errors.joinToString(" | "))
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
                """{"browseId":"VL$playlistId","context":{"client":{"clientName":"WEB","clientVersion":"2.20230101.00.00"}}}"""
            } else {
                """{"continuation":"$continuation","context":{"client":{"clientName":"WEB","clientVersion":"2.20230101.00.00"}}}"""
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
