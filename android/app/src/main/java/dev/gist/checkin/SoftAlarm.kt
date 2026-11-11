package dev.gist.checkin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Snooze and Dismiss for soft alarms (Checkin.soft), from the ringing screen or the alarm
 * notification. Strict alarms never get here: only their steps stop them.
 */
object SoftAlarm {
    /** Bumped on every snooze / dismiss, so an open alarm screen notices and moves on. */
    @Volatile
    var changes = 0
        private set

    /** Stop [p] without doing its steps. Logged as dismissed (a soft result, so no stats). */
    fun dismiss(context: Context, p: Pending) {
        val store = Store(context)
        if (!store.isSoft(p)) return
        val c = store.checkin(p.checkinId) ?: return
        if (!p.practice) store.markDismissed(p.day, c.id, p.time.ifEmpty { AlarmScheduler.nearestTime(c).toString() })
        store.removePending(p)
        settle(context)
    }

    /** Ring [p] again after its check-in's snooze length. Returns when (epoch millis). */
    fun snooze(context: Context, p: Pending): Long? {
        val store = Store(context)
        if (!store.isSoft(p)) return null
        val c = store.checkin(p.checkinId) ?: return null
        store.removePending(p)
        val at = AlarmScheduler.snooze(context, p, c.snoozeMinutes)
        settle(context)
        return at
    }

    /** After a snooze or dismiss: stop ringing if nothing else is waiting, else show the next. */
    private fun settle(context: Context) {
        changes++
        val store = Store(context)
        store.prunePending()
        if (store.pending.isEmpty()) {
            context.stopService(Intent(context, AlarmService::class.java))
            Notif.clearWaiting(context)
        } else if (AlarmService.running) {
            AlarmService.refreshNotification(context)
        } else {
            Notif.showWaiting(context) // the reminder now points at the next one
        }
    }
}

/** The Snooze / Dismiss buttons on a soft alarm's notification. */
class SoftAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = Pending(
            intent.getStringExtra(EXTRA_CHECKIN) ?: return,
            intent.getStringExtra(EXTRA_DAY) ?: return,
            intent.getStringExtra(EXTRA_TIME) ?: "",
            intent.getBooleanExtra(EXTRA_PRACTICE, false),
        )
        if (p !in Store(context).pending) return // already handled
        when (intent.action) {
            ACTION_DISMISS -> SoftAlarm.dismiss(context, p)
            ACTION_SNOOZE -> SoftAlarm.snooze(context, p)
        }
    }

    companion object {
        const val ACTION_DISMISS = "dev.gist.checkin.soft.DISMISS"
        const val ACTION_SNOOZE = "dev.gist.checkin.soft.SNOOZE"
        private const val EXTRA_CHECKIN = "checkin"
        private const val EXTRA_DAY = "day"
        private const val EXTRA_TIME = "time"
        private const val EXTRA_PRACTICE = "practice"

        fun intent(context: Context, action: String, p: Pending): Intent =
            Intent(context, SoftAlarmReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_CHECKIN, p.checkinId)
                .putExtra(EXTRA_DAY, p.day)
                .putExtra(EXTRA_TIME, p.time)
                .putExtra(EXTRA_PRACTICE, p.practice)
    }
}
