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
import java.net.Inet4Address
import java.net.InetAddress
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class PoTokenWebView private constructor(private val context: Context) {

    private lateinit var webView: WebView
    private val initDeferred = CompletableDeferred<Unit>()
    private val poTokenLock = Any()
    private val poTokenDeferreds = mutableListOf<Pair<String, CompletableDeferred<String>>>()
    private lateinit var expirationInstant: Instant
    // Resolved dynamically from player JS before initialization; falls back to constant.
    private var requestKey: String = REQUEST_KEY

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // Prefer IPv4 so youtube.com resolves consistently with the CDN URL ip= binding.
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addrs = okhttp3.Dns.SYSTEM.lookup(hostname)
                val v4 = addrs.filterIsInstance<Inet4Address>()
                return if (v4.isNotEmpty()) v4 else addrs
            }
        })
        .build()

    private suspend fun initialize() {
        withContext(Dispatchers.Main) {
            webView = WebView(context)
            webView.settings.apply {
                @Suppress("SetJavaScriptEnabled")
                javaScriptEnabled = true
                // Allow BotGuard to make validation network requests — blocking them
                // causes BotGuard to produce lower-quality tokens that YouTube rejects.
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
                    "[ \"$requestKey\" ]"
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
                    "[ \"$requestKey\", \"$botguardResponse\" ]"
                )
                val (integrityToken, ttlSeconds) = parseIntegrityTokenData(responseBody)
                expirationInstant = Instant.now().plusSeconds(ttlSeconds).minus(10, ChronoUnit.MINUTES)
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript(
                        """try {
                            this.integrityToken = $integrityToken
                            createPoTokenMinter(webPoSignalOutput, integrityToken).then(function() {
                                $JS_INTERFACE.onMinterCreated()
                            }).catch(function(error) {
                                $JS_INTERFACE.onJsInitializationError(error + "\n" + (error.stack || ''))
                            })
                        } catch(error) {
                            $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                        }""",
                        null
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "onRunBotguardResult failed", e)
                initDeferred.completeExceptionally(e)
            }
        }
    }

    @JavascriptInterface
    fun onMinterCreated() {
        Log.d(TAG, "PoToken minter ready, init complete")
        initDeferred.complete(Unit)
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
                    obtainPoToken(u8Identifier).then(function(poTokenU8) {
                        poTokenU8String = poTokenU8.join(",")
                        $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                    }).catch(function(error) {
                        $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + (error.stack || ''))
                    })
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

    // Fetch JNN request key, n-decode function, and sig-decode code from YouTube's player JS.
    private data class PlayerJsData(
        val requestKey: String?,
        val nDecodeFn: String?,
        val sigDecodeFn: String?,
    )

    private fun fetchPlayerJsData(): PlayerJsData {
        // Use embed pages — they bypass GDPR consent gates and are smaller than the homepage.
        // The consent page at youtube.com/consent lacks the player script path entirely.
        val sourceUrls = listOf(
            "https://www.youtube.com/embed/jNQXAC9IVRw",
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=jNQXAC9IVRw",
        )
        var lastFailReason = "no_attempt"
        for (sourceUrl in sourceUrls) {
            try {
                val pageHtml = httpClient.newCall(
                    Request.Builder().url(sourceUrl)
                        .addHeader("User-Agent", USER_AGENT)
                        .addHeader("Accept-Language", "en-US,en;q=0.9")
                        .get().build()
                ).execute().body?.string()
                if (pageHtml == null) {
                    Log.w(TAG, "fetchPlayerJsData: null body from $sourceUrl")
                    lastFailReason = "null_body"
                    continue
                }

                val scriptPath = Regex("""/s/player/[a-f0-9]+/player_ias\.vflset[^"' ]*base\.js""")
                    .find(pageHtml)?.value
                if (scriptPath == null) {
                    Log.w(TAG, "fetchPlayerJsData: script path not found in $sourceUrl (${pageHtml.length} bytes)")
                    lastFailReason = "no_path"
                    continue
                }

                val jsContent = httpClient.newCall(
                    Request.Builder().url("https://www.youtube.com$scriptPath")
                        .addHeader("User-Agent", USER_AGENT).get().build()
                ).execute().body?.string()
                if (jsContent == null) {
                    Log.w(TAG, "fetchPlayerJsData: null JS body from $scriptPath")
                    lastFailReason = "null_js"
                    continue
                }

                // --- JNN request key ---
                val jnnIdx = jsContent.indexOf("jnn/v1/Create")
                val requestKey: String? = if (jnnIdx >= 0) {
                    val nearWindow = jsContent.substring(maxOf(0, jnnIdx - 1000), minOf(jsContent.length, jnnIdx + 200))
                    Regex("""stringify\(\s*\[\s*["']([A-Za-z0-9_-]{15,30})["']\s*\]""").find(nearWindow)?.groupValues?.get(1)
                        ?: Regex("""\[\s*["']([A-Za-z0-9_-]{15,30})["']\s*[,\]]""").find(nearWindow)?.groupValues?.get(1)
                        ?: run {
                            val wideWindow = jsContent.substring(maxOf(0, jnnIdx - 5000), jnnIdx + 100)
                            Regex("""=\s*["']([A-Za-z0-9_-]{15,30})["']""").findAll(wideWindow)
                                .lastOrNull()?.groupValues?.get(1)
                        }
                } else null
                if (requestKey != null) {
                    Log.d(TAG, "Dynamic REQUEST_KEY: ${requestKey.take(8)}...")
                    lastRequestKeyHint = "dynamic:${requestKey.take(8)}"
                } else {
                    Log.w(TAG, "jnn/v1/Create not in player JS or key not found; using fallback")
                    lastRequestKeyHint = "fallback:${REQUEST_KEY.take(8)}"
                }

                // --- n-decode function ---
                val nDecodeFn = extractNDecodeFn(jsContent)
                    ?.also { Log.d(TAG, "n-decode fn extracted (${it.length} chars)") }
                    ?: run { Log.w(TAG, "n-decode fn not found in player JS"); null }

                // --- sig-decode code ---
                val sigDecodeFn = extractSigDecodeCode(jsContent)
                    ?.also { Log.d(TAG, "sig-decode code extracted (${it.length} chars)") }
                    ?: run { Log.w(TAG, "sig-decode code not found in player JS"); null }

                return PlayerJsData(requestKey, nDecodeFn, sigDecodeFn)
            } catch (e: Exception) {
                lastFailReason = "ex:${e.javaClass.simpleName}"
                Log.w(TAG, "fetchPlayerJsData: ${e.message} (source=$sourceUrl)")
            }
        }
        // All sources failed — encode the reason so diagnostics never just shows "fetch_fail"
        lastRequestKeyHint = "fail_$lastFailReason:${REQUEST_KEY.take(8)}"
        Log.w(TAG, "fetchPlayerJsData: all sources failed ($lastFailReason), using hardcoded fallback key")
        return PlayerJsData(null, null, null)
    }

    // Extract YouTube's signature-cipher decode code (helper object + main fn) from player JS.
    // The extracted code, when eval'd, sets decodeSig_global = mainFn so WebView can call it.
    private fun extractSigDecodeCode(js: String): String? {
        // Find the function called to decode the 's' parameter from signatureCipher
        val fnName = listOf(
            Regex("""[;,=]\s*([a-zA-Z0-9$]{2,})\(decodeURIComponent\([^)]+\.get\("s"\)"""),
            Regex("""\.sig\s*\|\|\s*([a-zA-Z0-9$]{2,})\("""),
            Regex("""a\.set\("sig"\s*,\s*([a-zA-Z0-9$]{2,})\("""),
        ).firstNotNullOfOrNull { it.find(js) }?.groupValues?.get(1) ?: return null
        Log.d(TAG, "sig decode fn: $fnName")

        // Extract main function body — player JS uses both `function NAME(` and `NAME=function(`
        val fnDefIdx = js.indexOf("function $fnName(").takeIf { it >= 0 }
            ?: js.indexOf("$fnName=function(").takeIf { it >= 0 }
            ?: return null
        val bodyOpen = js.indexOf("{", fnDefIdx).takeIf { it >= 0 } ?: return null
        val fnBody = extractBalanced(js, bodyOpen) ?: return null
        val paramOpen = js.indexOf("(", fnDefIdx)
        val paramEnd = js.indexOf(")", paramOpen)
        val params = js.substring(paramOpen + 1, paramEnd)

        // Find helper object name (first `OBJ.method(a` call in function body)
        val helperName = Regex("""([a-zA-Z0-9$]{2,})\.[a-zA-Z0-9$]+\(a""")
            .find(fnBody)?.groupValues?.get(1) ?: return null
        Log.d(TAG, "sig helper obj: $helperName")

        // Extract helper object definition
        val helperSearch = js.indexOf("var $helperName={")
            .takeIf { it >= 0 }
            ?: js.indexOf(",$helperName={").takeIf { it >= 0 }?.plus(1)
            ?: return null
        val helperOpen = js.indexOf("{", helperSearch).takeIf { it >= 0 } ?: return null
        val helperBody = extractBalanced(js, helperOpen) ?: return null

        // Build self-contained code block; exposes decodeSig_global in outer (WebView) scope
        return "var $helperName=$helperBody;\nfunction $fnName($params) $fnBody\ndecodeSig_global=$fnName;"
    }

    // Extract the YouTube n-parameter decode function from player JS.
    // YouTube stores it as an array: var X=[function(a){...}] called as X[0](n).
    private fun extractNDecodeFn(js: String): String? {
        // Find the variable name of the n-decode array via the call site pattern
        val arrName = listOf(
            Regex("""\.get\("n"\)\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]{2,})\[0\]\("""),
            Regex("""\.get\("n"\)\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]{2,})\("""),
        ).firstNotNullOfOrNull { it.find(js) }?.groupValues?.get(1) ?: return null

        // Find var arrName=[ and extract bracket-balanced content
        val searchToken = "var $arrName=["
        val start = js.indexOf(searchToken).takeIf { it >= 0 }
            ?: js.indexOf(",$arrName=[").takeIf { it >= 0 }?.plus(1)
            ?: return null
        val fnStart = start + searchToken.length - 1  // points at '['
        return extractBalanced(js, fnStart)
    }

    // Extract bracket-balanced content starting at `start` (must be '[', '{', or '(').
    private fun extractBalanced(js: String, start: Int): String? {
        if (start >= js.length) return null
        val closeChar = when (js[start]) { '[' -> ']'; '{' -> '}'; '(' -> ')'; else -> return null }
        var depth = 0
        var inString = false
        var stringChar = ' '
        var i = start
        while (i < js.length) {
            val c = js[i]
            if (inString) {
                if (c == stringChar && (i == start || js[i - 1] != '\\')) inString = false
            } else when (c) {
                '"', '\'', '`' -> { inString = true; stringChar = c }
                '[', '{', '(' -> depth++
                ']', '}', ')' -> { depth--; if (depth == 0) return js.substring(start, i + 1) }
            }
            i++
        }
        return null
    }

    // Decode a YouTube signature cipher 's' value using the injected player JS decode function.
    // Returns decoded signature, or null if unavailable/failed.
    suspend fun decodeSig(sig: String): String? {
        val deferred = CompletableDeferred<String?>()
        withContext(Dispatchers.Main) {
            val escaped = sig.replace("\\", "\\\\").replace("\"", "\\\"")
            webView.evaluateJavascript(
                """(function(){try{return decodeSig("$escaped");}catch(e){return null;}})()"""
            ) { result ->
                deferred.complete(
                    if (result == null || result == "null") null
                    else result.trim('"').ifBlank { null }
                )
            }
        }
        return deferred.await()
    }

    // Decode a YouTube CDN URL's n-parameter using the injected player JS function.
    // Returns the decoded n value, or null if unavailable/failed.
    suspend fun decodeNParam(nEncoded: String): String? {
        val deferred = CompletableDeferred<String?>()
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(
                """(function(){try{return decodeN("${nEncoded.replace("\\", "\\\\").replace("\"", "\\\"")}");}catch(e){return null;}})()"""
            ) { result ->
                deferred.complete(
                    if (result == null || result == "null") null
                    else result.trim('"').ifBlank { null }
                )
            }
        }
        return deferred.await()
    }

    companion object {
        private const val TAG = "PoTokenWebView"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw" // NOSONAR
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        /** Diagnostic: "dynamic:XXXXXXXX" if key was extracted from player JS, "fallback:XXXXXXXX" otherwise. */
        @Volatile var lastRequestKeyHint: String = "unknown"
            private set
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
        private const val JS_INTERFACE = "PoTokenWebView"

        suspend fun create(context: Context): PoTokenWebView {
            val wv = PoTokenWebView(context)
            val jsData = withContext(Dispatchers.IO) { wv.fetchPlayerJsData() }
            if (jsData.requestKey != null) wv.requestKey = jsData.requestKey
            else Log.w(TAG, "Using fallback REQUEST_KEY")
            wv.initialize()
            // Inject n-decode and sig-decode functions into the already-initialized WebView
            withContext(Dispatchers.Main) {
                if (jsData.nDecodeFn != null) {
                    val escaped = jsData.nDecodeFn.replace("\\", "\\\\").replace("'", "\\'")
                    wv.webView.evaluateJavascript("setupNDecode('$escaped')") { r ->
                        Log.d(TAG, "setupNDecode: $r")
                    }
                }
                if (jsData.sigDecodeFn != null) {
                    val escaped = jsData.sigDecodeFn.replace("\\", "\\\\").replace("'", "\\'")
                    wv.webView.evaluateJavascript("setupSigDecode('$escaped')") { r ->
                        Log.d(TAG, "setupSigDecode: $r")
                    }
                }
            }
            return wv
        }
    }
}

class PoTokenException(message: String) : Exception(message)
