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
        val answers = counted.mapNotNull { it.answers[q.id] }
        if (answers.any { !q.isGood(it) }) return Mark.OFF_TRACK
        if (answers.isNotEmpty()) return Mark.ON_TRACK
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
        r.status == SlotResult.RANG -> "✗ missed"
        r.status == SlotResult.DONE -> "✓ done" + if (r.early) " early" else ""
        c.questions.all { q -> r.answers[q.id]?.let { q.isGood(it) } != false } -> "✓ on track" + if (r.early) " (early)" else ""
        else -> "✗ off track"
    }
}
