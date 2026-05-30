/*
 * Copyright (C) 2025 OpenTune
 * Adapted from NewPipe (TeamNewPipe/NewPipe), which is licensed GPL-3.0.
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.utils.potoken

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

class PoTokenWebView private constructor(private val context: Context) {

    private lateinit var webView: WebView
    private val initDeferred = CompletableDeferred<Unit>()
    private val poTokenLock = Any()
    private val poTokenDeferreds = mutableListOf<Pair<String, CompletableDeferred<String>>>()
    private lateinit var expirationInstant: Instant

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private suspend fun initialize() {
        withContext(Dispatchers.Main) {
            webView = WebView(context)
            webView.settings.apply {
                @Suppress("SetJavaScriptEnabled")
                javaScriptEnabled = true
                blockNetworkLoads = true
                userAgentString = USER_AGENT
            }
            webView.addJavascriptInterface(this@PoTokenWebView, JS_INTERFACE)
            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.message().contains("Uncaught")) {
                        val ex = PoTokenException("WebView JS: ${m.message()}")
                        Log.e(TAG, "Uncaught JS error: ${m.message()}")
                        initDeferred.completeExceptionally(ex)
                        cancelAllDeferreds(ex)
                    }
                    return super.onConsoleMessage(m)
                }
            }

            val html = context.assets.open("po_token.html").bufferedReader().use { it.readText() }
            webView.loadDataWithBaseURL(
                "https://www.youtube.com",
                html.replaceFirst("</script>", "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"),
                "text/html", "utf-8", null
            )
        }
        // Suspend here (off main thread) until integrityToken is set in JS
        initDeferred.await()
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val responseBody = makeBotguardRequest(
                    "https://www.youtube.com/api/jnn/v1/Create",
                    "[ \"$REQUEST_KEY\" ]"
                )
                val parsedChallenge = parseChallengeData(responseBody)
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript(
                        """try {
                            data = $parsedChallenge
                            runBotGuard(data).then(function(result) {
                                this.webPoSignalOutput = result.webPoSignalOutput
                                $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                            }, function(error) {
                                $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                            })
                        } catch(error) {
                            $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                        }""",
                        null
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "downloadAndRunBotguard failed", e)
                initDeferred.completeExceptionally(e)
            }
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        Log.e(TAG, "JS init error: $error")
        initDeferred.completeExceptionally(PoTokenException("JS init: $error"))
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val responseBody = makeBotguardRequest(
                    "https://www.youtube.com/api/jnn/v1/GenerateIT",
                    "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]"
                )
                val (integrityToken, ttlSeconds) = parseIntegrityTokenData(responseBody)
                expirationInstant = Instant.now().plusSeconds(ttlSeconds - 600)
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("this.integrityToken = $integrityToken") {
                        Log.d(TAG, "Initialized, expiry=${ttlSeconds}s")
                        initDeferred.complete(Unit)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onRunBotguardResult failed", e)
                initDeferred.completeExceptionally(e)
            }
        }
    }

    suspend fun generatePoToken(identifier: String): String {
        val deferred = CompletableDeferred<String>()
        synchronized(poTokenLock) { poTokenDeferreds.add(identifier to deferred) }

        withContext(Dispatchers.Main) {
            val u8 = stringToU8(identifier)
            webView.evaluateJavascript(
                """try {
                    identifier = "$identifier"
                    u8Identifier = $u8
                    poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                    poTokenU8String = ""
                    for (i = 0; i < poTokenU8.length; i++) {
                        if (i != 0) poTokenU8String += ","
                        poTokenU8String += poTokenU8[i]
                    }
                    $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                } catch(error) {
                    $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                }""",
                null
            )
        }
        return deferred.await()
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val d = popDeferred(identifier) ?: return
        try {
            d.complete(u8ToBase64(poTokenU8))
        } catch (e: Exception) {
            d.completeExceptionally(e)
        }
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        popDeferred(identifier)?.completeExceptionally(PoTokenException("obtainPoToken: $error"))
    }

    fun isExpired(): Boolean =
        ::expirationInstant.isInitialized && Instant.now().isAfter(expirationInstant)

    fun close() {
        Handler(Looper.getMainLooper()).post {
            webView.clearHistory()
            webView.clearCache(true)
            webView.loadUrl("about:blank")
            webView.onPause()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    private fun popDeferred(identifier: String): CompletableDeferred<String>? =
        synchronized(poTokenLock) {
            val idx = poTokenDeferreds.indexOfFirst { it.first == identifier }
            if (idx < 0) null else poTokenDeferreds.removeAt(idx).second
        }

    private fun cancelAllDeferreds(e: Exception) {
        val all = synchronized(poTokenLock) {
            val copy = poTokenDeferreds.toList(); poTokenDeferreds.clear(); copy
        }
        all.forEach { (_, d) -> d.completeExceptionally(e) }
    }

    private fun makeBotguardRequest(url: String, body: String): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json+protobuf".toMediaType()))
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "application/json")
            .addHeader("x-goog-api-key", GOOGLE_API_KEY)
            .addHeader("x-user-agent", "grpc-web-javascript/0.1")
            .build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) throw PoTokenException("HTTP ${response.code} from $url")
        return response.body?.string() ?: throw PoTokenException("Empty body from $url")
    }

    companion object {
        private const val TAG = "PoTokenWebView"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw" // NOSONAR
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS_INTERFACE = "PoTokenWebView"

        suspend fun create(context: Context): PoTokenWebView {
            val wv = PoTokenWebView(context)
            wv.initialize()
            return wv
        }
    }
}

class PoTokenException(message: String) : Exception(message)
