package dev.gist.checkin

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Fired by AlarmManager at a check-in's time (or right after arriving at / leaving its place). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store(context)
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_CHECKIN) ?: return
        val c = store.checkin(id) ?: return // deleted since it was scheduled
        val time = intent.getStringExtra(AlarmScheduler.EXTRA_TIME)?.takeIf { AlarmTime.parse(it) != null }
            ?: AlarmScheduler.nearestTime(c).toString()
        val day = intent.getStringExtra(AlarmScheduler.EXTRA_DAY) ?: Days.forAlarmNow(c.aboutYesterday)
        val test = intent.getBooleanExtra(AlarmScheduler.EXTRA_TEST, false)
        val fromPlace = intent.getBooleanExtra(EXTRA_PLACE, false)
        if (!test && store.isClosed(day, c.id, time)) {
            // Already done early or skipped: stay quiet and move on to the next ring.
            AlarmScheduler.schedule(context, c)
            return
        }
        val t = c.trigger
        if (!test && !fromPlace && t.kind == Trigger.TIME && t.where != Trigger.ANYWHERE && t.place != null) {
            // "Only when at / away from a place": find out where the phone is first.
            val result: PendingResult? = goAsync() // null when called directly (tests)
            LocationTriggers.locate(context) { loc ->
                try {
                    val distance = loc?.let { LocationTriggers.distanceTo(t.place, it) }
                    if (Trigger.locationAllows(t, distance)) {
                        ring(context, c, day, time, test = false)
                    } else {
                        Store(context).markSkipped(day, c.id, time) // History shows "–": not there, so no ring
                        AlarmScheduler.schedule(context, c)
                    }
                } finally {
                    result?.finish()
                }
            }
            return
        }
        ring(context, c, day, time, test)
    }

    private fun ring(context: Context, c: Checkin, day: String, time: String, test: Boolean) {
        val store = Store(context)
        // A test ring logs nothing: no "missed" mark, and its answers aren't saved.
        if (!test) store.markRang(day, c.id, time)
        store.addPending(Pending(c.id, day, time, practice = test))
        try {
            context.startForegroundService(
                Intent(context, AlarmService::class.java).putExtra(AlarmService.EXTRA_CHECKIN, c.id)
            )
        } catch (e: Exception) {
            // Background start refused (only possible if exact alarms were denied).
            // Fall back to a plain full-screen notification.
            Notif.showWaiting(context)
        }
        AlarmScheduler.schedule(context, c) // queue the next ring
    }

    companion object {
        /** Set on the alarm a place event schedules (see LocationTriggers.ringNow). */
        const val EXTRA_PLACE = "fromPlace"
    }
}

/** Re-arms alarms after reboot, app update, or clock/time-zone changes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // This receiver is exported (system broadcasts need that): act only on the system
        // events it's registered for, never on arbitrary intents from other apps.
        if (intent.action !in SYSTEM_ACTIONS) return
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Android drops place watches on reboot (and may on update): set them up again.
            Store(context).checkins.forEach { LocationTriggers.remove(context, it.id) }
        }
        AlarmScheduler.scheduleAll(context)
        if (Build.VERSION.SDK_INT >= 34 &&
            !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            Notif.showFullScreenPermissionLost(context)
        }
        if (Store(context).pending.isNotEmpty()) Notif.showWaiting(context)
    }
}

private val SYSTEM_ACTIONS = setOf(
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
    Intent.ACTION_TIME_CHANGED,
    Intent.ACTION_TIMEZONE_CHANGED,
    "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
)
