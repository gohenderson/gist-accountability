package dev.gist.checkin

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Which dates a check-in rings on (at each of its times).
 *  - DAILY: every day.
 *  - WEEKLY: on [days] of the week (1 = Monday … 7 = Sunday).
 *  - MONTHLY: on [days] of the month (1…31, or [LAST_DAY]). A date a month doesn't have
 *    (e.g. the 31st in November) is skipped that month; use LAST_DAY for "end of month".
 */
data class Repeat(val kind: String = DAILY, val days: Set<Int> = emptySet()) {

    fun matches(date: LocalDate): Boolean = when (kind) {
        WEEKLY -> date.dayOfWeek.value in days
        MONTHLY -> date.dayOfMonth in days || (LAST_DAY in days && date.dayOfMonth == date.lengthOfMonth())
        else -> true
    }

    /** e.g. "every day", "Mon–Fri", "Mon, Wed, Fri", "monthly on the 1st and 15th". */
    fun describe(): String = when (kind) {
        WEEKLY -> when (days) {
            WEEKDAYS -> "Mon–Fri"
            WEEKENDS -> "Sat & Sun"
            (1..7).toSet() -> "every day"
            else -> days.sorted().joinToString(", ") { dayName(it) }
        }
        MONTHLY -> {
            val parts = days.filter { it != LAST_DAY }.sorted().map { ordinal(it) } +
                if (LAST_DAY in days) listOf("last day") else emptyList()
            "monthly on the " + joinWithAnd(parts)
        }
        else -> "every day"
    }

    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("days", JSONArray(days.sorted()))

    companion object {
        const val DAILY = "daily"
        const val WEEKLY = "weekly"
        const val MONTHLY = "monthly"
        const val LAST_DAY = -1

        val WEEKDAYS = setOf(1, 2, 3, 4, 5)
        val WEEKENDS = setOf(6, 7)

        val EVERY_DAY = Repeat()
        fun weekly(days: Set<Int>) = if (days.isEmpty() || days.size == 7) EVERY_DAY else Repeat(WEEKLY, days)
        fun monthly(days: Set<Int>) = if (days.isEmpty()) EVERY_DAY else Repeat(MONTHLY, days)

        /** Missing or unreadable → every day. */
        fun fromJson(o: JSONObject?): Repeat {
            if (o == null) return EVERY_DAY
            val arr = o.optJSONArray("days") ?: JSONArray()
            val days = (0 until arr.length()).map { arr.optInt(it) }.toSet()
            return when (o.optString("kind")) {
                WEEKLY -> weekly(days.filter { it in 1..7 }.toSet())
                MONTHLY -> monthly(days.filter { it in 1..31 || it == LAST_DAY }.toSet())
                else -> EVERY_DAY
            }
        }

        fun dayName(d: Int): String = DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, Locale.getDefault())

        fun ordinal(n: Int): String {
            val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
            return "$n$suffix"
        }

        private fun joinWithAnd(parts: List<String>): String = when (parts.size) {
            0 -> ""
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
    }
}
