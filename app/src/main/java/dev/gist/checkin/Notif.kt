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
            context, 10, SurveyActivity.alarmIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun alarm(context: Context): Notification {
        ensureChannels(context)
        val pi = surveyIntent(context)
        val b = Notification.Builder(context, CH_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Daily check-in")
            .setContentText("Answer the questions to silence the alarm.")
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
        ensureChannels(context)
        val pi = surveyIntent(context)
        val n = Notification.Builder(context, CH_WAITING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Check-in still waiting")
            .setContentText("Tap to answer your check-in.")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_WAITING, n)
    }

    fun clearWaiting(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_WAITING)
    }
}
