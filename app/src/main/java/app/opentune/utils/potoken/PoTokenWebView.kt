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
        // Watch pages contain the full player_ias/base.js; embed pages use a different player script.
        val sourceUrls = listOf(
            "https://www.youtube.com/watch?v=jNQXAC9IVRw",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com",
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

                val scriptPath = Regex("""/s/player/[0-9a-fA-F]+/[^\s"']*base\.js""")
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
                    // Search ±1000 chars around "jnn/v1/Create" with multiple strategies.
                    val nearWindow = jsContent.substring(maxOf(0, jnnIdx - 1000), minOf(jsContent.length, jnnIdx + 500))
                    Regex("""stringify\(\s*\[\s*["']([A-Za-z0-9_-]{15,30})["']\s*\]""").find(nearWindow)?.groupValues?.get(1)
                        ?: Regex("""\[\s*["']([A-Za-z0-9_-]{15,30})["']\s*[,\]]""").find(nearWindow)?.groupValues?.get(1)
                        // Object-property styles: {k:"KEY"} or {key:"KEY"}
                        ?: Regex("""["']?(?:key|k)["']?\s*:\s*["']([A-Za-z0-9_-]{15,30})["']""").find(nearWindow)?.groupValues?.get(1)
                        // Inline with the URL itself: "jnn/v1/Create","KEY" or "jnn/v1/Create" ,"KEY"
                        ?: Regex("""jnn/v1/Create["']\s*,\s*["']([A-Za-z0-9_-]{15,30})["']""").find(nearWindow)?.groupValues?.get(1)
                        ?: run {
                            // Wider window search: last quoted string assignment before jnn/v1/Create
                            val wideWindow = jsContent.substring(maxOf(0, jnnIdx - 8000), jnnIdx + 200)
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
    // Three strategies in order: join-anchor → splice-anchor → call-site.
    private fun extractSigDecodeCode(js: String): String? {
        val len = js.length
        val sp = buildString {
            if (js.contains(".split(\"\")")) append("dq")
            if (js.contains(".split('')")) append("sq")
            if (js.contains(".split(``)")) append("bt")
            if (js.contains("Array.from(")) append("af")
        }
        val jn = buildString {
            if (js.contains(".join(\"\")")) append("dq")
            if (js.contains(".join('')")) append("sq")
            if (js.contains(".join(``)")) append("bt")
        }
        Log.d(TAG, "extractSigDecodeCode: ${len}b sp=$sp jn=$jn rev=${js.contains(".reverse()")} spl=${js.contains(".splice(0,")}")

        val splitTokens = listOf(".split(\"\")", ".split('')", ".split(``)")
        val joinTokens  = listOf(".join(\"\")",  ".join('')",  ".join(``)")

        // Strategy 1: join-anchor
        var jFrom = 0
        while (true) {
            val joinIdx = joinTokens.map { js.indexOf(it, jFrom) }.filter { it >= 0 }.minOrNull() ?: break
            jFrom = joinIdx + 1
            val lbOff = maxOf(0, joinIdx - 10000)
            val seg = js.substring(lbOff, joinIdx)
            val hasSplit = splitTokens.any { seg.contains(it) } || seg.contains("Array.from(")
            if (!hasSplit) continue

            val fd = Regex("""([\w$]+)\s*=\s*function\(\s*([\w$]+)\s*\)\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""function\s+([\w$]+)\s*\(\s*([\w$]+)\s*\)\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""([\w$]+)\s*=\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""([\w$]+)\s*=\s*([\w$]+)\s*=>\s*\{""").findAll(seg).lastOrNull()
                ?: continue
            val fnName = fd.groupValues[1]; val fnParam = fd.groupValues[2]
            val fdAbsIdx = lbOff + fd.range.first
            val fnBraceIdx = js.indexOf("{", fdAbsIdx).takeIf { it >= 0 } ?: continue
            val fnBody = extractBalanced(js, fnBraceIdx) ?: continue
            val fnHasSplit = splitTokens.any { fnBody.contains(it) } || fnBody.contains("Array.from(")
            val fnHasJoin  = joinTokens.any { fnBody.contains(it) }
            if (!fnHasSplit || !fnHasJoin) continue

            val helperName =
                Regex("""([\w$]+)\.([\w$]+)\($fnParam""").find(fnBody)?.groupValues?.get(1)
                    ?: Regex("""([\w$]+)\["[\w$]+"\]\($fnParam""").find(fnBody)?.groupValues?.get(1)
                    ?: continue
            val (_, helperBody) = findHelper(js, helperName, fdAbsIdx) ?: continue

            Log.d(TAG, "extractSigDecodeCode(join): fn='$fnName' helper='$helperName'")
            return "var $helperName=$helperBody;\nfunction $fnName($fnParam) $fnBody\ndecodeSig_global=$fnName;"
        }

        // Strategy 2: splice-anchor
        var sFrom = 0
        while (true) {
            val spliceIdx = js.indexOf(".splice(0,", sFrom).takeIf { it >= 0 } ?: break
            sFrom = spliceIdx + 1
            val lbOff = maxOf(0, spliceIdx - 2000)
            val lb = js.substring(lbOff, spliceIdx)
            val hm = Regex("""(?:var\s+|[;,}\s(])([\w$]+)\s*=\s*\{""").findAll(lb).lastOrNull() ?: continue
            val helperName = hm.groupValues[1]
            val helperBraceIdx = lbOff + hm.range.first + hm.value.lastIndexOf('{')
            val helperBody = extractBalanced(js, helperBraceIdx) ?: continue
            if (!helperBody.contains(".splice(0,") || !helperBody.contains(".reverse()")) continue

            val afterHelper = helperBraceIdx + helperBody.length
            val callIdx = js.indexOf("$helperName.", afterHelper).takeIf { it >= 0 } ?: continue
            val pre = js.substring(maxOf(0, callIdx - 500), callIdx)
            val fd = Regex("""([\w$]+)\s*=\s*function\(\s*([\w$]+)\s*\)\s*\{""").findAll(pre).lastOrNull()
                ?: Regex("""function\s+([\w$]+)\s*\(\s*([\w$]+)\s*\)\s*\{""").findAll(pre).lastOrNull()
                ?: continue
            val fnName = fd.groupValues[1]; val fnParam = fd.groupValues[2]
            val fdAbsIdx = maxOf(0, callIdx - 500) + fd.range.first
            val fnBraceIdx = js.indexOf("{", fdAbsIdx).takeIf { it >= 0 } ?: continue
            val fnBody = extractBalanced(js, fnBraceIdx) ?: continue

            Log.d(TAG, "extractSigDecodeCode(splice): fn='$fnName' helper='$helperName'")
            return "var $helperName=$helperBody;\nfunction $fnName($fnParam) $fnBody\ndecodeSig_global=$fnName;"
        }

        // Strategy 3: call-site anchor (find fn by where it's called near decodeURIComponent)
        val csPatterns = listOf(
            Regex("""\b[\w$]+&&\([\w$]+=([a-zA-Z0-9_$]+)\((?:\d+,)?decodeURIComponent"""),
            Regex("""\bc&&\(c=([a-zA-Z0-9$]+)\(decodeURIComponent"""),
            Regex("""\bm=([a-zA-Z0-9$]+)\(decodeURIComponent\(h\.s\)\)"""),
            Regex("""[;,=]\s*([a-zA-Z0-9$]+)\(decodeURIComponent\([^)]+\.get\("s"\)"""),
            Regex("""\.set\(["']sig["'],([a-zA-Z0-9$]+)\("""),
            Regex("""b=([a-zA-Z0-9$]+)\(decodeURIComponent\(b\.get\("s"\)\)\)"""),
        )
        for (csp in csPatterns) {
            val csm = csp.find(js) ?: continue
            val fnName = csm.groupValues[1]; if (fnName.isBlank()) continue
            val fnDefIdx = js.indexOf("var $fnName=function(").takeIf { it >= 0 }
                ?: js.indexOf("$fnName=function(").takeIf { it >= 0 }
                ?: js.indexOf("function $fnName(").takeIf { it >= 0 } ?: continue
            val paramOpen = js.indexOf("(", fnDefIdx).takeIf { it >= 0 } ?: continue
            val paramClose = js.indexOf(")", paramOpen).takeIf { it >= 0 } ?: continue
            val fnParam = js.substring(paramOpen + 1, paramClose).trim().takeIf { it.isNotBlank() } ?: continue
            val fnBraceIdx = js.indexOf("{", paramClose).takeIf { it >= 0 } ?: continue
            val fnBody = extractBalanced(js, fnBraceIdx) ?: continue

            val helperName =
                Regex("""([\w$]+)\.([\w$]+)\($fnParam""").find(fnBody)?.groupValues?.get(1)
                    ?: Regex("""([\w$]+)\["[\w$]+"\]\($fnParam""").find(fnBody)?.groupValues?.get(1)
                    ?: continue
            val (_, helperBody) = findHelper(js, helperName, fnDefIdx) ?: continue

            Log.d(TAG, "extractSigDecodeCode(cs): fn='$fnName' param='$fnParam' helper='$helperName'")
            return "var $helperName=$helperBody;\nfunction $fnName($fnParam) $fnBody\ndecodeSig_global=$fnName;"
        }

        Log.w(TAG, "extractSigDecodeCode: all strategies failed, ${len}b sp=$sp jn=$jn")
        return null
    }

    private fun findHelper(js: String, name: String, beforeIdx: Int): Pair<Int, String>? {
        val before = js.substring(0, beforeIdx)
        val start = before.lastIndexOf("var $name={").takeIf { it >= 0 }
            ?: before.lastIndexOf("const $name={").takeIf { it >= 0 }
            ?: before.lastIndexOf("let $name={").takeIf { it >= 0 }
            ?: before.lastIndexOf(";$name={").let { if (it >= 0) it + 1 else -1 }.takeIf { it >= 0 }
            ?: before.lastIndexOf(",$name={").let { if (it >= 0) it + 1 else -1 }.takeIf { it >= 0 }
            ?: js.indexOf("var $name={", beforeIdx).takeIf { it >= 0 }
            ?: js.indexOf("const $name={", beforeIdx).takeIf { it >= 0 }
            ?: js.indexOf("let $name={", beforeIdx).takeIf { it >= 0 }
            ?: js.indexOf(";$name={", beforeIdx).let { if (it >= 0) it + 1 else -1 }.takeIf { it >= 0 }
            ?: return null
        val braceIdx = js.indexOf("{", start).takeIf { it >= 0 } ?: return null
        val body = extractBalanced(js, braceIdx) ?: return null
        return braceIdx to body
    }

    // Extract the YouTube n-parameter decode function from player JS.
    // Returns a JS expression that can be eval'd to produce a callable function or [fn] array.
    private fun extractNDecodeFn(js: String): String? {
        // ── Strategy 1: literal "n" call site — pre-2026 players ─────────────
        val arrName = listOf(
            Regex("""\.get\("n"\)\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]{2,})\[0\]\("""),
            Regex("""\.get\("n"\)\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]{2,})\("""),
            Regex("""\.set\("n",([a-zA-Z0-9$]{2,})\[0\]\("""),
            Regex("""\.set\("n",([a-zA-Z0-9$]{2,})\("""),
        ).firstNotNullOfOrNull { it.find(js) }?.groupValues?.get(1)

        if (arrName != null) {
            var bracketIdx = -1
            for (prefix in listOf("var $arrName=[", "const $arrName=[", "let $arrName=[")) {
                val idx = js.indexOf(prefix)
                if (idx >= 0) { bracketIdx = idx + prefix.length - 1; break }
            }
            if (bracketIdx < 0) {
                for (prefix in listOf(",$arrName=[", ";$arrName=[")) {
                    val idx = js.indexOf(prefix)
                    if (idx >= 0) { bracketIdx = idx + prefix.length - 1; break }
                }
            }
            if (bracketIdx >= 0) {
                val fn = extractBalanced(js, bracketIdx)
                if (fn != null) {
                    Log.d(TAG, "extractNDecodeFn[S1]: arrName=$arrName (${fn.length}b)")
                    return fn
                }
            }
            Log.w(TAG, "extractNDecodeFn[S1]: call site found (arr=$arrName) but declaration missing")
        }

        // ── Strategy 2: dispatcher-based n-decode (2026+ players, e.g. 5cabb421) ──
        // n-decode uses the SAME dispatcher function as sig-decode but different constants:
        //   Sig:    DISP(25,37, DISP(51,3416, sig.s))
        //   N-dec:  DISP(24,36, DISP(49,3418, n))
        // We synthesize a self-contained IIFE that includes u-table + helper + dispatcher.
        val sigCallPat = Regex(
            """(\w+)\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\1\(\s*\d+\s*,\s*\d+\s*,\s*\w+\.s\s*\)\s*\)""")
        val sigCm = sigCallPat.find(js)
            ?: run { Log.w(TAG, "extractNDecodeFn[S2]: sig call site not found"); return null }
        val dispName = sigCm.groupValues[1]
        val sigK = sigCm.groupValues[2].toIntOrNull() ?: return null
        val sigR = sigCm.groupValues[3].toIntOrNull() ?: return null

        val nCallPat = Regex(
            """\b${Regex.escape(dispName)}\(\s*(\d+)\s*,\s*(\d+)\s*,\s*${Regex.escape(dispName)}\(\s*(\d+)\s*,\s*(\d+)\s*,\s*[\w$]+\s*\)\s*\)""")
        val nCm = nCallPat.findAll(js).firstOrNull { m ->
            val k = m.groupValues[1].toIntOrNull() ?: 0
            val r = m.groupValues[2].toIntOrNull() ?: 0
            !(k == sigK && r == sigR)
        } ?: run {
            Log.w(TAG, "extractNDecodeFn[S2]: n-decode call not found for dispatcher=$dispName"); return null
        }
        val outerK = nCm.groupValues[1]; val outerR = nCm.groupValues[2]
        val innerK = nCm.groupValues[3]; val innerR = nCm.groupValues[4]
        Log.d(TAG, "extractNDecodeFn[S2]: $dispName($outerK,$outerR,$dispName($innerK,$innerR,x))")

        // Locate dispatcher function definition (search backwards from n-decode call site)
        val dispFnIdx = js.lastIndexOf("$dispName=function(", nCm.range.first)
            .takeIf { it >= 0 } ?: js.indexOf("$dispName=function(").takeIf { it >= 0 }
            ?: run { Log.w(TAG, "extractNDecodeFn[S2]: dispatcher fn def not found"); return null }
        val dispParamsM = Regex("""${Regex.escape(dispName)}=function\(([^)]*)\)""").find(js, dispFnIdx)
        val dispParams = dispParamsM?.groupValues?.get(1)?.trim() ?: "K,R,x"
        val dispBrace = js.indexOf("{", dispFnIdx).takeIf { it >= 0 }
            ?: run { Log.w(TAG, "extractNDecodeFn[S2]: dispatcher brace not found"); return null }
        val dispBody = extractBalanced(js, dispBrace)
            ?: run { Log.w(TAG, "extractNDecodeFn[S2]: dispatcher body extraction failed"); return null }

        // String table (u = "...".split("{") etc.) — contains "split","join","reverse","splice".
        // Use findAll: first match is often a URL string, not the real method-name table.
        var tableVar: String? = null; var tableCode: String? = null
        outer@ for (sep in listOf("{", ";", "|")) {
            val pat = Regex("""(?<![.\w])(\w+)\s*=\s*"([^"]{300,})"\s*\.split\s*\(\s*"${Regex.escape(sep)}"\s*\)""")
            for (tm in pat.findAll(js)) {
                val tv = tm.groupValues[1]
                val entries = tm.groupValues[2].split(sep)
                if (listOf("split", "join", "reverse", "splice").all { it in entries }) {
                    tableVar = tv
                    tableCode = """var $tv="${tm.groupValues[2]}".split("$sep");"""
                    break@outer
                }
            }
        }

        // Helper object (Pw) — found via TABLE_VAR subscript in dispatcher body
        var helperCode: String? = null
        if (tableVar != null) {
            val hName = Regex("""([\w$]+)\[${Regex.escape(tableVar)}\[""").find(dispBody)
                ?.groupValues?.get(1)?.takeIf { it != dispName && it.length <= 10 }
            if (hName != null) {
                val (_, hBody) = findHelper(js, hName, dispFnIdx) ?: (null to null)
                if (hBody != null) helperCode = "var $hName=$hBody;"
            }
        }

        val depsCode = listOfNotNull(tableCode, helperCode,
            "function $dispName($dispParams)$dispBody").joinToString("\n")
        val iife = "(function(){$depsCode\nreturn [function(x){return $dispName($outerK,$outerR,$dispName($innerK,$innerR,x))}]})()"
        Log.d(TAG, "extractNDecodeFn[S2]: synthesized IIFE (${iife.length}b)")
        return iife
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
