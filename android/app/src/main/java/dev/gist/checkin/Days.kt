package dev.gist.checkin

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object Days {
    /** An alarm that rings before 4 AM is about the previous day. */
    private const val ROLLOVER_HOUR = 4

    fun today(): String = LocalDate.now().toString()

    /** The day a check-in ringing right now is about. */
    fun forAlarmNow(aboutYesterday: Boolean, now: LocalDateTime = LocalDateTime.now()): String {
        var day = if (now.hour < ROLLOVER_HOUR) now.toLocalDate().minusDays(1) else now.toLocalDate()
        if (aboutYesterday) day = day.minusDays(1)
        return day.toString()
    }

    /** Most recent first. */
    fun lastN(n: Int, today: LocalDate = LocalDate.now()): List<String> =
        (0 until n).map { today.minusDays(it.toLong()).toString() }

    fun pretty(day: String): String =
        LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEEE, MMM d"))

    fun short(day: String): String =
        LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEE MMM d"))

    /** "21:30" → "9:30 PM" (in the phone's format); unparseable text is returned as is. */
    fun timeOf(hhmm: String): String = AlarmTime.parse(hhmm)?.let { time(it.hour, it.minute) } ?: hhmm

    fun time(hour: Int, minute: Int): String =
        LocalTime.of(hour, minute).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
}
