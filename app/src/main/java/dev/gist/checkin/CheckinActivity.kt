package dev.gist.checkin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher

/**
 * The alarm screen. When opened by an alarm ("forced") it shows over the lock screen,
 * ignores Back, and runs the check-in's steps in order: math → scan → questions.
 * The alarm stops only after the last step. If several check-ins are waiting, it moves
 * straight on to the next one.
 * When opened from history, it's a normal screen for filling in or fixing a past day's answers.
 */
class CheckinActivity : Activity() {
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
    private var steps: MutableList<Step> = mutableListOf()
    private var stepIndex = 0
    private var solved = 0                     // math problems solved in the current step
    private var problem: MathGen.Problem? = null
    private var scanFallback = false           // "can't scan" was chosen
    private val answers = mutableMapOf<String, Boolean>()
    private var scanner: ScannerView? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        store.prunePending()

        val editId = intent.getStringExtra(EXTRA_CHECKIN)
        val editDay = intent.getStringExtra(EXTRA_DAY)
        val t = if (editId != null && editDay != null) Pending(editId, editDay) else store.pending.firstOrNull()
        if (t == null || !load(t)) {
            stopIfNothingPending()
            finish()
            return
        }

        if (!forced) store.entry(target.day)?.let { answers.putAll(it) } // pre-fill when editing
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
        if (forced) Notif.clearWaiting(this)
        showStep()
    }

    override fun onPause() {
        stopScanner()
        ui.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Left without finishing, and the alarm is no longer ringing: bring the reminder back.
        if (forced && !isFinishing && store.pending.isNotEmpty() && !AlarmService.running) {
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

    /** Point the screen at [t]. Returns false if its check-in no longer exists. */
    private fun load(t: Pending): Boolean {
        val c = store.checkin(t.checkinId) ?: return false
        target = t
        checkin = c
        forced = t in store.pending
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
        if (!forced) return mutableListOf(Step.Survey) // editing history: questions only
        val list = mutableListOf<Step>()
        if (c.mathCount > 0) list += Step.Math(c.mathCount, c.mathLevel)
        if (c.scanCode != null) list += Step.Scan
        if (c.questions.isNotEmpty()) list += Step.Survey
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
        store.removePending(target)
        store.prunePending()
        val next = store.pending.firstOrNull()
        if (forced && next != null && load(next)) {
            answers.clear()
            showStep()
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
        stopScanner()
        ui.removeCallbacksAndMessages(null)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        val col = column()
        val title = if (forced) checkin.name else "Edit: ${checkin.name}"
        col.add(label(title, 26f, bold = true))
        val sub = buildString {
            append(Days.pretty(target.day))
            if (steps.size > 1) append("  ·  step ${stepIndex + 1} of ${steps.size}")
        }
        col.add(label(sub, 15f), dp(4))

        when (val step = steps[stepIndex]) {
            is Step.Math -> mathUi(col, step)
            is Step.Scan -> scanUi(col)
            is Step.Survey -> surveyUi(col)
            is Step.Dismiss -> col.add(button("Dismiss") { nextStep() }, dp(36))
        }

        val sv = ScrollView(this).apply {
            clipToPadding = false
            addView(col)
        }
        applySystemBarInsets(sv, dp(20))
        setContentView(sv)
    }

    private fun mathUi(col: LinearLayout, step: Step.Math) {
        val p = problem ?: MathGen.make(step.level).also { problem = it }
        col.add(label("Problem ${solved + 1} of ${step.count}", 15f), dp(24))
        col.add(label("${p.text} = ?", 34f, bold = true).apply { tag = "math-problem" }, dp(12))

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 28f
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
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { check(); true } else false
        }
        col.add(input, dp(12))
        col.add(button("Check") { check() }, dp(8))

        input.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun scanUi(col: LinearLayout) {
        val where = checkin.scanLabel.ifEmpty { "your registered code" }
        col.add(label("Go scan: $where", 22f, bold = true), dp(24))
        val status: TextView = label("Point the camera at the code.", 15f)
        col.add(status, dp(6))

        val sv = ScannerView(this)
        scanner = sv
        col.add(sv, dp(12))

        val fallback: Button = button("Can't scan? Solve $FALLBACK_PROBLEMS very hard problems instead") {
            useScanFallback()
            showStep()
        }
        fallback.visibility = View.GONE
        fallback.tag = "scan-fallback"
        col.add(fallback, dp(12))
        ui.postDelayed({ fallback.visibility = View.VISIBLE }, FALLBACK_DELAY_MS)

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
                fallback.visibility = View.VISIBLE
            }
        )
    }

    private fun surveyUi(col: LinearLayout) {
        val qs = checkin.questions
        if (forced) {
            col.add(label("Be honest — this is just for you.", 14f), dp(8))
        }
        lateinit var submit: Button
        fun refresh() {
            val left = qs.count { !answers.containsKey(it.id) }
            submit.isEnabled = left == 0
            submit.text = if (left == 0) "Submit" else "$left left to answer"
        }

        for (q in qs) {
            col.add(label(q.text, 19f), dp(28))
            val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
            val yes = RadioButton(this).apply { id = View.generateViewId(); text = "Yes"; textSize = 18f; tag = "q:${q.id}:yes" }
            val no = RadioButton(this).apply { id = View.generateViewId(); text = "No"; textSize = 18f; tag = "q:${q.id}:no" }
            group.addView(yes, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            group.addView(no, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            answers[q.id]?.let { (if (it) yes else no).isChecked = true }
            group.setOnCheckedChangeListener { _, checkedId ->
                answers[q.id] = checkedId == yes.id
                refresh()
            }
            col.add(group, dp(8))
        }

        submit = button("Submit") {
            val clean = qs.associate { it.id to answers.getValue(it.id) }
            store.saveEntry(target.day, clean)
            val good = qs.count { it.isGood(clean.getValue(it.id)) }
            toast("Logged: $good of ${qs.size} on track")
            nextStep()
        }
        col.add(submit, dp(36))
        refresh()
    }

    private fun stopScanner() {
        scanner?.stop()
        scanner = null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_CHECKIN = "checkin"
        private const val EXTRA_DAY = "day"
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

        /** Opens a check-in to fill in / fix a specific day's answers. */
        fun editIntent(context: Context, checkinId: String, day: String): Intent =
            Intent(context, CheckinActivity::class.java)
                .putExtra(EXTRA_CHECKIN, checkinId)
                .putExtra(EXTRA_DAY, day)
    }
}
