package app.opentune.innertube

import kotlin.math.abs

enum class AudioQuality(val label: String) {
    AUTO("Auto"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    BEST("Best");

    companion object {
        fun fromLabel(label: String) = entries.find { it.label == label } ?: BEST
    }
}

fun <T> List<T>.closestBitrate(target: Long, selector: (T) -> Long): T? =
    minByOrNull { abs(selector(it) - target) }
