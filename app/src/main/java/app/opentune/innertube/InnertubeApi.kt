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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.Inet4Address
import java.net.InetAddress
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
        // Force IPv4: YouTube CDN URLs are IP-bound; the API call and CDN fetch must
        // use the same IP family or YouTube returns 403.
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addrs = okhttp3.Dns.SYSTEM.lookup(hostname)
                val v4 = addrs.filterIsInstance<Inet4Address>()
                return if (v4.isNotEmpty()) v4 else addrs
            }
        })
        .build()

    // Short-timeout variant for Piped public instances — fail fast so NPE takes over quickly.
    // 4s connect + 5s read per instance; 5 instances worst-case = ~45s total but typically <2s.
    private val pipedHttpClient = httpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // ── Player JS cache ─────────────────────────────────────────────────────────
    // Both signatureTimestamp and sig-decode operations come from the same player JS.
    // Fetch once per hour; sig ops are used to decode signatureCipher URL fields.

    @Volatile private var cachedSigTs: Int = 0
    @Volatile private var cachedSigOps: List<SigOp>? = null
    @Volatile private var jsDataFetchedAt: Long = 0L

    private sealed class SigOp {
        object Reverse : SigOp()
        data class Splice(val n: Int) : SigOp()
        data class Swap(val n: Int) : SigOp()
    }

    private fun ensurePlayerJsData() {
        val now = System.currentTimeMillis()
        // Full 1-hour cache when sigOps were extracted successfully.
        // 5-minute retry when sigOps are null — the player version may have rotated.
        val maxAge = if (cachedSigOps != null) 3_600_000L else 300_000L
        if (jsDataFetchedAt > 0 && now - jsDataFetchedAt < maxAge) return
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        val scriptPathRegex = Regex("""/s/player/[0-9a-fA-F]+/[^\s"']*base\.js""")
        val sourceUrls = listOf(
            "https://www.youtube.com/watch?v=jNQXAC9IVRw",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com",
        )
        for (sourceUrl in sourceUrls) {
            try {
                val html = httpClient.newCall(
                    Request.Builder().url(sourceUrl).addHeader("User-Agent", ua).get().build()
                ).execute().body?.string() ?: continue
                val scriptPath = scriptPathRegex.find(html)?.value ?: continue
                val js = httpClient.newCall(
                    Request.Builder().url("https://www.youtube.com$scriptPath")
                        .addHeader("User-Agent", ua).get().build()
                ).execute().body?.string() ?: continue

                // signature timestamp
                Regex("""signatureTimestamp[=:]\s*(\d+)""").find(js)
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?.also { cachedSigTs = it; Log.d(tag, "signatureTimestamp=$it (from $sourceUrl)") }

                // sig-decode operations
                extractSigOps(js)?.also { cachedSigOps = it; Log.d(tag, "sigOps: ${it.size} ops") }
                    ?: Log.w(tag, "sig decode ops not found in player JS (from $sourceUrl)")

                // Expose player JS state to DiagnosticsLogger for in-app diagnostics
                val playerId = Regex("""/s/player/([0-9a-fA-F]+)/""").find(scriptPath)?.groupValues?.get(1) ?: "?"
                app.opentune.utils.DiagnosticsLogger.lastPlayerJsId = playerId
                app.opentune.utils.DiagnosticsLogger.lastPlayerJsFetchedAgo = System.currentTimeMillis()
                app.opentune.utils.DiagnosticsLogger.lastSigOpsStatus =
                    if (cachedSigOps != null) "OK (${cachedSigOps!!.size} ops) [$sigOpsHint]"
                    else "FAIL [$sigOpsHint]"

                jsDataFetchedAt = System.currentTimeMillis()
                return
            } catch (e: Exception) {
                Log.w(tag, "ensurePlayerJsData($sourceUrl) failed: ${e.message}")
            }
        }
        Log.w(tag, "ensurePlayerJsData: all sources failed")
    }

    private fun getSignatureTimestamp(): Int { ensurePlayerJsData(); return cachedSigTs }

    // Extract bracket-balanced substring from js starting at position `start`.
    private fun extractBalancedJs(js: String, start: Int): String? {
        if (start < 0 || start >= js.length) return null
        when (js[start]) { '[', '{', '(' -> Unit; else -> return null }
        // State machine that understands JS strings, template literals, regex literals,
        // and comments — so that { } inside them are not counted as brace depth.
        var depth = 0
        // 0=code 1=str" 2=str' 3=str` 4=regex 5=regex-charclass 6=line-comment 7=block-comment
        var state = 0
        var regexCtx = true   // true → next '/' starts a regex literal
        var i = start
        while (i < js.length) {
            val c = js[i]
            when (state) {
                6 -> if (c == '\n') state = 0
                7 -> if (c == '*' && i + 1 < js.length && js[i + 1] == '/') { state = 0; i++ }
                1 -> when { c == '\\' -> i++; c == '"'  -> { state = 0; regexCtx = false } }
                2 -> when { c == '\\' -> i++; c == '\'' -> { state = 0; regexCtx = false } }
                3 -> when { c == '\\' -> i++; c == '`'  -> { state = 0; regexCtx = false } }
                4 -> when { c == '\\' -> i++; c == '[' -> state = 5; c == '/' -> { state = 0; regexCtx = false } }
                5 -> when { c == '\\' -> i++; c == ']' -> state = 4 }
                else -> when {
                    c == '/' && i + 1 < js.length -> when {
                        js[i + 1] == '/' -> { state = 6; i++ }
                        js[i + 1] == '*' -> { state = 7; i++ }
                        regexCtx         -> { state = 4 }
                        else             ->   regexCtx = true
                    }
                    c == '"'  -> { state = 1; regexCtx = false }
                    c == '\'' -> { state = 2; regexCtx = false }
                    c == '`'  -> { state = 3; regexCtx = false }
                    c == '[' || c == '{' || c == '(' -> { depth++; regexCtx = true }
                    c == ']' || c == '}' || c == ')' -> {
                        depth--
                        if (depth == 0) return js.substring(start, i + 1)
                        regexCtx = false
                    }
                    c == ';' || c == ',' -> regexCtx = true
                    c == '=' || c == '+' || c == '-' || c == '*' || c == '!' ||
                    c == '<' || c == '>' || c == '~' || c == '^' || c == '&' ||
                    c == '|' || c == '?' || c == ':' || c == '%' -> regexCtx = true
                    c.isLetterOrDigit() || c == '_' || c == '$' -> regexCtx = false
                }
            }
            i++
        }
        return null
    }

    // Diagnostic: captures which step of extractSigOps failed; included in error messages.
    @Volatile private var sigOpsHint = "?"

    // Build the helper-method → SigOp mapping from the helper object body.
    // Accepts both classic  "function(x){" syntax and ES6 arrow "(x)=>{?" syntax,
    // since YouTube 2026 helper methods use arrow functions.
    private fun buildOpMap(helperBody: String): Map<String, SigOp>? {
        val m = mutableMapOf<String, SigOp>()
        // 1-param method head: function(a){ | (a)=>{ | (a)=> | a=>{  | a=>
        val h1 = """(?:function\s*\(\w+\)\s*\{|\(\w+\)\s*=>\s*\{?|\w+\s*=>\s*\{?)"""
        // 2-param method head: function(a,b){ | (a,b)=>{ | (a,b)=>
        val h2 = """(?:function\s*\(\w+,\w+\)\s*\{|\(\w+,\w+\)\s*=>\s*\{?)"""

        // Reverse (1 param, calls .reverse())
        Regex("""([\w$]+)\s*:\s*$h1[^}]*\.reverse\(\)""")
            .findAll(helperBody).forEach { m[it.groupValues[1]] = SigOp.Reverse }

        // Splice cut (2 params, calls .splice(0,)
        Regex("""([\w$]+)\s*:\s*$h2[^}]*\.splice\(0,""")
            .findAll(helperBody).forEach { m[it.groupValues[1]] = SigOp.Splice(0) }

        // Slice cut (2 params, assigns param=param.slice() — YouTube 2026 variant)
        Regex("""([\w$]+)\s*:\s*$h2[^}]*=\w+\.slice\(""")
            .findAll(helperBody).filter { m[it.groupValues[1]] == null }
            .forEach { m[it.groupValues[1]] = SigOp.Splice(0) }

        // Swap (2 params, uses %param.length — unique to swap operation)
        Regex("""([\w$]+)\s*:\s*$h2[^}]*%\w+\.length""")
            .findAll(helperBody).filter { m[it.groupValues[1]] == null }
            .forEach { m[it.groupValues[1]] = SigOp.Swap(0) }

        return m.ifEmpty { null }
    }

    // Parse the ordered op-call sequence from the sig fn body.
    private fun parseOpCalls(fnBody: String, fnParam: String, helperName: String, opMap: Map<String, SigOp>): List<SigOp>? {
        val callM = Regex("""${Regex.escape(helperName)}\.([\w$]+)\($fnParam(?:,(\d+))?\)""")
            .findAll(fnBody).toList().ifEmpty {
                Regex("""${Regex.escape(helperName)}\["([\w$]+)"\]\($fnParam(?:,(\d+))?\)""")
                    .findAll(fnBody).toList()
            }
        if (callM.isEmpty()) return null
        val ops = mutableListOf<SigOp>()
        for (c in callM) {
            val method = c.groupValues[1]; val n = c.groupValues[2].toIntOrNull() ?: 0
            when (opMap[method]) {
                SigOp.Reverse -> ops.add(SigOp.Reverse)
                is SigOp.Splice -> ops.add(SigOp.Splice(n))
                is SigOp.Swap -> ops.add(SigOp.Swap(n))
                null -> return null
            }
        }
        return ops.ifEmpty { null }
    }

    // Parse sig-cipher operations that are inlined directly in the function body.
    // Used when no helper object is found (YouTube 2026+ may inline operations).
    // Variable-agnostic: matches any identifier, not just the function parameter.
    private fun parseInlineOps(fnBody: String): List<SigOp>? {
        val opPositions = mutableListOf<Pair<Int, SigOp>>()
        // Reverse: ANYVAR.reverse()
        Regex("""[\w$]+\.reverse\(\)""").findAll(fnBody).forEach { m ->
            opPositions.add(m.range.first to SigOp.Reverse)
        }
        // Splice cut: ANYVAR.splice(0,N)
        Regex("""[\w$]+\.splice\(0,(\d+)\)""").findAll(fnBody).forEach { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            opPositions.add(m.range.first to SigOp.Splice(n))
        }
        // Swap: N%ANYVAR.length — each distinct N is one swap, deduplicate (pattern appears twice per swap)
        val seenN = mutableSetOf<Int>()
        Regex("""(\d+)%[\w$]+\.length""").findAll(fnBody).forEach { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@forEach
            if (seenN.add(n)) opPositions.add(m.range.first to SigOp.Swap(n))
        }
        if (opPositions.size < 2) return null
        return opPositions.sortedBy { it.first }.map { it.second }
    }

    // Find the helper object body given its name.
    // Searches backward from beforeIdx first (typical: helper before decode fn),
    // then forward (fallback: helper after decode fn).
    // Handles var/const/let declarations and inline assignments.
    private fun findHelperBody(js: String, helperName: String, beforeIdx: Int): Pair<Int, String>? {
        val before = js.substring(0, beforeIdx)
        val start = before.lastIndexOf("var $helperName={").takeIf { it >= 0 }
            ?: before.lastIndexOf("const $helperName={").takeIf { it >= 0 }
            ?: before.lastIndexOf("let $helperName={").takeIf { it >= 0 }
            ?: before.lastIndexOf(";$helperName={").let { if (it >= 0) it + 1 else -1 }.takeIf { it >= 0 }
            ?: before.lastIndexOf(",$helperName={").let { if (it >= 0) it + 1 else -1 }.takeIf { it >= 0 }
            // Forward search fallback (helper defined after the decode function)
            ?: js.indexOf("var $helperName={", beforeIdx).takeIf { it >= 0 }
            ?: js.indexOf("const $helperName={", beforeIdx).takeIf { it >= 0 }
            ?: js.indexOf("let $helperName={", beforeIdx).takeIf { it >= 0 }
            ?: js.indexOf(";$helperName={", beforeIdx).let { if (it >= 0) it + 1 else -1 }.takeIf { it >= 0 }
            ?: return null
        val braceIdx = js.indexOf("{", start).takeIf { it >= 0 } ?: return null
        val body = extractBalancedJs(js, braceIdx) ?: return null
        return braceIdx to body
    }

    // Extract sig-decode operation sequence from player JS (natively in JVM — no WebView).
    //
    // Primary strategy (join-anchor): the sig fn always converts string→array via split,
    // applies ops via a helper object, then joins back. Anchoring on join("") variants
    // identifies the sig fn regardless of obfuscation.
    //
    // Fallback strategy (splice-anchor): for older player variants that use splice(0,n).
    private fun extractSigOps(js: String): List<SigOp>? {
        val len = js.length

        // Content analysis — baked into hint so it's visible in the error even without logcat.
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
        val diag = "${len}b sp=$sp jn=$jn rev=${js.contains(".reverse()")} spl=${js.contains(".splice(0,")}"
        Log.d(tag, "extractSigOps: $diag")

        // All known split/join variants (double-quote, single-quote, backtick template literal)
        val splitTokens = listOf(".split(\"\")", ".split('')", ".split(``)")
        val joinTokens  = listOf(".join(\"\")",  ".join('')",  ".join(``)")

        // ── Strategy 1: join-anchor ──────────────────────────────────────────────
        sigOpsHint = "s0-noJoin($diag)"
        var jFrom = 0
        while (true) {
            val joinIdx = joinTokens.map { js.indexOf(it, jFrom) }.filter { it >= 0 }.minOrNull() ?: break
            jFrom = joinIdx + 1

            // Sig fn must also have a split/conversion within 10000 chars before the join.
            // Includes spread [... (ES2025+: [...a] replaces a.split("") or Array.from(a))
            val seg = js.substring(maxOf(0, joinIdx - 10000), joinIdx)
            val hasSplit = splitTokens.any { seg.contains(it) } || seg.contains("Array.from(") || seg.contains("[...")
            if (!hasSplit) continue

            // Find the enclosing function definition (regular, arrow, or colon object-property)
            val lbOff = maxOf(0, joinIdx - 10000)
            val fd = Regex("""([\w$]+)\s*=\s*function\(\s*([\w$]+)\s*\)\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""function\s+([\w$]+)\s*\(\s*([\w$]+)\s*\)\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""([\w$]+)\s*=\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""([\w$]+)\s*=\s*([\w$]+)\s*=>\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""([\w$]+)\s*:\s*function\s*\(\s*([\w$]+)\s*\)\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""([\w$]+)\s*:\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""([\w$]+)\s*:\s*([\w$]+)\s*=>\s*\{""").findAll(seg).lastOrNull()
                ?: continue
            val fnName = fd.groupValues[1]; val fnParam = fd.groupValues[2]
            val fdAbsIdx = lbOff + fd.range.first
            val fnBraceIdx = js.indexOf("{", fdAbsIdx).takeIf { it >= 0 } ?: continue
            val fnBody = extractBalancedJs(js, fnBraceIdx) ?: continue

            val fnHasSplit = splitTokens.any { fnBody.contains(it) } || fnBody.contains("Array.from(") || fnBody.contains("[...")
            val fnHasJoin  = joinTokens.any { fnBody.contains(it) }
            if (!fnHasSplit || !fnHasJoin) continue

            sigOpsHint = "s1-noHelper($diag/$fnName)"
            val helperName =
                Regex("""([\w$]+)\.([\w$]+)\($fnParam""").find(fnBody)?.groupValues?.get(1)
                    ?: Regex("""([\w$]+)\["[\w$]+"\]\($fnParam""").find(fnBody)?.groupValues?.get(1)
                    ?: run {
                        // No helper-object call found: try parsing operations inlined directly
                        val inlineOps = parseInlineOps(fnBody)
                        if (inlineOps != null) {
                            sigOpsHint = "ok-inline-${inlineOps.size}ops"
                            Log.d(tag, "extractSigOps: ${inlineOps.size} inline ops OK via join-anchor/'$fnName'")
                            return inlineOps
                        }
                        null
                    }
                    ?: continue

            sigOpsHint = "s2-noHelperDef($diag/$helperName)"
            val (_, helperBody) = findHelperBody(js, helperName, fdAbsIdx) ?: continue

            val opMap = buildOpMap(helperBody)
                ?: run { sigOpsHint = "s2-opMapEmpty($diag/$helperName)"; continue }

            sigOpsHint = "s3-noOpCalls($diag/$helperName/$fnName)"
            Log.d(tag, "extractSigOps(join): fn='$fnName' helper='$helperName' opMap=$opMap")
            val ops = parseOpCalls(fnBody, fnParam, helperName, opMap)
                ?: run { sigOpsHint = "s3-opCallsFail($diag/$helperName/$fnName)"; continue }

            sigOpsHint = "ok-${ops.size}ops"
            Log.d(tag, "extractSigOps: ${ops.size} ops OK via join-anchor/'$helperName'")
            return ops
        }

        val s1hint = sigOpsHint.substringBefore("(")

        // ── Strategy 2: splice-anchor (fallback for older player JS) ────────────
        sigOpsHint = "f0-noSplice($diag)"
        var sFrom = 0
        while (true) {
            val spliceIdx = js.indexOf(".splice(0,", sFrom).takeIf { it >= 0 } ?: break
            sFrom = spliceIdx + 1

            val lbOff = maxOf(0, spliceIdx - 2000)
            val lb = js.substring(lbOff, spliceIdx)
            val hm = Regex("""(?:var\s+|[;,}\s(])([\w$]+)\s*=\s*\{""").findAll(lb).lastOrNull()
            if (hm == null) {
                // No helper-object literal found; the splice may be inlined in the sig fn.
                // Look for an enclosing function and try inline op parsing.
                val fd2 = Regex("""([\w$]+)\s*=\s*function\(\s*([\w$]+)\s*\)\s*\{""").findAll(lb).lastOrNull()
                    ?: Regex("""function\s+([\w$]+)\s*\(\s*([\w$]+)\s*\)\s*\{""").findAll(lb).lastOrNull()
                    ?: Regex("""([\w$]+)\s*=\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{""").findAll(lb).lastOrNull()
                    ?: Regex("""([\w$]+)\s*=\s*([\w$]+)\s*=>\s*\{""").findAll(lb).lastOrNull()
                    ?: Regex("""([\w$]+)\s*:\s*function\s*\(\s*([\w$]+)\s*\)\s*\{""").findAll(lb).lastOrNull()
                    ?: Regex("""([\w$]+)\s*:\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{""").findAll(lb).lastOrNull()
                    ?: Regex("""([\w$]+)\s*:\s*([\w$]+)\s*=>\s*\{""").findAll(lb).lastOrNull()
                    ?: continue
                val fdAbsIdx2 = lbOff + fd2.range.first
                val fnBrace2 = js.indexOf("{", fdAbsIdx2).takeIf { it >= 0 } ?: continue
                val fnBody2 = extractBalancedJs(js, fnBrace2) ?: continue
                if (!joinTokens.any { fnBody2.contains(it) }) continue
                val inlineOps = parseInlineOps(fnBody2) ?: continue
                sigOpsHint = "ok-inline-f-${inlineOps.size}ops"
                Log.d(tag, "extractSigOps: ${inlineOps.size} inline ops OK via splice-anchor (inline)")
                return inlineOps
            }
            val helperName = hm.groupValues[1]
            val helperBraceIdx = lbOff + hm.range.first + hm.value.lastIndexOf('{')
            val helperBody = extractBalancedJs(js, helperBraceIdx) ?: continue
            if (!helperBody.contains(".splice(0,") || !helperBody.contains(".reverse()")) continue

            val opMap = buildOpMap(helperBody)
                ?: run { sigOpsHint = "f1-opMapEmpty($helperName)"; continue }

            val afterHelper = helperBraceIdx + helperBody.length
            val callIdx = js.indexOf("$helperName.", afterHelper).takeIf { it >= 0 } ?: continue
            val pre = js.substring(maxOf(0, callIdx - 500), callIdx)
            val fd = Regex("""([\w$]+)\s*=\s*function\(\s*([\w$]+)\s*\)\s*\{""").findAll(pre).lastOrNull()
                ?: Regex("""function\s+([\w$]+)\s*\(\s*([\w$]+)\s*\)\s*\{""").findAll(pre).lastOrNull()
                ?: continue
            val fnName = fd.groupValues[1]; val fnParam = fd.groupValues[2]
            val fdAbsIdx = maxOf(0, callIdx - 500) + fd.range.first
            val fnBraceIdx = js.indexOf("{", fdAbsIdx).takeIf { it >= 0 } ?: continue
            val fnBody = extractBalancedJs(js, fnBraceIdx) ?: continue

            sigOpsHint = "f2-noOpCalls($helperName/$fnName)"
            val ops = parseOpCalls(fnBody, fnParam, helperName, opMap)
                ?: run { sigOpsHint = "f2-opCallsFail($helperName/$fnName)"; continue }

            sigOpsHint = "ok-f-${ops.size}ops"
            Log.d(tag, "extractSigOps: ${ops.size} ops OK via splice-anchor/'$helperName'")
            return ops
        }

        val s2hint = sigOpsHint.substringBefore("(")

        // ── Strategy 3: call-site anchor ────────────────────────────────────────
        // Find the sig fn name from WHERE it is called (near decodeURIComponent/.get("s")),
        // then locate its definition without relying on split/join/splice patterns.
        sigOpsHint = "cs0-noCallSite($diag)"
        val csPatterns = listOf(
            // X&&(X=FN(decodeURIComponent(...))) — NPE patterns 1/2 with 1-char names allowed
            Regex("""\b[\w$]+&&\([\w$]+=([a-zA-Z0-9_$]+)\((?:\d+,)?decodeURIComponent"""),
            // c&&(c=FN(decodeURIComponent — NPE pattern 4
            Regex("""\bc&&\(c=([a-zA-Z0-9$]+)\(decodeURIComponent"""),
            // m=FN(decodeURIComponent(h.s)) — NPE pattern 3
            Regex("""\bm=([a-zA-Z0-9$]+)\(decodeURIComponent\(h\.s\)\)"""),
            // X=FN(decodeURIComponent(X.get("s")))
            Regex("""[;,=]\s*([a-zA-Z0-9$]+)\(decodeURIComponent\([^)]+\.get\("s"\)"""),
            // .set("sig", FN(
            Regex("""\.set\(["']sig["'],([a-zA-Z0-9$]+)\("""),
            // b=FN(decodeURIComponent(b.get("s")))
            Regex("""b=([a-zA-Z0-9$]+)\(decodeURIComponent\(b\.get\("s"\)\)\)"""),
            // YouTube 2026+: generic — any 2+-char function call directly wrapping decodeURIComponent
            Regex("""[;({,\s=]([a-zA-Z0-9$]{2,})\s*\(\s*decodeURIComponent\("""),
            // .get("s") nearby any function call (broader match)
            Regex("""([a-zA-Z0-9$]{2,})\(decodeURIComponent\([^)]{1,80}\.get\(["']s["']\)"""),
        )
        for (csp in csPatterns) {
            val csm = csp.find(js) ?: continue
            val fnName = csm.groupValues[1]
            if (fnName.length < 1) continue

            // Find the function definition
            val fnDefIdx = js.indexOf("var $fnName=function(").takeIf { it >= 0 }
                ?: js.indexOf("$fnName=function(").takeIf { it >= 0 }
                ?: js.indexOf("function $fnName(").takeIf { it >= 0 }
                ?: run { sigOpsHint = "cs1-noFnDef($diag/$fnName)"; continue }

            val paramOpen = js.indexOf("(", fnDefIdx).takeIf { it >= 0 } ?: continue
            val paramClose = js.indexOf(")", paramOpen).takeIf { it >= 0 } ?: continue
            val fnParam = js.substring(paramOpen + 1, paramClose).trim().takeIf { it.isNotBlank() }
                ?: run { sigOpsHint = "cs1-noParam($diag/$fnName)"; continue }

            val fnBraceIdx = js.indexOf("{", paramClose).takeIf { it >= 0 } ?: continue
            val fnBody = extractBalancedJs(js, fnBraceIdx) ?: continue

            sigOpsHint = "cs2-noHelper($diag/$fnName)"
            val helperName =
                Regex("""([\w$]+)\.([\w$]+)\($fnParam""").find(fnBody)?.groupValues?.get(1)
                    ?: Regex("""([\w$]+)\["[\w$]+"\]\($fnParam""").find(fnBody)?.groupValues?.get(1)
                    ?: run { sigOpsHint = "cs2-noHelperName($diag/$fnName)"; continue }

            sigOpsHint = "cs3-noHelperDef($diag/$helperName)"
            val (_, helperBody) = findHelperBody(js, helperName, fnDefIdx) ?: continue

            val opMap = buildOpMap(helperBody)
                ?: run { sigOpsHint = "cs3-opMapEmpty($diag/$helperName)"; continue }

            sigOpsHint = "cs4-noOpCalls($diag/$helperName/$fnName)"
            Log.d(tag, "extractSigOps(cs): fn='$fnName' param='$fnParam' helper='$helperName' opMap=$opMap")
            val ops = parseOpCalls(fnBody, fnParam, helperName, opMap)
                ?: run { sigOpsHint = "cs4-opCallsFail($diag/$helperName/$fnName)"; continue }

            sigOpsHint = "ok-cs-${ops.size}ops"
            Log.d(tag, "extractSigOps: ${ops.size} ops OK via call-site/'$helperName'")
            return ops
        }

        val s3hint = sigOpsHint.substringBefore("(")

        // ── Strategy 4: flat-dispatcher (YouTube 2026+) ──────────────────────
        // YouTube eliminated the traditional split/join helper entirely.
        // All ops flow through a single XOR-obfuscated dispatcher function.
        extractSigOpsDispatcher(js)?.let { return it }
        val s4hint = sigOpsHint.substringBefore("(")

        sigOpsHint = "allFail($diag [$s1hint|$s2hint|$s3hint|$s4hint])"
        Log.w(tag, "extractSigOps: all strategies failed, $diag [$s1hint|$s2hint|$s3hint|$s4hint]")
        return null
    }

    // Derive p (XOR key) and xorVar from consistent XOR-constant triples in the
    // string table.  Used when the nested call-site pattern is absent (2026+ players
    // where the outer FNAME(R,K,FNAME(...)) wrapper was removed).
    // Returns Pair(p, xorVarName) or null.
    private fun discoverPFromTable(js: String, tableVar: String, u: List<String>): Pair<Int, String>? {
        val splitIdx   = u.indexOf("split").takeIf   { it >= 0 } ?: return null
        val reverseIdx = u.indexOf("reverse").takeIf { it >= 0 } ?: return null
        val spliceIdx  = u.indexOf("splice").takeIf  { it >= 0 } ?: return null
        val diffSR = splitIdx xor reverseIdx
        val diffSS = splitIdx xor spliceIdx
        val eT = Regex.escape(tableVar)
        val byXorVar = mutableMapOf<String, MutableSet<Int>>()
        Regex("""([\w$]+)\[$eT\[([\w$]+)\^(\d+)\]\]""").findAll(js).forEach { m ->
            val xv = m.groupValues[2]
            val k  = m.groupValues[3].toIntOrNull() ?: return@forEach
            if (xv != tableVar) byXorVar.getOrPut(xv) { mutableSetOf() }.add(k)
        }
        Log.d(tag, "discoverPFromTable: $tableVar split@$splitIdx rev@$reverseIdx splice@$spliceIdx " +
            "candidates=${byXorVar.entries.sortedByDescending { it.value.size }.take(4).map { "${it.key}:${it.value.size}k" }}")
        for ((xv, constants) in byXorVar) {
            for (k1 in constants) {
                val k2 = k1 xor diffSR; val k3 = k1 xor diffSS
                if (k2 in constants && k3 in constants) {
                    val pCand = k1 xor splitIdx
                    if ((pCand xor k2) == reverseIdx && (pCand xor k3) == spliceIdx)
                        return pCand to xv
                }
            }
        }
        return null
    }

    // Returns Triple(p, xorVar, resultVar) — resultVar is the array produced by split,
    // used directly as splitVar so the caller doesn't need to re-detect it.
    private fun discoverPFromSplitCall(js: String, tableVar: String, u: List<String>): Triple<Int, String, String>? {
        val splitIdx = u.indexOf("split").takeIf { it >= 0 } ?: return null
        val eT = Regex.escape(tableVar)
        val re = Regex("""var\s+([\w$]+)\s*=\s*[\w$]+\[$eT\[([\w$]+)\^(\d+)\]\]\s*\(""")
        for (m in re.findAll(js)) {
            val resultVar = m.groupValues[1]
            val xorVar    = m.groupValues[2]
            val xorConst  = m.groupValues[3].toIntOrNull() ?: continue
            val pCand     = splitIdx xor xorConst
            val fnStart   = js.lastIndexOf("function", m.range.first).takeIf { it >= 0 } ?: continue
            val braceIdx  = js.indexOf("{", fnStart).takeIf { it >= 0 } ?: continue
            val fnBody    = extractBalancedJs(js, braceIdx) ?: continue
            val eX = Regex.escape(xorVar); val eR = Regex.escape(resultVar)
            val helperCount = Regex("""[\w$]+\[$eT\[$eX\^\d+\]\]\($eR""").findAll(fnBody).count()
            Log.d(tag, "discoverPFromSplitCall: xv=$xorVar const=$xorConst p=$pCand resultVar=$resultVar helperCalls=$helperCount")
            if (helperCount >= 2) return Triple(pCand, xorVar, resultVar)
        }
        return null
    }

    // Table-first Strategy 4 fallback: called when no nested call site is found.
    // Discovers p and xorVar from the string table, locates the dispatcher function
    // body, then runs the shared Steps 4b-9 extraction.
    private fun extractSigOpsTableFirst(js: String): List<SigOp>? {
        // Track the most specific failure so far (wins over the generic "no table" fallback).
        var bestHint = "d0-tf-noTable"
        for (sep in listOf("{", ";", "|", "~", "^", ",")) {
            val eS = Regex.escape(sep)
            val teM = Regex("""(?<![.\w])([\w$]+)\s*=\s*"([^"]{200,})"\s*\.split\s*\(\s*"$eS"\s*\)""").find(js)
                ?: continue
            val tableVar = teM.groupValues[1]
            val u = teM.groupValues[2].split(sep)
            if (listOf("split", "join", "reverse", "splice").any { it !in u }) {
                if (bestHint == "d0-tf-noTable") bestHint = "d0-tf-tableNoOps($tableVar/$sep)"
                continue
            }

            val tableDiscovered = discoverPFromTable(js, tableVar, u)
            val splitDiscovered = if (tableDiscovered == null) discoverPFromSplitCall(js, tableVar, u) else null
            if (tableDiscovered == null && splitDiscovered == null) {
                bestHint = "d0-tf-noP($tableVar/$sep)"; continue
            }
            val p        = tableDiscovered?.first  ?: splitDiscovered!!.first
            val xorVar   = tableDiscovered?.second ?: splitDiscovered!!.second
            val splitVarHint = splitDiscovered?.third  // non-null only when found via splitCall
            Log.d(tag, "extractSigOps(tf): table=$tableVar sep=$sep p=$p xorVar=$xorVar splitVarHint=$splitVarHint")

            // Find dispatcher body: function containing "var xorVar = A^B"
            // with at least 3 TABLE[xorVar^N] accesses (confirms it is the dispatcher).
            val xorDeclRe = Regex("""var\s+${Regex.escape(xorVar)}\s*=\s*\w+\s*\^\s*\w+""")
            val xorPat    = Regex("""${Regex.escape(tableVar)}\[${Regex.escape(xorVar)}\^\d+\]""")
            var dispBody: String? = null
            for (xm in xorDeclRe.findAll(js)) {
                val fnStart  = js.lastIndexOf("function", xm.range.first).takeIf { it >= 0 } ?: continue
                val braceIdx = js.indexOf("{", fnStart).takeIf { it >= 0 } ?: continue
                val body     = extractBalancedJs(js, braceIdx) ?: continue
                if (xorPat.findAll(body).count() >= 3) { dispBody = body; break }
            }
            val body = dispBody
                ?: run { bestHint = "d0-tf-noDispBody($tableVar/$xorVar)"; null } ?: continue

            // Steps 4b-9: same logic as main dispatcher path
            val splitIdx   = u.indexOf("split")
            val joinIdx    = u.indexOf("join")
            val reverseIdx = u.indexOf("reverse")
            val spliceIdx  = u.indexOf("splice")
            val eT = Regex.escape(tableVar); val eX = Regex.escape(xorVar)
            // splitVarHint from discoverPFromSplitCall is authoritative — it IS the var from the split call.
            val splitVar = splitVarHint
                ?: Regex("""var\s+([\w$]+)\s*=\s*\w+\[$eT\[$eX\^\d+\]\]\($eT\[\d+\]\)""").find(body)?.groupValues?.get(1)
                ?: Regex("""var\s+([\w$]+)\s*=\s*\w+\[$eT\[$eX\^\d+\]\]\(""\)""").find(body)?.groupValues?.get(1)
                ?: Regex("""var\s+([\w$]+)\s*=\s*\w+\[$eT\[$eX\^\d+\]\]\($eT\[$eX\^\d+\]\)""").find(body)?.groupValues?.get(1)
                ?: Regex("""return\s+([\w$]+)\[$eT\[$eX\^\d+\]\]""").find(body)?.groupValues?.get(1)
                ?: "b"
            val eS2 = Regex.escape(splitVar)
            val helperName = Regex("""([\w$]+)\s*\[$eT\[$eX\^\d+\]\]\s*\($eS2""").find(body)?.groupValues?.get(1)
                ?: Regex("""([\w$]+)\[$eT\[$eX\^\d+\]\]\(""").findAll(body)
                    .map { it.groupValues[1] }
                    .filter { it != tableVar && it != xorVar && it != splitVar }
                    .groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.key
                ?: run { bestHint = "d0-tf-noHelper($tableVar/$xorVar/$splitVar)"; null } ?: continue

            val (_, helperBody) = findHelperBody(js, helperName, 0)
                ?: run { bestHint = "d0-tf-noHelperBody($helperName)"; null } ?: continue
            val pwMap    = mutableMapOf<String, SigOp>()
            val revMark  = "$tableVar[$reverseIdx]"
            val splMark  = "$tableVar[$spliceIdx]"
            val mRe      = Regex("""([\w$]+)\s*:\s*function\s*\(([^)]*)\)\s*\{([^}]*)\}""")
            for (m in mRe.findAll(helperBody)) {
                val mn     = m.groupValues[1]
                val nParam = m.groupValues[2].split(",").count { it.isNotBlank() }
                val mbody  = m.groupValues[3]
                when {
                    nParam == 1 && mbody.contains(revMark) -> pwMap[mn] = SigOp.Reverse
                    nParam == 2 && mbody.contains(splMark) -> pwMap[mn] = SigOp.Splice(0)
                    nParam == 2 && mbody.contains("%") && mn !in pwMap -> pwMap[mn] = SigOp.Swap(0)
                }
            }
            if (pwMap.size < 2) {
                bestHint = "d0-tf-pwMapSmall($helperName/${pwMap.size})"; continue
            }

            val eH  = Regex.escape(helperName)
            val opRe = Regex("""$eH\[$eT\[$eX\^(\d+)\]\]\($eS2(?:,$eX\^(\d+)|,(\d+))?\)""")
            val ops  = mutableListOf<SigOp>()
            for (m in opRe.findAll(body)) {
                val methodXor = m.groupValues[1].toIntOrNull() ?: continue
                val methodIdx = p xor methodXor
                val methodNm  = u.getOrNull(methodIdx) ?: continue
                val baseOp    = pwMap[methodNm] ?: continue
                val arg = when {
                    m.groupValues[2].isNotEmpty() -> p xor m.groupValues[2].toInt()
                    m.groupValues[3].isNotEmpty() -> m.groupValues[3].toInt()
                    else -> 0
                }
                ops.add(when (baseOp) {
                    SigOp.Reverse   -> SigOp.Reverse
                    is SigOp.Splice -> SigOp.Splice(arg)
                    is SigOp.Swap   -> SigOp.Swap(arg)
                })
            }
            if (ops.isNotEmpty()) {
                sigOpsHint = "ok-d-tf-${ops.size}ops"
                Log.d(tag, "extractSigOps(dispatcher/table-first): ${ops.size} ops '$tableVar'/'$helperName' p=$p")
                return ops
            }
            bestHint = "d0-tf-emptyOps($tableVar/$helperName)"
        }
        sigOpsHint = bestHint
        return null
    }

    // YouTube 2026+ flat-dispatcher sig extraction.
    //
    // The player eliminated the traditional sig-fn-calls-helper pattern. Instead, a single
    // dispatcher function (e.g. Qp) handles all URL transforms:
    //   Qp(25,37,  Qp(51,3416, I.s))
    //       ↑ outer: sig ops (p = 37^25 = 60)
    //                 ↑ inner: decodeURIComponent(I.s)
    //
    // Inside the dispatcher:
    //   var p = K^R;
    //   b = x[u[p^48]](u[2]);          // x.split("") via string table u
    //   Pw[u[p^117]](b, 2);             // splice(0,2) via helper Pw + string table
    //   Pw[u[p^123]](b, p^14);          // reverse
    //   …
    //   t = b[u[p^5]](u[2]);            // b.join("")
    //
    // We find: call site → dispatcher fn → xor var → table var → helper var →
    //          string table → verify p → Pw opMap → extract ops in order.
    private fun extractSigOpsDispatcher(js: String): List<SigOp>? {
        // Step 1: nested call site  FNAME(R1,K1, FNAME(R2,K2, SIG.??))
        // Outer R1^K1 = p.  Inner call wraps the raw signature property.
        // YouTube may rename .s → .sig / .sc / etc., so we try three patterns.
        val csPatterns = listOf(
            // Exact: inner last arg is OBJ.s  (original / 5cabb421)
            Regex("""(\w+)\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\1\s*\(\s*\d+\s*,\s*\d+\s*,\s*\w+\.s\s*\)\s*\)"""),
            // Sig property renamed (OBJ.sig / OBJ.sc / OBJ.se …)
            Regex("""(\w+)\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\1\s*\(\s*\d+\s*,\s*\d+\s*,\s*\w+\.\w+\s*\)\s*\)"""),
            // Any short inner arg (no nested parens, ≤60 chars)
            Regex("""(\w+)\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\1\s*\(\s*\d+\s*,\s*\d+\s*,[^()]{1,60}\)\s*\)"""),
        )
        val cs = csPatterns.firstNotNullOfOrNull { it.find(js) }
        if (cs == null) {
            sigOpsHint = "d0-noCallSite"
            return extractSigOpsTableFirst(js)
        }
        val dispName = cs.groupValues[1]
        val outerR   = cs.groupValues[2].toIntOrNull() ?: return null
        val outerK   = cs.groupValues[3].toIntOrNull() ?: return null
        val p        = outerR xor outerK
        sigOpsHint   = "d1-noDispFn($dispName)"

        // Step 2: dispatcher function body
        val dispFnIdx = js.indexOf("$dispName=function(").takeIf { it >= 0 }
            ?: js.indexOf("var $dispName=function(").takeIf { it >= 0 }
            ?: run { sigOpsHint = "d1-noFnDef($dispName)"; return null }
        val dispBrace = js.indexOf("{", dispFnIdx).takeIf { it >= 0 } ?: return null
        val dispBody  = extractBalancedJs(js, dispBrace) ?: return null
        sigOpsHint    = "d2-noXorVar($dispName)"

        // Step 3: XOR variable ("p" from "var p = K^R")
        val xorM = Regex("""var\s+([\w$]+)\s*=\s*[\w$]+\s*\^\s*[\w$]+""").find(dispBody)
            ?: run { sigOpsHint = "d2-noXorVar($dispName/body)"; return null }
        val xorVar = xorM.groupValues[1]
        sigOpsHint = "d3-noTableVar($dispName/$xorVar)"

        // Step 4: string-table variable — find any "TABLE[xorVar^N]" access.
        // The XOR constant was 48 for player 5cabb421 (p=60, split@12, 60^12=48),
        // but is player-specific. Use \d+ to match any constant.
        val tvM = Regex("""([\w$]+)\s*\[${Regex.escape(xorVar)}\^\d+\]""").find(dispBody)
            ?: run { sigOpsHint = "d3-noTableVar($dispName/$xorVar/body)"; return null }
        val tableVar = tvM.groupValues[1]
        sigOpsHint   = "d4-noHelperVar($dispName/$tableVar)"

        // Step 4b: find the split-result array variable name.
        // Separator can be u[M] (table-indexed) or literal ""; try both.
        // Final fallback: use the return/join call's subject (the array joined at the end).
        val eT0 = Regex.escape(tableVar); val eX0 = Regex.escape(xorVar)
        val splitVar = Regex("""var\s+([\w$]+)\s*=\s*\w+\[$eT0\[$eX0\^\d+\]\]\($eT0\[\d+\]\)""").find(dispBody)?.groupValues?.get(1)
            ?: Regex("""var\s+([\w$]+)\s*=\s*\w+\[$eT0\[$eX0\^\d+\]\]\(""\)""").find(dispBody)?.groupValues?.get(1)
            ?: Regex("""return\s+([\w$]+)\[$eT0\[$eX0\^\d+\]\]""").find(dispBody)?.groupValues?.get(1)
            ?: "b"

        // Step 5: helper-object name.
        // Primary: "HELPER[TABLE[XOR^N]](SPLIT_VAR" — splitVar as first arg avoids matching
        //          the split call itself where the first arg is u[M] or "".
        // Fallback: the variable subscripted with TABLE[XOR^N] that is called 2+ times
        //           (helper is invoked once per sig op; split/join are only called once each).
        val eS0 = Regex.escape(splitVar)
        val helperName = Regex("""([\w$]+)\s*\[$eT0\[$eX0\^\d+\]\]\s*\($eS0""").find(dispBody)?.groupValues?.get(1)
            ?: Regex("""([\w$]+)\[$eT0\[$eX0\^\d+\]\]\(""").findAll(dispBody)
                .map { it.groupValues[1] }
                .filter { it != tableVar && it != xorVar && it != splitVar }
                .groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.key
            ?: run { sigOpsHint = "d4-noHelperVar($dispName/$tableVar/$splitVar/body)"; return null }
        sigOpsHint = "d5-noTable($dispName/$helperName)"

        // Step 6: string table — tableVar = "...".split("<sep>")
        // YouTube has used "{" (5cabb421), "|" and ";" (82ae1e3c) as separators.
        val (tableRaw, tableSep) = listOf("{", ";", "|").firstNotNullOfOrNull { sep ->
            val re = Regex("""(?<![.\w])${Regex.escape(tableVar)}\s*=\s*"([^"]{300,})"\s*\.split\s*\(\s*"${Regex.escape(sep)}"\s*\)""")
            re.find(js)?.let { it.groupValues[1] to sep }
        } ?: run { sigOpsHint = "d5-noTableStr($tableVar)"; return null }
        val u   = tableRaw.split(tableSep)
        sigOpsHint = "d6-noTableVerify($dispName/p=$p)"

        // Step 7: verify string table has all required op names.
        // The XOR constants (^48 for split, ^5 for join) are player-specific — don't check them.
        // Just confirm the four required strings exist in the table.
        val splitIdx   = u.indexOf("split").takeIf   { it >= 0 } ?: run { sigOpsHint = "d6-noSplitInTable";   return null }
        val joinIdx    = u.indexOf("join").takeIf    { it >= 0 } ?: run { sigOpsHint = "d6-noJoinInTable";    return null }
        val reverseIdx = u.indexOf("reverse").takeIf { it >= 0 } ?: run { sigOpsHint = "d6-noReverseInTable"; return null }
        val spliceIdx  = u.indexOf("splice").takeIf  { it >= 0 } ?: run { sigOpsHint = "d6-noSpliceInTable";  return null }
        sigOpsHint = "d7-noPwDef($helperName)"

        // Step 8: Pw helper object — methods reference string table for method names
        // e.g. bi:function(R){R[u[48]]()}  jR:function(R,K){R[u[17]](0,K)}  ue:swap
        val (_, helperBody) = findHelperBody(js, helperName, dispFnIdx)
            ?: run { sigOpsHint = "d7-noPwBody($helperName)"; return null }
        val pwMap = mutableMapOf<String, SigOp>()
        val mRe   = Regex("""([\w$]+)\s*:\s*function\s*\(([^)]*)\)\s*\{([^}]*)\}""")
        val revMark = "$tableVar[$reverseIdx]"
        val splMark = "$tableVar[$spliceIdx]"
        for (m in mRe.findAll(helperBody)) {
            val mn  = m.groupValues[1]
            val nParams = m.groupValues[2].split(",").count { it.isNotBlank() }
            val body    = m.groupValues[3]
            when {
                nParams == 1 && body.contains(revMark) -> pwMap[mn] = SigOp.Reverse
                nParams == 2 && body.contains(splMark)  -> pwMap[mn] = SigOp.Splice(0)
                nParams == 2 && body.contains("%") && mn !in pwMap -> pwMap[mn] = SigOp.Swap(0)
            }
        }
        if (pwMap.size < 2) {
            sigOpsHint = "d7-pwMapSmall($helperName/${pwMap.size})"; return null
        }
        sigOpsHint = "d8-noOps($dispName/$helperName)"

        // Step 9: extract ordered ops from dispatcher body
        // Each op call: helperName[tableVar[xorVar^METHOD_XOR]](splitVar, xorVar^ARG_XOR | LITERAL)
        val eH = Regex.escape(helperName)
        val eT = Regex.escape(tableVar)
        val eX = Regex.escape(xorVar)
        val eS = Regex.escape(splitVar)
        val opRe = Regex("""$eH\[$eT\[$eX\^(\d+)\]\]\($eS(?:,$eX\^(\d+)|,(\d+))?\)""")
        val ops  = mutableListOf<SigOp>()
        for (m in opRe.findAll(dispBody)) {
            val methodXor = m.groupValues[1].toIntOrNull() ?: continue
            val methodIdx = p xor methodXor
            val methodNm  = u.getOrNull(methodIdx) ?: continue
            val baseOp    = pwMap[methodNm] ?: continue
            val arg = when {
                m.groupValues[2].isNotEmpty() -> p xor m.groupValues[2].toInt()
                m.groupValues[3].isNotEmpty() -> m.groupValues[3].toInt()
                else -> 0
            }
            ops.add(when (baseOp) {
                SigOp.Reverse    -> SigOp.Reverse
                is SigOp.Splice  -> SigOp.Splice(arg)
                is SigOp.Swap    -> SigOp.Swap(arg)
            })
        }
        if (ops.isEmpty()) {
            sigOpsHint = "d8-emptyOps($dispName/$helperName)"; return null
        }
        sigOpsHint = "ok-d-${ops.size}ops"
        Log.d(tag, "extractSigOps(dispatcher): ${ops.size} ops OK '$dispName'/'$helperName' p=$p pwMap=$pwMap")
        return ops
    }

    // Apply extracted sig-decode operations to a raw signature string.
    private fun applySigOps(sig: String, ops: List<SigOp>): String {
        val a = sig.toMutableList()
        for (op in ops) when (op) {
            SigOp.Reverse -> a.reverse()
            is SigOp.Splice -> repeat(op.n) { if (a.isNotEmpty()) a.removeAt(0) }
            is SigOp.Swap -> {
                if (a.isNotEmpty()) {
                    val idx = op.n % a.size
                    val tmp = a[0]; a[0] = a[idx]; a[idx] = tmp
                }
            }
        }
        return a.joinToString("")
    }

    /** NPE-only stream resolution — used in the parallel race with Piped. */
    private fun fetchAudioStreamNpe(videoId: String): String {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val allStreams = info.audioStreams.filter { it.content != null }
        val nonIos = allStreams.filter { !it.content!!.contains("c=IOS", ignoreCase = true) }
        val best = (nonIos.ifEmpty { allStreams }).maxByOrNull { it.averageBitrate }
            ?: run {
                val potErr = app.opentune.utils.potoken.OpenTunePoTokenProvider.lastError
                throw Exception("${info.audioStreams.size} streams, none with URL${if (potErr != null) " [pot:$potErr]" else ""}")
            }
        return best.content!!
    }

    /** Resolve a video id to a direct audio CDN URL (highest-bitrate audio stream). */
    fun getAudioStreamUrl(videoId: String): String {
        val start = System.currentTimeMillis()
        val errors = mutableListOf<String>()

        // 1+2. Race Piped vs NPE simultaneously — both fire at t=0, first success wins.
        // Eliminates sequential wait: if Piped is slow/down, NPE result arrives without delay.
        data class RaceItem(val url: String?, val client: String, val err: String? = null)
        val raceJob = SupervisorJob()
        val raceScope = CoroutineScope(Dispatchers.IO + raceJob)
        val ch = Channel<RaceItem>(2)
        raceScope.launch {
            try { ch.trySend(RaceItem(fetchAudioStreamPiped(videoId), "Piped")) }
            catch (e: Exception) { ch.trySend(RaceItem(null, "Piped", e.message?.take(80))) }
        }
        raceScope.launch {
            try { ch.trySend(RaceItem(fetchAudioStreamNpe(videoId), "NPE")) }
            catch (e: Exception) { ch.trySend(RaceItem(null, "NPE", e.message?.take(80))) }
        }
        var raceWinner: RaceItem? = null
        var raceFails = 0
        runBlocking {
            while (raceWinner == null && raceFails < 2) {
                val r = ch.receive()
                if (r.url != null) raceWinner = r else { errors += "${r.client}: ${r.err}"; raceFails++ }
            }
        }
        raceJob.cancel()

        if (raceWinner != null) {
            val url = raceWinner.url!!
            val client = raceWinner.client
            val elapsed = System.currentTimeMillis() - start
            val isIos = url.contains("c=IOS", ignoreCase = true)
            Log.d(tag, "getAudioStreamUrl($videoId) ok via $client ${elapsed}ms${if (isIos) " ios=true" else ""}")
            app.opentune.utils.DiagnosticsLogger.logStream(videoId, true, elapsed, client = "$client${if (isIos) "(ios)" else ""}", urlHint = urlHint(url))
            return url
        }

        // 3. Native player API cascade (WEB_EMBEDDED → WEB_REMIX+PoToken → WEB+PoToken → ANDROID_VR → …).
        // Kept as fallback: direct-URL clients (ANDROID_VR, ANDROID_MUSIC) bypass sig decode entirely.
        var nativeUrl: String? = null
        var nativeClientTag: String? = null
        var nativeFallbackErrs: List<String> = emptyList()
        try {
            val result = fetchAudioStreamNative(videoId)
            nativeUrl = result.first
            nativeClientTag = result.second
            nativeFallbackErrs = result.third
        } catch (nativeEx: Exception) {
            errors += "native: ${nativeEx.message?.take(800)}"
        }

        val nStatus = nativeClientTag?.substringAfter("|n=", "enc") ?: ""
        if (nativeUrl != null && nStatus != "raw") {
            val elapsed = System.currentTimeMillis() - start
            val nativeClient = nativeClientTag!!.substringBefore("|n=")
            Log.d(tag, "getAudioStreamUrl($videoId) ok via $nativeClient ${elapsed}ms n=$nStatus")
            val fallbackNote = nativeFallbackErrs.joinToString("; ").take(600).ifBlank { null }
            app.opentune.utils.DiagnosticsLogger.logStream(
                videoId, true, elapsed, client = nativeClient, urlHint = urlHint(nativeUrl, nStatus), error = fallbackNote
            )
            return nativeUrl
        }
        if (nativeUrl != null && nStatus == "raw") {
            val nativeClient = nativeClientTag!!.substringBefore("|n=")
            Log.w(tag, "getAudioStreamUrl($videoId) native ok ($nativeClient) n=raw — no more fallback")
            errors += "native($nativeClient): n=raw"
        }

        val elapsed = System.currentTimeMillis() - start
        val combined = errors.joinToString(" | ")
        Log.w(tag, "getAudioStreamUrl($videoId) ALL FAILED ${elapsed}ms: $combined")
        app.opentune.utils.DiagnosticsLogger.logStream(videoId, false, elapsed, combined.take(600))
        throw Exception(combined)
    }

    // Piped is a public YouTube proxy that resolves streams server-side — no user auth needed.
    // Tries multiple public instances in order; first successful response wins.
    // Static fallback list — used when the live instance directory is unreachable.
    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.ducks.party",
        "https://pipedapi.reallyaweso.me",
    )

    // Live instance list from the official Piped directory, refreshed every 6h.
    // Public instances die frequently; fetching the directory at runtime keeps
    // the escape route working without app updates.
    @Volatile
    private var dynamicPipedInstances: List<String>? = null

    @Volatile
    private var pipedInstancesFetchedAt = 0L

    private fun currentPipedInstances(): List<String> {
        val now = System.currentTimeMillis()
        if (now - pipedInstancesFetchedAt > 6 * 60 * 60 * 1000L) {
            pipedInstancesFetchedAt = now  // throttle: one directory attempt per window
            try {
                val response = pipedHttpClient.newCall(
                    Request.Builder()
                        .url("https://piped-instances.kavin.rocks/")
                        .addHeader("User-Agent", "OpenTune/2 Android")
                        .get()
                        .build()
                ).execute()
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val arr = JSONArray(body)
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val apiUrl = arr.getJSONObject(i).optString("api_url")
                        if (apiUrl.startsWith("https://")) list.add(apiUrl.trimEnd('/'))
                    }
                    if (list.isNotEmpty()) {
                        dynamicPipedInstances = list.take(6)
                        Log.d(tag, "piped directory: ${list.size} instances, using ${dynamicPipedInstances!!.size}")
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "piped directory fetch failed: ${e.message?.take(80)}")
            }
        }
        // Merge: live list first, static fallbacks after (deduped).
        val dynamic = dynamicPipedInstances ?: emptyList()
        return (dynamic + pipedInstances).distinct()
    }

    private fun fetchAudioStreamPiped(videoId: String): String {
        var lastError = "no instances tried"
        // Cap attempts so a fully-dead list fails in bounded time (NPE usually
        // wins the race long before this path exhausts anyway).
        for (instance in currentPipedInstances().take(6)) {
            try {
                val response = pipedHttpClient.newCall(
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

    // Decode a YouTube signatureCipher string into a playable URL using native Kotlin sig decode.
    // signatureCipher format: url=BASE_URL&s=ENCODED_SIG&sp=PARAM_NAME (URL-encoded values)
    private fun decodeCipherUrl(cipher: String): String? {
        val params = cipher.split("&").associate {
            val eq = it.indexOf('=')
            if (eq < 0) it to ""
            else it.substring(0, eq) to try {
                java.net.URLDecoder.decode(it.substring(eq + 1), "UTF-8")
            } catch (_: Exception) { it.substring(eq + 1) }
        }
        val baseUrl = params["url"] ?: return null
        val rawSig = params["s"] ?: return null
        val sigParam = params["sp"] ?: "sig"

        val ops = cachedSigOps ?: run { ensurePlayerJsData(); cachedSigOps }
        val decodedSig = if (ops != null) {
            applySigOps(rawSig, ops)
        } else {
            // Kotlin sigOps unavailable (player JS extraction failed). Try WebView sig-decode:
            // PoTokenWebView injects the actual player JS decode function during init, so it works
            // even when our regex-based parser can't parse the current JS obfuscation pattern.
            val wvSig = try {
                runBlocking { app.opentune.utils.potoken.OpenTunePoTokenProvider.decodeSig(rawSig) }
            } catch (_: Exception) { null }
            if (wvSig != null) {
                Log.d(tag, "decodeCipherUrl: used WebView sig-decode fallback")
                wvSig
            } else {
                Log.w(tag, "decodeCipherUrl: sig decode unavailable (Kotlin sigOps=null, WebView=null)")
                return null
            }
        }
        val sep = if (baseUrl.contains('?')) '&' else '?'
        return "$baseUrl$sep$sigParam=${java.net.URLEncoder.encode(decodedSig, "UTF-8")}"
    }

    // Build a safe URL hint for logging: host + key params (no sensitive data).
    // nStatus: "dec" = n decoded, "raw" = n decode failed, "enc" = n present (unknown), "none" = no n param.
    private fun urlHint(url: String, nStatus: String = "enc"): String {
        val host = try { android.net.Uri.parse(url).host?.take(30) ?: "?" } catch (_: Exception) { "?" }
        val hasN = url.contains("&n=") || url.contains("?n=")
        val hasAlr = url.contains("alr=yes")
        val nTag = if (!hasN) " n=none" else " n=$nStatus"
        val alrTag = if (hasAlr) " alr=yes" else ""
        // Show expire delta and IP type to help diagnose CDN 403
        val expireTag = try {
            val exp = Regex("""[?&]expire=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
            if (exp != null) { val delta = exp - System.currentTimeMillis() / 1000; " exp=${delta}s" } else ""
        } catch (_: Exception) { "" }
        val ipTag = try {
            val ip = Regex("""[?&]ip=([^&]+)""").find(url)?.groupValues?.get(1)
            if (ip != null) { if (ip.contains(":")) " ip=v6" else " ip=v4" } else ""
        } catch (_: Exception) { "" }
        return "$host$nTag$alrTag$expireTag$ipTag"
    }

    // Decode the n-parameter in a YouTube CDN URL using the player JS function.
    // Returns Pair(finalUrl, nStatus) where nStatus is "dec", "dec_np", "raw", or "none".
    // videoId is passed to NewPipe so it can locate the correct player JS.
    private fun decodeNParamInUrl(url: String, videoId: String = ""): Pair<String, String> {
        val nMatch = Regex("""[?&]n=([^&]+)""").find(url) ?: return url to "none"
        val nRaw = nMatch.groupValues[1]

        // Primary: NewPipe Extractor — handles all player versions including es6 class-based n-decode.
        // Uses cached Rhino JS execution; fast on repeated calls for the same player.
        try {
            val vid = videoId.ifBlank { "jNQXAC9IVRw" }
            val deobfUrl = YoutubeJavaScriptPlayerManager
                .getUrlWithThrottlingParameterDeobfuscated(vid, url)
            if (deobfUrl != url) {
                Log.d(tag, "n-param decoded via NPE: ${nRaw.take(8)}..")
                app.opentune.utils.potoken.OpenTunePoTokenProvider.nDecodeStatus = "dec_np"
                return deobfUrl to "dec_np"
            }
            Log.w(tag, "n-param NPE returned same URL — trying WebView fallback")
        } catch (e: Exception) {
            Log.w(tag, "n-param NPE failed: ${e.message} — trying WebView fallback")
        }

        // Fallback: WebView-based decode (IIFE extracted from player JS).
        val nDecoded = try {
            runBlocking { app.opentune.utils.potoken.OpenTunePoTokenProvider.decodeNParam(nRaw) }
        } catch (e: Exception) {
            Log.w(tag, "n-param WebView decode exception: ${e.message}")
            null
        }
        if (nDecoded != null && nDecoded != nRaw) {
            Log.d(tag, "n-param decoded via WebView: ${nRaw.take(8)}.. → ${nDecoded.take(8)}..")
            return url.replace("&n=$nRaw", "&n=$nDecoded").replace("?n=$nRaw", "?n=$nDecoded") to "dec"
        }

        return url to "raw"
    }

    // Multi-client native player API cascade. Tries clients in order, returns first working URL.
    private data class NativeClient(
        val name: String, val version: String, val clientId: String,
        val url: String, val userAgent: String, val origin: String = "",
        val apiKey: String = "",              // passed as X-Goog-Api-Key header (not URL param)
        val clientContextJson: String = "",  // fields appended inside context.client {}
        val contextJson: String = "",         // fields appended inside context {} alongside client
        val usePoToken: Boolean = false,
        val useVideoEmbedUrl: Boolean = false,
        val isNativeApp: Boolean = false,     // true = no web headers, use API key header
        val useAuth: Boolean = false,         // true = requires OAuth Bearer token
        val addApiFormatVersion: Boolean = false, // true = add X-Goog-Api-Format-Version: 2 (IOS only)
    )

    private val nativeClients = listOf(
        // WEB_EMBEDDED_PLAYER — tried FIRST: no PoToken, no Play Integrity, no auth.
        // Direct replacement for deprecated TVHTML5_SEP (clientId=85, blocked June 2026 with
        // "no longer supported" error). Uses same embed URL trick; YouTube allows embedded player
        // requests without PoToken for publicly embeddable content. Returns signatureCipher decoded
        // via Kotlin sigOps or WebView fallback. Web CDN URLs play correctly in ExoPlayer.
        NativeClient(
            name = "WEB_EMBEDDED_PLAYER", version = "1.20250101.09.00", clientId = "56",
            url = "https://www.youtube.com/youtubei/v1/player",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
            origin = "https://www.youtube.com",
            useVideoEmbedUrl = true,
        ),
        // WEB_REMIX — YouTube Music web client. PoToken-gated; CDN URLs from web clients
        // work reliably with ExoPlayer (no UA binding). Primary when PoToken is valid.
        NativeClient(
            name = "WEB_REMIX", version = "1.20260213.01.00", clientId = "67",
            url = "https://music.youtube.com/youtubei/v1/player",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            origin = "https://music.youtube.com",
            usePoToken = true,
        ),
        // WEB + PoToken — BotGuard WebView token.
        NativeClient(
            name = "WEB", version = "2.20260101.00.00", clientId = "1",
            url = "https://www.youtube.com/youtubei/v1/player",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
            origin = "https://www.youtube.com",
            usePoToken = true,
        ),
        // IOS CDN URLs consistently return 403 to ExoPlayer regardless of UA or headers.
        // Removed from cascade — NPE (NewPipeExtractor) handles sig decode with up-to-date
        // patterns and returns non-IOS URLs that work reliably.
        // NativeClient("IOS", ...) — intentionally omitted.
        // ANDROID_VR — YouTube VR (Oculus Quest 3) client.
        NativeClient(
            name = "ANDROID_VR", version = "1.61.48", clientId = "28",
            url = "https://www.youtube.com/youtubei/v1/player",
            apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KpynQLqgref8Xw",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Quest 3 Build/SQ3A.220605.009.A1) gzip",
            clientContextJson = """"deviceMake":"Oculus","deviceModel":"Quest 3","osName":"Android","osVersion":"12","androidSdkVersion":"32","platform":"MOBILE"""",
            isNativeApp = true,
        ),
        // ANDROID_MUSIC — YouTube Music Android client.
        NativeClient(
            name = "ANDROID_MUSIC", version = "7.27.52", clientId = "21",
            url = "https://www.youtube.com/youtubei/v1/player",
            apiKey = "AIzaSyAOghZGza2MQSZkY_zfZ370N-PUdXEo8AI",
            userAgent = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14; en_US) gzip",
            clientContextJson = """"deviceMake":"Google","deviceModel":"Pixel 9","osName":"Android","osVersion":"14","androidSdkVersion":"34","platform":"MOBILE"""",
            isNativeApp = true,
        ),
        // TVHTML5 authenticated — requires OAuth; bypasses all bot detection.
        NativeClient(
            name = "TVHTML5", version = "7.20260101.00.00", clientId = "7",
            url = "https://www.youtube.com/youtubei/v1/player",
            userAgent = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1",
            origin = "https://www.youtube.com",
            useAuth = true,
        ),
        // ANDROID_TESTSUITE — internal test client.
        NativeClient(
            name = "ANDROID_TESTSUITE", version = "1.9", clientId = "30",
            url = "https://www.youtube.com/youtubei/v1/player",
            apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KpynQLqgref8Xw",
            userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 14) gzip",
            clientContextJson = """"osName":"Android","osVersion":"14","androidSdkVersion":"30","platform":"MOBILE"""",
            isNativeApp = true,
        ),
    )

    private fun fetchAudioStreamNative(videoId: String): Triple<String, String, List<String>> {
        val json = "application/json; charset=utf-8".toMediaType()
        val errors = mutableListOf<String>()
        // Log player JS state immediately — visible in logcat and helps diagnose sigOps issues.
        ensurePlayerJsData()
        Log.i(tag, "fetchAudioStreamNative($videoId) playerJs: sigTs=$cachedSigTs sigOps=${cachedSigOps?.size ?: "null"}")

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
            // signatureTimestamp: only for browser (non-native) clients. Native app clients
            // (IOS, Android) don't use signatureCiphers and YouTube returns HTTP 400
            // "Precondition check failed" if this field is sent to them.
            val sigTs = getSignatureTimestamp()
            val playbackCtx = if (sigTs > 0 && !client.isNativeApp) {
                ""","playbackContext":{"contentPlaybackContext":{"signatureTimestamp":$sigTs}}"""
            } else ""

            val body = """{"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true,"context":{"client":{"clientName":"${client.name}","clientVersion":"${client.version}","hl":"en","gl":"US","timeZone":"UTC","utcOffsetMinutes":0$visitorDataFrag$clientFrag}$contextFrag}$poTokenFrag$playbackCtx}"""
            try {
                val reqBuilder = Request.Builder()
                    .url(client.url)
                    .post(body.toRequestBody(json))
                    .addHeader("User-Agent", client.userAgent)
                    .addHeader("Content-Type", "application/json")
                if (client.isNativeApp) {
                    // Native app clients: API key goes in header, not URL.
                    // Web-style headers (X-YouTube-Client-*, Origin, Cookie) cause rejections.
                    if (client.apiKey.isNotBlank()) {
                        reqBuilder.addHeader("X-Goog-Api-Key", client.apiKey)
                    }
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
                    val br = fmt.optInt("averageBitrate", 0).takeIf { it > 0 } ?: fmt.optInt("bitrate", 0)

                    // Try direct URL first (native app clients: iOS, Android)
                    val directUrl = fmt.optString("url", "").takeIf { it.isNotBlank() }
                    if (directUrl != null) {
                        if (br > bestBitrate) { bestBitrate = br; bestUrl = directUrl }
                        continue
                    }

                    // Handle signatureCipher / cipher (WEB/WEB_REMIX browser clients)
                    val cipher = fmt.optString("signatureCipher").takeIf { it.isNotBlank() }
                        ?: fmt.optString("cipher").takeIf { it.isNotBlank() }
                    if (cipher != null) {
                        val cipherUrl = decodeCipherUrl(cipher)
                        if (cipherUrl != null && br > bestBitrate) { bestBitrate = br; bestUrl = cipherUrl }
                    }
                }
                if (bestUrl != null) {
                    // Strip alr=yes — YouTube's "adaptive live redirect" causes CDN to return
                    // a non-standard redirect body (not HTTP 302) that ExoPlayer can't handle,
                    // resulting in 403 or wrong-format errors.
                    val cleanUrl = bestUrl
                        .replace("&alr=yes", "")
                        .replace("?alr=yes&", "?")
                        .replace("?alr=yes", "")
                    val (finalUrl, nStatus) = decodeNParamInUrl(cleanUrl, videoId)
                    Log.d(tag, "fetchAudioStreamNative($videoId) ok via ${client.name} bitrate=$bestBitrate alr=${bestUrl.contains("alr=yes")} n=$nStatus")
                    return Triple(finalUrl, "${client.name}|n=$nStatus", errors.toList())
                }
                errors += "${client.name}: no audio URL in ${formats.length()} formats (sigTs=$cachedSigTs sigOps=${cachedSigOps?.size ?: "null"} hint=$sigOpsHint)"

            } catch (e: Exception) {
                errors += "${client.name}: ${e.javaClass.simpleName} ${e.message?.take(80)}"
            }
        }
        error(errors.joinToString(" | "))
    }

    fun search(query: String): SearchResult<YtMusicTrack> {
        return try {
            val info = SearchInfo.getInfo(
                ServiceList.YouTube,
                ServiceList.YouTube.searchQHFactory.fromQuery(query, listOf("music_songs"), ""),
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
            val qh = ServiceList.YouTube.searchQHFactory.fromQuery(query, listOf("music_songs"), "")
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
        val start = System.currentTimeMillis()
        var result = fetchPlaylistViaInnertube(playlistId)
        if (result.isEmpty()) {
            // YouTube occasionally returns an empty response on the first request;
            // a single retry after a short delay is enough to recover.
            Thread.sleep(800)
            result = fetchPlaylistViaInnertube(playlistId)
        }
        if (result.isEmpty()) {
            // Fallback: NPE PlaylistInfo. Was broken in v0.26.1
            // (RuntimeException: Field browseId_ for ux3 not found) but works on v0.26.3.
            result = fetchPlaylistViaNpe(playlistId, originalUrl)
        }
        val elapsed = System.currentTimeMillis() - start
        if (result.isEmpty()) {
            app.opentune.utils.DiagnosticsLogger.logStream(
                "PL:${playlistId.take(13)}", false, elapsed, error = "playlist: 0 tracks (innertube+npe)"
            )
        }
        return result
    }

    // NPE-based playlist fetch — fallback when the Innertube browse extraction returns nothing
    // (e.g. YouTube rolled out a response format our renderer walker doesn't know yet).
    private fun fetchPlaylistViaNpe(playlistId: String, originalUrl: String): List<YtMusicTrack> {
        return try {
            val url = originalUrl.ifBlank { "https://www.youtube.com/playlist?list=$playlistId" }
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
            val items = info.relatedItems.filterIsInstance<StreamInfoItem>().toMutableList()
            var next = info.nextPage
            var pages = 0
            while (next != null && pages < 4 && items.size < 300) {
                val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next)
                items.addAll(more.items.filterIsInstance<StreamInfoItem>())
                next = more.nextPage
                pages++
            }
            Log.d(tag, "fetchPlaylistViaNpe('$playlistId'): ${items.size} tracks in ${pages + 1} pages")
            items.mapNotNull { it.toTrack() }
        } catch (e: Exception) {
            Log.w(tag, "fetchPlaylistViaNpe('$playlistId') failed [${e.javaClass.simpleName}]: ${e.message}")
            emptyList()
        }
    }

    // Fetch playlist songs via YouTube's internal Innertube /browse API.
    // This bypasses NewPipeExtractor's broken PlaylistExtractor.
    private fun fetchPlaylistViaInnertube(playlistId: String): List<YtMusicTrack> {
        val tracks = mutableListOf<YtMusicTrack>()
        var continuation: String? = null
        var pageCount = 0
        val json = "application/json; charset=utf-8".toMediaType()

        do {
            // Client version must be reasonably current — YouTube serves the new
            // lockupViewModel layout (or rejects the call) for very old versions.
            val webVer = "2.20260101.00.00"
            val body = if (continuation == null) {
                """{"browseId":"VL$playlistId","context":{"client":{"clientName":"WEB","clientVersion":"$webVer"}}}"""
            } else {
                """{"continuation":"$continuation","context":{"client":{"clientName":"WEB","clientVersion":"$webVer"}}}"""
            }

            val response = httpClient.newCall(
                Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/browse")
                    .post(body.toRequestBody(json))
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .addHeader("X-YouTube-Client-Name", "1")
                    .addHeader("X-YouTube-Client-Version", webVer)
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

    // Recursively find every playlist item in the Innertube response.
    // Handles classic renderers (playlistVideoRenderer & friends) plus the new
    // lockupViewModel layout YouTube has been rolling out since 2025.
    private fun extractPlaylistVideoRenderers(node: Any?): List<YtMusicTrack> {
        val tracks = mutableListOf<YtMusicTrack>()
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("playlistVideoRenderer")
                    ?: node.optJSONObject("playlistPanelVideoRenderer")
                    ?: node.optJSONObject("videoRenderer")
                    ?: node.optJSONObject("compactVideoRenderer")
                val lockup = node.optJSONObject("lockupViewModel")
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
                } else if (lockup != null && lockup.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO") {
                    val videoId = lockup.optString("contentId").takeIf { it.isNotBlank() }
                    if (videoId != null) {
                        val meta = lockup.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                        val title = meta?.optJSONObject("title")?.optString("content")
                            ?.takeIf { it.isNotBlank() } ?: videoId
                        val artist = meta?.optJSONObject("metadata")
                            ?.optJSONObject("contentMetadataViewModel")
                            ?.optJSONArray("metadataRows")?.optJSONObject(0)
                            ?.optJSONArray("metadataParts")?.optJSONObject(0)
                            ?.optJSONObject("text")?.optString("content") ?: ""
                        tracks.add(YtMusicTrack(
                            videoId = videoId,
                            title = title,
                            artistName = artist,
                            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                            durationText = null,
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
