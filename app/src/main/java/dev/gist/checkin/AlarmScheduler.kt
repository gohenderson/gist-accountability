package dev.gist.checkin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalTime
import java.time.ZonedDateTime

/** One upcoming ring: which time, when (epoch millis), and the day its answers count for. */
data class Occurrence(val time: AlarmTime, val at: Long, val day: String) {
    val timeKey: String get() = time.toString()
}

object AlarmScheduler {
    const val EXTRA_CHECKIN = "checkinId"
    const val EXTRA_TIME = "time"
    const val EXTRA_DAY = "day"
    const val EXTRA_TEST = "test"

    fun nextTriggerMillis(hour: Int, minute: Int, now: ZonedDateTime = ZonedDateTime.now()): Long {
        var t = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusDays(1)
        return t.toInstant().toEpochMilli()
    }

    /** The check-in's rings over the next few days, soonest first, all strictly after [now]. */
    fun occurrences(c: Checkin, now: ZonedDateTime = ZonedDateTime.now(), days: Int = 3): List<Occurrence> =
        (0..days).flatMap { offset ->
            val date = now.toLocalDate().plusDays(offset.toLong())
            c.times.mapNotNull { t ->
                val at = ZonedDateTime.of(date, LocalTime.of(t.hour, t.minute), now.zone)
                if (at.isAfter(now)) {
                    Occurrence(t, at.toInstant().toEpochMilli(), Days.forAlarmNow(c.aboutYesterday, at.toLocalDateTime()))
                } else null
            }
        }.sortedBy { it.at }

    /** The next ring that isn't [closed] (done early or skipped). */
    fun nextOccurrence(
        c: Checkin,
        now: ZonedDateTime = ZonedDateTime.now(),
        closed: (Occurrence) -> Boolean = { false },
    ): Occurrence? = occurrences(c, now).firstOrNull { !closed(it) }

    /** Same, using what's saved in [store]. */
    fun nextOpenOccurrence(store: Store, c: Checkin, now: ZonedDateTime = ZonedDateTime.now()): Occurrence? =
        nextOccurrence(c, now) { store.isClosed(it.day, c.id, it.timeKey) }

    /** The soonest of the check-in's times, strictly after [now]. */
    fun nextTriggerMillis(c: Checkin, now: ZonedDateTime = ZonedDateTime.now()): Long =
        occurrences(c, now).first().at

    /** The check-in's time closest to [at] (either direction, around midnight too). */
    fun nearestTime(c: Checkin, at: LocalTime = LocalTime.now()): AlarmTime {
        val m = at.hour * 60 + at.minute
        return c.times.minBy { t ->
            val d = Math.abs(t.hour * 60 + t.minute - m)
            minOf(d, 24 * 60 - d)
        }
    }

    /** (Re)schedule every check-in from current settings. Safe to call any time. */
    fun scheduleAll(context: Context) {
        Store(context).checkins.forEach { schedule(context, it) }
    }

    fun schedule(context: Context, c: Checkin) {
        val am = context.getSystemService(AlarmManager::class.java)
        if (!c.enabled || c.times.isEmpty()) {
            am.cancel(broadcast(context, c.id, test = false))
            return
        }
        // One alarm per check-in, set for its next ring that wasn't done early or skipped
        // (its time and day are passed along so results are saved under them). After it
        // rings, AlarmReceiver calls this again, which picks the one after that.
        val next = nextOpenOccurrence(Store(context), c)
        if (next == null) {
            am.cancel(broadcast(context, c.id, test = false))
            return
        }
        set(context, am, next.at, broadcast(context, c.id, test = false, time = next.timeKey, day = next.day))
    }

    /** Call when a check-in is deleted. */
    fun cancel(context: Context, checkinId: String) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(broadcast(context, checkinId, test = false))
        am.cancel(broadcast(context, checkinId, test = true))
    }

    fun scheduleTest(context: Context, checkinId: String, delayMs: Long = 10_000L) {
        val am = context.getSystemService(AlarmManager::class.java)
        // A test ring is saved under whichever of its times is closest to now.
        val time = Store(context).checkin(checkinId)?.let { nearestTime(it).toString() }
        set(context, am, System.currentTimeMillis() + delayMs, broadcast(context, checkinId, test = true, time = time))
    }

    /** The next ring of [c] that hasn't been done early or skipped, for the UI. */
    fun upcoming(context: Context, c: Checkin): Occurrence? =
        if (c.enabled && c.times.isNotEmpty()) nextOpenOccurrence(Store(context), c) else null

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun set(context: Context, am: AlarmManager, atMillis: Long, pi: PendingIntent) {
        if (canExact(context)) {
            // setAlarmClock = same mechanism as the stock Clock app: exact, survives Doze,
            // and shows the alarm icon in the status bar.
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, show), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    /** One distinct PendingIntent per check-in (and one more for its test ring). */
    private fun broadcast(
        context: Context,
        checkinId: String,
        test: Boolean,
        time: String? = null,
        day: String? = null,
    ): PendingIntent {
        val requestCode = (checkinId.hashCode() and 0x3FFFFFFF) * 2 + (if (test) 1 else 0)
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(EXTRA_CHECKIN, checkinId)
            .putExtra(EXTRA_TEST, test)
        if (time != null) intent.putExtra(EXTRA_TIME, time)
        if (day != null) intent.putExtra(EXTRA_DAY, day)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
