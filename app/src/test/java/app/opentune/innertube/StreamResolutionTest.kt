/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * JVM test to verify which Innertube player client can resolve audio streams.
 * Makes real HTTP calls — requires outbound internet access.
 *
 * Run with: ./gradlew :app:test --tests "app.opentune.innertube.StreamResolutionTest"
 */
package app.opentune.innertube

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class StreamResolutionTest {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // A known public YouTube video (YOASOBI - Idol)
    private val testVideoId = "y1JqI2z2npc"

    data class ClientConfig(
        val name: String, val version: String, val clientId: String,
        val url: String, val userAgent: String, val origin: String,
    )

    private val clients = listOf(
        ClientConfig("ANDROID_TESTSUITE", "1.9", "30",
            "https://www.youtube.com/youtubei/v1/player",
            "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip",
            "https://www.youtube.com"),
        ClientConfig("WEB_REMIX", "1.20230501.01.00", "67",
            "https://music.youtube.com/youtubei/v1/player",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
            "https://music.youtube.com"),
        ClientConfig("WEB", "2.20230101.00.00", "1",
            "https://www.youtube.com/youtubei/v1/player",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
            "https://www.youtube.com"),
        ClientConfig("MWEB", "2.20230101.00.00", "2",
            "https://www.youtube.com/youtubei/v1/player",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1",
            "https://www.youtube.com"),
        ClientConfig("TVHTML5", "7.20230516.00.00", "7",
            "https://www.youtube.com/youtubei/v1/player",
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
            "https://www.youtube.com"),
    )

    @Test
    fun `probe all clients and report which ones return playable audio streams`() {
        val json = "application/json; charset=utf-8".toMediaType()
        val results = mutableListOf<String>()
        var anySuccess = false

        for (client in clients) {
            val body = """{"videoId":"$testVideoId","context":{"client":{"clientName":"${client.name}","clientVersion":"${client.version}","hl":"en","gl":"US"}}}"""
            try {
                val response = httpClient.newCall(
                    Request.Builder()
                        .url(client.url)
                        .post(body.toRequestBody(json))
                        .addHeader("User-Agent", client.userAgent)
                        .addHeader("X-YouTube-Client-Name", client.clientId)
                        .addHeader("X-YouTube-Client-Version", client.version)
                        .addHeader("Origin", client.origin)
                        .addHeader("Referer", "${client.origin}/")
                        .build()
                ).execute()

                val rb = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val snippet = rb.take(120).replace("\n", " ")
                    results += "  [${client.name}] FAIL HTTP ${response.code}: $snippet"
                    continue
                }

                val root = JSONObject(rb)
                val status = root.optJSONObject("playabilityStatus")?.optString("status") ?: "?"
                val reason = root.optJSONObject("playabilityStatus")?.optString("reason") ?: ""

                if (status != "OK") {
                    results += "  [${client.name}] FAIL status=$status reason=$reason"
                    continue
                }

                val adaptiveFormats = root.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
                if (adaptiveFormats == null) {
                    results += "  [${client.name}] FAIL no adaptiveFormats"
                    continue
                }

                var directCount = 0
                var cipherCount = 0
                var bestBitrate = 0
                for (i in 0 until adaptiveFormats.length()) {
                    val fmt = adaptiveFormats.getJSONObject(i)
                    if (!fmt.optString("mimeType", "").startsWith("audio/")) continue
                    if (fmt.has("url") && fmt.optString("url").isNotBlank()) directCount++
                    if (fmt.has("signatureCipher")) cipherCount++
                    val br = fmt.optInt("averageBitrate", 0).takeIf { it > 0 } ?: fmt.optInt("bitrate", 0)
                    if (br > bestBitrate) bestBitrate = br
                }

                if (directCount > 0) {
                    results += "  [${client.name}] OK  direct=$directCount cipher=$cipherCount bestBitrate=$bestBitrate"
                    anySuccess = true
                } else if (cipherCount > 0) {
                    results += "  [${client.name}] CIPHER-ONLY direct=0 cipher=$cipherCount (needs decryption)"
                } else {
                    results += "  [${client.name}] FAIL no audio formats found"
                }

            } catch (e: Exception) {
                results += "  [${client.name}] EXCEPTION ${e.javaClass.simpleName}: ${e.message?.take(80)}"
            }
        }

        println("\n=== Stream Client Probe for videoId=$testVideoId ===")
        results.forEach { println(it) }
        println("=== End ===\n")

        assertTrue(
            "No client returned a direct playable audio URL.\nResults:\n${results.joinToString("\n")}",
            anySuccess
        )
    }
}
