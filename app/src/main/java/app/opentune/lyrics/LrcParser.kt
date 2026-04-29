package app.opentune.lyrics

data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {
    // Matches [mm:ss.xx], [mm:ss:xx], [mm:ss.xxx]
    private val TAG_REGEX = Regex("""\[(\d{2}):(\d{2})[.:](\d{1,3})\](.*)""")

    fun parse(lrc: String): List<LrcLine> = lrc.lines()
        .mapNotNull { line ->
            val m = TAG_REGEX.matchEntire(line.trim()) ?: return@mapNotNull null
            val (min, sec, ms, text) = m.destructured
            val msNormalized = when (ms.length) {
                1 -> ms.toLong() * 100
                2 -> ms.toLong() * 10
                else -> ms.toLong()
            }
            val timeMs = min.toLong() * 60_000 + sec.toLong() * 1_000 + msNormalized
            val trimmed = text.trim()
            if (trimmed.isEmpty()) null else LrcLine(timeMs, trimmed)
        }
        .sortedBy { it.timeMs }

    fun isLrc(text: String): Boolean = TAG_REGEX.containsMatchIn(text)
}
