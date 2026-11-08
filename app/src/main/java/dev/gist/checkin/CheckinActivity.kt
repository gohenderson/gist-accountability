package dev.gist.checkin

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.time.format.FormatStyle
import java.time.format.DateTimeFormatter
import java.time.LocalTime
import android.view.Gravity
import android.util.TypedValue

/**
 * The alarm screen. When opened by an alarm ("forced") it shows over the lock screen,
 * ignores Back, and runs the check-in's steps in the order chosen on its page (see Steps).
 * The alarm stops only after the last step. If several check-ins are waiting, it moves
 * straight on to the next one.
 * When opened from history, it's a normal screen for filling in or fixing a past day's answers.
 */
class CheckinActivity : BaseActivity() {
    private sealed class Step {
        data class Math(val count: Int, val level: Int) : Step()
        object Scan : Step()
        object Survey : Step()
        object Dismiss : Step()
    }

    private lateinit var store: Store
    private lateinit var target: Pending
    private lateinit var checkin: Checkin
    private var forced = false
    private var early = false                  // resolving an upcoming ring ahead of time
    private var earlyRequested = false
    private var steps: MutableList<Step> = mutableListOf()
    private var stepIndex = 0
    private var solved = 0                     // math problems solved in the current step
    private var problem: MathGen.Problem? = null
    private var scanFallback = false           // "can't scan" was chosen
    private val answers = mutableMapOf<String, Boolean>()
    private var scanner: ScannerView? = null
    private val ui = Handler(Looper.getMainLooper())

    // Pause countdown (see showRinging / updateBar). Separate handler so showStep's
    // removeCallbacks doesn't stop it.
    private val ticks = Handler(Looper.getMainLooper())
    // Slideshow on the ringing screen; and the muted background video.
    private val slides = Handler(Looper.getMainLooper())
    private var video: MediaPlayer? = null
    private var showingRinging = false
    private var barLabel: TextView? = null
    private var barProgress: ProgressBar? = null
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            ticks.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        store.prunePending()

        val editId = intent.getStringExtra(EXTRA_CHECKIN)
        val editDay = intent.getStringExtra(EXTRA_DAY)
        val editTime = intent.getStringExtra(EXTRA_TIME) ?: ""
        earlyRequested = intent.getBooleanExtra(EXTRA_EARLY, false)
        val t = if (editId != null && editDay != null) Pending(editId, editDay, editTime) else store.pending.firstOrNull()
        if (t == null || !load(t)) {
            stopIfNothingPending()
            finish()
            return
        }

