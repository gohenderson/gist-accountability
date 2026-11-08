package dev.gist.checkin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher

/**
 * The check-in screen. When opened by an alarm ("forced"), it shows over the lock screen,
 * ignores Back, and only stops the alarm once every question has an answer. If several
 * check-ins are waiting, it moves straight on to the next one.
 * When opened from history, it's a normal screen for filling in or fixing a past day.
 */
class SurveyActivity : Activity() {
    private lateinit var store: Store
    private lateinit var target: Pending
    private lateinit var checkin: Checkin
    private lateinit var submit: Button
    private var forced = false
    private val answers = mutableMapOf<String, Boolean>()

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
            answers.clear()
            s.getStringArrayList(STATE_YES)?.forEach { answers[it] = true }
            s.getStringArrayList(STATE_NO)?.forEach { answers[it] = false }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { if (!forced) finish() }
        }

        setContentView(buildUi())
    }

    /** Point the screen at [t]. Returns false if its check-in no longer exists. */
    private fun load(t: Pending): Boolean {
        val c = store.checkin(t.checkinId) ?: return false
        target = t
        checkin = c
        forced = t in store.pending
        if (forced) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        return true
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!forced) super.onBackPressed() // no escaping the alarm
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_YES, ArrayList(answers.filterValues { it }.keys))
        outState.putStringArrayList(STATE_NO, ArrayList(answers.filterValues { !it }.keys))
    }

    private fun buildUi(): View {
        val col = column()
        col.add(label(if (forced) "${checkin.name} check-in" else "Edit: ${checkin.name}", 26f, bold = true))
        col.add(label(Days.pretty(target.day), 16f), dp(4))
        if (forced) {
            col.add(
                label("The alarm stops once every question is answered. Be honest — this is just for you.", 14f),
                dp(8)
            )
        }

        for (q in checkin.questions) {
            col.add(label(q.text, 19f), dp(28))
            val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
            val yes = RadioButton(this).apply { id = View.generateViewId(); text = "Yes"; textSize = 18f }
            val no = RadioButton(this).apply { id = View.generateViewId(); text = "No"; textSize = 18f }
            group.addView(yes, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            group.addView(no, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            answers[q.id]?.let { (if (it) yes else no).isChecked = true }
            group.setOnCheckedChangeListener { _, checkedId ->
                answers[q.id] = checkedId == yes.id
                refreshSubmit()
            }
            col.add(group, dp(8))
        }

        submit = button("Submit") { submitAnswers() }
        col.add(submit, dp(36))
        refreshSubmit()

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(col)
        }
        applySystemBarInsets(scroll, dp(20))
        return scroll
    }

    private fun refreshSubmit() {
        val left = checkin.questions.count { !answers.containsKey(it.id) }
        submit.isEnabled = left == 0
        submit.text = if (left == 0) "Submit" else "$left left to answer"
    }

    private fun submitAnswers() {
        val qs = checkin.questions
        val clean = qs.associate { it.id to answers.getValue(it.id) }
        store.saveEntry(target.day, clean)
        store.removePending(target)
        val good = qs.count { it.isGood(clean.getValue(it.id)) }
        Toast.makeText(this, "Logged: $good of ${qs.size} on track", Toast.LENGTH_SHORT).show()

        // Another check-in still waiting? Show it right here.
        store.prunePending()
        val next = store.pending.firstOrNull()
        if (forced && next != null && load(next)) {
            answers.clear()
            setContentView(buildUi())
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

    companion object {
        private const val EXTRA_CHECKIN = "checkin"
        private const val EXTRA_DAY = "day"
        private const val STATE_YES = "yes"
        private const val STATE_NO = "no"

        /** Opens whichever check-in is waiting. */
        fun alarmIntent(context: Context): Intent =
            Intent(context, SurveyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens a check-in to fill in / fix a specific day. */
        fun editIntent(context: Context, checkinId: String, day: String): Intent =
            Intent(context, SurveyActivity::class.java)
                .putExtra(EXTRA_CHECKIN, checkinId)
                .putExtra(EXTRA_DAY, day)
    }
}
