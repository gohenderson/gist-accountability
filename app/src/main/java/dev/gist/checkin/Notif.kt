package dev.gist.checkin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build

object Notif {
    private const val CH_ALARM = "checkin_alarm"
    private const val CH_WAITING = "checkin_waiting"
    private const val CH_SETUP = "setup"
    const val ID_PERMISSION = 3
    const val ID_ALARM = 1
    private const val ID_WAITING = 2

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Check-in alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "The ringing check-in alarm"
                setSound(null, null)      // the service plays the sound itself
                enableVibration(false)    // ...and vibrates itself
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WAITING, "Check-in reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown when a check-in is still unanswered"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun surveyIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 10, CheckinActivity.alarmIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /**
     * The waiting check-in, if it's a soft alarm: its notifications get Snooze and Dismiss
     * buttons. Strict alarms get none (only their steps stop them).
     */
    private fun softPending(context: Context): Pending? {
        val store = Store(context)
        return store.pending.firstOrNull()?.takeIf { store.isSoft(it) }
    }

    private fun Notification.Builder.softActions(context: Context, p: Pending?): Notification.Builder {
        if (p == null) return this
        val c = Store(context).checkin(p.checkinId) ?: return this
        fun action(label: String, action: String, code: Int): Notification.Action {
            val pi = PendingIntent.getBroadcast(
                context, code, SoftAlarmReceiver.intent(context, action, p),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Action.Builder(null as android.graphics.drawable.Icon?, label, pi).build()
        }
        addAction(action("Snooze ${c.snoozeMinutes} min", SoftAlarmReceiver.ACTION_SNOOZE, 20))
        addAction(action("Dismiss", SoftAlarmReceiver.ACTION_DISMISS, 21))
        return this
    }

    fun alarm(context: Context): Notification {
        ensureChannels(context)
        val pi = surveyIntent(context)
        val soft = softPending(context)
        val name = soft?.let { Store(context).checkin(it.checkinId)?.name }
        val b = Notification.Builder(context, CH_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name ?: "Daily check-in")
            .setContentText(if (soft != null) "Snooze, dismiss, or tap to check in." else "Answer the questions to silence the alarm.")
            .softActions(context, soft)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    fun showWaiting(context: Context) {
        // Pointless (and in the way) while the alarm screen itself is open.
        if (CheckinActivity.visible) return
        ensureChannels(context)
        val pi = surveyIntent(context)
        val n = Notification.Builder(context, CH_WAITING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Check-in still waiting")
            .setContentText("Tap to answer your check-in.")
            .softActions(context, softPending(context))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_WAITING, n)
    }

    /**
     * Android 14+ can turn off "Full-screen notifications" for apps installed outside Google
     * Play when they're updated. Without it, alarms can't take over the lock screen, so say so
     * right away and link straight to the switch.
     */
    fun showFullScreenPermissionLost(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SETUP, "Setup problems", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When a permission alarms need gets turned off"
            }
        )
        val fix = android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            android.net.Uri.parse("package:${context.packageName}")
        )
        val pi = PendingIntent.getActivity(context, 11, fix, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(context, CH_SETUP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Turn full-screen alarms back on")
            .setContentText("Android switched it off (this can happen after an update). Tap to fix.")
            .setStyle(Notification.BigTextStyle().bigText(
                "Android switched off Gist's full-screen notification permission, which can happen after " +
                    "an update. Until it's back on, alarms can't appear over the lock screen. Tap to turn it on."
            ))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(ID_PERMISSION, n)
    }

    fun clearWaiting(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_WAITING)
    }
}