        // Pre-fill when editing a past day.
        if (!forced && !early) store.result(target.day, checkin.id, slot)?.answers?.let { answers.putAll(it) }
        savedInstanceState?.let { s ->
            stepIndex = s.getInt(STATE_STEP, 0).coerceIn(0, steps.size - 1)
            solved = s.getInt(STATE_SOLVED, 0)
            if (s.getBoolean(STATE_FALLBACK, false)) useScanFallback()
            answers.clear()
            s.getStringArrayList(STATE_YES)?.forEach { answers[it] = true }
            s.getStringArrayList(STATE_NO)?.forEach { answers[it] = false }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { if (!forced) finish() }
        }
        // The UI is built in onResume (so the camera restarts properly too).
    }

    override fun onResume() {
        super.onResume()
        if (!::checkin.isInitialized) return
        // You're looking at the check-in, so the "still waiting" reminder would only cover it.
        visible = true
        timesShown++
        if (forced) Notif.clearWaiting(this)
        showStep()
        if (forced) ticks.post(ticker)
    }

    override fun onPause() {
        stopBackground()
        stopScanner()
        ui.removeCallbacksAndMessages(null)
        ticks.removeCallbacksAndMessages(null)
        super.onPause()
    }

    // Any tap or key press while the alarm is paused restarts the quiet countdown.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) keepQuiet()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        keepQuiet()
        return super.dispatchKeyEvent(event)
    }

    private fun keepQuiet() {
        if (forced && AlarmService.isPaused) AlarmService.instance?.pauseFor()
    }

    /** The alarm is sounding right now (not paused) and this screen is the alarm. */
    private fun ringingNow() = forced && AlarmService.running && !AlarmService.isPaused

    /** The alarm is paused and the countdown bar should be shown. */
    private fun quietNow() = forced && AlarmService.running && AlarmService.isPaused

    private fun tick() {
        if (ringingNow() != showingRinging || (barLabel != null && !quietNow())) {
            showStep() // paused ↔ ringing changed (countdown ran out, or alarm stopped)
            return
        }
        updateBar()
    }

    private fun updateBar() {
        val label = barLabel ?: return
        val bar = barProgress ?: return
        val total = AlarmService.pauseMs.coerceAtLeast(1)
        val left = (AlarmService.pausedUntil - SystemClock.elapsedRealtime()).coerceIn(0, total)
        val secs = ((left + 999) / 1000).toInt()
        bar.progress = (left * 1000 / total).toInt()
        val warn = left <= 10_000
        bar.progressTintList = ColorStateList.valueOf(if (warn) colorBad else colorPrimary)
        label.setTextColor(if (warn) colorBad else colorText)
        label.text = if (warn) {
            "Alarm resumes in $secs s. Tap anything to keep it quiet."
        } else {
            "Alarm paused · $secs s. Each tap resets it."
        }
    }

    override fun onStop() {
        super.onStop()
        visible = false
        // Left without finishing, and the alarm is no longer ringing: bring the reminder back.
        if (forced && !isFinishing && !isChangingConfigurations && store.pending.isNotEmpty() && !AlarmService.running) {
            Notif.showWaiting(this)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!forced) super.onBackPressed() // no escaping the alarm
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, stepIndex)
        outState.putInt(STATE_SOLVED, solved)
        outState.putBoolean(STATE_FALLBACK, scanFallback)
        outState.putStringArrayList(STATE_YES, ArrayList(answers.filterValues { it }.keys))
        outState.putStringArrayList(STATE_NO, ArrayList(answers.filterValues { !it }.keys))
    }

    // ------------------------------------------------------------ flow

    /** The time ("HH:mm") these answers are saved under; the first time if unknown. */
    private val slot: String
        get() = target.time.ifEmpty { checkin.times.first().toString() }

    /** Point the screen at [t]. Returns false if its check-in no longer exists. */
    private fun load(t: Pending): Boolean {
        val c = store.checkin(t.checkinId) ?: return false
        target = t
        checkin = c
        forced = t in store.pending
        early = earlyRequested && !forced
        earlyRequested = false
        steps = buildSteps(c)
        stepIndex = 0
        solved = 0
        problem = null
        scanFallback = false
        if (forced) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        return true
    }

    private fun buildSteps(c: Checkin): MutableList<Step> {
        if (!forced && !early) return mutableListOf(Step.Survey) // editing history: questions only
        val list: MutableList<Step> = Steps.active(c).map { kind ->
            when (kind) {
                Steps.MATH -> Step.Math(c.mathCount, c.mathLevel)
                Steps.SCAN -> Step.Scan
                else -> Step.Survey
            }
        }.toMutableList()
        if (list.isEmpty()) list += Step.Dismiss
        return list
    }

    private fun useScanFallback() {
        val i = steps.indexOf(Step.Scan)
        if (i >= 0) steps[i] = Step.Math(FALLBACK_PROBLEMS, 4)
        scanFallback = true
    }

    private fun nextStep() {
        stepIndex++
        solved = 0
        problem = null
        if (stepIndex >= steps.size) complete() else showStep()
    }

    private fun complete() {
        ticks.removeCallbacksAndMessages(null) // no more countdown redraws while finishing
        if (checkin.questions.isEmpty() && (forced || early)) {
            store.markDone(target.day, checkin.id, slot, early)
        }
        if (early) {
            AlarmScheduler.schedule(this, checkin) // skip this ring; arm the one after
            toast("Done early. No alarm at ${Days.timeOf(slot)}.")
            finish()
            return
        }
        store.removePending(target)
        store.prunePending()
        val next = store.pending.firstOrNull()
        if (forced && next != null && load(next)) {
            answers.clear()
            showStep()
            ticks.post(ticker)
            return
        }
        stopIfNothingPending()
        finish()
    }

    private fun stopIfNothingPending() {
        if (store.pending.isEmpty()) {
            stopService(Intent(this, AlarmService::class.java))
            Notif.clearWaiting(this)
        }
    }

    // ------------------------------------------------------------ UI

    private fun showStep() {
        // Closing, or past the last step (a countdown tick can land right as we finish).
        if (isFinishing || stepIndex !in steps.indices) return
        stopScanner()
        stopBackground()
        ui.removeCallbacksAndMessages(null)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        barLabel = null
        barProgress = null

        showingRinging = ringingNow()
        if (showingRinging) {
            showRinging()
            return
        }

        val col = column()
        val title = when {
            forced -> checkin.name
            early -> "${checkin.name} · early"
            else -> "Edit: ${checkin.name}"
        }
        col.add(label(title, 28f, bold = true))
        val sub = buildString {
            append(Days.pretty(target.day))
            if (checkin.times.size > 1 || !forced) append(" · ${Days.timeOf(slot)}")
        }
        col.add(muted(sub, 15f), dp(2))
        if (steps.size > 1) col.add(stepDots(), dp(12))
        if (early) {
            col.add(chip("Done now, this time's alarm won't ring", colorOf(R.color.on_primary_container), colorOf(R.color.primary_container)), dp(12))
        }

        when (val step = steps[stepIndex]) {
            is Step.Math -> mathUi(col, step)
            is Step.Scan -> scanUi(col)
            is Step.Survey -> surveyUi(col)
            is Step.Dismiss -> col.add(primaryButton("Dismiss") { nextStep() }, dp(32))
        }

        setScreen(col, withBar = quietNow())
    }

    /** "Step 2 of 3" with a dot per step. */
    private fun stepDots(): View {
        val r = row()
        steps.indices.forEach { i ->
            val dot = View(this).apply {
                background = rounded(if (i <= stepIndex) colorPrimary else colorOf(R.color.outline), 4)
            }
            r.addView(dot, LinearLayout.LayoutParams(dp(if (i == stepIndex) 28 else 14), dp(6)).apply { marginEnd = dp(6) })
        }
        r.addWrapped(muted("Step ${stepIndex + 1} of ${steps.size}", 13f), dp(6))
        return r
    }

    /** The alarm is sounding: big clock, and one button pauses it and shows the steps. */
    private fun showRinging() {
        val bgView = backgroundView(checkin)
        val fg = if (bgView != null) Color.WHITE else colorText
        val soft = if (bgView != null) 0xCCFFFFFF.toInt() else colorMuted

        val col = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.add(label(LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)), 64f, bold = true, color = fg).apply {
            gravity = Gravity.CENTER
        }, dp(48))
        col.add(label(checkin.name, 26f, bold = true, color = fg).apply { gravity = Gravity.CENTER }, dp(4))
        col.add(label("Alarm ringing · ${CheckinText.steps(checkin)}", 15f, color = soft).apply { gravity = Gravity.CENTER }, dp(6))
        if (checkin.background.caption.isNotBlank()) {
            col.add(label(checkin.background.caption, 22f, bold = true, color = fg).apply {
                gravity = Gravity.CENTER
                tag = "ringing-caption"
                if (bgView != null) setShadowLayer(8f, 0f, 2f, Color.BLACK)
            }, dp(28))
        }
        if (stepIndex > 0) {
            col.add(label("Step ${stepIndex + 1} of ${steps.size} (your progress is kept)", 14f, color = soft).apply {
                gravity = Gravity.CENTER
            }, dp(4))
        }
        col.add(
            primaryButton("Start check-in") {
                AlarmService.instance?.pauseFor()
                showStep()
            }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                minHeight = dp(88)
            },
            dp(56)
        )
        col.add(
            label("Pauses the alarm for ${AlarmService.pauseMs / 1000} seconds. Every tap restarts the countdown; " +
                "if it runs out, the alarm rings again.", 13f, color = soft).apply { gravity = Gravity.CENTER },
            dp(14)
        )
        setScreen(col, withBar = false, background = bgView)
    }

    /** Scrolling content, with the countdown bar fixed above it while the alarm is paused. */
    private fun setScreen(content: View, withBar: Boolean, background: View? = null) {
        val sv = ScrollView(this).apply {
            clipToPadding = false
            setPadding(dp(20), dp(20), dp(20), dp(28))
            addView(content)
        }
        val root = column()
        if (withBar) root.addView(pauseBar())
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        applySystemBarInsets(root, 0)
        if (background == null) {
            setContentView(root)
        } else {
            // Photo or video behind everything, darkened so white text stays readable.
            val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
            frame.addView(background, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(View(this).apply { setBackgroundColor(0x99000000.toInt()) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setContentView(frame)
        }
        updateBar()
    }

    // ------------------------------------------------------------ background

    /** The check-in's photo / slideshow / video, or null for a plain screen. */
    private fun backgroundView(c: Checkin): View? {
        val bg = c.background
        return when (bg.kind) {
            Background.PHOTO, Background.SLIDESHOW -> {
                val files = Backgrounds.photos(this, c.id).let { if (bg.kind == Background.PHOTO) it.take(1) else it }
                val first = files.firstOrNull()?.let { Backgrounds.load(it) } ?: return null
                val iv = ImageView(this).apply {
                    setImageBitmap(first)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    tag = "ringing-background"
                    contentDescription = "Background photo 1 of ${files.size}"
                }
                if (files.size > 1) startSlideshow(iv, files, bg.intervalSec * 1000L)
                iv
            }
            Background.VIDEO -> {
                val file = Backgrounds.videoFile(this, c.id)
                if (!file.exists()) null else videoView(file)
            }
            else -> null
        }
    }

    private fun startSlideshow(iv: ImageView, files: List<java.io.File>, everyMs: Long) {
        var index = 0
        val next = object : Runnable {
            override fun run() {
                index = (index + 1) % files.size
                val bmp = Backgrounds.load(files[index])
                if (bmp != null) {
                    iv.animate().alpha(0f).setDuration(250).withEndAction {
                        iv.setImageBitmap(bmp)
                        iv.contentDescription = "Background photo ${index + 1} of ${files.size}"
                        iv.animate().alpha(1f).setDuration(250).start()
                    }.start()
                }
                slides.postDelayed(this, everyMs)
            }
        }
        slides.postDelayed(next, everyMs)
    }

    /** Muted, looping, cropped to fill the screen. */
    private fun videoView(file: java.io.File): View {
        val tv = TextureView(this).apply { tag = "ringing-video" }
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                releaseVideo()
                val mp = MediaPlayer()
                try {
                    mp.setDataSource(file.path)
                    mp.setSurface(Surface(st))
                    mp.isLooping = true
                    mp.setVolume(0f, 0f)
                    mp.setOnVideoSizeChangedListener { _, vw, vh -> cropToFill(tv, vw, vh) }
                    mp.setOnPreparedListener {
                        it.start()
                    }
                    mp.setOnErrorListener { _, _, _ -> true }
                    mp.prepareAsync()
                    video = mp
                } catch (e: Exception) {
                    mp.release()
                }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                video?.let { cropToFill(tv, it.videoWidth, it.videoHeight) }
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releaseVideo()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        return tv
    }

    /** Is this screen's background video playing? (For tests.) */
    val videoIsPlaying: Boolean
        get() = try { video?.isPlaying == true } catch (e: IllegalStateException) { false }

    private fun cropToFill(tv: TextureView, vw: Int, vh: Int) {
        val w = tv.width.toFloat()
        val h = tv.height.toFloat()
        if (vw <= 0 || vh <= 0 || w <= 0f || h <= 0f) return
        val scale = maxOf(w / vw, h / vh)
        tv.setTransform(Matrix().apply { setScale(vw * scale / w, vh * scale / h, w / 2, h / 2) })
    }

    private fun releaseVideo() {
        val mp = video ?: return
        video = null
        try { mp.stop() } catch (e: Exception) {}
        mp.release()
    }

    private fun stopBackground() {
        slides.removeCallbacksAndMessages(null)
        releaseVideo()
    }

    private fun pauseBar(): View {
        val box = column().apply {
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setBackgroundColor(colorOf(R.color.surface))
            elevation = dp(3).toFloat()
            tag = "pause-bar"
        }
        val text = label("", 14f, bold = true)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            progressBackgroundTintList = ColorStateList.valueOf(colorOf(R.color.surface_variant))
        }
        box.add(text)
        box.add(bar, dp(8))
        barLabel = text
        barProgress = bar
        return box
    }

    private fun mathUi(col: LinearLayout, step: Step.Math) {
        val p = problem ?: MathGen.make(step.level).also { problem = it }
        val box = card()
        box.add(muted("Problem ${solved + 1} of ${step.count}", 14f))
        box.add(label("${p.text} = ?", 34f, bold = true).apply { tag = "math-problem" }, dp(10))

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(colorText)
            hint = "Answer"
            imeOptions = EditorInfo.IME_ACTION_DONE
            tag = "math-answer"
        }
        val check = {
            val entered = input.text.toString().trim()
            if (entered.isNotEmpty()) {
                if (entered.toLongOrNull() == p.answer) {
                    solved++
                    problem = null
                    if (solved >= step.count) nextStep() else showStep()
                } else {
                    toast("Wrong. Here's a new one.")
                    problem = MathGen.make(step.level)
                    showStep()
                }
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = keepQuiet()
            override fun afterTextChanged(s: Editable?) {}
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { check(); true } else false
        }
        box.add(input, dp(8))
        col.add(box, dp(20))
        col.add(primaryButton("Check") { check() }, dp(16))

        input.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun scanUi(col: LinearLayout) {
        val where = checkin.scanLabel.ifEmpty { "your registered code" }
        col.add(label("Go scan: $where", 22f, bold = true), dp(24))
        val status: TextView = muted("Point the camera at the code.", 15f)
        col.add(status, dp(4))

        val sv = ScannerView(this).apply {
            background = rounded(colorOf(R.color.surface_variant), 20)
            clipToOutline = true
        }
        scanner = sv
        col.add(sv, dp(14))

        // Early: the code must really be scanned. Not near it? Go back and use Skip instead.
        val fallback: Button? = if (early) null else outlineButton("Can't scan? Solve $FALLBACK_PROBLEMS very hard problems instead") {
            useScanFallback()
            showStep()
        }.apply {
            visibility = View.GONE
            tag = "scan-fallback"
        }
        if (fallback != null) {
            col.add(fallback, dp(14))
            ui.postDelayed({ fallback.visibility = View.VISIBLE }, FALLBACK_DELAY_MS)
        } else {
            col.add(muted("Doing it early means really scanning it. Not near it? Go back and use Skip.", 13f), dp(14))
        }

        sv.start(
            onCode = { code ->
                if (code == checkin.scanCode) {
                    toast("Got it.")
                    nextStep()
                } else {
                    status.text = "That's not the registered code. Keep looking."
                    ui.postDelayed({ scanner?.rearm() }, 1500)
                }
            },
            onError = { msg ->
                status.text = msg
                fallback?.visibility = View.VISIBLE
            }
        )
    }

    private fun surveyUi(col: LinearLayout) {
        val qs = checkin.questions
        if (forced) col.add(muted("Be honest. This is just for you.", 14f), dp(10))
        lateinit var submit: Button
        fun refresh() {
            val left = qs.count { !answers.containsKey(it.id) }
            submit.isEnabled = left == 0
            submit.text = if (left == 0) "Submit" else "$left left to answer"
        }

        for (q in qs) {
            val box = card()
            box.add(label(q.text, 18f))
            val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
            val yes = choiceTile("Yes").apply { id = View.generateViewId(); tag = "q:${q.id}:yes" }
            val no = choiceTile("No").apply { id = View.generateViewId(); tag = "q:${q.id}:no" }
            group.addView(yes, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            group.addView(no, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
            answers[q.id]?.let { (if (it) yes else no).isChecked = true }
            group.setOnCheckedChangeListener { _, checkedId ->
                answers[q.id] = checkedId == yes.id
                refresh()
            }
            box.add(group, dp(12))
            col.add(box, dp(12))
        }

        submit = primaryButton("Submit") {
            val clean = qs.associate { it.id to answers.getValue(it.id) }
            store.saveAnswers(target.day, checkin.id, slot, clean, early)
            val good = qs.count { it.isGood(clean.getValue(it.id)) }
            toast("Logged: $good of ${qs.size} on track")
            nextStep()
        }
        col.add(submit, dp(24))
        refresh()
    }

    private fun stopScanner() {
        scanner?.stop()
        scanner = null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** True while an alarm/check-in screen is in front (the waiting reminder is then suppressed). */
        @Volatile
        var visible = false
            private set

        /** How many times the screen has come to the front (for tests). */
        @Volatile
        var timesShown = 0
            private set


        private const val EXTRA_CHECKIN = "checkin"
        private const val EXTRA_DAY = "day"
        private const val EXTRA_TIME = "time"
        private const val EXTRA_EARLY = "early"
        private const val STATE_STEP = "step"
        private const val STATE_SOLVED = "solved"
        private const val STATE_FALLBACK = "fallback"
        private const val STATE_YES = "yes"
        private const val STATE_NO = "no"
        private const val FALLBACK_PROBLEMS = 5
        private const val FALLBACK_DELAY_MS = 2 * 60 * 1000L

        /** Opens whichever check-in is waiting. */
        fun alarmIntent(context: Context): Intent =
            Intent(context, CheckinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Resolves an upcoming ring now (all steps, no alarm); that ring then stays quiet. */
        fun earlyIntent(context: Context, checkinId: String, day: String, time: String): Intent =
            editIntent(context, checkinId, day, time).putExtra(EXTRA_EARLY, true)

        /** Opens a check-in to fill in / fix a specific day's answers. */
        fun editIntent(context: Context, checkinId: String, day: String, time: String = ""): Intent =
            Intent(context, CheckinActivity::class.java)
                .putExtra(EXTRA_CHECKIN, checkinId)
                .putExtra(EXTRA_DAY, day)
                .putExtra(EXTRA_TIME, time)
    }
}
