package dev.gist.checkin

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Short descriptions of a check-in, shared by the start page and the edit page. */
object CheckinText {
    /** e.g. "solve 3 math → scan Bathroom sink → answer 2 questions" */
    fun steps(c: Checkin): String {
        val parts = mutableListOf<String>()
        if (c.mathCount > 0) parts += "solve ${c.mathCount} math"
        if (c.scanCode != null) parts += "scan ${c.scanLabel.ifEmpty { "code" }}"
        if (c.questions.isNotEmpty()) {
            parts += "answer ${c.questions.size} question${if (c.questions.size == 1) "" else "s"}"
        }
        return if (parts.isEmpty()) "tap Dismiss" else parts.joinToString(" → ")
    }

    /** e.g. "tomorrow at 7:00 AM" */
    fun next(c: Checkin): String {
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
    fun times(c: Checkin): String = c.times.joinToString(", ") { Days.time(it.hour, it.minute) }

    /** One line for the start page, e.g. "7:00 AM · solve 3 math → answer 2 questions" */
    fun oneLine(c: Checkin): String =
        if (c.enabled) "${times(c)}${repeatSuffix(c)} · ${steps(c)}" else "Off · ${steps(c)}"

    private fun repeatSuffix(c: Checkin): String =
        if (c.repeat.kind == Repeat.DAILY) "" else ", ${c.repeat.describe()}"
}
