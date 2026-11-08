package dev.gist.checkin

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast

/** Everything about one check-in: name, schedule, steps (math, scan, questions), test, delete. */
class CheckinEditActivity : Activity() {
    private lateinit var store: Store
    private lateinit var checkinId: String
    private var scroll: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val extra = intent.getStringExtra(EXTRA_ID)
        if (extra == null || store.checkin(extra) == null) {
            finish()
            return
        }
        checkinId = extra
    }

    override fun onResume() {
        super.onResume()
        if (!::checkinId.isInitialized) return
        if (store.checkin(checkinId) == null) { finish(); return } // deleted elsewhere
        render()
    }

    // ---------------------------------------------------------------- layout

    private fun render() {
        val c = store.checkin(checkinId) ?: return finish()
        val oldY = scroll?.scrollY ?: 0
        val col = column()

        val title = label(c.name, 26f, bold = true)
        col.add(title)

        // Name: saved as you type (no redraw, so the keyboard stays put).
        col.add(label("Name", 14f), dp(16))
        val name = EditText(this).apply {
            setText(c.name)
            hint = "e.g. Starting the Day"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val n = s?.toString()?.trim().orEmpty()
                    if (n.isNotEmpty()) {
                        store.updateCheckin(checkinId) { it.copy(name = n) }
                        title.text = n
                    }
                }
            })
        }
        col.add(name, dp(2))

        // Schedule
        section(col, "Schedule")
        val sw = Switch(this).apply {
            text = "Ring daily"
            textSize = 17f
            isChecked = c.enabled
            setOnCheckedChangeListener { _, on -> change { it.copy(enabled = on) } }
        }
        col.add(sw, dp(4))
        col.add(button("Time: ${Days.time(c.hour, c.minute)}") { pickTime(c) }, dp(4))
        col.add(label(if (c.enabled) "Next: ${CheckinText.next(c)}" else "Alarm is off", 14f), dp(2))
        val yesterday = CheckBox(this).apply {
            text = "Answers count for the previous day (e.g. a morning check-in about last night)"
            isChecked = c.aboutYesterday
            setOnCheckedChangeListener { _, on -> change { it.copy(aboutYesterday = on) } }
        }
        col.add(yesterday, dp(8))

        // Steps
        section(col, "When it rings")
        col.add(label(CheckinText.steps(c), 15f), dp(4))
        col.add(label("Steps always run in this order: math → scan → questions.", 13f).apply {
            setTextColor(COLOR_MUTED)
        }, dp(2))

        val mathText = if (c.mathCount > 0) {
            "1. Math: ${c.mathCount} × ${MathGen.levelName(c.mathLevel)}"
        } else {
            "1. Math: off"
        }
        col.add(button(mathText) { editMath(c) }, dp(10))
        val scanText = if (c.scanCode != null) "2. Scan: ${c.scanLabel.ifEmpty { "registered code" }}" else "2. Scan: off"
        col.add(button(scanText) { editScan(c) }, dp(4))

        col.add(label("3. Questions", 17f, bold = true), dp(16))
        if (c.questions.isEmpty()) {
            col.add(label("None, so nothing gets logged.", 14f).apply { setTextColor(COLOR_MUTED) }, dp(4))
        }
        c.questions.forEachIndexed { i, q -> col.add(questionRow(i, q), dp(6)) }
        col.add(button("+ Add question") { editQuestion(c, null) }, dp(6))

        // Test & delete
        section(col, "Try it")
        col.add(button("Test: ring in 10 seconds") {
            AlarmScheduler.scheduleTest(this, checkinId)
            toast("Ringing in 10 seconds. Lock your phone to see the full-screen version. (Answers are logged.)")
        }, dp(4))

        col.add(button("Delete this check-in") { confirmDelete(c) }.apply { setTextColor(COLOR_BAD) }, dp(36))

        val sv = ScrollView(this).apply {
            clipToPadding = false
            addView(col)
        }
        applySystemBarInsets(sv, dp(20))
        setContentView(sv)
        scroll = sv
        sv.post { sv.scrollTo(0, oldY) }
    }

    private fun section(col: LinearLayout, title: String) {
        col.add(label(title, 20f, bold = true), dp(28))
    }

    private fun questionRow(index: Int, q: Question): View {
        val r = row()
        val onTrack = if (q.yesIsGood) "On track = Yes" else "On track = No"
        r.addView(
            label("${index + 1}. ${q.text}\n$onTrack", 15f),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (index > 0) r.addView(button("↑") { moveQuestionUp(index) })
        val c = store.checkin(checkinId)
        r.addView(button("Edit") { if (c != null) editQuestion(c, q) })
        return r
    }

    // ---------------------------------------------------------------- actions

    /** Update this check-in, re-arm its alarm, redraw. */
    private fun change(f: (Checkin) -> Checkin) {
        store.updateCheckin(checkinId, f)
        store.checkin(checkinId)?.let { AlarmScheduler.schedule(this, it) }
        render()
    }

    private fun pickTime(c: Checkin) {
        TimePickerDialog(
            this,
            { _, h, m -> change { it.copy(hour = h, minute = m, enabled = true) } },
            c.hour,
            c.minute,
            DateFormat.is24HourFormat(this)
        ).show()
    }

    private fun confirmDelete(c: Checkin) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${c.name}\"?")
            .setMessage("Its alarm, steps and questions are removed. Answers it already logged stay on the phone but won't show in history or the CSV.")
            .setPositiveButton("Delete") { _, _ ->
                AlarmScheduler.cancel(this, checkinId)
                store.checkins = store.checkins.filter { it.id != checkinId }
                store.prunePending()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editMath(c: Checkin) {
        val box = column().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        box.add(label("Problems to solve (0 = off)", 15f))
        val picker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 10
            value = c.mathCount.coerceIn(0, 10)
            wrapSelectorWheel = false
        }
        box.add(picker, dp(4))
        box.add(label("Difficulty", 15f), dp(12))
        val group = RadioGroup(this)
        val ids = MathGen.LEVELS.mapIndexed { i, lvl ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${lvl.name}   e.g. ${lvl.example}"
                isChecked = i + 1 == c.mathLevel
            }
            group.addView(rb)
            rb.id
        }
        box.add(group, dp(4))

        AlertDialog.Builder(this)
            .setTitle("Math")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val i = ids.indexOf(group.checkedRadioButtonId)
                val level = if (i >= 0) i + 1 else c.mathLevel
                change { it.copy(mathCount = picker.value, mathLevel = level) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editScan(c: Checkin) {
        if (c.scanCode == null) {
            startRegister()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Scan: ${c.scanLabel.ifEmpty { "registered code" }}")
            .setMessage("To stop this alarm you have to scan the code with this content:\n\n${c.scanCode}")
            .setPositiveButton("Re-register") { _, _ -> startRegister() }
            .setNeutralButton("Remove") { _, _ -> change { it.copy(scanCode = null, scanLabel = "") } }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun startRegister() {
        startActivityForResult(Intent(this, ScanActivity::class.java), REQ_REGISTER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_REGISTER || resultCode != RESULT_OK) return
        val code = data?.getStringExtra(ScanActivity.EXTRA_CODE) ?: return
        finishRegister(code)
    }

    private fun finishRegister(code: String) {
        val c = store.checkin(checkinId) ?: return
        val box = column().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val where = EditText(this).apply {
            hint = "Where is it? e.g. Bathroom sink"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(c.scanLabel)
        }
        box.add(label("Code saved. Name the spot so the alarm can tell you where to go.", 14f))
        box.add(where, dp(8))
        AlertDialog.Builder(this)
            .setTitle("Code registered")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val spot = where.text.toString().trim()
                change { it.copy(scanCode = code, scanLabel = spot) }
            }
            .show()
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
            if (index > 0 && index < list.size) {
                val tmp = list[index - 1]
                list[index - 1] = list[index]
                list[index] = tmp
            }
            cur.copy(questions = list)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_ID = "checkinId"
        private const val REQ_REGISTER = 4

        fun intent(context: Context, checkinId: String): Intent =
            Intent(context, CheckinEditActivity::class.java).putExtra(EXTRA_ID, checkinId)
    }
}
