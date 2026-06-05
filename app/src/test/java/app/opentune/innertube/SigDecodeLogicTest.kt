/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * Pure-logic unit tests for the sig-decode pipeline (no network required).
 * Uses a realistic synthetic player JS snippet that mirrors YouTube's current obfuscation style.
 *
 * Run with: ./gradlew :app:test --tests "app.opentune.innertube.SigDecodeLogicTest"
 */
package app.opentune.innertube

import org.junit.Assert.*
import org.junit.Test

class SigDecodeLogicTest {

    // ── Mirrors InnertubeApi private types ────────────────────────────────────────

    private sealed class SigOp {
        object Reverse : SigOp() { override fun toString() = "Reverse" }
        data class Splice(val n: Int) : SigOp()
        data class Swap(val n: Int) : SigOp()
    }

    private fun extractBalanced(js: String, start: Int): String? {
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

    private fun extractSigOps(js: String): List<SigOp>? {
        val fnName = listOf(
            Regex("""[;,=]\s*([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent\([^)]+\.get\("s"\)"""),
            Regex("""c&&\(c=([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent"""),
            Regex("""a\.set\("sig"\s*,\s*([a-zA-Z0-9${'$'}]{2,})\("""),
            Regex("""\.sig\s*\|\|\s*([a-zA-Z0-9${'$'}]{2,})\("""),
            Regex("""b=([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent\(b\.get\("s"\)"""),
            Regex("""\.set\("sig"\s*,([a-zA-Z0-9${'$'}]{2,})\("""),
        ).firstNotNullOfOrNull { it.find(js) }?.groupValues?.get(1) ?: return null

        val fnIdx = js.indexOf("function $fnName(").takeIf { it >= 0 }
            ?: js.indexOf("$fnName=function(").takeIf { it >= 0 } ?: return null
        val bodyStart = js.indexOf("{", fnIdx).takeIf { it >= 0 } ?: return null
        val fnBody = extractBalanced(js, bodyStart) ?: return null
        val helperName = Regex("""([a-zA-Z0-9${'$'}]{2,})\.[a-zA-Z0-9${'$'}]+\(a""").find(fnBody)?.groupValues?.get(1) ?: return null

        val helperSearch = js.indexOf("var $helperName={").takeIf { it >= 0 }
            ?: js.indexOf(",$helperName={").takeIf { it >= 0 }?.plus(1) ?: return null
        val helperObjStart = js.indexOf("{", helperSearch).takeIf { it >= 0 } ?: return null
        val helperBody = extractBalanced(js, helperObjStart) ?: return null

        val opMap = mutableMapOf<String, SigOp>()
        Regex("""([a-zA-Z0-9${'$'}]+)\s*:\s*function\s*\(\w+\)\s*\{[^}]*\.reverse\(\)""")
            .findAll(helperBody).forEach { opMap[it.groupValues[1]] = SigOp.Reverse }
        Regex("""([a-zA-Z0-9${'$'}]+)\s*:\s*function\s*\(\w+,\w+\)\s*\{[^}]*\.splice\(0,""")
            .findAll(helperBody).forEach { opMap[it.groupValues[1]] = SigOp.Splice(0) }
        Regex("""([a-zA-Z0-9${'$'}]+)\s*:\s*function\s*\(\w+,\w+\)\s*\{[^}]*\.length[^}]*\}""")
            .findAll(helperBody).forEach { opMap[it.groupValues[1]] = SigOp.Swap(0) }

        val ops = mutableListOf<SigOp>()
        Regex("""${Regex.escape(helperName)}\.([a-zA-Z0-9${'$'}]+)\(a(?:,(\d+))?\)""")
            .findAll(fnBody).forEach { m ->
                val method = m.groupValues[1]
                val n = m.groupValues[2].toIntOrNull() ?: 0
                when (val base = opMap[method]) {
                    SigOp.Reverse -> ops.add(SigOp.Reverse)
                    is SigOp.Splice -> ops.add(SigOp.Splice(n))
                    is SigOp.Swap -> ops.add(SigOp.Swap(n))
                    null -> return null
                }
            }
        return ops.takeIf { it.isNotEmpty() }
    }

    private fun applySigOps(sig: String, ops: List<SigOp>): String {
        val a = sig.toMutableList()
        for (op in ops) when (op) {
            SigOp.Reverse -> a.reverse()
            is SigOp.Splice -> repeat(op.n) { if (a.isNotEmpty()) a.removeAt(0) }
            is SigOp.Swap -> if (a.isNotEmpty()) {
                val idx = op.n % a.size
                val tmp = a[0]; a[0] = a[idx]; a[idx] = tmp
            }
        }
        return a.joinToString("")
    }

    // ── Synthetic player JS snippets — mirror YouTube's current obfuscation styles ─

    // Style A: `function NAME(a){...}` declaration + `c&&(c=NAME(...))` call site
    private val jsStyleA = """
        var Vo={Gy:function(a,b){a.splice(0,b)},uW:function(a){a.reverse()},yw:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}};
        function Wo(a){a=a.split("");Vo.Gy(a,2);Vo.yw(a,40);Vo.uW(a);Vo.yw(a,6);return a.join("")}
        ;c&&(c=Wo(decodeURIComponent(c.get("s"))))
        signatureTimestamp=19950
    """.trimIndent()

    // Style B: `NAME=function(a){...}` minified assignment + `b=NAME(decodeURIComponent(b.get("s")))` call site
    private val jsStyleB = """
        var Xp={Ra:function(a,b){a.splice(0,b)},Qa:function(a){a.reverse()},Pa:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}};
        Zp=function(a){a=a.split("");Xp.Qa(a);Xp.Ra(a,3);Xp.Pa(a,17);return a.join("")}
        ;b=Zp(decodeURIComponent(b.get("s")))
        signatureTimestamp=19851
    """.trimIndent()

    // Style C: `.set("sig",NAME(...)` call site  — tests the sixth regex pattern
    private val jsStyleC = """
        var Bp={zz:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c},yy:function(a){a.reverse()},xx:function(a,b){a.splice(0,b)}};
        function Cp(a){a=a.split("");Bp.xx(a,1);Bp.zz(a,55);Bp.yy(a);return a.join("")}
        ;a.set("sig",Cp(decodeURIComponent(a.get("s"))))
        signatureTimestamp=20100
    """.trimIndent()

    // Style D: YouTube 2026 — all methods (including the decode fn) live inside one
    // helper object using colon + arrow syntax; call site is OBJ.METHOD(...).
    // The four existing `=`-based fd patterns miss this; the colon patterns added in
    // v1.2.57 are required.
    private val jsStyleD = """
        var Tp={Yh:(a)=>{a=a.split("");Tp.Xh(a,3);Tp.Ah(a);Tp.Wh(a,5);return a.join("")},Xh:(a,b)=>{a.splice(0,b)},Ah:(a)=>{a.reverse()},Wh:(a,b)=>{var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}};
        c&&(c=Tp.Yh(decodeURIComponent(c.get("s"))))
        signatureTimestamp=20400
    """.trimIndent()

    // Join-anchor strategy that also handles colon object-property fn definitions.
    // Mirrors InnertubeApi Strategy 1 with the v1.2.57 fix.
    private fun extractSigOpsJoinAnchor(js: String): List<SigOp>? {
        val splitTokens = listOf(".split(\"\")", ".split('')", ".split(``)")
        val joinTokens  = listOf(".join(\"\")",  ".join('')",  ".join(``)")
        var jFrom = 0
        while (true) {
            val joinIdx = joinTokens.map { js.indexOf(it, jFrom) }.filter { it >= 0 }.minOrNull() ?: break
            jFrom = joinIdx + 1
            val lbOff = maxOf(0, joinIdx - 10000)
            val seg = js.substring(lbOff, joinIdx)
            if (!splitTokens.any { seg.contains(it) } && !seg.contains("Array.from(") && !seg.contains("[...")) continue

            val fd = Regex("""([\w$]+)\s*=\s*function\(\s*([\w$]+)\s*\)\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""function\s+([\w$]+)\s*\(\s*([\w$]+)\s*\)\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""([\w$]+)\s*=\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""([\w$]+)\s*=\s*([\w$]+)\s*=>\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""([\w$]+)\s*:\s*function\s*\(\s*([\w$]+)\s*\)\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""([\w$]+)\s*:\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{""").findAll(seg).lastOrNull()
                ?: Regex("""([\w$]+)\s*:\s*([\w$]+)\s*=>\s*\{""").findAll(seg).lastOrNull()
                ?: continue
            val fnParam = fd.groupValues[2]
            val fdAbsIdx = lbOff + fd.range.first
            val fnBraceIdx = js.indexOf("{", fdAbsIdx).takeIf { it >= 0 } ?: continue
            val fnBody = extractBalanced(js, fnBraceIdx) ?: continue
            if (!splitTokens.any { fnBody.contains(it) } || !joinTokens.any { fnBody.contains(it) }) continue

            val helperName = Regex("""([\w$]+)\.([\w$]+)\($fnParam""").find(fnBody)?.groupValues?.get(1) ?: continue
            val hSearch = js.indexOf("var $helperName={").takeIf { it >= 0 }
                ?: js.indexOf("$helperName={").takeIf { it >= 0 } ?: continue
            val hBrace = js.indexOf("{", hSearch).takeIf { it >= 0 } ?: continue
            val helperBody = extractBalanced(js, hBrace) ?: continue

            val h1 = """(?:function\s*\(\w+\)\s*\{|\(\w+\)\s*=>\s*\{?|\w+\s*=>\s*\{?)"""
            val h2 = """(?:function\s*\(\w+,\w+\)\s*\{|\(\w+,\w+\)\s*=>\s*\{?)"""
            val opMap = mutableMapOf<String, SigOp>()
            Regex("""([\w$]+)\s*:\s*$h1[^}]*\.reverse\(\)""").findAll(helperBody)
                .forEach { opMap[it.groupValues[1]] = SigOp.Reverse }
            Regex("""([\w$]+)\s*:\s*$h2[^}]*\.splice\(0,""").findAll(helperBody)
                .forEach { opMap[it.groupValues[1]] = SigOp.Splice(0) }
            Regex("""([\w$]+)\s*:\s*$h2[^}]*%\w+\.length""").findAll(helperBody)
                .filter { opMap[it.groupValues[1]] == null }
                .forEach { opMap[it.groupValues[1]] = SigOp.Swap(0) }
            if (opMap.isEmpty()) continue

            val ops = mutableListOf<SigOp>()
            Regex("""${Regex.escape(helperName)}\.([\w$]+)\($fnParam(?:,(\d+))?\)""").findAll(fnBody)
                .forEach { m ->
                    val n = m.groupValues[2].toIntOrNull() ?: 0
                    when (val base = opMap[m.groupValues[1]]) {
                        SigOp.Reverse -> ops.add(SigOp.Reverse)
                        is SigOp.Splice -> ops.add(SigOp.Splice(n))
                        is SigOp.Swap -> ops.add(SigOp.Swap(n))
                        null -> {}
                    }
                }
            if (ops.isNotEmpty()) return ops
        }
        return null
    }

    // ── Tests for extractSigOps ───────────────────────────────────────────────────

    @Test
    fun `extractSigOps — standard function declaration (style A)`() {
        val ops = extractSigOps(jsStyleA)
        assertNotNull("extractSigOps returned null for style A (function declaration)", ops)
        assertEquals("Expected 4 ops for style A", 4, ops!!.size)
        // Vo.Gy(a,2) = Splice(2), Vo.yw(a,40) = Swap(40), Vo.uW(a) = Reverse, Vo.yw(a,6) = Swap(6)
        assertEquals(SigOp.Splice(2), ops[0])
        assertEquals(SigOp.Swap(40), ops[1])
        assertEquals(SigOp.Reverse, ops[2])
        assertEquals(SigOp.Swap(6), ops[3])
    }

    @Test
    fun `extractSigOps — minified assignment form (style B, NAME=function)`() {
        val ops = extractSigOps(jsStyleB)
        assertNotNull(
            "extractSigOps returned null for style B (NAME=function assignment).\n" +
            "This is the BUG — the fn body search must handle BOTH 'function NAME(' AND 'NAME=function('.",
            ops
        )
        assertEquals("Expected 3 ops for style B", 3, ops!!.size)
        // Xp.Qa(a) = Reverse, Xp.Ra(a,3) = Splice(3), Xp.Pa(a,17) = Swap(17)
        assertEquals(SigOp.Reverse, ops[0])
        assertEquals(SigOp.Splice(3), ops[1])
        assertEquals(SigOp.Swap(17), ops[2])
    }

    @Test
    fun `extractSigOps — set-sig call site pattern (style C)`() {
        val ops = extractSigOps(jsStyleC)
        assertNotNull("extractSigOps returned null for style C (.set('sig',...) call site)", ops)
        assertEquals("Expected 3 ops for style C", 3, ops!!.size)
        assertEquals(SigOp.Splice(1), ops[0])
        assertEquals(SigOp.Swap(55), ops[1])
        assertEquals(SigOp.Reverse, ops[2])
    }

    @Test
    fun `extractSigOps — signatureTimestamp extracted from same JS`() {
        for ((style, js) in listOf("A" to jsStyleA, "B" to jsStyleB, "C" to jsStyleC)) {
            val sigTs = Regex("""signatureTimestamp[=:]\s*(\d+)""").find(js)
                ?.groupValues?.get(1)?.toIntOrNull()
            assertNotNull("signatureTimestamp not found in style $style", sigTs)
            assertTrue("signatureTimestamp should be > 0 in style $style, got $sigTs", (sigTs ?: 0) > 0)
        }
    }

    // ── Tests for applySigOps (operations correct regardless of extraction) ───────

    @Test
    fun `applySigOps — Reverse is its own inverse`() {
        val sig = "ABCDEFGHIJ"
        val ops = listOf(SigOp.Reverse)
        val once = applySigOps(sig, ops)
        val twice = applySigOps(once, ops)
        assertEquals("Reverse applied twice should return original", sig, twice)
        assertEquals("Reverse of ABCDEFGHIJ should be JIHGFEDCBA", "JIHGFEDCBA", once)
    }

    @Test
    fun `applySigOps — Splice removes n chars from front`() {
        val sig = "ABCDEFGHIJ"
        val result = applySigOps(sig, listOf(SigOp.Splice(3)))
        assertEquals("Splice(3) should remove 3 chars from front", "DEFGHIJ", result)
    }

    @Test
    fun `applySigOps — Swap exchanges first char with nth`() {
        val sig = "ABCDEFGHIJ"
        val result = applySigOps(sig, listOf(SigOp.Swap(4)))
        assertEquals("Swap(4) should exchange index 0 and 4", "EBCDAFGHIJ", result)
    }

    @Test
    fun `applySigOps — Swap with n larger than size wraps via modulo`() {
        val sig = "ABCDE"
        val result = applySigOps(sig, listOf(SigOp.Swap(7)))
        // 7 % 5 = 2; swap index 0 and 2: A↔C → CBADE
        assertEquals("Swap(7) on 5-char sig: 7%5=2, swap [0] and [2]", "CBADE", result)
    }

    @Test
    fun `applySigOps — composite sequence (style A ops) is deterministic`() {
        val sig = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQR"
        // Style A ops: Splice(2), Swap(40), Reverse, Swap(6)
        val ops = listOf(SigOp.Splice(2), SigOp.Swap(40), SigOp.Reverse, SigOp.Swap(6))
        val result = applySigOps(sig, ops)
        assertNotEquals("Ops should change the signature", sig, result)
        // Result length: Splice removes 2 chars; length goes from 52 → 50
        assertEquals("After Splice(2) on 52-char sig, length should be 50", 50, result.length)
        // Apply same ops twice → different result (not symmetric), but must not throw
        val result2 = applySigOps(sig, ops)
        assertEquals("Same ops on same input must be deterministic", result, result2)
    }

    @Test
    fun `applySigOps — empty sig does not throw`() {
        val ops = listOf(SigOp.Reverse, SigOp.Splice(3), SigOp.Swap(10))
        val result = applySigOps("", ops)
        assertEquals("Empty sig should survive all op types without throwing", "", result)
    }

    // ── End-to-end: extract from JS then apply to synthetic cipher ───────────────

    @Test
    fun `end-to-end — style A extraction + apply produces correct decoded sig`() {
        val ops = extractSigOps(jsStyleA)!!
        // Construct a fake 50-char signature
        val rawSig = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwx"
        val decoded = applySigOps(rawSig, ops)

        // Manual computation: Splice(2) → drops AB; Swap(40) on 48-char string: 40%48=40, swap[0]↔[40];
        // Reverse; Swap(6) on 48-char result: 6%48=6, swap[0]↔[6].
        val step1 = rawSig.drop(2)                                    // Splice(2)
        val step2 = step1.toMutableList().also { a ->
            val idx = 40 % a.size; val t = a[0]; a[0] = a[idx]; a[idx] = t
        }.joinToString("")                                             // Swap(40)
        val step3 = step2.reversed()                                   // Reverse
        val step4 = step3.toMutableList().also { a ->
            val idx = 6 % a.size; val t = a[0]; a[0] = a[idx]; a[idx] = t
        }.joinToString("")                                             // Swap(6)

        assertEquals("End-to-end style A decode mismatch", step4, decoded)
    }

    @Test
    fun `end-to-end — style B extraction + apply produces correct decoded sig`() {
        val ops = extractSigOps(jsStyleB)!!
        val rawSig = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmn"
        val decoded = applySigOps(rawSig, ops)

        // Style B ops: Reverse, Splice(3), Swap(17)
        val step1 = rawSig.reversed()
        val step2 = step1.drop(3)
        val step3 = step2.toMutableList().also { a ->
            val idx = 17 % a.size; val t = a[0]; a[0] = a[idx]; a[idx] = t
        }.joinToString("")

        assertEquals("End-to-end style B decode mismatch", step3, decoded)
    }

    // ── Style D: colon + arrow syntax (YouTube 2026) ─────────────────────────────

    @Test
    fun `extractSigOps — old call-site strategy fails on style D (documents the bug)`() {
        // The call site is Tp.Yh(...) — the old extractSigOps captures "Tp" as fnName,
        // then can't find "function Tp(" or "Tp=function(" → returns null.
        // This test documents the known limitation of the call-site strategy for style D.
        val ops = extractSigOps(jsStyleD)
        assertNull(
            "extractSigOps (call-site strategy) should return null for style D " +
            "because Tp.Yh call site is not handled — this documents the bug fixed in v1.2.57",
            ops
        )
    }

    @Test
    fun `extractSigOpsJoinAnchor — colon-arrow object property form (style D, v1257 fix)`() {
        val ops = extractSigOpsJoinAnchor(jsStyleD)
        assertNotNull(
            "extractSigOpsJoinAnchor returned null for style D.\n" +
            "The join-anchor strategy with colon patterns (v1.2.57 fix) must handle:\n" +
            "  Yh:(a)=>{...} inside var Tp={...}",
            ops
        )
        assertEquals("Expected 3 ops for style D", 3, ops!!.size)
        // Tp.Xh(a,3) = Splice(3), Tp.Ah(a) = Reverse, Tp.Wh(a,5) = Swap(5)
        assertEquals(SigOp.Splice(3), ops[0])
        assertEquals(SigOp.Reverse,   ops[1])
        assertEquals(SigOp.Swap(5),   ops[2])
    }

    @Test
    fun `end-to-end — style D extraction + apply produces correct decoded sig`() {
        val ops = extractSigOpsJoinAnchor(jsStyleD)!!
        val rawSig = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwx"
        val decoded = applySigOps(rawSig, ops)

        // Style D ops: Splice(3), Reverse, Swap(5)
        val step1 = rawSig.drop(3)                      // Splice(3)
        val step2 = step1.reversed()                    // Reverse
        val step3 = step2.toMutableList().also { a ->
            val idx = 5 % a.size; val t = a[0]; a[0] = a[idx]; a[idx] = t
        }.joinToString("")                              // Swap(5)

        assertEquals("End-to-end style D decode mismatch", step3, decoded)
    }
}
