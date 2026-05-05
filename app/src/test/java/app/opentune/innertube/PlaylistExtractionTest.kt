/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * JVM test to verify NewPipeExtractor can fetch YouTube playlist items.
 * Makes real HTTP calls — CI must have outbound internet access.
 *
 * Run with: ./gradlew :app:test --tests "app.opentune.innertube.PlaylistExtractionTest"
 */
package app.opentune.innertube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit

class PlaylistExtractionTest {

    companion object {
        @BeforeClass @JvmStatic
        fun setup() {
            NewPipe.init(object : Downloader() {
                private val client = OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                override fun execute(request: Request): Response {
                    val body = request.dataToSend()?.toRequestBody(null)
                    val builder = okhttp3.Request.Builder().url(request.url())
                    when (request.httpMethod().uppercase()) {
                        "GET" -> builder.get()
                        "POST" -> builder.post(body ?: ByteArray(0).toRequestBody(null))
                        else -> builder.method(request.httpMethod(), body)
                    }
                    for ((name, values) in request.headers())
                        for (v in values) builder.addHeader(name, v)
                    client.newCall(builder.build()).execute().use { resp ->
                        return Response(resp.code, resp.message,
                            resp.headers.toMultimap(), resp.body?.string(),
                            resp.request.url.toString())
                    }
                }
            }, Localization("en", "US"), ContentCountry("US"))
        }
    }

    @Test
    fun `search playlists returns results`() {
        val info = SearchInfo.getInfo(
            ServiceList.YouTube,
            ServiceList.YouTube.searchQHFactory.fromQuery("yoasobi", listOf("music_playlists"), ""),
        )
        val playlists = info.relatedItems
        println("[TEST] searchPlaylists: ${playlists.size} results")
        playlists.take(3).forEach { println("  -> ${it.name} | url=${it.url}") }
        assertTrue("Expected at least one playlist result", playlists.isNotEmpty())
    }

    @Test
    fun `PlaylistInfo returns StreamInfoItems for a known public playlist`() {
        // A well-known public YouTube playlist (Top 100 - Global, YouTube Music Charts)
        val playlistUrl = "https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"
        println("[TEST] Fetching playlist: $playlistUrl")

        val info = PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl)
        println("[TEST] relatedItems count: ${info.relatedItems.size}")
        println("[TEST] item types: ${info.relatedItems.map { it.javaClass.simpleName }.toSet()}")

        val streams = info.relatedItems.filterIsInstance<StreamInfoItem>()
        println("[TEST] StreamInfoItem count: ${streams.size}")
        streams.take(3).forEach { println("  -> ${it.name} | url=${it.url}") }

        assertTrue(
            "PlaylistInfo returned ${info.relatedItems.size} items but 0 StreamInfoItem — " +
            "types found: ${info.relatedItems.map { it.javaClass.simpleName }.toSet()}",
            streams.isNotEmpty()
        )
    }

    @Test
    fun `first playlist from search can have its songs fetched`() {
        val searchInfo = SearchInfo.getInfo(
            ServiceList.YouTube,
            ServiceList.YouTube.searchQHFactory.fromQuery("yoasobi", listOf("music_playlists"), ""),
        )
        val firstPlaylist = searchInfo.relatedItems.firstOrNull()
            ?: throw AssertionError("No playlists returned from search")

        val playlistUrl = firstPlaylist.url ?: throw AssertionError("Playlist URL is null")
        println("[TEST] First playlist: ${firstPlaylist.name} url=$playlistUrl")

        val normalised = playlistUrl
            .replace("music.youtube.com", "www.youtube.com")
            .let { u -> if ("watch?" in u) "https://www.youtube.com/playlist?list=${
                u.substringAfter("list=").substringBefore("&")
            }" else u }

        println("[TEST] Normalised url: $normalised")

        val info = PlaylistInfo.getInfo(ServiceList.YouTube, normalised)
        val streams = info.relatedItems.filterIsInstance<StreamInfoItem>()
        println("[TEST] Songs fetched: ${streams.size}")
        streams.take(3).forEach { s -> println("  -> ${s.name} | url=${s.url}") }

        assertTrue(
            "Expected songs from playlist '${firstPlaylist.name}' but got 0. " +
            "relatedItems=${info.relatedItems.size}, types=${info.relatedItems.map { it.javaClass.simpleName }.toSet()}",
            streams.isNotEmpty()
        )
    }
}
