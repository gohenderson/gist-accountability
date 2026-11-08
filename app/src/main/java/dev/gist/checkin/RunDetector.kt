package dev.gist.checkin

/**
 * Notices runs from the phone's step counter, with no GPS and no Google Play services.
 * Running has a high, steady cadence (usually 150–180 steps a minute; brisk walking is
 * about 100–130), so: a cadence of at least [runCadence] for [startAfterMs] starts a run, and
 * [endAfterMs] below it (stopped, or back to walking) finishes it. Short dips (a traffic
 * light) don't end a run; a short dash for the bus doesn't start one.
 *
 * Feed it the counter's running total with [steps], and call [tick] now and then so a run
 * can finish even when no more steps come in. Times are wall-clock milliseconds.
 */
class RunDetector(
    val runCadence: Int = 140,
    private val windowMs: Long = 30_000,
    private val startAfterMs: Long = 60_000,
    private val endAfterMs: Long = 90_000,
) {
    sealed class Event {
        data class Started(val at: Long) : Event()
        data class Finished(val startedAt: Long, val endedAt: Long) : Event() {
            val minutes: Int get() = ((endedAt - startedAt) / 60_000L).toInt()
        }
    }

    private val samples = ArrayDeque<Pair<Long, Long>>() // (time, total steps)
    var running = false
        private set
    private var fastSince: Long? = null
    private var runStart = 0L
    private var lastFast = 0L

    fun steps(timeMs: Long, total: Long): Event? {
        val last = samples.lastOrNull()
        if (last != null && (total < last.second || timeMs < last.first)) samples.clear() // counter reset (reboot)
        samples.addLast(timeMs to total)
        while (samples.size > 2 && samples[1].first < timeMs - 2 * windowMs) samples.removeFirst()
        return evaluate(timeMs)
    }

    fun tick(nowMs: Long): Event? = evaluate(nowMs)

    /** Steps per minute over the last [windowMs] up to [now]; 0 until there's enough data. */
    fun cadence(now: Long): Double {
        val latest = samples.lastOrNull() ?: return 0.0
        val base = samples.lastOrNull { it.first <= now - windowMs } ?: samples.first()
        val span = now - base.first
        if (span < windowMs / 2) return 0.0
        return (latest.second - base.second) * 60_000.0 / span
    }

    private fun evaluate(now: Long): Event? {
        val fast = cadence(now) >= runCadence
        if (!running) {
            if (!fast) {
                fastSince = null
                return null
            }
            val since = fastSince ?: now.also { fastSince = it }
            if (now - since >= startAfterMs) {
                running = true
                runStart = since - windowMs / 2 // the cadence window lags a little behind
                lastFast = now
                return Event.Started(runStart)
            }
            return null
        }
        if (fast) {
            lastFast = now
            return null
        }
        if (now - lastFast >= endAfterMs) {
            running = false
            fastSince = null
            return Event.Finished(runStart, lastFast)
        }
        return null
    }
}
