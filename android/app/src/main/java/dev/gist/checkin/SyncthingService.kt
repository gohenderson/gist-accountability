package dev.gist.checkin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps Syncthing running while Easy Connect is on, as a foreground
 * service with its notification. Every few seconds it lets in the device a code was shown to,
 * finishes a join waiting for My devices, and keeps your devices in step ([EasyConnect.tick]).
 *
 * Android 15 allows this kind of service about 6 hours a day; when that's used up it stops
 * (onTimeout), and starts again the next time Gist opens.
 */
class SyncthingService : Service() {
    @Volatile
    private var stopped = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Automatic connections", NotificationManager.IMPORTANCE_LOW).apply {
            description = "While Gist keeps your devices in step"
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (worker?.isAlive != true) {
            stopped = false
            worker = Thread({ run() }, "gist-syncthing").apply { start() }
        }
        return START_STICKY
    }

    private fun run() {
        val engine = SyncthingEngine.shared(this)
        var last = ""
        while (!stopped) {
            try {
                if (!engine.running) engine.start()
                EasyConnect.tick(this, engine)
                val text = EasyConnect.status(this) ?: "Running"
                if (text != last) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text))
                    last = text
                }
            } catch (e: Exception) {
                android.util.Log.w("GistSyncthing", "tick failed", e)
            }
            try {
                Thread.sleep(TICK_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        worker?.interrupt()
        Thread { SyncthingEngine.shared(this).stop() }.start()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, ConnectionsActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Gist: automatic connections")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "easy_connect"
        private const val NOTIFICATION = 7301
        private const val ACTION_STOP = "dev.gist.checkin.SYNCTHING_STOP"
        private const val TICK_MS = 5_000L

        fun start(context: Context) {
            if (!SyncthingEngine.shared(context).available) return
            context.startForegroundService(Intent(context, SyncthingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SyncthingService::class.java).setAction(ACTION_STOP))
        }
    }
}
