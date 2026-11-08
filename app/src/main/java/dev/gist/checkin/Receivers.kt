package dev.gist.checkin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fired by AlarmManager at a check-in's time. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store(context)
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_CHECKIN) ?: return
        val c = store.checkin(id) ?: return // deleted since it was scheduled
        store.addPending(Pending(c.id, Days.forAlarmNow(c.aboutYesterday)))
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
        AlarmScheduler.scheduleAll(context)
        if (Store(context).pending.isNotEmpty()) Notif.showWaiting(context)
    }
}
