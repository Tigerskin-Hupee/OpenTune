/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * Verification test that proves NewPipeExtractor can:
 *   1. Initialise with a custom downloader
 *   2. Resolve a YouTube search query and return non-empty results
 *   3. Resolve a known video ID into a playable HTTP audio stream URL
 *
 * If this test passes in CI, NewPipeExtractor is a viable replacement for
 * the broken yt-dlp + custom-innertube combo currently in the app.
 *
 * The test makes real HTTP calls to youtube.com — CI runners have outbound
 * internet so this is fine.
 */
package app.opentune.innertube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertNotNull
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
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit

class NewPipeExtractorVerificationTest {

    /** Inline OkHttp downloader (no dependency on production code). */
    private class TestDownloader : Downloader() {
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody(null)
            val builder = okhttp3.Request.Builder().url(request.url())
            when (request.httpMethod().uppercase()) {
                "GET" -> builder.get()
                "HEAD" -> builder.head()
                "POST" -> builder.post(body ?: ByteArray(0).toRequestBody(null))
                else -> builder.method(request.httpMethod(), body)
            }
            for ((name, values) in request.headers()) {
                for (value in values) builder.addHeader(name, value)
            }
            client.newCall(builder.build()).execute().use { resp ->
                return Response(
                    resp.code,
                    resp.message,
                    resp.headers.toMultimap(),
                    resp.body?.string(),
                    resp.request.url.toString(),
                )
            }
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun init() {
            NewPipe.init(TestDownloader(), Localization("en", "US"), ContentCountry("US"))
        }
    }

    @Test(timeout = 60_000)
    fun `search returns non-empty results for a popular query`() {
        val extractor = ServiceList.YouTube.getSearchExtractor("Beethoven Symphony 5")
        extractor.fetchPage()
        val items = extractor.initialPage.items
        println("[verify] search returned ${items.size} items")
        assertTrue("Search returned no items at all", items.isNotEmpty())

        val streamItem = items.firstOrNull { it is StreamInfoItem } as? StreamInfoItem
        assertNotNull("No StreamInfoItem in search results", streamItem)
        println("[verify] first stream: name='${streamItem!!.name}' url='${streamItem.url}'")
        assertTrue("StreamInfoItem url not http: ${streamItem.url}", streamItem.url.startsWith("http"))
    }

    @Test(timeout = 60_000)
    fun `stream extraction returns playable audio URL`() {
        // Rick Astley - Never Gonna Give You Up. Stable, public, never region-locked.
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val streams = info.audioStreams
        assertNotNull("audioStreams was null", streams)
        assertTrue("audioStreams was empty", streams.isNotEmpty())

        val best = streams.maxByOrNull { it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate }!!
        val url = best.content
        println("[verify] best audio: bitrate=${best.averageBitrate} format=${best.format?.name} url=${url?.take(120)}")
        assertNotNull("audio stream had null url", url)
        assertTrue("audio url not http: $url", url!!.startsWith("http"))
    }
}
