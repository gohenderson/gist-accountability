package dev.gist.checkin

/** How one question went on one day, across all of that day's times. */
enum class Mark { ON_TRACK, OFF_TRACK, SKIPPED, NONE }

/**
 * Statistics for one check-in, shared by the start page and the History page.
 * Results are saved per time; reports roll them up per day:
 *  - ✗ OFF_TRACK if any time that day was answered off track, or rang and was never
 *    finished (missed);
 *  - ✓ ON_TRACK if every time that rang was answered on track;
 *  - – SKIPPED if every time that day was skipped;
 *  - · NONE if nothing rang that day.
 */
object Stats {
    fun dayMark(q: Question, day: Map<String, SlotResult>): Mark {
        val counted = day.values.filter { it.status != SlotResult.SKIPPED }
        if (counted.any { it.status == SlotResult.RANG }) return Mark.OFF_TRACK // missed
        val marks = counted.mapNotNull { q.onTrack(it) }
        if (marks.any { !it }) return Mark.OFF_TRACK
        if (marks.isNotEmpty()) return Mark.ON_TRACK
        if (day.values.any { it.status == SlotResult.SKIPPED }) return Mark.SKIPPED
        return Mark.NONE
    }

    /** Days among [days] when the check-in rang or was answered (skipped-only days don't count). */
    fun logged(c: Checkin, results: Map<String, Map<String, SlotResult>>, days: List<String>) =
        days.mapNotNull { results[it] }.filter { day -> day.values.any { it.status != SlotResult.SKIPPED } }

    /** A logged day is fully on track if no question was off track (or missed) at any time. */
    fun onTrack(c: Checkin, day: Map<String, SlotResult>): Boolean =
        c.questions.none { q -> dayMark(q, day) == Mark.OFF_TRACK } &&
            day.values.none { it.status == SlotResult.RANG }

    /** e.g. "Last 7 days: 5 logged, 4 fully on track" */
    fun line(c: Checkin, results: Map<String, Map<String, SlotResult>>, n: Int): String {
        val logged = logged(c, results, Days.lastN(n))
        val clean = logged.count { onTrack(c, it) }
        return "Last $n days: ${logged.size} logged, $clean fully on track"
    }

    /** Short status of one time, e.g. for the "which time?" picker. */
    fun describe(c: Checkin, r: SlotResult?): String = when {
        r == null -> "didn't ring"
        r.status == SlotResult.SKIPPED -> "– skipped"
        r.status == SlotResult.DISMISSED -> "dismissed (soft)"
        r.status == SlotResult.RANG -> "✗ missed"
        r.status == SlotResult.DONE -> "✓ done" + if (r.early) " early" else ""
        c.questions.all { q -> q.onTrack(r) != false } -> "✓ on track" + if (r.early) " (early)" else ""
        else -> "✗ off track"
    }
}

/**
 * How long check-ins take (from [SlotResult.took]): getting started (first ring to Start
 * check-in) and doing it (Start check-in to done). A day with several times uses their
 * average; summaries use the median, so one slow morning doesn't swamp the rest.
 */
object Timing {
    /** A day's average, in seconds. [start] is null when every timed check-in that day was early. */
    data class Day(val day: String, val start: Int?, val steps: Int)

    /** Days among [days] (most recent first, as given) that have timings. */
    fun days(results: Map<String, Map<String, SlotResult>>, days: List<String>): List<Day> =
        days.mapNotNull { d ->
            val took = results[d]?.values?.mapNotNull { it.took }.orEmpty()
            if (took.isEmpty()) return@mapNotNull null
            val starts = took.mapNotNull { it.start }
            Day(d, if (starts.isEmpty()) null else starts.sum() / starts.size, took.sumOf { it.steps } / took.size)
        }

    fun median(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** e.g. "Getting started 1m 10s · doing it 3m 05s" (medians), or null with nothing timed. */
    fun line(days: List<Day>): String? {
        if (days.isEmpty()) return null
        val start = median(days.mapNotNull { it.start })
        val steps = median(days.map { it.steps })!!
        return listOfNotNull(start?.let { "Getting started ${Took.text(it)}" }, "doing it ${Took.text(steps)}")
            .joinToString(" · ").replaceFirstChar { it.uppercase() }
    }
}
