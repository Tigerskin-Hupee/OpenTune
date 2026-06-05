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
        if (jsDataFetchedAt > 0 && now - jsDataFetchedAt < 3_600_000L) return
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
        val close = when (js[start]) { '[' -> ']'; '{' -> '}'; '(' -> ')'; else -> return null }
        var depth = 0; var inStr = false; var strCh = ' '; var i = start
        while (i < js.length) {
            val c = js[i]
            if (inStr) { if (c == strCh && js[i - 1] != '\\') inStr = false }
            else when (c) {
                '"', '\'', '`' -> { inStr = true; strCh = c }
                '[', '{', '(' -> depth++
                ']', '}', ')' -> { depth--; if (depth == 0) return js.substring(start, i + 1) }
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

            // Sig fn must also have a split within 10000 chars before the join
            val seg = js.substring(maxOf(0, joinIdx - 10000), joinIdx)
            val hasSplit = splitTokens.any { seg.contains(it) } || seg.contains("Array.from(")
            if (!hasSplit) continue

            // Find the enclosing function definition (regular or arrow)
            val lbOff = maxOf(0, joinIdx - 10000)
            val fd = Regex("""([\w$]+)\s*=\s*function\(\s*([\w$]+)\s*\)\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""function\s+([\w$]+)\s*\(\s*([\w$]+)\s*\)\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""([\w$]+)\s*=\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""([\w$]+)\s*=\s*([\w$]+)\s*=>\s*\{""").findAll(seg).lastOrNull()
                ?: continue
            val fnName = fd.groupValues[1]; val fnParam = fd.groupValues[2]
            val fdAbsIdx = lbOff + fd.range.first
            val fnBraceIdx = js.indexOf("{", fdAbsIdx).takeIf { it >= 0 } ?: continue
            val fnBody = extractBalancedJs(js, fnBraceIdx) ?: continue

            val fnHasSplit = splitTokens.any { fnBody.contains(it) } || fnBody.contains("Array.from(")
            val fnHasJoin  = joinTokens.any { fnBody.contains(it) }
            if (!fnHasSplit || !fnHasJoin) continue

            sigOpsHint = "s1-noHelper($diag/$fnName)"
            val helperName =
                Regex("""([\w$]+)\.([\w$]+)\($fnParam""").find(fnBody)?.groupValues?.get(1)
                    ?: Regex("""([\w$]+)\["[\w$]+"\]\($fnParam""").find(fnBody)?.groupValues?.get(1)
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
            val hm = Regex("""(?:var\s+|[;,}\s(])([\w$]+)\s*=\s*\{""").findAll(lb).lastOrNull() ?: continue
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
        sigOpsHint = "allFail($diag [$s1hint|$s2hint|$s3hint])"
        Log.w(tag, "extractSigOps: all strategies failed, $diag [$s1hint|$s2hint|$s3hint]")
        return null
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

    /** Resolve a video id to a direct audio CDN URL (highest-bitrate audio stream). */
    fun getAudioStreamUrl(videoId: String): String {
        val start = System.currentTimeMillis()
        val errors = mutableListOf<String>()

        // 1. Piped public API — server-side YouTube proxy, no user login needed.
        try {
            val url = fetchAudioStreamPiped(videoId)
            val elapsed = System.currentTimeMillis() - start
            Log.d(tag, "getAudioStreamUrl($videoId) ok via Piped ${elapsed}ms")
            app.opentune.utils.DiagnosticsLogger.logStream(videoId, true, elapsed, client = "Piped", urlHint = urlHint(url))
            return url
        } catch (e: Exception) {
            val msg = "Piped: ${e.message?.take(80)}"
            errors += msg
            Log.w(tag, "getAudioStreamUrl($videoId) $msg")
        }

        // 2. Native player API cascade (WEB_EMBEDDED → WEB_REMIX+PoToken → WEB+PoToken → IOS → ANDROID_VR → …).
        // NPE is tried AFTER native because it takes 10+ seconds on failure.
        return try {
            val (nativeUrl, nativeClient, fallbackErrs) = fetchAudioStreamNative(videoId)
            val elapsed = System.currentTimeMillis() - start
            Log.d(tag, "getAudioStreamUrl($videoId) ok via $nativeClient ${elapsed}ms")
            // Include ALL fallback errors so diagnostic shows every client tried before the winner.
            val fallbackNote = fallbackErrs.joinToString("; ").take(600).ifBlank { null }
            app.opentune.utils.DiagnosticsLogger.logStream(
                videoId, true, elapsed, client = nativeClient, urlHint = urlHint(nativeUrl), error = fallbackNote
            )
            nativeUrl
        } catch (nativeEx: Exception) {
            errors += "native: ${nativeEx.message?.take(800)}"

            // 3. NewPipeExtractor — last resort; slow (10+ s) but handles more edge cases.
            try {
                val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
                // Filter out IOS-client streams: NPE falls back to IOS when WEB fails, but IOS
                // CDN URLs cause ExoPlayer to buffer indefinitely (no error, no audio). Prefer
                // any non-IOS stream; only accept IOS as last resort if nothing else is available.
                val allStreams = info.audioStreams.filter { it.content != null }
                val nonIos = allStreams.filter { !it.content!!.contains("c=IOS", ignoreCase = true) }
                val best = (nonIos.ifEmpty { allStreams }).maxByOrNull { it.averageBitrate }
                if (best != null) {
                    val elapsed = System.currentTimeMillis() - start
                    val isIos = best.content!!.contains("c=IOS", ignoreCase = true)
                    Log.d(tag, "getAudioStreamUrl($videoId) ok via NPE ${elapsed}ms ios=$isIos streams=${allStreams.size} nonIos=${nonIos.size}")
                    app.opentune.utils.DiagnosticsLogger.logStream(videoId, true, elapsed, client = "NPE${if (isIos) "(ios)" else ""}", urlHint = urlHint(best.content!!))
                    return best.content!!
                }
                val potErr = app.opentune.utils.potoken.OpenTunePoTokenProvider.lastError
                errors += "NPE: ${info.audioStreams.size} streams, none with URL" +
                    if (potErr != null) " [pot:$potErr]" else ""
            } catch (e: Exception) {
                errors += "NPE: ${e.message?.take(80)}"
            }

            val elapsed = System.currentTimeMillis() - start
            val combined = errors.joinToString(" | ")
            Log.w(tag, "getAudioStreamUrl($videoId) ALL FAILED ${elapsed}ms: $combined")
            app.opentune.utils.DiagnosticsLogger.logStream(videoId, false, elapsed, combined.take(600))
            throw Exception(combined)
        }
    }

    // Piped is a public YouTube proxy that resolves streams server-side — no user auth needed.
    // Tries multiple public instances in order; first successful response wins.
    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://piped-api.garudalinux.org",
        "https://api.piped.yt",
        "https://pipedapi.in.projectsegfau.lt",
    )

    private fun fetchAudioStreamPiped(videoId: String): String {
        var lastError = "no instances tried"
        for (instance in pipedInstances) {
            try {
                val response = httpClient.newCall(
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
    private fun urlHint(url: String): String {
        val host = try { android.net.Uri.parse(url).host?.take(30) ?: "?" } catch (_: Exception) { "?" }
        val hasN = url.contains("&n=") || url.contains("?n=")
        val hasAlr = url.contains("alr=yes")
        val nTag = if (hasN) " n=enc" else " n=none"
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
    private fun decodeNParamInUrl(url: String): String {
        val nMatch = Regex("""[?&]n=([^&]+)""").find(url) ?: return url
        val nRaw = nMatch.groupValues[1]
        val nDecoded = try {
            runBlocking { app.opentune.utils.potoken.OpenTunePoTokenProvider.decodeNParam(nRaw) }
        } catch (e: Exception) {
            Log.w(tag, "n-param decode exception: ${e.message}")
            null
        }
        return if (nDecoded != null && nDecoded != nRaw) {
            Log.d(tag, "n-param decoded: ${nRaw.take(8)}.. → ${nDecoded.take(8)}..")
            url.replace("&n=$nRaw", "&n=$nDecoded").replace("?n=$nRaw", "?n=$nDecoded")
        } else {
            if (nDecoded == null) Log.w(tag, "n-param decode returned null, using raw n")
            url
        }
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
                    val finalUrl = decodeNParamInUrl(cleanUrl)
                    Log.d(tag, "fetchAudioStreamNative($videoId) ok via ${client.name} bitrate=$bestBitrate alr=${bestUrl.contains("alr=yes")}")
                    return Triple(finalUrl, client.name, errors.toList())
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
            // Use unfiltered YouTube search — music_songs filter has no nextPage token,
            // so infinite scroll never works with it.
            val info = SearchInfo.getInfo(
                ServiceList.YouTube,
                ServiceList.YouTube.searchQHFactory.fromQuery(query),
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
            val qh = ServiceList.YouTube.searchQHFactory.fromQuery(query)
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
        // NewPipeExtractor v0.26.1 PlaylistInfo is broken with current YouTube
        // (RuntimeException: Field browseId_ for ux3 not found).
        // Use the Innertube browse API directly instead.
        val result = fetchPlaylistViaInnertube(playlistId)
        if (result.isEmpty()) {
            // YouTube occasionally returns an empty response on the first request;
            // a single retry after a short delay is enough to recover.
            Thread.sleep(800)
            return fetchPlaylistViaInnertube(playlistId)
        }
        return result
    }

    // Fetch playlist songs via YouTube's internal Innertube /browse API.
    // This bypasses NewPipeExtractor's broken PlaylistExtractor.
    private fun fetchPlaylistViaInnertube(playlistId: String): List<YtMusicTrack> {
        val tracks = mutableListOf<YtMusicTrack>()
        var continuation: String? = null
        var pageCount = 0
        val json = "application/json; charset=utf-8".toMediaType()

        do {
            val body = if (continuation == null) {
                """{"browseId":"VL$playlistId","context":{"client":{"clientName":"WEB","clientVersion":"2.20230101.00.00"}}}"""
            } else {
                """{"continuation":"$continuation","context":{"client":{"clientName":"WEB","clientVersion":"2.20230101.00.00"}}}"""
            }

            val response = httpClient.newCall(
                Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/browse")
                    .post(body.toRequestBody(json))
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .addHeader("X-YouTube-Client-Name", "1")
                    .addHeader("X-YouTube-Client-Version", "2.20230101.00.00")
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

    // Recursively find every playlistVideoRenderer in the Innertube response.
    // This handles any nesting depth and is immune to YouTube layout changes.
    private fun extractPlaylistVideoRenderers(node: Any?): List<YtMusicTrack> {
        val tracks = mutableListOf<YtMusicTrack>()
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("playlistVideoRenderer")
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
