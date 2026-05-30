/*
 * Copyright (C) 2025 OpenTune
 * Adapted from NewPipe (TeamNewPipe/NewPipe), which is licensed GPL-3.0.
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.utils.potoken

import android.util.Log
import app.opentune.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import java.util.concurrent.TimeUnit

object OpenTunePoTokenProvider : PoTokenProvider {

    private const val TAG = "OpenTunePoTokenProvider"

    /** Exposed for diagnostics — last error from getWebClientPoToken, or null if OK. */
    @Volatile
    var lastError: String? = null
        private set

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var webView: PoTokenWebView? = null
    @Volatile private var visitorData: String? = null
    @Volatile private var streamingPot: String? = null

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        return try {
            getWebClientPoTokenInternal(videoId, forceRecreate = false)
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message?.take(200)}"
            Log.e(TAG, "getWebClientPoToken($videoId) FAILED: $msg")
            lastError = msg
            null
        }
    }

    @Synchronized
    private fun getWebClientPoTokenInternal(videoId: String, forceRecreate: Boolean): PoTokenResult {
        val shouldRecreate = webView == null || forceRecreate || webView!!.isExpired()

        if (shouldRecreate) {
            webView?.close()
            webView = null
            visitorData = null
            streamingPot = null

            runBlocking {
                val vd = withContext(Dispatchers.IO) { fetchVisitorData() }
                Log.d(TAG, "visitorData fetched: ${vd.take(20)}...")
                val wv = PoTokenWebView.create(App.instance)
                Log.d(TAG, "WebView PoToken generator ready")
                val sp = wv.generatePoToken(vd)
                Log.d(TAG, "streaming PoToken generated: ${sp.take(20)}...")
                visitorData = vd
                webView = wv
                streamingPot = sp
            }
            lastError = null
        }

        val playerPot = try {
            runBlocking { webView!!.generatePoToken(videoId) }
        } catch (e: Exception) {
            if (forceRecreate) throw e
            Log.w(TAG, "generatePoToken failed, retrying: ${e.message}")
            return getWebClientPoTokenInternal(videoId, forceRecreate = true)
        }

        Log.d(TAG, "PoToken ok for $videoId: player=${playerPot.take(20)}...")
        return PoTokenResult(visitorData!!, playerPot, streamingPot)
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null
    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null

    private fun fetchVisitorData(): String {
        val clientCtx = """{"client":{"clientName":"WEB","clientVersion":"2.20241030.09.00","hl":"en","gl":"US"}}"""
        val endpoints = listOf(
            "https://www.youtube.com/youtubei/v1/guide?prettyPrint=false" to
                """{"context":$clientCtx}""",
            "https://www.youtube.com/youtubei/v1/search?prettyPrint=false" to
                """{"query":"a","context":$clientCtx}""",
        )

        var lastEx: Exception? = null
        for ((url, body) in endpoints) {
            try {
                val response = httpClient.newCall(
                    Request.Builder()
                        .url(url)
                        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .addHeader("X-YouTube-Client-Name", "1")
                        .addHeader("X-YouTube-Client-Version", "2.20241030.09.00")
                        .addHeader("User-Agent", PoTokenWebView.USER_AGENT)
                        .addHeader("Origin", "https://www.youtube.com")
                        .build()
                ).execute()

                if (!response.isSuccessful) {
                    lastEx = PoTokenException("fetchVisitorData HTTP ${response.code} from $url")
                    continue
                }
                val vd = JSONObject(response.body!!.string())
                    .getJSONObject("responseContext")
                    .getString("visitorData")
                if (vd.isNotBlank()) return vd
                lastEx = PoTokenException("visitorData blank from $url")
            } catch (e: Exception) {
                lastEx = e
                Log.w(TAG, "fetchVisitorData failed for $url: ${e.message}")
            }
        }
        throw lastEx ?: PoTokenException("fetchVisitorData: all endpoints failed")
    }
}
