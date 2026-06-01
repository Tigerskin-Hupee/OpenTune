/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * Diagnostic test that verifies each step of the audio stream resolution pipeline.
 * Each test isolates one hypothesis so a failure immediately identifies the root cause.
 *
 * Run with:
 *   ./gradlew :app:test --tests "app.opentune.innertube.PlaybackCipherTest" --info
 */
package app.opentune.innertube

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlaybackCipherTest {

    // A stable, always-public YouTube video (YouTube's first video — "Me at the zoo").
    // Use a generic video (not YouTube Music exclusive) so client restrictions don't interfere.
    private val videoId = "jNQXAC9IVRw"

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Shared state filled by step 1 & 2, consumed by later tests ───────────────

    companion object {
        // Run step 1 once; cache results for subsequent tests.
        @Volatile var cachedJs: String? = null
        @Volatile var cachedSigTs: Int = -1
        @Volatile var cachedSigOps: List<Any>? = null  // kept generic to avoid type coupling
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // STEP 1 — Can we fetch player JS and find the script URL?
    // Hypothesis: ensurePlayerJsData() fails because the script-path regex never matches.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun step1_playerJsScriptPathFound() {
        val scriptPathRegex = Regex("""/s/player/[a-f0-9]+/player_ias\.vflset[^"' ]*base\.js""")
        val sourceUrls = listOf("https://www.youtube.com", "https://www.youtube.com/watch?v=$videoId")

        var foundPath: String? = null
        var foundJs: String? = null

        for (src in sourceUrls) {
            val html = http.newCall(
                Request.Builder().url(src).addHeader("User-Agent", ua).get().build()
            ).execute().body?.string() ?: continue

            foundPath = scriptPathRegex.find(html)?.value
            if (foundPath == null) {
                println("[STEP1] script path NOT found in $src — trying next source")
                continue
            }
            println("[STEP1] script path found: $foundPath  (source: $src)")

            foundJs = http.newCall(
                Request.Builder().url("https://www.youtube.com$foundPath")
                    .addHeader("User-Agent", ua).get().build()
            ).execute().body?.string()

            println("[STEP1] player JS fetched: ${foundJs?.length ?: 0} bytes")
            cachedJs = foundJs
            break
        }

        assertNotNull(
            "FAIL — player JS script path not found in any source URL.\n" +
            "This means ensurePlayerJsData() always returns early (cachedSigTs stays 0, sigOps stays null).\n" +
            "Root cause: YouTube changed the script URL format, or the homepage returned a bot-check page.",
            foundPath
        )
        assertNotNull(
            "FAIL — script path found ($foundPath) but player JS fetch returned null/empty.",
            foundJs
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // STEP 2a — Is signatureTimestamp extracted from player JS?
    // Hypothesis: sigTs = 0 → playbackContext is missing → YouTube rejects WEB/TVHTML5_SEP.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun step2a_signatureTimestampExtracted() {
        val js = cachedJs ?: run {
            // Re-fetch if step1 ran first in isolation
            fetchPlayerJs() ?: fail("player JS not available — run step1 first or check network").let { return }
        }

        val sigTs = Regex("""signatureTimestamp[=:]\s*(\d+)""").find(js)
            ?.groupValues?.get(1)?.toIntOrNull()

        println("[STEP2a] signatureTimestamp = $sigTs")
        cachedSigTs = sigTs ?: 0

        assertTrue(
            "FAIL — signatureTimestamp not found in player JS (got: $sigTs).\n" +
            "If sigTs = 0, playbackContext is empty for all clients → WEB/WEB_REMIX likely returns UNPLAYABLE.",
            (sigTs ?: 0) > 0
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // STEP 2b — Is the sig-decode function name found in player JS?
    // Hypothesis: sig fn name regex doesn't match → sigOps stays null → cipher decode fails.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun step2b_sigDecodeFunctionNameFound() {
        val js = cachedJs ?: fetchPlayerJs() ?: run {
            fail("player JS not available"); return
        }

        val patterns = listOf(
            Regex("""[;,=]\s*([a-zA-Z0-9${'$'}{2,})\(decodeURIComponent\([^)]+\.get\("s"\)"""),
            Regex("""c&&\(c=([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent"""),
            Regex("""a\.set\("sig"\s*,\s*([a-zA-Z0-9${'$'}]{2,})\("""),
            Regex("""\.sig\s*\|\|\s*([a-zA-Z0-9${'$'}]{2,})\("""),
            Regex("""b=([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent\(b\.get\("s"\)"""),
            Regex("""\.set\("sig"\s*,([a-zA-Z0-9${'$'}]{2,})\("""),
        )

        val match = patterns.firstNotNullOfOrNull { it.find(js) }
        val fnName = match?.groupValues?.get(1)
        val matchedPattern = match?.let { patterns.indexOf(patterns.firstOrNull { p -> p.find(js) != null }) }

        println("[STEP2b] sig fn name = $fnName  (pattern index: $matchedPattern)")

        assertNotNull(
            "FAIL — sig-decode function name not found by any regex pattern.\n" +
            "This means extractSigOps() returns null → cachedSigOps = null → decodeCipherUrl() always returns null.\n" +
            "Root cause: YouTube changed the sig-dispatch call site; need to add a new regex pattern.\n" +
            "Context around 'decodeURIComponent': ${js.indexOf("decodeURIComponent").let { if (it >= 0) js.substring(maxOf(0,it-60), minOf(js.length,it+80)) else \"not found\" }}",
            fnName
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // STEP 3 — Does TVHTML5_SIMPLY_EMBEDDED_PLAYER return OK + signatureCipher?
    // Hypothesis: TVHTML5_SEP returns UNPLAYABLE, not just IOS.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun step3_tvhtml5SepReturnsOkWithCipher() {
        val json = "application/json; charset=utf-8".toMediaType()
        val sigTs = cachedSigTs.takeIf { it > 0 }

        val playbackCtx = if (sigTs != null)
            ""","playbackContext":{"contentPlaybackContext":{"signatureTimestamp":$sigTs}}"""
        else ""

        val body = """{"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true,"context":{"client":{"clientName":"TVHTML5_SIMPLY_EMBEDDED_PLAYER","clientVersion":"2.0","hl":"en","gl":"US","timeZone":"UTC","utcOffsetMinutes":0},"thirdParty":{"embedUrl":"https://www.youtube.com/watch?v=$videoId"}}$playbackCtx}"""

        val resp = http.newCall(
            Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player")
                .post(body.toRequestBody(json))
                .addHeader("User-Agent", "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.106 Safari/537.36")
                .addHeader("X-YouTube-Client-Name", "85")
                .addHeader("X-YouTube-Client-Version", "2.0")
                .addHeader("Origin", "https://www.youtube.com")
                .addHeader("Referer", "https://www.youtube.com/watch?v=$videoId")
                .addHeader("Cookie", "SOCS=CAI=")
                .build()
        ).execute()

        val rb = resp.body?.string() ?: ""
        val root = JSONObject(rb)
        val status = root.optJSONObject("playabilityStatus")?.optString("status") ?: "?"
        val reason = root.optJSONObject("playabilityStatus")?.optString("reason") ?: ""
        val formats = root.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")

        var directCount = 0; var cipherCount = 0
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val fmt = formats.getJSONObject(i)
                if (!fmt.optString("mimeType","").startsWith("audio/")) continue
                if (fmt.has("url") && fmt.optString("url").isNotBlank()) directCount++
                if (fmt.has("signatureCipher") || fmt.has("cipher")) cipherCount++
            }
        }

        println("[STEP3] TVHTML5_SEP status=$status reason=$reason direct=$directCount cipher=$cipherCount sigTs=$sigTs")

        assertEquals(
            "FAIL — TVHTML5_SEP returned '$status' (reason: '$reason') instead of OK.\n" +
            "This means TVHTML5_SEP is also blocked for this video — need a different client entirely.",
            "OK", status
        )
        assertTrue(
            "FAIL — TVHTML5_SEP returned OK but zero audio formats (formats count=${formats?.length()}).",
            (directCount + cipherCount) > 0
        )

        // Report format type — cipher requires sig decode; direct is ready-to-use.
        if (cipherCount > 0 && directCount == 0) {
            println("[STEP3] Formats are cipher-only — sig decode is required. Proceeding to step 4.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // STEP 4 — Does the sig-decode pipeline produce a valid URL from the cipher?
    // Hypothesis: sigOps extraction or applySigOps logic is broken → URL is malformed.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun step4_cipherDecodePipelineProducesValidUrl() {
        // Get a real signatureCipher from TVHTML5_SEP
        val json = "application/json; charset=utf-8".toMediaType()
        val body = """{"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true,"context":{"client":{"clientName":"TVHTML5_SIMPLY_EMBEDDED_PLAYER","clientVersion":"2.0","hl":"en","gl":"US"},"thirdParty":{"embedUrl":"https://www.youtube.com/watch?v=$videoId"}}}"""

        val rb = http.newCall(
            Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player")
                .post(body.toRequestBody(json))
                .addHeader("User-Agent", "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.106 Safari/537.36")
                .addHeader("X-YouTube-Client-Name", "85")
                .addHeader("X-YouTube-Client-Version", "2.0")
                .addHeader("Origin", "https://www.youtube.com")
                .addHeader("Referer", "https://www.youtube.com/watch?v=$videoId")
                .addHeader("Cookie", "SOCS=CAI=")
                .build()
        ).execute().body?.string() ?: run { fail("TVHTML5_SEP returned no body"); return }

        val root = JSONObject(rb)
        val formats = root.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
            ?: run { fail("no adaptiveFormats in TVHTML5_SEP response"); return }

        var rawCipher: String? = null
        for (i in 0 until formats.length()) {
            val fmt = formats.getJSONObject(i)
            if (!fmt.optString("mimeType","").startsWith("audio/")) continue
            rawCipher = fmt.optString("signatureCipher").takeIf { it.isNotBlank() }
                ?: fmt.optString("cipher").takeIf { it.isNotBlank() }
            if (rawCipher != null) break
        }

        assertNotNull(
            "FAIL — no signatureCipher found in TVHTML5_SEP audio formats.\n" +
            "Either TVHTML5_SEP returned only direct URLs (step 3 passed for wrong reason) or status was not OK.",
            rawCipher
        )

        println("[STEP4] raw cipher: ${rawCipher!!.take(120)}...")

        // Now run the same sig extraction that InnertubeApi uses
        val js = cachedJs ?: fetchPlayerJs() ?: run { fail("player JS not available"); return }
        val sigOps = extractSigOpsFromJs(js)

        assertNotNull(
            "FAIL — sigOps extraction returned null from real player JS.\n" +
            "The sig-decode function was not found or the helper object parsing failed.\n" +
            "This is why decodeCipherUrl() always returns null.",
            sigOps
        )
        assertTrue(
            "FAIL — sigOps extracted but list is empty — no operations found.",
            sigOps!!.isNotEmpty()
        )
        println("[STEP4] sigOps: ${sigOps.size} operations extracted")

        // Decode cipher
        val decoded = decodeCipher(rawCipher, sigOps)
        assertNotNull("FAIL — decodeCipher returned null (URL or sig param missing from cipher)", decoded)
        assertTrue(
            "FAIL — decoded URL is blank or too short to be valid: '$decoded'",
            decoded!!.length > 50
        )
        assertTrue(
            "FAIL — decoded URL does not start with https: '$decoded'",
            decoded.startsWith("https://")
        )
        println("[STEP4] decoded URL: ${decoded.take(80)}...")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // STEP 5 — Does the decoded CDN URL return HTTP 200/206 (not 403)?
    // Hypothesis: Even after correct cipher decode, CDN blocks the request.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun step5_decodedUrlIsStreamable() {
        // Re-run the full pipeline to get a fresh decoded URL
        val json = "application/json; charset=utf-8".toMediaType()
        val apiBody = """{"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true,"context":{"client":{"clientName":"TVHTML5_SIMPLY_EMBEDDED_PLAYER","clientVersion":"2.0","hl":"en","gl":"US"},"thirdParty":{"embedUrl":"https://www.youtube.com/watch?v=$videoId"}}}"""

        val rb = http.newCall(
            Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player")
                .post(apiBody.toRequestBody(json))
                .addHeader("User-Agent", "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.106 Safari/537.36")
                .addHeader("X-YouTube-Client-Name", "85")
                .addHeader("X-YouTube-Client-Version", "2.0")
                .addHeader("Origin", "https://www.youtube.com")
                .addHeader("Referer", "https://www.youtube.com/watch?v=$videoId")
                .addHeader("Cookie", "SOCS=CAI=")
                .build()
        ).execute().body?.string() ?: run { fail("no body from TVHTML5_SEP"); return }

        val formats = JSONObject(rb).optJSONObject("streamingData")
            ?.optJSONArray("adaptiveFormats") ?: run { fail("no adaptiveFormats"); return }

        var cipher: String? = null
        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            if (!f.optString("mimeType","").startsWith("audio/")) continue
            cipher = f.optString("signatureCipher").takeIf { it.isNotBlank() }
                ?: f.optString("cipher").takeIf { it.isNotBlank() }
            if (cipher != null) break
        }
        if (cipher == null) { fail("no cipher found"); return }

        val js = cachedJs ?: fetchPlayerJs() ?: run { fail("player JS unavailable"); return }
        val sigOps = extractSigOpsFromJs(js) ?: run { fail("sigOps extraction failed"); return }
        val cdnUrl = decodeCipher(cipher, sigOps) ?: run { fail("cipher decode returned null"); return }

        // Strip alr=yes (causes non-standard redirect body that confuses HTTP clients)
        val cleanUrl = cdnUrl.replace("&alr=yes","").replace("?alr=yes&","?").replace("?alr=yes","")

        println("[STEP5] HEAD $cleanUrl")

        val headResp = http.newCall(
            Request.Builder()
                .url(cleanUrl)
                .head()
                .addHeader("User-Agent", ua)
                .addHeader("Range", "bytes=0-1")
                .build()
        ).execute()

        println("[STEP5] CDN response: HTTP ${headResp.code}")

        assertFalse(
            "FAIL — CDN returned 403 for the decoded TVHTML5_SEP URL.\n" +
            "This means even correctly decoded cipher URLs are blocked at CDN level.\n" +
            "URL: $cleanUrl",
            headResp.code == 403
        )
        assertTrue(
            "FAIL — CDN returned unexpected code ${headResp.code} (expected 200 or 206).\n" +
            "URL: $cleanUrl",
            headResp.code in listOf(200, 206)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // BONUS — What does WEB_REMIX actually return? Confirms if UNPLAYABLE is PoToken issue.
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun bonus_webRemixWithoutPoTokenReturnsWhat() {
        val json = "application/json; charset=utf-8".toMediaType()
        val sigTs = cachedSigTs.takeIf { it > 0 }
        val playbackCtx = if (sigTs != null)
            ""","playbackContext":{"contentPlaybackContext":{"signatureTimestamp":$sigTs}}"""
        else ""

        val body = """{"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true,"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20260213.01.00","hl":"en","gl":"US","timeZone":"UTC","utcOffsetMinutes":0}}$playbackCtx}"""

        val rb = http.newCall(
            Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/player")
                .post(body.toRequestBody(json))
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
                .addHeader("X-YouTube-Client-Name", "67")
                .addHeader("X-YouTube-Client-Version", "1.20260213.01.00")
                .addHeader("Origin", "https://music.youtube.com")
                .addHeader("Referer", "https://music.youtube.com/watch?v=$videoId")
                .addHeader("Cookie", "SOCS=CAI=")
                .build()
        ).execute().body?.string() ?: ""

        val root = JSONObject(rb)
        val status = root.optJSONObject("playabilityStatus")?.optString("status") ?: "?"
        val reason = root.optJSONObject("playabilityStatus")?.optString("reason") ?: ""
        val formats = root.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
        val audioFmtCount = formats?.let { arr ->
            (0 until arr.length()).count { arr.getJSONObject(it).optString("mimeType","").startsWith("audio/") }
        } ?: 0

        println("[BONUS] WEB_REMIX (no PoToken): status=$status reason=$reason audioFormats=$audioFmtCount sigTs=$sigTs")
        // Not asserting pass/fail — just reporting. If status=OK here, UNPLAYABLE in production is 100% PoToken issue.
        // If status=UNPLAYABLE here too, it's something else (client version, sigTs, or YouTube policy change).
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers — mirrors the logic in InnertubeApi (kept here to be self-contained)
    // ─────────────────────────────────────────────────────────────────────────────

    private fun fetchPlayerJs(): String? {
        val scriptPathRegex = Regex("""/s/player/[a-f0-9]+/player_ias\.vflset[^"' ]*base\.js""")
        for (src in listOf("https://www.youtube.com", "https://www.youtube.com/watch?v=$videoId")) {
            val html = try { http.newCall(Request.Builder().url(src).addHeader("User-Agent", ua).get().build()).execute().body?.string() } catch (e: Exception) { null } ?: continue
            val path = scriptPathRegex.find(html)?.value ?: continue
            val js = try { http.newCall(Request.Builder().url("https://www.youtube.com$path").addHeader("User-Agent", ua).get().build()).execute().body?.string() } catch (e: Exception) { null } ?: continue
            cachedJs = js
            cachedSigTs = Regex("""signatureTimestamp[=:]\s*(\d+)""").find(js)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return js
        }
        return null
    }

    private sealed class SigOp {
        object Reverse : SigOp()
        data class Splice(val n: Int) : SigOp()
        data class Swap(val n: Int) : SigOp()
    }

    private fun extractSigOpsFromJs(js: String): List<SigOp>? {
        val fnName = listOf(
            Regex("""[;,=]\s*([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent\([^)]+\.get\("s"\)"""),
            Regex("""c&&\(c=([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent"""),
            Regex("""a\.set\("sig"\s*,\s*([a-zA-Z0-9${'$'}]{2,})\("""),
            Regex("""\.sig\s*\|\|\s*([a-zA-Z0-9${'$'}]{2,})\("""),
            Regex("""b=([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent\(b\.get\("s"\)"""),
            Regex("""\.set\("sig"\s*,([a-zA-Z0-9${'$'}]{2,})\("""),
        ).firstNotNullOfOrNull { it.find(js) }?.groupValues?.get(1) ?: run {
            println("[SIG] fn name not found"); return null
        }
        println("[SIG] fn name: $fnName")

        val fnIdx = js.indexOf("function $fnName(").takeIf { it >= 0 }
            ?: js.indexOf("$fnName=function(").takeIf { it >= 0 }
            ?: run { println("[SIG] fn body not found for '$fnName'"); return null }
        val bodyStart = js.indexOf("{", fnIdx).takeIf { it >= 0 } ?: return null
        val fnBody = extractBalanced(js, bodyStart) ?: return null
        println("[SIG] fn body: ${fnBody.take(200)}")

        val helperName = Regex("""([a-zA-Z0-9${'$'}]{2,})\.[a-zA-Z0-9${'$'}]+\(a""").find(fnBody)?.groupValues?.get(1)
            ?: run { println("[SIG] helper name not found in body"); return null }
        println("[SIG] helper: $helperName")

        val helperSearch = js.indexOf("var $helperName={").takeIf { it >= 0 }
            ?: js.indexOf(",$helperName={").takeIf { it >= 0 }?.plus(1)
            ?: run { println("[SIG] helper object not found"); return null }
        val helperObjStart = js.indexOf("{", helperSearch).takeIf { it >= 0 } ?: return null
        val helperBody = extractBalanced(js, helperObjStart) ?: return null
        println("[SIG] helper body: ${helperBody.take(300)}")

        val opMap = mutableMapOf<String, SigOp>()
        Regex("""([a-zA-Z0-9${'$'}]+)\s*:\s*function\s*\(\w+\)\s*\{[^}]*\.reverse\(\)""")
            .findAll(helperBody).forEach { opMap[it.groupValues[1]] = SigOp.Reverse }
        Regex("""([a-zA-Z0-9${'$'}]+)\s*:\s*function\s*\(\w+,\w+\)\s*\{[^}]*\.splice\(0,""")
            .findAll(helperBody).forEach { opMap[it.groupValues[1]] = SigOp.Splice(0) }
        Regex("""([a-zA-Z0-9${'$'}]+)\s*:\s*function\s*\(\w+,\w+\)\s*\{[^}]*\.length[^}]*\}""")
            .findAll(helperBody).forEach { opMap[it.groupValues[1]] = SigOp.Swap(0) }
        println("[SIG] opMap: $opMap")

        val ops = mutableListOf<SigOp>()
        Regex("""${Regex.escape(helperName)}\.([a-zA-Z0-9${'$'}]+)\(a(?:,(\d+))?\)""")
            .findAll(fnBody).forEach { m ->
                val method = m.groupValues[1]
                val n = m.groupValues[2].toIntOrNull() ?: 0
                when (val base = opMap[method]) {
                    SigOp.Reverse -> ops.add(SigOp.Reverse)
                    is SigOp.Splice -> ops.add(SigOp.Splice(n))
                    is SigOp.Swap -> ops.add(SigOp.Swap(n))
                    null -> { println("[SIG] unknown method '$method'"); return null }
                }
            }
        println("[SIG] ops: $ops")
        return ops.takeIf { it.isNotEmpty() }
    }

    private fun decodeCipher(cipher: String, ops: List<SigOp>): String? {
        val params = cipher.split("&").associate {
            val eq = it.indexOf('=')
            if (eq < 0) it to "" else it.substring(0, eq) to try {
                java.net.URLDecoder.decode(it.substring(eq + 1), "UTF-8")
            } catch (_: Exception) { it.substring(eq + 1) }
        }
        val baseUrl = params["url"] ?: return null
        val rawSig = params["s"] ?: return null
        val sigParam = params["sp"] ?: "sig"

        val a = rawSig.toMutableList()
        for (op in ops) when (op) {
            SigOp.Reverse -> a.reverse()
            is SigOp.Splice -> repeat(op.n) { if (a.isNotEmpty()) a.removeAt(0) }
            is SigOp.Swap -> if (a.isNotEmpty()) {
                val idx = op.n % a.size
                val tmp = a[0]; a[0] = a[idx]; a[idx] = tmp
            }
        }
        val decodedSig = a.joinToString("")
        val sep = if (baseUrl.contains('?')) '&' else '?'
        return "$baseUrl$sep$sigParam=${java.net.URLEncoder.encode(decodedSig, "UTF-8")}"
    }

    private fun extractBalanced(js: String, start: Int): String? {
        val close = when (js[start]) { '[' -> ']'; '{' -> '}'; '(' -> ')'; else -> return null }
        var depth = 0; var inStr = false; var strCh = ' '; var i = start
        while (i < js.length) {
            val c = js[i]
            if (inStr) { if (c == strCh && js[i-1] != '\\') inStr = false }
            else when (c) {
                '"', '\'', '`' -> { inStr = true; strCh = c }
                '[', '{', '(' -> depth++
                ']', '}', ')' -> { depth--; if (depth == 0) return js.substring(start, i+1) }
            }
            i++
        }
        return null
    }
}
