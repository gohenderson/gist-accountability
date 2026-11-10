package dev.gist.checkin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Plays a voice check-in (Checkin.VOICE): speaks its text, or plays its recorded clip,
 * through the headphones, like a navigation app does: music or a podcast dips while it
 * talks, then comes back. No alarm, no screen, nothing to tap. With "only through
 * headphones" (the default) it stays quiet when no headphones are connected, so it never
 * blurts out of a pocket.
 */
class VoiceService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>() // check-in ids waiting to play
    private var playing = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val giveUp = Runnable { finishOne() } // a clip or voice that never reports back

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        intent?.getStringExtra(EXTRA_CHECKIN)?.let { queue.addLast(it) }
        if (!playing) next()
        return START_NOT_STICKY
    }

    private fun next() {
        val id = queue.removeFirstOrNull()
        if (id == null) {
            stopSelf()
            return
        }
        val c = Store(this).checkin(id)
        if (c == null) {
            next()
            return
        }
        if (c.voiceHeadphonesOnly && !headphonesConnected(this)) {
            lastSkipped = c.id
            next()
            return
        }
        playing = true
        val clip = Backgrounds.voiceFile(this, c.id)
        val useClip = c.voiceSource == Checkin.VOICE_CLIP && Backgrounds.hasVoice(this, c.id)
        lastPlayed = if (useClip) CLIP else spokenText(c)
        plays++
        requestFocus()
        handler.postDelayed(giveUp, MAX_PLAY_MS)
        if (useClip) playClip(clip) else speak(spokenText(c))
    }

    private fun playClip(file: java.io.File) {
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { finishOne() }
            mp.setOnErrorListener { _, _, _ -> finishOne(); true }
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            mp.release()
            finishOne()
        }
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine != null && ttsReady) {
            say(engine, text)
            return
        }
        if (engine != null) return // still starting; it speaks when ready
        tts = TextToSpeech(this) { status ->
            val t = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                finishOne()
                return@TextToSpeech
            }
            ttsReady = true
            t.setAudioAttributes(attrs)
            t.language = Locale.getDefault()
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { handler.post { finishOne() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { handler.post { finishOne() } }
            })
            say(t, text)
        }
    }

    private fun say(t: TextToSpeech, text: String) {
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gist-voice") != TextToSpeech.SUCCESS) finishOne()
    }

    /** One finished (or failed): let the music come back up, then play the next or stop. */
    private fun finishOne() {
        if (!playing) return
        playing = false
        handler.removeCallbacks(giveUp)
        player?.let { try { it.release() } catch (e: Exception) {} }
        player = null
        abandonFocus()
        next()
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        focus = req
        try { getSystemService(AudioManager::class.java).requestAudioFocus(req) } catch (e: Exception) {}
    }

    private fun abandonFocus() {
        focus?.let { try { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) } catch (e: Exception) {} }
        focus = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.let { try { it.release() } catch (e: Exception) {} }
        player = null
        tts?.shutdown()
        tts = null
        abandonFocus()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CHECKIN = "checkinId"
        private const val ID = 5
        private const val CHANNEL = "voice"
        private const val MAX_PLAY_MS = 60_000L
        const val CLIP = "(clip)"

        /** What it last played: the spoken text, or [CLIP]. For tests. */
        @Volatile
        var lastPlayed: String? = null

        /** The last check-in kept quiet because no headphones were connected. For tests. */
        @Volatile
        var lastSkipped: String? = null

        /** How many voices it has played. For tests. */
        @Volatile
        var plays = 0

        /** Tests (emulators have no headphones) set this. */
        @Volatile
        var testHeadphones: Boolean? = null

        /** Plays [c] now. */
        fun play(context: Context, c: Checkin) {
            val i = Intent(context, VoiceService::class.java).putExtra(EXTRA_CHECKIN, c.id)
            try {
                context.startForegroundService(i)
            } catch (e: Exception) {
                // Not allowed from the background just now (alarms and the step watcher may).
            }
        }

        /** The text it says: the check-in's line, or its name if there's none. */
        fun spokenText(c: Checkin): String = c.voiceText.trim().ifEmpty { c.name }

        /** Wired, USB, Bluetooth or hearing-aid audio is connected (not the phone's speaker). */
        fun headphonesConnected(context: Context): Boolean {
            testHeadphones?.let { return it }
            val types = mutableSetOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
            )
            if (android.os.Build.VERSION.SDK_INT >= 31) types += AudioDeviceInfo.TYPE_BLE_HEADSET
            return context.getSystemService(AudioManager::class.java)
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
        }

        private fun notification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Voice check-ins", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown for a moment while a voice check-in plays"
                    setShowBadge(false)
                }
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Voice check-in")
                .setContentText("Playing through your headphones")
                .build()
        }
    }
}
