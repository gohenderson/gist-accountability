package dev.gist.checkin

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast

/** Everything about one check-in: name, schedule, steps (math, scan, questions), test, delete. */
class CheckinEditActivity : BaseActivity() {
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
        val page = column()
        var col: LinearLayout = page // sections below swap this for their own card

        val title = label(c.name, 30f, bold = true)
        col.add(title)
        col.add(muted(CheckinText.oneLine(c), 15f), dp(2))

        // Name: saved as you type (no redraw, so the keyboard stays put).
        col = section(page, "Name")
        val name = EditText(this).apply {
            setText(c.name)
            hint = "e.g. Starting the Day"
            tag = "checkin-name"
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
        col = section(page, "Schedule")
        val sw = styledSwitch("Alarm on").apply {
            isChecked = c.enabled
            setOnCheckedChangeListener { _, on -> change { it.copy(enabled = on) } }
        }
        col.add(sw, dp(4))
        col.add(button("Repeats: ${c.repeat.describe()}") { pickRepeat(c) }.apply { tag = "repeat" }, dp(8))
        col.add(label("Rings at:", 15f), dp(12))
        c.times.forEachIndexed { i, t ->
            val r = row()
            r.addView(
                button(Days.time(t.hour, t.minute)) { pickTime(c, i) }.apply { tag = "time:$i" },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (c.times.size > 1) {
                r.addView(button("✕") { removeTime(i) }.apply {
                    tag = "remove-time:$i"
                    contentDescription = "Remove ${Days.time(t.hour, t.minute)}"
                })
            }
            col.add(r, dp(4))
        }
        col.add(button("+ Add time") { pickTime(c, null) }, dp(4))
        col.add(label(if (c.enabled) "Next: ${CheckinText.next(c)}" else "Alarm is off", 14f), dp(4))
        val yesterday = CheckBox(this).apply {
            text = "Answers count for the previous day (e.g. a morning check-in about last night)"
            isChecked = c.aboutYesterday
            setOnCheckedChangeListener { _, on -> change { it.copy(aboutYesterday = on) } }
        }
        col.add(yesterday, dp(8))

        // Sound & background
        col = section(page, "While it rings")
        col.add(button("Sound: ${soundName(c)}") { pickSound(c) }, dp(4))
        val photoCount = Backgrounds.photos(this, checkinId).size
        col.add(
            button("Background: ${c.background.describe(photoCount)}") { pickBackgroundKind(c) }.apply { tag = "background-kind" },
            dp(4)
        )
        if (c.background.kind == Background.SLIDESHOW) {
            col.add(button("Change photo every ${c.background.intervalSec} s") { pickInterval(c) }.apply {
                tag = "slideshow-interval"
            }, dp(4))
        }
        if (c.background.hasMedia) {
            Backgrounds.preview(this, checkinId, c.background)?.let { bmp ->
                val preview = ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    tag = "background-preview"
                    contentDescription = "Background preview"
                }
                col.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)).apply {
                    topMargin = dp(6)
                })
            }
            if (c.background.kind == Background.VIDEO) col.add(muted("Plays muted and loops while it rings.", 13f), dp(2))
        }
        // Caption: saved as you type, like the name.
        val caption = EditText(this).apply {
            setText(c.background.caption)
            hint = "Caption (optional), e.g. Future you says thanks"
            tag = "background-caption"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString()?.trim().orEmpty()
                    store.updateCheckin(checkinId) { it.copy(background = it.background.copy(caption = text)) }
                }
            })
        }
        col.add(caption, dp(8))

        // Steps
        col = section(page, "When it rings")
        col.add(label(CheckinText.steps(c), 15f), dp(4))
        col.add(muted("Steps always run in this order: math → scan → questions.", 13f), dp(2))

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
            col.add(muted("None, so nothing gets logged.", 14f), dp(4))
        }
        c.questions.forEachIndexed { i, q -> col.add(questionRow(i, q), dp(6)) }
        col.add(button("+ Add question") { editQuestion(c, null) }, dp(6))

        // Test & delete
        col = section(page, "Try it")
        col.add(button("Test: ring in 10 seconds") {
            AlarmScheduler.scheduleTest(this, checkinId)
            toast("Ringing in 10 seconds. Lock your phone to see the full-screen version. (Answers are logged.)")
        }, dp(4))

        page.add(dangerButton("Delete this check-in") { confirmDelete(c) }, dp(24))

        val sv = screen(page)
        
        setContentView(sv)
        scroll = sv
        sv.post { sv.scrollTo(0, oldY) }
    }

    /** Starts a new card on [page] with a small title; returns it to add the section's content to. */
    private fun section(page: LinearLayout, title: String): LinearLayout {
        val card = card()
        card.add(sectionTitle(title))
        page.add(card, dp(14))
        return card
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

    /** Change the time at [index], or add a new one when [index] is null. */
    private fun pickTime(c: Checkin, index: Int?) {
        val start = index?.let { c.times[it] } ?: AlarmTime((c.times.last().hour + 1) % 24, 0)
        TimePickerDialog(
            this,
            { _, h, m ->
                change { cur ->
                    val list = cur.times.toMutableList()
                    if (index != null && index < list.size) list[index] = AlarmTime(h, m) else list += AlarmTime(h, m)
                    cur.copy(times = list.distinct().sorted(), enabled = true)
                }
            },
            start.hour,
            start.minute,
            DateFormat.is24HourFormat(this)
        ).show()
    }

    // ---------------------------------------------------------------- repeat

    private fun pickRepeat(c: Checkin) {
        val options = arrayOf(
            "Every day",
            "Weekdays (Mon–Fri)",
            "Weekends (Sat & Sun)",
            "Specific days of the week…",
            "Monthly on certain dates…",
        )
        AlertDialog.Builder(this)
            .setTitle("Repeats")
            .setItems(options) { _, i ->
                when (i) {
                    0 -> change { it.copy(repeat = Repeat.EVERY_DAY) }
                    1 -> change { it.copy(repeat = Repeat.weekly(Repeat.WEEKDAYS)) }
                    2 -> change { it.copy(repeat = Repeat.weekly(Repeat.WEEKENDS)) }
                    3 -> pickWeekdays(c)
                    4 -> pickMonthDays(c)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickWeekdays(c: Checkin) {
        val labels = (1..7).map {
            java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        }.toTypedArray()
        val checked = BooleanArray(7) { i -> c.repeat.kind == Repeat.WEEKLY && (i + 1) in c.repeat.days }
        AlertDialog.Builder(this)
            .setTitle("Rings on")
            .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Save") { _, _ ->
                val days = (1..7).filter { checked[it - 1] }.toSet()
                if (days.isEmpty()) toast("Pick at least one day.") else change { it.copy(repeat = Repeat.weekly(days)) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickMonthDays(c: Checkin) {
        val values = (1..31).toList() + Repeat.LAST_DAY
        val labels = ((1..31).map { Repeat.ordinal(it) } + "Last day of the month").toTypedArray()
        val checked = BooleanArray(values.size) { i -> c.repeat.kind == Repeat.MONTHLY && values[i] in c.repeat.days }
        AlertDialog.Builder(this)
            .setTitle("Rings each month on")
            .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Save") { _, _ ->
                val days = values.filterIndexed { i, _ -> checked[i] }.toSet()
                if (days.isEmpty()) {
                    toast("Pick at least one date.")
                } else {
                    change { it.copy(repeat = Repeat.monthly(days)) }
                    if (days.any { it in 29..31 }) toast("Months without that date are skipped; use “Last day” for month-end.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeTime(index: Int) {
        change { cur ->
            if (cur.times.size <= 1 || index !in cur.times.indices) cur
            else cur.copy(times = cur.times.filterIndexed { i, _ -> i != index })
        }
    }

    private fun confirmDelete(c: Checkin) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${c.name}\"?")
            .setMessage("Its alarm, steps and questions are removed. Answers it already logged stay on the phone but won't show in history or the CSV.")
            .setPositiveButton("Delete") { _, _ ->
                AlarmScheduler.cancel(this, checkinId)
                Backgrounds.delete(this, checkinId)
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
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_REGISTER -> data.getStringExtra(ScanActivity.EXTRA_CODE)?.let { finishRegister(it) }
            REQ_SOUND -> soundPicked(pickedRingtone(data))
            REQ_IMAGE, REQ_IMAGES, REQ_VIDEO -> mediaPicked(requestCode, data)
        }
    }

    // ---------------------------------------------------------------- sound

    private fun soundName(c: Checkin): String = when (c.soundUri) {
        null -> "Default alarm sound"
        Checkin.SILENT -> "Silent (vibrate only)"
        else -> c.soundTitle.ifEmpty { "Custom sound" }
    }

    @Suppress("DEPRECATION")
    private fun pickSound(c: Checkin) {
        val existing: Uri? = when (c.soundUri) {
            null -> Settings.System.DEFAULT_ALARM_ALERT_URI
            Checkin.SILENT -> null
            else -> Uri.parse(c.soundUri)
        }
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound for ${c.name}")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
        startActivityForResult(intent, REQ_SOUND)
    }

    @Suppress("DEPRECATION")
    private fun pickedRingtone(data: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }

    private fun soundPicked(uri: Uri?) {
        when (uri) {
            null -> change { it.copy(soundUri = Checkin.SILENT, soundTitle = "") }
            Settings.System.DEFAULT_ALARM_ALERT_URI -> change { it.copy(soundUri = null, soundTitle = "") }
            else -> {
                val title = try {
                    RingtoneManager.getRingtone(this, uri)?.getTitle(this)
                } catch (e: Exception) {
                    null
                } ?: "Custom sound"
                change { it.copy(soundUri = uri.toString(), soundTitle = title) }
            }
        }
    }

    // ---------------------------------------------------------------- background

    private fun pickBackgroundKind(c: Checkin) {
        val options = arrayOf("None", "One photo…", "Slideshow of photos…", "Video…")
        AlertDialog.Builder(this)
            .setTitle("Background while ringing")
            .setItems(options) { _, i ->
                when (i) {
                    0 -> setBackgroundKind(Background.NONE)
                    1 -> pickMedia("image/*", multiple = false, REQ_IMAGE)
                    2 -> pickMedia("image/*", multiple = true, REQ_IMAGES)
                    3 -> pickMedia("video/*", multiple = false, REQ_VIDEO)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickInterval(c: Checkin) {
        val values = Background.INTERVALS
        AlertDialog.Builder(this)
            .setTitle("Change photo every")
            .setSingleChoiceItems(values.map { "$it seconds" }.toTypedArray(), values.indexOf(c.background.intervalSec)) { d, i ->
                change { it.copy(background = it.background.copy(intervalSec = values[i])) }
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Switches the background type (keeping the caption) and drops media it no longer uses. */
    private fun setBackgroundKind(kind: String) {
        Backgrounds.keepOnly(this, checkinId, Background(kind))
        change { it.copy(background = it.background.copy(kind = kind)) }
    }

    @Suppress("DEPRECATION")
    private fun pickMedia(type: String, multiple: Boolean, request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type)
        if (multiple) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(intent, request)
    }

    private fun mediaPicked(request: Int, data: Intent) {
        val uris = buildList {
            val clip = data.clipData
            if (clip != null) for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri)
            else data.data?.let { add(it) }
        }.filter { it.scheme == "content" }
        if (uris.isEmpty()) return
        toast(if (request == REQ_VIDEO) "Preparing video…" else "Preparing photo${if (uris.size > 1) "s" else ""}…")
        if (request == REQ_IMAGES && uris.size > Backgrounds.MAX_PHOTOS) {
            toast("Using the first ${Backgrounds.MAX_PHOTOS} photos.")
        }
        val app = applicationContext
        val id = checkinId
        Thread {
            try {
                val kind = when (request) {
                    REQ_VIDEO -> { Backgrounds.setVideoFromUri(app, id, uris.first()); Background.VIDEO }
                    REQ_IMAGES -> { Backgrounds.setPhotosFromUris(app, id, uris); Background.SLIDESHOW }
                    else -> { Backgrounds.setPhotosFromUris(app, id, uris.take(1)); Background.PHOTO }
                }
                runOnUiThread { setBackgroundKind(kind) }
            } catch (e: Exception) {
                runOnUiThread { toast("Couldn't use that: ${e.message}") }
            }
        }.start()
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
        private const val REQ_SOUND = 5
        private const val REQ_IMAGE = 6
        private const val REQ_IMAGES = 7
        private const val REQ_VIDEO = 8

        fun intent(context: Context, checkinId: String): Intent =
            Intent(context, CheckinEditActivity::class.java).putExtra(EXTRA_ID, checkinId)
    }
}
