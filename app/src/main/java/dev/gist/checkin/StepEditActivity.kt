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
    private lateinit var kind: String
    private var scroll: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val id = intent.getStringExtra(EXTRA_ID)
        val k = intent.getStringExtra(EXTRA_KIND)
        if (id == null || k !in Steps.ALL || store.checkin(id) == null) {
            finish()
            return
        }
        checkinId = id
        kind = k!!
    }

    override fun onResume() {
        super.onResume()
        if (!::checkinId.isInitialized) return
        render()
    }

    private fun render() {
        val c = store.checkin(checkinId) ?: return finish()
        val oldY = scroll?.scrollY ?: 0
        val order = Steps.order(c)
        val pos = order.indexOf(kind)
        val page = column()
        val where = if (pos >= 0) "Step ${pos + 1} of ${order.size}" else "Not in this check-in"
        page.add(header(Steps.title(kind), "${c.name} · $where"))
        page.add(muted(Steps.blurb(kind), 14f), dp(4))

        val body = card()
        when (kind) {
            Steps.MATH -> mathUi(body, c)
            Steps.SCAN -> scanUi(body, c)
            else -> questionsUi(body, c)
        }
        page.add(body, dp(16))

        if (pos >= 0) page.add(dangerButton("Remove this step") { confirmRemove(c) }, dp(24))

        val sv = screen(page)
        setContentView(sv)
        scroll = sv
        sv.post { sv.scrollTo(0, oldY) }
    }

    private fun change(f: (Checkin) -> Checkin) {
        store.updateCheckin(checkinId, f)
        render()
    }

    // ---------------------------------------------------------------- math

    private fun mathUi(col: LinearLayout, c: Checkin) {
        col.add(sectionTitle("Problems to solve"))
        val picker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 10
            value = c.mathCount.coerceIn(1, 10)
            wrapSelectorWheel = false
            tag = "math-count"
            // Saved without a redraw, so the wheel keeps spinning smoothly.
            setOnValueChangedListener { _, _, v -> store.updateCheckin(checkinId) { it.copy(mathCount = v) } }
        }
        col.add(picker, dp(4))
        if (c.mathCount == 0 && Steps.MATH in Steps.order(c)) store.updateCheckin(checkinId) { it.copy(mathCount = picker.value) }

        col.add(sectionTitle("Difficulty"), dp(16))
        val group = RadioGroup(this)
        MathGen.LEVELS.forEachIndexed { i, lvl ->
            val tile = choiceTile("${lvl.name}\ne.g. ${lvl.example}").apply {
                id = View.generateViewId()
                tag = "math-level:${i + 1}"
                isChecked = i + 1 == c.mathLevel
            }
            group.addView(tile, RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = if (i == 0) 0 else dp(6)
            })
            tile.setOnCheckedChangeListener { _, on -> if (on) store.updateCheckin(checkinId) { it.copy(mathLevel = i + 1) } }
        }
        col.add(group, dp(6))
        col.add(muted("A wrong answer gives you a new problem.", 13f), dp(10))
    }

    // ---------------------------------------------------------------- scan

    private fun scanUi(col: LinearLayout, c: Checkin) {
        val code = c.scanCode
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
            setText(c.scanLabel)
            hint = "e.g. Bathroom sink"
            tag = "scan-label"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString()?.trim().orEmpty()
                    store.updateCheckin(checkinId) { it.copy(scanLabel = text) }
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

    private fun questionsUi(col: LinearLayout, c: Checkin) {
        if (c.questions.isEmpty()) {
            col.add(label("No questions yet, so this step is skipped when the alarm rings.", 15f, color = colorWarn))
        }
        c.questions.forEachIndexed { i, q -> col.add(questionRow(c, i, q), if (i == 0) 0 else dp(10)) }
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
        val yesGood = CheckBox(this).apply {
            text = "Answering \"Yes\" is on track"
            isChecked = q?.yesIsGood ?: false
        }
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

    private fun confirmRemove(c: Checkin) {
        val what = when (kind) {
            Steps.MATH -> "The alarm will no longer ask for math."
            Steps.SCAN -> "The registered code is forgotten."
            else -> "Its questions are removed. Answers already logged stay on the phone but won't show in History."
        }
        AlertDialog.Builder(this)
            .setTitle("Remove ${Steps.title(kind)}?")
            .setMessage(what)
            .setPositiveButton("Remove") { _, _ ->
                store.updateCheckin(checkinId) { Steps.remove(it, kind) }
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val EXTRA_ID = "checkinId"
        private const val EXTRA_KIND = "kind"
        private const val REQ_REGISTER = 1

        fun intent(context: Context, checkinId: String, kind: String): Intent =
            Intent(context, StepEditActivity::class.java).putExtra(EXTRA_ID, checkinId).putExtra(EXTRA_KIND, kind)
    }
}
