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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @GuardedBy("this")
    private var webView: PoTokenWebView? = null
    @GuardedBy("this")
    private var visitorData: String? = null
    @GuardedBy("this")
    private var streamingPot: String? = null

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        return try {
            getWebClientPoTokenInternal(videoId, forceRecreate = false)
        } catch (e: Exception) {
            Log.e(TAG, "getWebClientPoToken($videoId) failed: ${e.message}")
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
                val wv = PoTokenWebView.create(App.instance)
                val sp = wv.generatePoToken(vd)
                visitorData = vd
                webView = wv
                streamingPot = sp
                Log.d(TAG, "Initialized WebView PoToken provider, visitorData=$vd")
            }
        }

        val playerPot = try {
            runBlocking { webView!!.generatePoToken(videoId) }
        } catch (e: Exception) {
            if (forceRecreate) throw e
            Log.w(TAG, "generatePoToken failed, retrying with fresh WebView: ${e.message}")
            return getWebClientPoTokenInternal(videoId, forceRecreate = true)
        }

        return PoTokenResult(visitorData!!, playerPot, streamingPot)
    }

    // Returns null for all other clients — BotGuard (WEB) tokens are sufficient.
    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null
    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null

    private fun fetchVisitorData(): String {
        val body = """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20241030.09.00","hl":"en","gl":"US"}}}"""
        val response = httpClient.newCall(
            Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/guide?prettyPrint=false")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("X-YouTube-Client-Name", "1")
                .addHeader("X-YouTube-Client-Version", "2.20241030.09.00")
                .addHeader("User-Agent", PoTokenWebView.USER_AGENT)
                .addHeader("Origin", "https://www.youtube.com")
                .addHeader("Referer", "https://www.youtube.com/")
                .build()
        ).execute()

        if (!response.isSuccessful) {
            throw PoTokenException("fetchVisitorData HTTP ${response.code}")
        }
        val json = JSONObject(response.body!!.string())
        return json.getJSONObject("responseContext").getString("visitorData")
    }
}

// Stub annotation to document the locking contract; not enforced at runtime.
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FIELD)
private annotation class GuardedBy(val value: String)
