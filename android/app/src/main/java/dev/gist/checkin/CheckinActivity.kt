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
        data class Scan(val code: String, val label: String) : Step()
        data class Survey(val questions: List<Question>) : Step()
        data class PickTasks(val count: Int) : Step()
        object Dismiss : Step()
    }

    private lateinit var store: Store

    /**
     * The profile whose check-in is on screen. An alarm from another profile is answered
     * here, in its own profile, without switching the app to it.
     */
    private var profile: String? = null
    override val profileId: String get() = profile ?: super.profileId
    private lateinit var target: Pending
    private lateinit var checkin: Checkin
    private var forced = false
    private var early = false                  // resolving an upcoming ring ahead of time
    private var earlyRequested = false
    private var steps: MutableList<Step> = mutableListOf()
    private var stepIndex = 0
    private var solved = 0                     // math problems solved in the current step
    private var problem: MathGen.Problem? = null
    private val fallbacks = mutableSetOf<Int>() // scan steps swapped for math ("can't scan")
    private val answers = mutableMapOf<String, Boolean>()
    /** Typed answers (number and text questions), as typed. */
    private val values = mutableMapOf<String, String>()
    /** Backlog tasks ticked on a pick-tasks step, in the order ticked. */
    private val picked = linkedSetOf<String>()
    private var scanner: ScannerView? = null
    private val ui = Handler(Looper.getMainLooper())

    // Pause countdown (see showRinging / updateBar). Separate handler so showStep's
    // removeCallbacks doesn't stop it.
    private val ticks = Handler(Looper.getMainLooper())
    // Slideshow on the ringing screen; and the muted background video.
    private val slides = Handler(Looper.getMainLooper())
    private var video: MediaPlayer? = null
    private var showingRinging = false
    /** When the steps first showed (Start check-in), epoch ms; for how long it took. */
    private var startedAt: Long? = null
    private var seenChanges = 0 // SoftAlarm.changes when last checked
    /** Showing "Task done" (with Create follow-up): stays put until you choose. */
    private var taskDoneShown = false
    private var againShown = false
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
        val editId = intent.getStringExtra(EXTRA_CHECKIN)
        // Ringing: a profile with an alarm waiting (the one asked for, if it still has one).
        profile = if (editId == null) Profiles.withAlarm(this, intent.getStringExtra(Profiles.EXTRA)) else null
        store = Store(this)
        store.prunePending()

        val editDay = intent.getStringExtra(EXTRA_DAY)
        val editTime = intent.getStringExtra(EXTRA_TIME) ?: ""
        earlyRequested = intent.getBooleanExtra(EXTRA_EARLY, false)
        val t = if (editId != null && editDay != null) Pending(editId, editDay, editTime) else store.alarmPending.firstOrNull()
        if (t == null || !load(t)) {
            stopIfNothingPending()
            finish()
            return
        }

        // Pre-fill when editing a past day.
        if (!forced && !early) store.result(target.day, checkin.id, slot)?.let {
            answers.putAll(it.answers)
            values.putAll(it.values)
        }
        savedInstanceState?.let { s ->
            stepIndex = s.getInt(STATE_STEP, 0).coerceIn(0, steps.size - 1)
            solved = s.getInt(STATE_SOLVED, 0)
            s.getIntArray(STATE_FALLBACK)?.forEach { useScanFallback(it) }
            answers.clear()
            s.getStringArrayList(STATE_YES)?.forEach { answers[it] = true }
            s.getStringArrayList(STATE_NO)?.forEach { answers[it] = false }
            values.clear()
            s.getBundle(STATE_VALUES)?.let { b -> b.keySet().forEach { k -> b.getString(k)?.let { values[k] = it } } }
            startedAt = s.getLong(STATE_STARTED, 0L).takeIf { it > 0 }
            picked.clear()
            s.getStringArrayList(STATE_PICKED)?.let { picked.addAll(it) }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                when {
                    againShown || !forced -> finish()
                    target.practice -> endTest()
                    checkin.soft || checkin.quiet -> finish() // can be left: Snooze / Dismiss, or it waits quietly
                }
            }
        }
        // The UI is built in onResume (so the camera restarts properly too).
    }

    override fun onResume() {
        super.onResume()
        if (!::checkin.isInitialized || taskDoneShown || againShown) return
        // Snoozed or dismissed from the notification while this screen was away: move on.
        if (forced && target !in store.pending && !moveOn()) return
        seenChanges = SoftAlarm.changes
        // You're looking at the check-in, so the "still waiting" reminder would only cover it.
        visible = true
        timesShown++
        if (forced) Notif.clearWaiting(this)
        showStep()
        if (forced) ticks.post(ticker)
        if (forced) ticks.post(devicesTick)
    }

    /**
     * While an alarm is up, check your other devices now and then: answered on one of them,
     * it stops here (SoftAlarm.closedElsewhere; the ticker then moves on).
     */
    private val devicesTick = object : Runnable {
        override fun run() {
            Devices.syncInBackground(this@CheckinActivity)
            ticks.postDelayed(this, DEVICES_EVERY_MS)
        }
    }

    override fun onPause() {
        stopBackground()
        stopScanner()
        ui.removeCallbacksAndMessages(null)
        ticks.removeCallbacksAndMessages(null)
        Devices.syncInBackground(this) // what you answered, to your other devices
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
        if (seenChanges != SoftAlarm.changes) {
            seenChanges = SoftAlarm.changes
            if (target !in store.pending) {
                if (moveOn()) showStep()
                return
            }
        }
        if (ringingNow() != showingRinging || (barLabel != null && !quietNow())) {
            showStep() // paused ↔ ringing changed (countdown ran out, or alarm stopped)
            return
        }
        updateBar()
    }

    private fun updateBar() {
        val label = barLabel ?: return
        val bar = barProgress ?: return
        val total = AlarmService.pauseMs(this).coerceAtLeast(1)
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
        if (forced && !isFinishing && !isChangingConfigurations && store.alarmPending.isNotEmpty() && !AlarmService.running) {
            Notif.showWaiting(this)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            againShown -> finish() // checked in: Back is "Done"
            !forced -> super.onBackPressed()
            target.practice -> endTest() // a test can be left at any point
            checkin.soft -> super.onBackPressed() // so can a soft alarm (it keeps ringing until snoozed or dismissed)
            checkin.quiet -> super.onBackPressed() // and a "no alarm" one: it waits on the start page
            else -> {} // no escaping the alarm
        }
    }

    /** Stop a test ring part-way: nothing was logged, so just stop. */
    private fun endTest() {
        if (!target.practice) return
        ticks.removeCallbacksAndMessages(null)
        store.removePending(target)
        val next = nextWaiting()
        if (next != null && load(next)) {
            answers.clear()
            AlarmService.refreshNotification(this)
            showStep()
            ticks.post(ticker)
            return
        }
        stopIfNothingPending()
        finish()
    }

    /** A soft alarm: snooze it, or dismiss it without the steps. */
    private fun snoozeSoft() {
        val at = SoftAlarm.snooze(this, target) ?: return
        seenChanges = SoftAlarm.changes
        toast("Snoozed until ${CheckinText.at(at)}")
        if (moveOn()) showStep()
    }

    private fun dismissSoft() {
        SoftAlarm.dismiss(this, target)
        seenChanges = SoftAlarm.changes
        // Dismissing a soft task completes it: offer a follow-up first.
        if (checkin.isTask && !target.practice && store.checkin(checkin.id)?.taskDone == true) {
            showTaskDone { if (moveOn()) showStep() }
            return
        }
        if (moveOn()) showStep()
    }

    /**
     * A task was just done: say so, and offer to create a follow-up (backlog or today), every
     * time; [then] carries on (the next alarm, or closing).
     */
    private fun showTaskDone(then: () -> Unit) {
        taskDoneShown = true
        ticks.removeCallbacksAndMessages(null)
        stopScanner()
        stopBackground()
        val col = column()
        col.add(label("✓ Task done", 30f, bold = true, color = colorGood).apply { tag = "task-done-title" })
        col.add(label(checkin.name, 20f, bold = true), dp(6))
        col.add(muted("Is there a next step? A follow-up starts as a copy of this task, so you only have to change what's new.", 15f), dp(16))
        fun carryOn() {
            taskDoneShown = false
            then()
        }
        col.add(primaryButton("Create follow-up") {
            FollowUp.ask(this, store.checkin(checkin.id) ?: checkin) { carryOn() }
        }.apply { tag = "create-follow-up" }, dp(20))
        col.add(outlineButton("Close") { carryOn() }.apply { tag = "task-done-close" }, dp(10))
        setScreen(col, withBar = false)
    }

    /**
     * The check-in on screen is no longer waiting: show the next one that is (true), or close
     * (false). SoftAlarm has already stopped the ringing if nothing is left.
     */
    private fun moveOn(): Boolean {
        store.prunePending()
        val next = nextWaiting()
        if (next != null && load(next)) {
            answers.clear()
            return true
        }
        ticks.removeCallbacksAndMessages(null)
        finish()
        return false
    }

    private fun snoozeButton(): Button = outlineButton("Snooze ${checkin.snoozeMinutes} min") { snoozeSoft() }.apply { tag = "snooze" }

    private fun dismissButton(): Button = textButton("Dismiss without the check-in") { dismissSoft() }.apply { tag = "dismiss-alarm" }

    /**
     * Put the keyboard away before closing. A math step keeps it up, and closing the alarm screen
     * over the lock screen with the keyboard still showing could leave the next screen, after
     * unlocking, drawn but not taking touches.
     */
    override fun finish() {
        try {
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            val focus = currentFocus ?: window.decorView
            imm.hideSoftInputFromWindow(focus.windowToken, 0)
            focus.clearFocus()
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        } catch (e: Exception) {
        }
        super.finish()
    }

    private fun endTestButton(): View = outlineButton("End test") { endTest() }.apply { tag = "end-test" }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, stepIndex)
        outState.putInt(STATE_SOLVED, solved)
        outState.putIntArray(STATE_FALLBACK, fallbacks.toIntArray())
        outState.putStringArrayList(STATE_YES, ArrayList(answers.filterValues { it }.keys))
        outState.putStringArrayList(STATE_NO, ArrayList(answers.filterValues { !it }.keys))
        outState.putBundle(STATE_VALUES, Bundle().apply { values.forEach { (k, v) -> putString(k, v) } })
        startedAt?.let { outState.putLong(STATE_STARTED, it) }
        outState.putStringArrayList(STATE_PICKED, ArrayList(picked))
    }

    // ------------------------------------------------------------ flow

    /** The time ("HH:mm") these answers are saved under; the first time if unknown. */
    private val slot: String
        get() = target.time.ifEmpty { AlarmScheduler.nearestTime(checkin).toString() }

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
        startedAt = null
        picked.clear()
        solved = 0
        problem = null
        fallbacks.clear()
        if (forced) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        return true
    }

    private fun buildSteps(c: Checkin): MutableList<Step> {
        if (!forced && !early) return mutableListOf(Step.Survey(c.questions)) // editing history: questions only
        // A pick-tasks step with enough tasks already active is satisfied: left out.
        val list: MutableList<Step> = Steps.active(c).mapNotNull { st ->
            when (st.kind) {
                Steps.MATH -> Step.Math(st.mathCount, st.mathLevel)
                Steps.SCAN -> Step.Scan(st.scanCode!!, st.scanLabel)
                Steps.PICK_TASKS -> Step.PickTasks(st.pickCount).takeIf { store.activeTasks.size < st.pickCount }
                else -> Step.Survey(st.questions)
            }
        }.toMutableList()
        if (list.isEmpty()) list += Step.Dismiss
        return list
    }

    /**
     * How long this check-in took: from its first ring to Start check-in, and from then to now.
     * Only for real check-ins that count (not tests, soft ones, or fixing a day in History).
     */
    private fun took(): Took? {
        if (target.practice || !checkin.counted || !(forced || early)) return null
        val now = System.currentTimeMillis()
        val begun = startedAt ?: now
        val rang = if (early) null else store.result(target.day, checkin.id, slot)?.rangAt
        return Took(rang?.let { ((begun - it) / 1000).coerceAtLeast(0).toInt() }, ((now - begun) / 1000).coerceAtLeast(0).toInt())
    }

    /** "Can't scan?": this scan step becomes very hard math. */
    private fun useScanFallback(i: Int = stepIndex) {
        if (i in steps.indices && steps[i] is Step.Scan) steps[i] = Step.Math(FALLBACK_PROBLEMS, 4)
        fallbacks += i
    }

    private fun nextStep() {
        stepIndex++
        solved = 0
        problem = null
        if (stepIndex >= steps.size) complete() else showStep()
    }

    private fun complete() {
        ticks.removeCallbacksAndMessages(null) // no more countdown redraws while finishing
        if (checkin.questions.isEmpty() && (forced || early) && !target.practice) {
            store.markDone(target.day, checkin.id, slot, early, took())
        }
        // A task done (here or by its answers): stop its alarm and any place / run watching,
        // and offer a follow-up before carrying on.
        if (checkin.isTask && !target.practice && store.checkin(checkin.id)?.taskDone == true) {
            AlarmScheduler.schedule(this, store.checkin(checkin.id)!!)
            if (!early) { // it's done: no more ringing for it while the screen asks
                store.removePending(target)
                Notif.clearQuiet(this, checkin.id)
                store.prunePending()
                stopIfNothingPending()
            }
            showTaskDone { finishUp() }
            return
        }
        finishUp()
    }

    /** After the steps (and any task-done screen): the next alarm, or close. */
    private fun finishUp() {
        if (early) {
            AlarmScheduler.schedule(this, store.checkin(checkin.id) ?: checkin) // skip this ring; arm the one after (a done task: none)
            if (!checkin.isTask) toast("Done early. No alarm at ${Days.timeOf(slot)}.")
            finish()
            return
        }
        // A ring from a one-off time ("Ring in…", "Ring again in…"): offer the next one, once
        // nothing else is waiting. In this ring's profile (the next alarm can be another's).
        val offerAgain = forced && !target.practice && !checkin.isTask && QuickTime.wasOneOff(checkin, slot)
        val againIn = Profiles.scoped(this, profileId)
        val again = checkin
        store.removePending(target)
        Notif.clearQuiet(this, checkin.id)
        store.prunePending()
        val next = if (forced) nextWaiting() else null
        if (next != null && load(next)) {
            answers.clear()
            AlarmService.refreshNotification(this) // Snooze / Dismiss buttons if the next one is soft
            showStep()
            ticks.post(ticker)
            return
        }
        stopIfNothingPending()
        if (offerAgain) {
            showAgain(againIn, again)
            return
        }
        finish()
    }

    /**
     * Checked in, from a one-off time: ring again in a while? The last interval picked is first
     * (30 minutes to start with); Done closes.
     */
    private fun showAgain(ctx: Context, c: Checkin) {
        againShown = true
        ticks.removeCallbacksAndMessages(null)
        stopScanner()
        stopBackground()
        val last = Store(ctx).quickMinutes
        fun ringIn(m: Int) {
            val t = QuickTime.add(ctx, c.id, m)
            Toast.makeText(this, QuickTime.said(m, t), Toast.LENGTH_LONG).show()
            finish()
        }
        val col = column()
        col.add(label("✓ Checked in", 30f, bold = true, color = colorGood).apply { tag = "again-title" })
        col.add(label(c.name, 20f, bold = true), dp(6))
        col.add(muted("Ring again?", 15f), dp(16))
        col.add(primaryButton("Again in ${QuickTime.text(last)}") { ringIn(last) }.apply { tag = "again:$last" }, dp(12))
        val others = QuickTime.CHOICES.filter { it != last && it >= 10 }.take(4)
        val r = row()
        others.forEachIndexed { k, m ->
            r.addWeighted(outlineButton(QuickTime.text(m)) { ringIn(m) }.apply { tag = "again:$m" }, startMargin = if (k > 0) dp(6) else 0)
        }
        col.add(r, dp(8))
        col.add(outlineButton("Other…") { QuickTime.askOther(this) { ringIn(it) } }.apply { tag = "again-other" }, dp(8))
        col.add(textButton("Done") { finish() }.apply { tag = "again-close" }, dp(12))
        setScreen(col, withBar = false)
    }

    /**
     * The next alarm waiting: this profile's, else another profile's (the screen moves to
     * that profile, so its answers are saved there).
     */
    private fun nextWaiting(): Pending? {
        store.alarmPending.firstOrNull()?.let { return it }
        val other = Profiles.withAlarm(this, profileId) ?: return null
        profile = other
        store = Store(this)
        store.prunePending()
        return store.alarmPending.firstOrNull()
    }

    private fun stopIfNothingPending() {
        if (Profiles.withAlarm(this) == null) { // no profile has one waiting
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

        if (startedAt == null) startedAt = System.currentTimeMillis() // Start check-in tapped (or no ringing screen)

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
        if (target.practice) {
            col.add(chip("Test ring · nothing is logged", colorOf(R.color.on_primary_container), colorOf(R.color.primary_container)).apply {
                tag = "practice-chip"
            }, dp(12))
            col.add(endTestButton(), dp(8))
        }
        if (forced && checkin.soft && steps[stepIndex] !is Step.Dismiss) {
            // Soft: the steps are optional.
            val r = row().apply { gravity = Gravity.CENTER_VERTICAL }
            r.addWeighted(muted("Soft alarm · the steps are optional", 13f))
            r.addWrapped(textButton("Snooze") { snoozeSoft() }.apply { tag = "snooze" })
            r.addWrapped(textButton("Dismiss") { dismissSoft() }.apply { tag = "dismiss-alarm" }, dp(4))
            col.add(r, dp(10))
        }
        if (early) {
            col.add(chip("Done now, this time's alarm won't ring", colorOf(R.color.on_primary_container), colorOf(R.color.primary_container)), dp(12))
        }

        when (val step = steps[stepIndex]) {
            is Step.Math -> mathUi(col, step)
            is Step.Scan -> scanUi(col, step)
            is Step.Survey -> surveyUi(col, step.questions)
            is Step.PickTasks -> pickTasksUi(col, step.count)
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

        // Clock, name and caption at the top; the buttons at the bottom, out of the picture's way.
        val col = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.add(label(LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)), 64f, bold = true, color = fg).apply {
            gravity = Gravity.CENTER
        }, dp(48))
        if (Profiles.several(this)) {
            // Which profile it's from: it may not be the one you're on.
            col.add(label(Profiles.name(this, profileId), 15f, bold = true, color = soft).apply {
                gravity = Gravity.CENTER
                tag = "alarm-profile"
            }, dp(4))
        }
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
        col.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(32) // the picture shows through here
        })
        val softAlarm = checkin.soft
        val hasSteps = steps.any { it !is Step.Dismiss }
        if (!softAlarm || hasSteps) {
            col.add(
                primaryButton("Start check-in") {
                    AlarmService.instance?.pauseFor()
                    showStep()
                }.apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    minHeight = dp(88)
                    tag = "start-checkin"
                },
                0
            )
        }
        if (softAlarm) {
            // Soft: snooze or dismiss right here; the steps are optional.
            col.add(snoozeButton().apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                minHeight = dp(if (hasSteps) 64 else 88)
            }, if (hasSteps) dp(12) else 0)
            val dismiss = if (hasSteps) dismissButton() else primaryButton("Dismiss") { dismissSoft() }.apply {
                tag = "dismiss-alarm"
                minHeight = dp(64)
            }
            col.add(dismiss, dp(12))
        }
        if (target.practice) col.add(endTestButton(), dp(12))
        val hint = if (softAlarm) {
            "Soft alarm: snooze rings again in ${checkin.snoozeMinutes} minutes. Not counted in your stats."
        } else {
            "Pauses the alarm for ${AlarmService.pauseMs(this) / 1000} seconds. Every tap restarts the countdown; " +
                "if it runs out, the alarm rings again."
        }
        col.add(label(hint, 13f, color = soft).apply { gravity = Gravity.CENTER; tag = "ringing-hint" }, dp(14))
        setScreen(col, withBar = false, background = bgView, fill = true)
    }

    /** Scrolling content, with the countdown bar fixed above it while the alarm is paused. */
    private fun setScreen(content: View, withBar: Boolean, background: View? = null, fill: Boolean = false) {
        val sv = ScrollView(this).apply {
            isFillViewport = fill // lets the content's spacer push the buttons to the bottom
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

    private fun scanUi(col: LinearLayout, step: Step.Scan) {
        val where = step.label.ifEmpty { "your registered code" }
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
                if (code == step.code) {
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

    /**
     * Pick today's tasks: tick exactly [count] tasks from the backlog (all of them if there are
     * fewer); they're turned on, so they show on the start page. A test ring changes nothing.
     */
    private fun pickTasksUi(col: LinearLayout, count: Int) {
        val backlog = store.backlog
        picked.retainAll(backlog.map { it.id }.toSet())
        val active = store.activeTasks.size
        val need = minOf((count - active).coerceAtLeast(0), backlog.size)
        val already = if (active == 0) "" else "You have $active active already. "
        if (need == 0) {
            col.add(label(if (count <= active) "You already have $active active tasks." else "Your backlog is empty: nothing to pick today.", 17f), dp(8))
            if (count > active) col.add(muted("Add tasks with + on the Backlog tab of the start page.", 14f), dp(4))
            col.add(primaryButton("Continue") { nextStep() }.apply { tag = "pick-continue" }, dp(24))
            return
        }
        val more = if (active == 0) "" else " more"
        col.add(label(if (need == 1) "Pick 1$more task to do today" else "Pick $need$more tasks to do today", 20f, bold = true), dp(4))
        col.add(muted(already + (if (backlog.size > need) "From your backlog of ${backlog.size}. They'll show on your start page."
            else "That's your whole backlog. They'll show on your start page."), 14f), dp(4))
        lateinit var go: Button
        fun refresh() {
            val left = need - picked.size
            go.isEnabled = left == 0
            go.text = when {
                left > 0 -> "Pick $left more"
                left < 0 -> "Untick ${-left}"
                else -> if (target.practice) "Done (test: nothing changes)" else "Add to today"
            }
        }
        val list = column()
        for (t in backlog) {
            val detail = listOfNotNull(t.category, CheckinText.steps(t).takeIf { Steps.active(t).isNotEmpty() })
                .joinToString(" · ").ifEmpty { null }
            list.add(checkTile(t.name, t.id in picked, detail).apply {
                tag = "pick:${t.id}"
                setOnCheckedChangeListener { _, on ->
                    keepQuiet()
                    if (on) picked += t.id else picked -= t.id
                    refresh()
                }
            }, dp(8))
        }
        col.add(list, dp(10))
        go = primaryButton("") {
            if (!target.practice) {
                for (id in picked) {
                    store.updateCheckin(id) { it.copy(enabled = true) }
                    store.checkin(id)?.let { AlarmScheduler.schedule(this, it) }
                }
                toast("Added ${picked.size} task${if (picked.size == 1) "" else "s"} to today")
            }
            picked.clear()
            nextStep()
        }.apply { tag = "pick-go" }
        col.add(go, dp(16))
        refresh()
    }

    private fun surveyUi(col: LinearLayout, qs: List<Question>) {
        if (forced) col.add(muted("Be honest. This is just for you.", 14f), dp(10))
        lateinit var submit: Button
        // Yes/no questions must be answered; typed ones are optional (empty is off track).
        fun refresh() {
            val left = qs.count { !it.takesValue && !answers.containsKey(it.id) }
            val badNumber = qs.any { it.type == Question.NUMBER && !values[it.id].isNullOrBlank() &&
                Question.cleanNumber(values.getValue(it.id)) == null }
            submit.isEnabled = left == 0 && !badNumber
            submit.text = when {
                left > 0 -> "$left left to answer"
                badNumber -> "Fix the number"
                else -> "Submit"
            }
        }

        for (q in qs) {
            val box = card()
            box.add(label(q.text, 18f))
            if (q.takesValue) {
                val input = EditText(this).apply {
                    inputType = if (q.type == Question.NUMBER) {
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                    } else {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    }
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    setTextColor(colorText)
                    hint = if (q.type == Question.NUMBER) "A number (optional)" else "Your answer (optional)"
                    setText(values[q.id].orEmpty())
                    tag = "q:${q.id}:value"
                }
                input.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = keepQuiet()
                    override fun afterTextChanged(s: Editable?) {
                        values[q.id] = s?.toString().orEmpty()
                        refresh()
                    }
                })
                box.add(input, dp(8))
                box.add(muted("Leave it empty if you can't answer: that counts as off track.", 13f), dp(4))
                col.add(box, dp(12))
                continue
            }
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
            val clean = qs.filter { !it.takesValue }.associate { it.id to answers.getValue(it.id) }
            // Every typed question, empty ones as "" (clears a value saved before).
            val typed = qs.filter { it.takesValue }.associate { q ->
                val v = values[q.id].orEmpty().trim()
                q.id to (if (q.type == Question.NUMBER) Question.cleanNumber(v) ?: "" else v)
            }
            val good = qs.count { q -> if (q.takesValue) typed[q.id].orEmpty().isNotEmpty() else q.isGood(clean.getValue(q.id)) }
            if (target.practice) {
                toast("Test: $good of ${qs.size} on track (not logged)")
            } else {
                store.saveAnswers(target.day, checkin.id, slot, clean, early, typed, took())
                toast("Logged: $good of ${qs.size} on track")
            }
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
        /** How often an alarm screen checks your other devices. */
        private const val DEVICES_EVERY_MS = 20_000L

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
        private const val STATE_VALUES = "values"
        private const val STATE_STARTED = "started"
        private const val STATE_PICKED = "picked"
        private const val FALLBACK_PROBLEMS = 5
        private const val FALLBACK_DELAY_MS = 2 * 60 * 1000L

        /** Opens whichever check-in is waiting. */
        fun alarmIntent(context: Context): Intent =
            Intent(context, CheckinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Profiles.EXTRA, Profiles.withAlarm(context, Profiles.explicit(context)) ?: Profiles.current(context))

        /** Resolves an upcoming ring now (all steps, no alarm); that ring then stays quiet. */
        fun earlyIntent(context: Context, checkinId: String, day: String, time: String): Intent =
            editIntent(context, checkinId, day, time).putExtra(EXTRA_EARLY, true)

        /** Opens a check-in to fill in / fix a specific day's answers. */
        fun editIntent(context: Context, checkinId: String, day: String, time: String = ""): Intent =
            Profiles.distinct(Intent(context, CheckinActivity::class.java), context, "edit/$checkinId")
                .putExtra(EXTRA_CHECKIN, checkinId)
                .putExtra(EXTRA_DAY, day)
                .putExtra(EXTRA_TIME, time)
    }
}
