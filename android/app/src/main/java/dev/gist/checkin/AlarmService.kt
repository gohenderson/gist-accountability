package dev.gist.checkin

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import kotlin.math.ceil

/**
 * Rings (sound + vibration) until CheckinActivity stops it after the questions are answered.
 * Keeps the alarm volume from being turned down below [VOLUME_FLOOR] while ringing.
 *
 * The first ring starts quietly and swells to full volume over [rampMs] (about 30 s), like
 * a gentle alarm clock. Once you've been woken, it doesn't start soft again: when a pause
 * runs out, it comes back at full volume.
 */
class AlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audio: AudioManager? = null
    private var originalVolume = -1
    private var floorVolume = 1
    private var ringing = false
    private var paused = false
    private var attrs: AudioAttributes? = null

    private val volumeGuard = object : Runnable {
        override fun run() {
            enforceVolume()
            handler.postDelayed(this, 1000)
        }
    }

    private val giveUp = Runnable { stopSelf() }

    private var rampStart = 0L
    private val ramp = object : Runnable {
        override fun run() {
            val total = rampMs()
            val f = ((SystemClock.elapsedRealtime() - rampStart).toFloat() / total).coerceIn(0f, 1f)
            // Loudness is perceived roughly logarithmically: squaring keeps the early part soft.
            setGain(RAMP_START + (1f - RAMP_START) * f * f)
            if (f < 1f) handler.postDelayed(this, RAMP_STEP_MS)
        }
    }

    private fun setGain(g: Float) {
        currentGain = g
        try { player?.setVolume(g, g) } catch (e: Exception) {}
    }

    /**
     * Leaving the alarm screen (Home, the app switcher, another app) doesn't get you out of
     * it: while the alarm is active, the check-in comes straight back to the front. Android
     * only allows that for apps with "Display over other apps" (the start page asks for it).
     */
    private val frontGuard = object : Runnable {
        override fun run() {
            bringBack()
            handler.postDelayed(this, FRONT_CHECK_MS)
        }
    }

    private fun bringBack() {
        if (CheckinActivity.visible) return
        if (!Settings.canDrawOverlays(this)) return
        if (!getSystemService(PowerManager::class.java).isInteractive) return // screen off: the lock screen shows it
        // A test ring can be left, and so can a soft alarm (it has Snooze / Dismiss instead).
        val store = Store(this)
        if (store.pending.none { !it.practice && !store.isSoft(it) }) return
        try {
            startActivity(CheckinActivity.alarmIntent(this).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) {
        }
    }

    private val resumeRinging = Runnable { resume() }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        startForeground(
            Notif.ID_ALARM,
            Notif.alarm(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        if (!ringing) {
            ringing = true
            val checkin = intent?.getStringExtra(EXTRA_CHECKIN)?.let { Store(this).checkin(it) }
                ?: Store(this).pending.firstOrNull()?.let { Store(this).checkin(it.checkinId) }
            startRinging(checkin?.soundUri)
            handler.postDelayed(giveUp, MAX_RING_MS)
            handler.postDelayed(frontGuard, FRONT_CHECK_MS)
        }
        // Works when the screen is on and unlocked on some devices; otherwise the
        // notification's full-screen intent opens the survey.
        try {
            startActivity(CheckinActivity.alarmIntent(this))
        } catch (e: Exception) {
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    /** [soundUri]: the check-in's choice (null = default alarm sound, Checkin.SILENT = vibrate only). */
    private fun startRinging(soundUri: String?) {
        val am = getSystemService(AudioManager::class.java)
        audio = am
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        originalVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        floorVolume = ceil(max * VOLUME_FLOOR).toInt().coerceIn(1, max.coerceAtLeast(1))
        handler.post(volumeGuard)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        this.attrs = attrs

        // The check-in's sound first (it may not exist, e.g. after restoring a backup on a new
        // phone), then the phone's default alarm, ringtone and notification sounds.
        val candidates = mutableListOf<Uri>()
        if (soundUri != null && soundUri != Checkin.SILENT) candidates += Uri.parse(soundUri)
        for (type in intArrayOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)) {
            RingtoneManager.getDefaultUri(type)?.let { candidates += it }
        }
        if (soundUri == Checkin.SILENT) candidates.clear()
        currentSound = if (soundUri == Checkin.SILENT) Checkin.SILENT else null
        for (uri in candidates) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
                mp.prepare()
                mp.setVolume(RAMP_START, RAMP_START)
                mp.start()
                player = mp
                currentSound = uri.toString()
                break
            } catch (e: Exception) {
                mp.release()
            }
        }

        val vib = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        vibrator = vib
        startVibration()
        // Start soft and swell to full volume.
        rampStart = SystemClock.elapsedRealtime()
        setGain(RAMP_START)
        handler.post(ramp)
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val a = attrs ?: return
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 700), 0), a)
    }

    /**
     * Silence the alarm for [ms] while the person works through the check-in. Calling it
     * again (on every tap or keystroke) restarts the countdown. When it runs out, ringing
     * resumes, even if the alarm screen isn't open.
     */
    fun pauseFor(ms: Long = pauseMs(this)) {
        if (!ringing) return
        pausedUntil = SystemClock.elapsedRealtime() + ms
        if (!paused) {
            paused = true
            isPaused = true
            try { player?.pause() } catch (e: Exception) {}
            handler.removeCallbacks(ramp)
            vibrator?.cancel()
            handler.removeCallbacks(volumeGuard)
        }
        handler.removeCallbacks(resumeRinging)
        handler.postDelayed(resumeRinging, ms)
    }

    private fun resume() {
        if (!paused) return
        paused = false
        isPaused = false
        pausedUntil = 0L
        setGain(1f) // you were already woken: back at full volume, no slow start
        try { player?.start() } catch (e: Exception) {}
        startVibration()
        handler.post(volumeGuard)
    }

    private fun enforceVolume() {
        val am = audio ?: return
        try {
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < floorVolume) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, floorVolume, 0)
            }
        } catch (e: SecurityException) {
        }
    }

    override fun onDestroy() {
        running = false
        currentSound = null
        currentGain = 0f
        isPaused = false
        pausedUntil = 0L
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        player?.let {
            try { it.stop() } catch (e: Exception) {}
            it.release()
        }
        player = null
        vibrator?.cancel()
        val am = audio
        if (am != null && originalVolume >= 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
            } catch (e: SecurityException) {
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Gave up ringing (or got killed) without answers: leave a sticky reminder.
        if (Store(this).pending.isNotEmpty()) Notif.showWaiting(this)
        super.onDestroy()
    }

    companion object {
        /** The running service, for the alarm screen to pause it (same process). */
        @Volatile
        var instance: AlarmService? = null
            private set

        /** True while ringing is paused for the check-in. */
        @Volatile
        var isPaused = false
            private set

        /** When the pause ends, in SystemClock.elapsedRealtime() time; 0 when not paused. */
        @Volatile
        var pausedUntil = 0L
            private set

        const val EXTRA_CHECKIN = "checkinId"

        /** Redraw the alarm notification (e.g. the waiting check-in changed). */
        fun refreshNotification(context: Context) {
            if (!running) return
            context.getSystemService(android.app.NotificationManager::class.java).notify(Notif.ID_ALARM, Notif.alarm(context))
        }

        /** What's playing: a sound URI, Checkin.SILENT, or null. For tests and diagnostics. */
        @Volatile
        var currentSound: String? = null
            private set

        /** Tests set a short pause here; otherwise it's the Settings choice (default 45 s). */
        @Volatile
        var testPauseMs: Long? = null

        /** How long the alarm stays quiet after the last tap. */
        fun pauseMs(context: Context): Long = testPauseMs ?: Store(context).pauseSeconds * 1000L

        /** How long the first ring takes to reach full volume. Tests shorten it. */
        @Volatile
        var testRampMs: Long? = null
        fun rampMs(): Long = (testRampMs ?: RAMP_MS).coerceAtLeast(1)
        private const val RAMP_MS = 30_000L
        private const val RAMP_STEP_MS = 250L
        const val RAMP_START = 0.03f

        /** The sound's current loudness, 0–1 of the alarm volume (for tests). */
        @Volatile
        var currentGain = 0f
            private set

        /** True while the alarm is active (its own notification is showing). */
        @Volatile
        var running = false
            private set

        /** Stop making noise after an hour; the check-in stays pending. */
        private const val MAX_RING_MS = 60 * 60 * 1000L
        private const val FRONT_CHECK_MS = 1_000L
        private const val VOLUME_FLOOR = 0.8
    }
}
