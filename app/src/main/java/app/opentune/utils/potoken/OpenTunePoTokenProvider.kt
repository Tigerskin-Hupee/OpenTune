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

    /** Exposed for diagnostics — null = OK/success, non-null = last error message.
     *  Initial value "not_called" means getWebClientPoToken has never been invoked. */
    @Volatile
    var lastError: String? = "not_called"
        private set

    /** Tracks n-decode pipeline status for diagnostics.
     *  "none" = not attempted, "iife_null" = extractNDecodeFn returned null,
     *  "setup_fail" = setupNDecode returned false, "ready" = _nDecFn is set,
     *  "decoded" = at least one n-param was successfully decoded. */
    @Volatile
    var nDecodeStatus: String = "none"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var webView: PoTokenWebView? = null
    @Volatile private var visitorData: String? = null
    @Volatile private var streamingPot: String? = null
    // True = PoToken state is stale (e.g. after a 403), but webView is kept alive for n-decode.
    // The WebView is actually replaced on the next getWebClientPoToken call.
    @Volatile private var poTokenNeedsRebuild = false

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
        val shouldRecreate = webView == null || forceRecreate || poTokenNeedsRebuild || visitorData == null || webView!!.isExpired()

        if (shouldRecreate) {
            webView?.close()
            webView = null
            visitorData = null
            streamingPot = null
            poTokenNeedsRebuild = false

            runBlocking {
                val vd = withContext(Dispatchers.IO) { fetchVisitorData() }
                Log.d(TAG, "visitorData fetched: ${vd.take(20)}...")
                val wv = PoTokenWebView.create(App.instance)
                // Set webView immediately after page load so decodeNParam works
                // even if BotGuard initialization fails below.
                webView = wv
                visitorData = vd
                wv.awaitBotGuardReady()  // wait for BotGuard (may throw; n-decode unaffected)
                Log.d(TAG, "WebView PoToken generator ready")
                val sp = wv.generatePoToken(vd)
                Log.d(TAG, "streaming PoToken generated: ${sp.take(20)}...")
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

    /** Flag the PoToken state as stale so it is rebuilt on the next getWebClientPoToken call.
     *  The WebView itself is NOT closed here — it remains usable for n-decode/sig-decode so
     *  stream re-resolution can still decode the n-parameter while the PoToken is refreshed. */
    @Synchronized
    fun forceRecreate() {
        visitorData = null
        streamingPot = null
        poTokenNeedsRebuild = true
        lastError = "recreate_pending"
        Log.i(TAG, "forceRecreate: PoToken marked stale; WebView kept alive for n-decode")
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = getWebClientPoToken(videoId)
    // Android and iOS clients don't use WEB-type PoTokens; return null so NPE
    // falls back to WEB client where our token is valid.
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null
    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null

    /** Decode YouTube CDN n-parameter using the player JS function loaded in the WebView. */
    suspend fun decodeNParam(nEncoded: String): String? {
        val result = try {
            webView?.decodeNParam(nEncoded)
        } catch (e: Exception) {
            Log.w(TAG, "decodeNParam('${nEncoded.take(12)}..') failed: ${e.message}")
            null
        }
        if (result != null && result != nEncoded) nDecodeStatus = "decoded"
        return result
    }

    /** Decode YouTube signatureCipher 's' param using the player JS function in the WebView. */
    suspend fun decodeSig(sig: String): String? {
        return try {
            webView?.decodeSig(sig)
        } catch (e: Exception) {
            Log.w(TAG, "decodeSig('${sig.take(12)}..') failed: ${e.message}")
            null
        }
    }

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
