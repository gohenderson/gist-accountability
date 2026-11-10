package dev.gist.checkin

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How on track a check-in has been: the share of the last [DAYS] logged days that were
 * fully on track (days it didn't ring, or that were only skipped, don't count; a missed
 * ring counts as off track). Groups and the whole start page show the average of their
 * check-ins' scores. Colors run from the off-track red (0%) to the on-track green (100%).
 */
object Score {
    const val DAYS = 7

    /** 0.0–1.0, or null when nothing was logged in the last [DAYS] days. */
    fun of(c: Checkin, results: Map<String, Map<String, SlotResult>>, days: List<String> = Days.lastN(DAYS)): Double? {
        val logged = Stats.logged(c, results, days)
        if (logged.isEmpty()) return null
        return logged.count { Stats.onTrack(c, it) }.toDouble() / logged.size
    }

    /** The average of the known scores, or null if there are none. */
    fun average(scores: List<Double?>): Double? = scores.filterNotNull().takeIf { it.isNotEmpty() }?.average()

    fun percent(score: Double): String = "${(score * 100).roundToInt()}%"

    /**
     * The color for [score]: [bad] at 0, [good] at 1, and in between a blend that moves
     * around the color wheel (red → amber → green), so middling scores read as "getting there"
     * instead of a muddy brown.
     */
    fun color(score: Double, bad: Int, good: Int): Int {
        val t = score.coerceIn(0.0, 1.0).toFloat()
        val a = hsv(bad)
        val b = hsv(good)
        var dh = b[0] - a[0]
        if (abs(dh) > 180f) dh -= 360f * kotlin.math.sign(dh)
        val h = (a[0] + dh * t + 360f) % 360f
        val s = a[1] + (b[1] - a[1]) * t
        val v = a[2] + (b[2] - a[2]) * t
        return fromHsv(h, s, v)
    }

    // Plain-Kotlin HSV (android.graphics.Color's isn't available in JVM unit tests).

    internal fun hsv(color: Int): FloatArray {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0) it + 360f else it }
        val s = if (max == 0f) 0f else d / max
        return floatArrayOf(h, s, max)
    }

    internal fun fromHsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2f - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
