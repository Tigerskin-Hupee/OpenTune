#!/usr/bin/env kotlin
// Self-contained sig decode logic test — no dependencies, no build required.
// Run: java -jar /root/.gradle/wrapper/dists/gradle-9.4.1-bin/.../lib/kotlin-compiler-embeddable-2.3.0.jar -script test_sig_decode.kts

sealed class SigOp {
    object Reverse : SigOp() { override fun toString() = "Reverse" }
    data class Splice(val n: Int) : SigOp()
    data class Swap(val n: Int) : SigOp()
}

fun extractBalanced(js: String, start: Int): String? {
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

fun extractSigOps(js: String): List<SigOp>? {
    val fnName = listOf(
        Regex("""[;,=]\s*([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent\([^)]+\.get\("s"\)"""),
        Regex("""c&&\(c=([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent"""),
        Regex("""a\.set\("sig"\s*,\s*([a-zA-Z0-9${'$'}]{2,})\("""),
        Regex("""\.sig\s*\|\|\s*([a-zA-Z0-9${'$'}]{2,})\("""),
        Regex("""b=([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent\(b\.get\("s"\)"""),
        Regex("""\.set\("sig"\s*,([a-zA-Z0-9${'$'}]{2,})\("""),
    ).firstNotNullOfOrNull { it.find(js) }?.groupValues?.get(1) ?: run {
        println("  ERROR: sig fn name not found by any pattern"); return null
    }
    println("  sig fn name: $fnName")

    val fnIdx = js.indexOf("function $fnName(").takeIf { it >= 0 }
        ?: js.indexOf("$fnName=function(").takeIf { it >= 0 } ?: run {
        println("  ERROR: fn '$fnName' body not found"); return null
    }
    val bodyStart = js.indexOf("{", fnIdx).takeIf { it >= 0 } ?: return null
    val fnBody = extractBalanced(js, bodyStart) ?: return null
    println("  fn body: ${fnBody.take(120)}")

    val helperName = Regex("""([a-zA-Z0-9${'$'}]{2,})\.[a-zA-Z0-9${'$'}]+\(a""").find(fnBody)?.groupValues?.get(1) ?: run {
        println("  ERROR: helper name not found"); return null
    }
    println("  helper: $helperName")

    val helperSearch = js.indexOf("var $helperName={").takeIf { it >= 0 }
        ?: js.indexOf(",$helperName={").takeIf { it >= 0 }?.plus(1) ?: run {
        println("  ERROR: helper object '$helperName' not found"); return null
    }
    val helperObjStart = js.indexOf("{", helperSearch).takeIf { it >= 0 } ?: return null
    val helperBody = extractBalanced(js, helperObjStart) ?: return null
    println("  helper body: ${helperBody.take(200)}")

    val opMap = mutableMapOf<String, SigOp>()
    Regex("""([a-zA-Z0-9${'$'}]+)\s*:\s*function\s*\(\w+\)\s*\{[^}]*\.reverse\(\)""")
        .findAll(helperBody).forEach { opMap[it.groupValues[1]] = SigOp.Reverse }
    Regex("""([a-zA-Z0-9${'$'}]+)\s*:\s*function\s*\(\w+,\w+\)\s*\{[^}]*\.splice\(0,""")
        .findAll(helperBody).forEach { opMap[it.groupValues[1]] = SigOp.Splice(0) }
    Regex("""([a-zA-Z0-9${'$'}]+)\s*:\s*function\s*\(\w+,\w+\)\s*\{[^;]*=\w\[0\][^}]*=\w\[0\]""")
        .findAll(helperBody).forEach { opMap[it.groupValues[1]] = SigOp.Swap(0) }
    println("  opMap: $opMap")

    val ops = mutableListOf<SigOp>()
    Regex("""${Regex.escape(helperName)}\.([a-zA-Z0-9${'$'}]+)\(a(?:,(\d+))?\)""")
        .findAll(fnBody).forEach { m ->
            val method = m.groupValues[1]; val n = m.groupValues[2].toIntOrNull() ?: 0
            when (val base = opMap[method]) {
                SigOp.Reverse -> ops.add(SigOp.Reverse)
                is SigOp.Splice -> ops.add(SigOp.Splice(n))
                is SigOp.Swap -> ops.add(SigOp.Swap(n))
                null -> { println("  ERROR: unknown method '$method'"); return null }
            }
        }
    return ops.takeIf { it.isNotEmpty() }
}

fun applySigOps(sig: String, ops: List<SigOp>): String {
    val a = sig.toMutableList()
    for (op in ops) when (op) {
        SigOp.Reverse -> a.reverse()
        is SigOp.Splice -> repeat(op.n) { if (a.isNotEmpty()) a.removeAt(0) }
        is SigOp.Swap -> if (a.isNotEmpty()) { val idx = op.n % a.size; val t = a[0]; a[0] = a[idx]; a[idx] = t }
    }
    return a.joinToString("")
}

// ── Test data ────────────────────────────────────────────────────────────────

val jsStyleA = """var Vo={Gy:function(a,b){a.splice(0,b)},uW:function(a){a.reverse()},yw:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}};function Wo(a){a=a.split("");Vo.Gy(a,2);Vo.yw(a,40);Vo.uW(a);Vo.yw(a,6);return a.join("")};c&&(c=Wo(decodeURIComponent(c.get("s"))));signatureTimestamp=19950"""
val jsStyleB = """var Xp={Ra:function(a,b){a.splice(0,b)},Qa:function(a){a.reverse()},Pa:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}};Zp=function(a){a=a.split("");Xp.Qa(a);Xp.Ra(a,3);Xp.Pa(a,17);return a.join("")};b=Zp(decodeURIComponent(b.get("s")));signatureTimestamp=19851"""
val jsStyleC = """var Bp={zz:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c},yy:function(a){a.reverse()},xx:function(a,b){a.splice(0,b)}};function Cp(a){a=a.split("");Bp.xx(a,1);Bp.zz(a,55);Bp.yy(a);return a.join("")};a.set("sig",Cp(decodeURIComponent(a.get("s"))));signatureTimestamp=20100"""

var passed = 0; var failed = 0
fun pass(t: String) { println("  PASS: $t"); passed++ }
fun fail(t: String, msg: String = "") { println("  FAIL: $t${if (msg.isNotBlank()) "\n        $msg" else ""}"); failed++ }

// ── Run tests ─────────────────────────────────────────────────────────────────

println("\n══ signatureTimestamp extraction ══")
for ((name, js) in listOf("styleA" to jsStyleA, "styleB" to jsStyleB, "styleC" to jsStyleC)) {
    val ts = Regex("""signatureTimestamp[=:]\s*(\d+)""").find(js)?.groupValues?.get(1)?.toIntOrNull()
    if ((ts ?: 0) > 0) pass("sigTs from $name = $ts") else fail("sigTs not found in $name")
}

println("\n══ Style A — standard function declaration ══")
val opsA = extractSigOps(jsStyleA)
if (opsA == null) { fail("extractSigOps returned null") } else {
    pass("ops extracted: $opsA")
    val expected = listOf(SigOp.Splice(2), SigOp.Swap(40), SigOp.Reverse, SigOp.Swap(6))
    if (opsA == expected) pass("ops match expected $expected") else fail("ops mismatch: got $opsA expected $expected")
    val sig = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwx"
    val decoded = applySigOps(sig, opsA)
    val step1 = sig.drop(2)
    val step2 = step1.toMutableList().also { a -> val idx=40%a.size; val t=a[0]; a[0]=a[idx]; a[idx]=t }.joinToString("")
    val step3 = step2.reversed()
    val step4 = step3.toMutableList().also { a -> val idx=6%a.size; val t=a[0]; a[0]=a[idx]; a[idx]=t }.joinToString("")
    if (decoded == step4) pass("applySigOps result correct: ${decoded.take(20)}...") else fail("applySigOps wrong: got ${decoded.take(20)} expected ${step4.take(20)}")
}

println("\n══ Style B — minified NAME=function assignment ══")
val opsB = extractSigOps(jsStyleB)
if (opsB == null) {
    fail("extractSigOps returned null for NAME=function style",
        "This is the KEY BUG — code must search for 'NAME=function(' as fallback")
} else {
    pass("ops extracted: $opsB")
    val expected = listOf(SigOp.Reverse, SigOp.Splice(3), SigOp.Swap(17))
    if (opsB == expected) pass("ops match expected $expected") else fail("ops mismatch: got $opsB expected $expected")
}

println("\n══ Style C — .set('sig',...) call site pattern ══")
val opsC = extractSigOps(jsStyleC)
if (opsC == null) { fail("extractSigOps returned null for set-sig style") } else {
    pass("ops extracted: $opsC")
    val expected = listOf(SigOp.Splice(1), SigOp.Swap(55), SigOp.Reverse)
    if (opsC == expected) pass("ops match expected $expected") else fail("ops mismatch: got $opsC expected $expected")
}

println("\n══ applySigOps correctness ══")
run {
    val r = applySigOps("ABCDE", listOf(SigOp.Reverse))
    if (r == "EDCBA") pass("Reverse ABCDE → EDCBA") else fail("Reverse wrong: $r")
}
run {
    val r = applySigOps("ABCDEFGH", listOf(SigOp.Splice(3)))
    if (r == "DEFGH") pass("Splice(3) ABCDEFGH → DEFGH") else fail("Splice wrong: $r")
}
run {
    val r = applySigOps("ABCDE", listOf(SigOp.Swap(2)))
    if (r == "CBADE") pass("Swap(2) ABCDE → CBADE") else fail("Swap wrong: $r")
}
run {
    val r = applySigOps("ABCDE", listOf(SigOp.Swap(7)))
    // 7 % 5 = 2
    if (r == "CBADE") pass("Swap(7) on 5-char wraps mod5=2 → CBADE") else fail("Swap modulo wrong: $r")
}
run {
    val r = applySigOps("", listOf(SigOp.Reverse, SigOp.Splice(3), SigOp.Swap(10)))
    if (r == "") pass("Empty sig survives all ops") else fail("Empty sig gave: $r")
}

println("\n══ SUMMARY ══")
println("  PASS: $passed   FAIL: $failed")
if (failed == 0) println("  All logic tests passed — sig decode algorithm is correct")
else println("  Tests failed — fix the algorithm before deploying")
