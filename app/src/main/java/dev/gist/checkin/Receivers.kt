package dev.gist.checkin

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Fired by AlarmManager at a check-in's time. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store(context)
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_CHECKIN) ?: return
        val c = store.checkin(id) ?: return // deleted since it was scheduled
        val time = intent.getStringExtra(AlarmScheduler.EXTRA_TIME)?.takeIf { AlarmTime.parse(it) != null }
            ?: AlarmScheduler.nearestTime(c).toString()
        val day = intent.getStringExtra(AlarmScheduler.EXTRA_DAY) ?: Days.forAlarmNow(c.aboutYesterday)
        val test = intent.getBooleanExtra(AlarmScheduler.EXTRA_TEST, false)
        if (!test && store.isClosed(day, c.id, time)) {
            // Already done early or skipped: stay quiet and move on to the next ring.
            AlarmScheduler.schedule(context, c)
            return
        }
        store.markRang(day, c.id, time)
        store.addPending(Pending(c.id, day, time))
        try {
            context.startForegroundService(
                Intent(context, AlarmService::class.java).putExtra(AlarmService.EXTRA_CHECKIN, c.id)
            )
        } catch (e: Exception) {
            // Background start refused (only possible if exact alarms were denied).
            // Fall back to a plain full-screen notification.
            Notif.showWaiting(context)
        }
        AlarmScheduler.schedule(context, c) // queue tomorrow's ring
    }
}

/** Re-arms alarms after reboot, app update, or clock/time-zone changes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // This receiver is exported (system broadcasts need that): act only on the system
        // events it's registered for, never on arbitrary intents from other apps.
        if (intent.action !in SYSTEM_ACTIONS) return
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
