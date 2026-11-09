package dev.gist.checkin

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Short descriptions of a check-in, shared by the start page and the edit page. */
object CheckinText {
    /** e.g. "solve 3 math → scan Bathroom sink → answer 2 questions" */
    fun steps(c: Checkin): String {
        val parts = Steps.active(c).map { st ->
            when (st.kind) {
                Steps.MATH -> "solve ${st.mathCount} math"
                Steps.SCAN -> "scan ${st.scanLabel.ifEmpty { "code" }}"
                else -> "answer ${st.questions.size} question${if (st.questions.size == 1) "" else "s"}"
            }
        }
        return if (parts.isEmpty()) "tap Dismiss" else parts.joinToString(" → ")
    }

    /** e.g. "tomorrow 7:00 AM"; for event-only check-ins, what they wait for. */
    fun next(c: Checkin): String {
        if (c.times.isEmpty()) return if (c.events.isEmpty()) "never (no triggers)" else events(c)
        val millis = AlarmScheduler.nextTriggerMillis(c) ?: return "not scheduled"
        return at(millis)
    }

    /** "today 9:30 PM", "tomorrow 7:00 AM", "Wed 7:00 AM" (this week), "Dec 1 7:00 AM". */
    fun at(millis: Long): String {
        val at = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val today = LocalDate.now()
        val date = at.toLocalDate()
        val day = when {
            date == today -> "today"
            date == today.plusDays(1) -> "tomorrow"
            date.isBefore(today.plusDays(7)) -> date.format(java.time.format.DateTimeFormatter.ofPattern("EEE"))
            else -> date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
        }
        return "$day ${Days.time(at.hour, at.minute)}"
    }

    /** "4:45 AM, 9:00 PM", or with details per time: "4:45 AM every day but Tue; 4:00 AM Tue, at Home". */
    fun times(c: Checkin): String =
        if (!c.perTimeDays && c.times.none { it.usesLocation }) c.times.joinToString(", ") { Days.time(it.hour, it.minute) }
        else c.times.joinToString("; ") { time(c, it) }

    /** One timed trigger: "4:45 AM every day", "4:00 AM Tue, only when at Home". */
    fun time(c: Checkin, t: AlarmTime): String {
        val where = if (t.usesLocation) ", only when ${Trigger.whereText(t.where, t.place)}" else ""
        return "${Days.time(t.hour, t.minute)} ${c.daysFor(t).describe()}$where"
    }

    /** One event trigger, with all its conditions: "when you arrive at Gym, Mon–Fri, between 6:00 AM and 9:00 AM, once a day". */
    fun event(t: Trigger): String {
        val parts = mutableListOf(Trigger.describe(t))
        if (t.days.kind != Repeat.DAILY) parts += t.days.describe()
        Trigger.windowText(t) { Days.time(it.hour, it.minute) }.takeIf { it.isNotEmpty() }?.let { parts += it }
        if (t.oncePerDay) parts += "once a day"
        return parts.joinToString(", ")
    }

    fun events(c: Checkin): String = c.events.joinToString("; ") { event(it) }

    /** One line for the start page, e.g. "7:00 AM · solve 3 math → answer 2 questions" */
    fun oneLine(c: Checkin): String =
        if (!c.enabled) "Off · ${steps(c)}" else "${whenText(c).replaceFirstChar { it.uppercase() }} · ${steps(c)}"

    /** Every trigger: times first, then events; e.g. "7:00 AM, Mon–Fri; when you arrive at Gym, once a day". */
    fun whenText(c: Checkin): String {
        val parts = mutableListOf<String>()
        if (c.times.isNotEmpty()) parts += times(c) + repeatSuffix(c)
        if (c.events.isNotEmpty()) parts += events(c)
        return parts.joinToString("; ").ifEmpty { "no triggers" }
    }

    private fun repeatSuffix(c: Checkin): String =
        if (c.repeat.kind == Repeat.DAILY || c.perTimeDays || c.times.any { it.usesLocation }) "" else ", ${c.repeat.describe()}"
}
