package dev.gist.checkin

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import java.time.ZonedDateTime

/**
 * One-off times a while from now: "In 30 min" on a check-in's page, and "Ring again in 30 min"
 * after a ring that was one. Each is a time "On a specific date" (it rings once), so nothing
 * new is stored or synced. Adding one also clears this check-in's one-off times that have
 * already passed, so a series (basting the turkey every half hour) doesn't pile up.
 */
object QuickTime {
    /** The choices offered, in minutes. */
    val CHOICES = listOf(5, 10, 15, 30, 45, 60, 90, 120)
    const val DEFAULT = 30
    /** The longest "Other…" allows: a day. */
    const val MAX = 24 * 60

    fun text(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> if (minutes == 60) "1 hour" else "${minutes / 60} hours"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    /** The one-off time [minutes] from [now], to the nearest minute. */
    fun at(minutes: Int, now: ZonedDateTime = ZonedDateTime.now()): AlarmTime {
        val t = now.plusSeconds(minutes * 60L + 30).withSecond(0).withNano(0)
        return AlarmTime(t.hour, t.minute, days = Repeat.once(t.toLocalDate()))
    }

    /** When one-off time [t] rings, or null if it isn't one. */
    fun moment(t: AlarmTime, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        val days = t.days ?: return null
        if (days.kind != Repeat.ONCE) return null
        val date = days.date ?: return null
        return ZonedDateTime.of(date, java.time.LocalTime.of(t.hour, t.minute), now.zone)
    }

    /**
     * [c] with a one-off time [minutes] from [now] (and without its one-off times that have
     * passed). The time it rings at is [AlarmTime] in the pair; null if it already rings then.
     */
    fun added(c: Checkin, minutes: Int, now: ZonedDateTime = ZonedDateTime.now()): Pair<Checkin, AlarmTime?> {
        val t = at(minutes, now)
        val date = t.days!!.date!!
        val kept = c.times.filter { tm -> moment(tm, now)?.isAfter(now) ?: true }
        if (kept.any { it.hour == t.hour && it.minute == t.minute && c.daysFor(it).matches(date) }) {
            return c.copy(times = kept) to null
        }
        return c.copy(times = (kept + t).distinct().sorted(), enabled = true) to t
    }

    /**
     * Adds a one-off time [minutes] from now to check-in [id] in [context]'s profile and sets its
     * alarm; remembers [minutes] as the one to offer first. Returns the time, or null if it
     * already rings then (or the check-in is gone).
     */
    fun add(context: Context, id: String, minutes: Int): AlarmTime? {
        val store = Store(context)
        val c = store.checkin(id) ?: return null
        val (changed, t) = added(c, minutes)
        store.updateCheckin(id) { changed }
        store.checkin(id)?.let { AlarmScheduler.schedule(context, it) }
        store.quickMinutes = minutes
        return t
    }

    /** "Rings at 10:30" (or that it already does), for a toast. */
    fun said(minutes: Int, t: AlarmTime?): String =
        if (t == null) "It already rings then." else "Rings in ${text(minutes)}, at ${Days.time(t.hour, t.minute)}."

    /** Was the ring at [slot] ("HH:mm") one of [c]'s one-off times, within the last day? */
    fun wasOneOff(c: Checkin, slot: String, now: ZonedDateTime = ZonedDateTime.now()): Boolean =
        c.times.any { tm ->
            tm.toString() == slot && moment(tm, now)?.let { !it.isAfter(now.plusMinutes(1)) && it.isAfter(now.minusDays(1)) } == true
        }

    /** Asks for a number of minutes (1 to a day), then [onPick]. */
    fun askOther(activity: Activity, onPick: (Int) -> Unit) {
        val field = EditText(activity).apply {
            hint = "Minutes"
            tag = "quick-other-minutes"
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        }
        AlertDialog.Builder(activity)
            .setTitle("In how many minutes?")
            .setView(activity.column().apply { setPadding(activity.dp(24), activity.dp(8), activity.dp(24), activity.dp(8)); add(field) })
            .setPositiveButton("Set") { _, _ ->
                val m = field.text.toString().toIntOrNull()
                if (m == null || m < 1 || m > MAX) {
                    android.widget.Toast.makeText(activity, "Between 1 and $MAX minutes.", android.widget.Toast.LENGTH_SHORT).show()
                } else onPick(m)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
