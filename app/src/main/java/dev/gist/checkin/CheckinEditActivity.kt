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

        // Steps, in the order they run. Drag ≡ to reorder; tap one to set it up.
        col = section(page, "When it rings")
        col.add(label(CheckinText.steps(c), 15f), dp(4))
        val order = Steps.order(c)
        if (order.isEmpty()) {
            col.add(muted("No steps: the alarm just shows a Dismiss button.", 13f), dp(2))
        } else {
            col.add(muted("Steps run from top to bottom. Drag ≡ to change the order; tap a step to set it up.", 13f), dp(2))
            val list = ReorderList(this) { from, to -> change { Steps.move(it, from, to) } }
            order.forEachIndexed { i, kind -> stepRow(list, c, i, kind) }
            col.add(list, dp(10))
        }
        if (Steps.available(c).isNotEmpty()) {
            col.add(button("+ Add step") { addStep(c) }.apply { tag = "add-step" }, dp(10))
        }

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

    /** One step: drag handle, number and name, what it's set to. Tap to open its page. */
    private fun stepRow(list: ReorderList, c: Checkin, index: Int, kind: String) {
        val ready = Steps.configured(c, kind)
        val r = row().apply {
            background = rounded(colorOf(R.color.surface_variant), 14)
            setPadding(dp(4), dp(10), dp(12), dp(10))
            tag = "step:$kind"
            isClickable = true
            setOnClickListener { startActivity(StepEditActivity.intent(this@CheckinEditActivity, checkinId, kind)) }
        }
        val handle = label("≡", 26f, color = colorMuted).apply {
            setPadding(dp(12), dp(4), dp(14), dp(4))
            tag = "drag:$kind"
            contentDescription = "Drag to reorder: ${Steps.title(kind)}"
        }
        r.addWrapped(handle)
        val text = column()
        text.add(label("${index + 1}. ${Steps.title(kind)}", 16f, bold = true))
        text.add(label(Steps.summary(c, kind), 14f, color = if (ready) colorMuted else colorWarn), dp(2))
        r.addWeighted(text)
        r.addWrapped(label("›", 22f, color = colorMuted))
        list.addRow(r, handle, dp(8))
    }

    private fun addStep(c: Checkin) {
        val kinds = Steps.available(c)
        AlertDialog.Builder(this)
            .setTitle("Add a step")
            .setItems(kinds.map { "${Steps.title(it)}\n${Steps.blurb(it)}" }.toTypedArray()) { _, i ->
                val kind = kinds[i]
                change { Steps.add(it, kind) }
                startActivity(StepEditActivity.intent(this, checkinId, kind))
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val EXTRA_ID = "checkinId"
        private const val REQ_SOUND = 5
        private const val REQ_IMAGE = 6
        private const val REQ_IMAGES = 7
        private const val REQ_VIDEO = 8

        fun intent(context: Context, checkinId: String): Intent =
            Intent(context, CheckinEditActivity::class.java).putExtra(EXTRA_ID, checkinId)
    }
}
