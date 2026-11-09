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

    /** e.g. "tomorrow at 7:00 AM" */
    fun next(c: Checkin): String {
        if (c.trigger.isEvent) return whenText(c)
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

    /** e.g. "7:00 AM, 12:30 PM, 9:30 PM" */
    /** "4:45 AM, 9:00 PM", or with days per time: "4:45 AM every day but Tue; 4:00 AM Tue". */
    fun times(c: Checkin): String =
        if (!c.perTimeDays) c.times.joinToString(", ") { Days.time(it.hour, it.minute) }
        else c.times.joinToString("; ") { "${Days.time(it.hour, it.minute)} ${c.daysFor(it).describe()}" }

    /** One line for the start page, e.g. "7:00 AM · solve 3 math → answer 2 questions" */
    fun oneLine(c: Checkin): String = when {
        !c.enabled -> "Off · ${steps(c)}"
        c.trigger.isEvent -> "${whenText(c).replaceFirstChar { it.uppercase() }} · ${steps(c)}"
        c.trigger.where != Trigger.ANYWHERE && c.trigger.place != null ->
            "${times(c)}${repeatSuffix(c)}, ${Trigger.describe(c.trigger)} · ${steps(c)}"
        else -> "${times(c)}${repeatSuffix(c)} · ${steps(c)}"
    }

    /**
     * For place triggers, everything that must hold, e.g. "when you arrive at Gym, Mon–Fri,
     * between 6:00 AM and 9:00 AM, once a day".
     */
    fun whenText(c: Checkin): String {
        val t = c.trigger
        if (!t.isEvent) return times(c) + repeatSuffix(c)
        val parts = mutableListOf(Trigger.describe(t))
        if (c.repeat.kind != Repeat.DAILY) parts += c.repeat.describe()
        Trigger.windowText(t) { Days.time(it.hour, it.minute) }.takeIf { it.isNotEmpty() }?.let { parts += it }
        if (t.oncePerDay) parts += "once a day"
        return parts.joinToString(", ")
    }

    private fun repeatSuffix(c: Checkin): String =
        if (c.repeat.kind == Repeat.DAILY || c.perTimeDays) "" else ", ${c.repeat.describe()}"
}
