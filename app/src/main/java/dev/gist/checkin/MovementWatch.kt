package dev.gist.checkin

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.time.LocalDateTime

/**
 * Running triggers: whether they can work on this phone, starting and stopping the step
 * watcher ([MovementService]), and ringing check-ins when a run starts or finishes.
 */
object MovementWatch {
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun hasSensor(context: Context): Boolean =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    fun canWatch(context: Context) = hasPermission(context) && hasSensor(context)

    /** Why running triggers won't work here, in a sentence. */
    fun problem(context: Context): String = when {
        !hasSensor(context) -> "This phone has no step sensor, so runs can't be noticed."
        !hasPermission(context) -> "Needs \"physical activity\" access (see Finish setup)."
        else -> ""
    }

    fun anyUse(context: Context): Boolean = Store(context).checkins.any { it.enabled && it.trigger.isMovement }

    /** Runs the watcher while any check-in needs it (and it can work), stops it otherwise. */
    fun sync(context: Context) {
        val want = anyUse(context) && canWatch(context)
        val intent = Intent(context, MovementService::class.java)
        if (want && !MovementService.running) {
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Not allowed from the background right now; it starts when the app or phone next starts.
            }
        } else if (!want && MovementService.running) {
            context.stopService(intent)
        }
    }

    /** A run started ([finishedMinutes] = null) or finished: ring every check-in that wants it. */
    fun onRun(context: Context, finishedMinutes: Int?, now: LocalDateTime = LocalDateTime.now()) {
        val store = Store(context)
        for (c in store.checkins) {
            if (!c.trigger.isMovement) continue
            val day = Days.forAlarmNow(c.aboutYesterday, now)
            val rangToday = store.results(day, c.id).values.any { it.status != SlotResult.SKIPPED } ||
                store.pending.any { it.checkinId == c.id && !it.practice }
            if (Trigger.runEventRings(c, finishedMinutes, now, rangToday)) LocationTriggers.ringNow(context, c)
        }
    }
}

/**
 * Watches the step counter while a check-in has a running trigger, and shows a quiet
 * notification while it does (Android requires one). Uses the step sensor's own low-power
 * batching; during a run it keeps the CPU awake so the end of the run is noticed on time.
 */
class MovementService : Service(), SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private val detector = RunDetector()
    private var wakeLock: PowerManager.WakeLock? = null

    private val ticker = object : Runnable {
        override fun run() {
            handle(detector.tick(System.currentTimeMillis()))
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val n = notification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(ID, n)
        }
        val sm = getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true) ?: sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null || !MovementWatch.hasPermission(this)) {
            stopSelf()
            return
        }
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, BATCH_US)
        handler.postDelayed(ticker, TICK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onSensorChanged(event: SensorEvent) {
        // event.timestamp is in the elapsed-realtime clock; turn it into wall-clock time.
        val ageMs = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L
        handle(detector.steps(System.currentTimeMillis() - ageMs.coerceAtLeast(0), event.values[0].toLong()))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun handle(e: RunDetector.Event?) {
        when (e) {
            is RunDetector.Event.Started -> {
                holdAwake(true)
                MovementWatch.onRun(this, null)
            }
            is RunDetector.Event.Finished -> {
                holdAwake(false)
                MovementWatch.onRun(this, e.minutes)
            }
            null -> {}
        }
    }

    private fun holdAwake(on: Boolean) {
        if (on) {
            if (wakeLock?.isHeld == true) return
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gist:run").apply { acquire(MAX_RUN_MS) }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        getSystemService(SensorManager::class.java).unregisterListener(this)
        holdAwake(false)
        super.onDestroy()
    }

    companion object {
        @Volatile
        var running = false
            private set

        private const val ID = 4
        private const val CHANNEL = "movement"
        private const val TICK_MS = 15_000L
        private const val BATCH_US = 10_000_000
        private const val MAX_RUN_MS = 6 * 60 * 60 * 1000L

        private fun notification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Watching for runs", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while Gist watches your steps for a check-in that rings when you run"
                    setShowBadge(false)
                }
            )
            val open = PendingIntent.getActivity(
                context, 11, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Watching for runs")
                .setContentText("For your check-ins that ring when you start or finish running. Steps stay on this phone.")
                .setOngoing(true)
                .setContentIntent(open)
                .build()
        }
    }
}
