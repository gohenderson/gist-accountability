package dev.gist.checkin

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast

/** Sets up one step of a check-in (math, scan or questions). Changes save as you make them. */
class StepEditActivity : BaseActivity() {
    private lateinit var store: Store
    private lateinit var checkinId: String
    private lateinit var stepId: String
    private var scroll: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val id = intent.getStringExtra(EXTRA_ID)
        val sid = intent.getStringExtra(EXTRA_STEP)
        if (id == null || sid == null || store.checkin(id)?.steps?.any { it.id == sid } != true) {
            finish()
            return
        }
        checkinId = id
        stepId = sid
    }

    override fun onResume() {
        super.onResume()
        if (!::checkinId.isInitialized) return
        render()
    }

    private fun render() {
        val c = store.checkin(checkinId) ?: return finish()
        val st = c.steps.firstOrNull { it.id == stepId } ?: return finish()
        val oldY = scroll?.scrollY ?: 0
        val pos = c.steps.indexOf(st)
        val page = column()
        page.add(header(Steps.title(st.kind), "${c.name} · Step ${pos + 1} of ${c.steps.size}"))
        page.add(muted(Steps.blurb(st.kind), 14f), dp(4))

        val body = card()
        when (st.kind) {
            Steps.MATH -> mathUi(body, st)
            Steps.SCAN -> scanUi(body, st)
            else -> questionsUi(body, c, st)
        }
        page.add(body, dp(16))

        val actions = row()
        actions.addWeighted(outlineButton("Duplicate step") {
            val updated = Steps.duplicate(c, stepId)
            store.checkins = store.checkins.map { if (it.id == checkinId) updated else it }
            val copy = updated.steps.getOrNull(pos + 1)
            if (copy != null && updated.steps.size > c.steps.size) {
                Toast.makeText(this, "Added a copy as step ${pos + 2}.", Toast.LENGTH_SHORT).show()
                startActivity(intent(this, checkinId, copy.id))
                finish()
            }
        }.apply { tag = "duplicate-step" })
        page.add(actions, dp(20))
        page.add(dangerButton("Remove this step") { confirmRemove(st) }, dp(12))

        val sv = screen(page)
        setContentView(sv)
        scroll = sv
        sv.post { sv.scrollTo(0, oldY) }
    }

    private fun change(f: (StepConfig) -> StepConfig) {
        save(f)
        render()
    }

    /** Saves without redrawing (e.g. while typing or spinning a wheel). */
    private fun save(f: (StepConfig) -> StepConfig) {
        store.updateCheckin(checkinId) { Steps.update(it, stepId, f) }
    }

    // ---------------------------------------------------------------- math

    private fun mathUi(col: LinearLayout, st: StepConfig) {
        col.add(sectionTitle("Problems to solve"))
        val picker = NumberPicker(this).apply {
            minValue = 1
            maxValue = Steps.MAX_PROBLEMS
            value = st.mathCount.coerceIn(1, Steps.MAX_PROBLEMS)
            wrapSelectorWheel = false
            tag = "math-count"
            // Saved without a redraw, so the wheel keeps spinning smoothly.
            setOnValueChangedListener { _, _, v -> save { it.copy(mathCount = v) } }
        }
        col.add(picker, dp(4))
        col.add(muted("Up to ${Steps.MAX_PROBLEMS} per step. Need more? Duplicate this step.", 13f), dp(2))

        col.add(sectionTitle("Difficulty"), dp(16))
        val group = RadioGroup(this)
        MathGen.LEVELS.forEachIndexed { i, lvl ->
            val tile = choiceTile("${lvl.name}\ne.g. ${lvl.example}").apply {
                id = View.generateViewId()
                tag = "math-level:${i + 1}"
                isChecked = i + 1 == st.mathLevel
            }
            group.addView(tile, RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = if (i == 0) 0 else dp(6)
            })
            tile.setOnCheckedChangeListener { _, on -> if (on) save { it.copy(mathLevel = i + 1) } }
        }
        col.add(group, dp(6))
        col.add(muted("A wrong answer gives you a new problem.", 13f), dp(10))
    }

    // ---------------------------------------------------------------- scan

    private fun scanUi(col: LinearLayout, st: StepConfig) {
        val code = st.scanCode
        if (code == null) {
            col.add(label("No code registered yet, so this step is skipped when the alarm rings.", 15f, color = colorWarn))
            col.add(muted("Any QR code or product barcode works: a sticker on the bathroom mirror, a cereal box in the kitchen.", 13f), dp(4))
            col.add(primaryButton("Register a code") { startRegister() }.apply { tag = "register-code" }, dp(12))
            return
        }
        col.add(sectionTitle("Registered code"))
        col.add(label(code, 15f).apply { setTextIsSelectable(true) }, dp(4))
        col.add(sectionTitle("Where is it?"), dp(16))
        val spot = EditText(this).apply {
            setText(st.scanLabel)
            hint = "e.g. Bathroom sink"
            tag = "scan-label"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString()?.trim().orEmpty()
                    save { it.copy(scanLabel = text) }
                }
            })
        }
        col.add(spot, dp(2))
        col.add(muted("Shown on the alarm screen so you know where to go.", 13f), dp(2))
        col.add(button("Register a different code") { startRegister() }, dp(12))
        col.add(muted("If you can't scan (traveling, camera broken), a \"Can't scan?\" button appears after 2 minutes " +
            "and swaps this step for 5 very hard problems.", 13f), dp(10))
    }

    @Suppress("DEPRECATION")
    private fun startRegister() {
        startActivityForResult(Intent(this, ScanActivity::class.java), REQ_REGISTER)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_REGISTER || resultCode != RESULT_OK) return
        val code = data?.getStringExtra(ScanActivity.EXTRA_CODE) ?: return
        change { it.copy(scanCode = code) }
        Toast.makeText(this, "Code registered. Name the spot below.", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- questions

    private fun questionsUi(col: LinearLayout, c: Checkin, st: StepConfig) {
        if (st.questions.isEmpty()) {
            col.add(label("No questions yet, so this step is skipped when the alarm rings.", 15f, color = colorWarn))
        }
        st.questions.forEachIndexed { i, q -> col.add(questionRow(c, i, q), if (i == 0) 0 else dp(10)) }
        col.add(button("+ Add question") { editQuestion(c, null) }, dp(12))
        col.add(muted("Each question says whether \"Yes\" or \"No\" is on track; that drives the ✓/✗ in History.", 13f), dp(8))
    }

    private fun questionRow(c: Checkin, index: Int, q: Question): View {
        val r = row()
        val onTrack = if (q.yesIsGood) "On track = Yes" else "On track = No"
        r.addWeighted(label("${index + 1}. ${q.text}\n$onTrack", 15f))
        if (index > 0) r.addWrapped(button("↑") { moveQuestionUp(index) }.apply { contentDescription = "Move up" })
        r.addWrapped(button("Edit") { editQuestion(c, q) }, dp(4))
        return r
    }

    private fun editQuestion(c: Checkin, q: Question?) {
        val box = column().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val input = EditText(this).apply {
            hint = "e.g. Did you drink alcohol today?"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(q?.text ?: "")
        }
        val yesGood = checkTile("Answering \"Yes\" is on track", q?.yesIsGood ?: false)
        box.add(input)
        box.add(yesGood, dp(8))

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (q == null) "New question for ${c.name}" else "Edit question")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    change { cur ->
                        val list = cur.questions.toMutableList()
                        if (q == null) {
                            list += Question(Store.newId(), text, yesGood.isChecked)
                        } else {
                            val i = list.indexOfFirst { it.id == q.id }
                            if (i >= 0) list[i] = q.copy(text = text, yesIsGood = yesGood.isChecked)
                        }
                        cur.copy(questions = list)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
        if (q != null) {
            dialog.setNeutralButton("Delete") { _, _ ->
                change { cur -> cur.copy(questions = cur.questions.filter { it.id != q.id }) }
            }
        }
        dialog.show()
    }

    private fun moveQuestionUp(index: Int) {
        change { cur ->
            val list = cur.questions.toMutableList()
            if (index > 0 && index < list.size) list.add(index - 1, list.removeAt(index))
            cur.copy(questions = list)
        }
    }

    // ---------------------------------------------------------------- remove

    private fun confirmRemove(st: StepConfig) {
        val what = when (st.kind) {
            Steps.MATH -> "The alarm will no longer ask for these problems."
            Steps.SCAN -> "The registered code is forgotten."
            else -> "Its questions are removed. Answers already logged stay on the phone but won't show in History."
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Remove this step?")
            .setMessage(what)
            .setPositiveButton("Remove") { _, _ ->
                store.updateCheckin(checkinId) { Steps.remove(it, stepId) }
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val EXTRA_ID = "checkinId"
        private const val EXTRA_STEP = "step"
        private const val REQ_REGISTER = 1

        fun intent(context: Context, checkinId: String, stepId: String): Intent =
            Intent(context, StepEditActivity::class.java).putExtra(EXTRA_ID, checkinId).putExtra(EXTRA_STEP, stepId)
    }
}
