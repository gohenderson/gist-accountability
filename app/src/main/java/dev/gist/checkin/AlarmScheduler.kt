package dev.gist.checkin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZonedDateTime

object AlarmScheduler {
    const val EXTRA_CHECKIN = "checkinId"

    fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val now = ZonedDateTime.now()
        var t = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusDays(1)
        return t.toInstant().toEpochMilli()
    }

    /** (Re)schedule every check-in from current settings. Safe to call any time. */
    fun scheduleAll(context: Context) {
        Store(context).checkins.forEach { schedule(context, it) }
    }

    fun schedule(context: Context, c: Checkin) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = broadcast(context, c.id, test = false)
        if (!c.enabled) {
            am.cancel(pi)
            return
        }
        set(context, am, nextTriggerMillis(c.hour, c.minute), pi)
    }

    /** Call when a check-in is deleted. */
    fun cancel(context: Context, checkinId: String) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(broadcast(context, checkinId, test = false))
        am.cancel(broadcast(context, checkinId, test = true))
    }

    fun scheduleTest(context: Context, checkinId: String, delayMs: Long = 10_000L) {
        val am = context.getSystemService(AlarmManager::class.java)
        set(context, am, System.currentTimeMillis() + delayMs, broadcast(context, checkinId, test = true))
    }

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
    private fun broadcast(context: Context, checkinId: String, test: Boolean): PendingIntent {
        val requestCode = (checkinId.hashCode() and 0x3FFFFFFF) * 2 + (if (test) 1 else 0)
        val intent = Intent(context, AlarmReceiver::class.java).putExtra(EXTRA_CHECKIN, checkinId)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
