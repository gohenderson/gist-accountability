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
    fun forAlarmNow(aboutYesterday: Boolean): String {
        val now = LocalDateTime.now()
        var day = if (now.hour < ROLLOVER_HOUR) now.toLocalDate().minusDays(1) else now.toLocalDate()
        if (aboutYesterday) day = day.minusDays(1)
        return day.toString()
    }

    /** Most recent first. */
    fun lastN(n: Int): List<String> = (0 until n).map { LocalDate.now().minusDays(it.toLong()).toString() }

    fun pretty(day: String): String =
        LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEEE, MMM d"))

    fun short(day: String): String =
        LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEE MMM d"))

    fun time(hour: Int, minute: Int): String =
        LocalTime.of(hour, minute).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
}
