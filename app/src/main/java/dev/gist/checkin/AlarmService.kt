package dev.gist.checkin

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.ceil

/**
 * Rings (sound + vibration) until SurveyActivity stops it after the questions are answered.
 * Keeps the alarm volume from being turned down below [VOLUME_FLOOR] while ringing.
 */
class AlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audio: AudioManager? = null
    private var originalVolume = -1
    private var floorVolume = 1
    private var ringing = false

    private val volumeGuard = object : Runnable {
        override fun run() {
            enforceVolume()
            handler.postDelayed(this, 1000)
        }
    }

    private val giveUp = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            Notif.ID_ALARM,
            Notif.alarm(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        if (!ringing) {
            ringing = true
            startRinging()
            handler.postDelayed(giveUp, MAX_RING_MS)
        }
        // Works when the screen is on and unlocked on some devices; otherwise the
        // notification's full-screen intent opens the survey.
        try {
            startActivity(SurveyActivity.alarmIntent(this))
        } catch (e: Exception) {
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startRinging() {
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

        // Use the phone's default alarm sound; fall back to ringtone, then notification sound.
        for (type in intArrayOf(
            RingtoneManager.TYPE_ALARM,
            RingtoneManager.TYPE_RINGTONE,
            RingtoneManager.TYPE_NOTIFICATION
        )) {
            val uri = RingtoneManager.getDefaultUri(type) ?: continue
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
                mp.prepare()
                mp.start()
                player = mp
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
        vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 700), 0), attrs)
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
        /** Stop making noise after an hour; the check-in stays pending. */
        private const val MAX_RING_MS = 60 * 60 * 1000L
        private const val VOLUME_FLOOR = 0.8
    }
}
