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
        val oldY = scroll?.pageScrollY ?: 0
        val page = column()
        var col: LinearLayout = page // sections below swap this for their own card

        val title = label(c.name, 30f, bold = true)
        col.add(title)
        col.add(muted(CheckinText.oneLine(c), 15f), dp(2))
        // How on track it's been, like the overall score on the start page.
        Score.of(c, store.allResults(c.id))?.let { score ->
            val box = row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            box.addWrapped(label(Score.percent(score), 34f, bold = true,
                color = Score.color(score, colorOf(R.color.bad), colorOf(R.color.good))).apply { tag = "checkin-score" })
            box.addWeighted(muted("on track over the last ${Score.DAYS} days", 14f), startMargin = dp(12))
            col.add(box, dp(8))
        }

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
        col.add(button("Category: ${c.category.ifBlank { "none" }}") { pickCategory(c) }.apply { tag = "category" }, dp(8))

        // Schedule
        col = section(page, "Schedule")
        val sw = styledSwitch("Alarm on").apply {
            isChecked = c.enabled
            setOnCheckedChangeListener { _, on -> change { it.copy(enabled = on) } }
        }
        col.add(sw, dp(4))
        // Triggers: each is one way this check-in rings, with its own conditions.
        val total = c.times.size + c.events.size
        if (total == 0) {
            col.add(label("No triggers: it never rings on its own. Add one below.", 15f, color = colorWarn), dp(10))
        }
        c.times.forEachIndexed { i, tm -> col.add(timeTriggerCard(c, i, tm, i), dp(10)) }
        c.events.forEachIndexed { j, ev -> col.add(eventTriggerCard(c, j, ev, c.times.size + j), dp(10)) }
        col.add(button("+ Add trigger") { addTrigger(c) }.apply { tag = "add-trigger" }, dp(10))
        if (c.enabled && total > 0) {
            col.add(label("Rings ${CheckinText.whenText(c)}", 14f).apply { tag = "trigger-summary" }, dp(8))
            if (c.times.isNotEmpty()) col.add(label("Next: ${CheckinText.next(c)}", 14f).apply { tag = "next-ring" }, dp(2))
        } else if (!c.enabled) {
            col.add(label("Alarm is off", 14f), dp(8))
        }
        if (c.events.any { it.isMovement }) {
            col.add(muted("Runs are noticed from your step rate (running is about 140+ steps a minute), so it works " +
                "with the phone in a pocket or armband, and on a treadmill. A run counts after about a " +
                "minute, and ends about 1½ minutes after you stop or slow to a walk.", 13f), dp(6))
            if (!MovementWatch.canWatch(this)) {
                col.add(label(MovementWatch.problem(this), 14f, color = colorWarn), dp(6))
                if (MovementWatch.hasSensor(this)) {
                    col.add(outlineButton("Allow physical activity") { askActivity() }.apply { tag = "allow-activity" }, dp(4))
                }
            }
        }
        if (c.enabled && c.usesLocation && !LocationTriggers.hasBackground(this)) {
            col.add(label("Location must be allowed \"all the time\" for this to work while Gist is closed.", 14f,
                color = colorWarn), dp(10))
            col.add(outlineButton("Allow location") { askLocation(background = true) }.apply { tag = "allow-location" }, dp(4))
        }
        val yesterday = CheckBox(this).apply {
            text = "Answers count for the previous day (e.g. a morning check-in about last night)"
            isChecked = c.aboutYesterday
            setOnCheckedChangeListener { _, on -> change { it.copy(aboutYesterday = on) } }
        }
        col.add(yesterday, dp(8))

        if (!c.voice) whileItRings(page, c) // a voice check-in doesn't ring

        // Commitment (strict, soft or voice), then the steps, or for a voice what it says.
        col = section(page, if (c.voice) "What it says" else "When it rings")
        col.add(button("Commitment: ${commitmentName(c.commitment)}") { pickCommitment(c) }.apply { tag = "commitment" }, dp(4))
        col.add(muted(commitmentDetail(c.commitment), 13f).apply { tag = "commitment-detail" }, dp(2))
        if (c.soft) {
            col.add(button("Snooze: ${c.snoozeMinutes} minutes") { pickSnooze(c) }.apply { tag = "snooze-length" }, dp(8))
        }
        if (c.voice) {
            voiceSection(col, c)
        } else {
            stepsSection(col, c)
        }

        // Test & delete
        col = section(page, "Try it")
        if (c.voice) {
            col.add(button("Play it now") { playNow() }.apply { tag = "test-voice" }, dp(4))
        } else {
            col.add(button("Test: ring in 10 seconds") {
                AlarmScheduler.scheduleTest(this, checkinId)
                toast("Ringing in 10 seconds. Lock your phone to see the full-screen version. Nothing is logged, and the next real alarm still rings.")
            }, dp(4))
        }

        page.add(dangerButton("Delete this check-in") { confirmDelete(c) }, dp(24))

        val sv = screen(page)
        
        setContentView(sv)
        scroll = sv
        sv.restoreScroll(oldY)
    }

    private fun stepsSection(col: LinearLayout, c: Checkin) {
        col.add(label(CheckinText.steps(c), 15f), dp(14))
        if (c.steps.isEmpty()) {
            col.add(muted("No steps: the alarm just shows a Dismiss button.", 13f), dp(2))
        } else {
            col.add(muted("Steps run from top to bottom. Drag ≡ to change the order; tap a step to set it up.", 13f), dp(2))
            val list = ReorderList(this) { from, to -> change { Steps.move(it, from, to) } }
            c.steps.forEachIndexed { i, st -> stepRow(list, i, st) }
            col.add(list, dp(10))
        }
        col.add(button("+ Add step") { addStep(c) }.apply { tag = "add-step" }, dp(10))
    }

    /** Starts a new card on [page] with a small title; returns it to add the section's content to. */
    private fun section(page: LinearLayout, title: String): LinearLayout {
        val card = card()
        card.add(sectionTitle(title))
        page.add(card, dp(14))
        return card
    }

    /** One step: drag handle, number and name, what it's set to. Tap to open its page. */
    /** One step: drag handle, number and name, what it's set to. Tap to open its page. */
    private fun stepRow(list: ReorderList, index: Int, st: StepConfig) {
        val r = row().apply {
            background = rounded(colorOf(R.color.surface_variant), 14)
            setPadding(dp(4), dp(10), dp(12), dp(10))
            tag = "step:$index"
            isClickable = true
            setOnClickListener { startActivity(StepEditActivity.intent(this@CheckinEditActivity, checkinId, st.id)) }
        }
        val handle = label("≡", 26f, color = colorMuted).apply {
            setPadding(dp(12), dp(4), dp(14), dp(4))
            tag = "drag:$index"
            contentDescription = "Drag to reorder: step ${index + 1}, ${Steps.title(st.kind)}"
        }
        r.addWrapped(handle)
        val text = column()
        text.add(label("${index + 1}. ${Steps.title(st.kind)}", 16f, bold = true))
        text.add(label(Steps.summary(st), 14f, color = if (st.configured) colorMuted else colorWarn), dp(2))
        r.addWeighted(text)
        r.addWrapped(label("›", 22f, color = colorMuted))
        list.addRow(r, handle, dp(8))
    }

    private fun addStep(c: Checkin) {
        if (c.steps.size >= Steps.MAX_STEPS) {
            toast("That's the most steps a check-in can have (${Steps.MAX_STEPS}).")
            return
        }
        val options = Steps.ALL.map { DialogOption(Steps.title(it), Steps.blurb(it), "add:$it") }
        pickDialog("Add a step", options, message = "Steps can repeat: add as many of each as you like.") { i ->
            val kind = Steps.ALL[i]
            val updated = Steps.add(c, kind)
            change { updated }
            startActivity(StepEditActivity.intent(this, checkinId, updated.steps.last().id))
        }
    }

    // ---------------------------------------------------------------- actions

    /** Update this check-in, re-arm its alarm, redraw. */
    /** Sound, background and caption: what the alarm screen looks and sounds like. */
    private fun whileItRings(page: LinearLayout, c: Checkin) {
        val col = section(page, "While it rings")
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
    }

    private fun pickCommitment(c: Checkin) {
        val options = listOf(
            DialogOption("Strict", "Only finishing the steps stops it. No snooze; it comes back if you leave it. Counted in your stats.", "commitment:strict"),
            DialogOption("Soft", "Snooze or dismiss any time; the steps are optional. Not counted in your stats.", "commitment:soft"),
            DialogOption("Voice", "No alarm: says a line (or plays your clip) through your headphones. Not counted in your stats.", "commitment:voice"),
        )
        pickDialog("Commitment", options, Checkin.COMMITMENTS.indexOf(c.commitment)) { i ->
            change { it.copy(commitment = Checkin.COMMITMENTS[i]) }
        }
    }

    private fun commitmentName(k: String) = when (k) {
        Checkin.SOFT -> "Soft"
        Checkin.VOICE -> "Voice"
        else -> "Strict"
    }

    private fun commitmentDetail(k: String) = when (k) {
        Checkin.SOFT -> "Snooze or dismiss it any time; the steps are optional. It doesn't come back if you leave it, " +
            "and it isn't counted in your stats."
        Checkin.VOICE -> "No alarm and nothing to tap: when a trigger fires, it says its line (or plays your clip) " +
            "through your headphones, and your music dips while it talks. Not counted in your stats."
        else -> "Only finishing the steps stops it: no snooze, and it comes back if you leave it. Counted in your stats."
    }

    // ---------------------------------------------------------------- voice

    private var recorder: android.media.MediaRecorder? = null
    private var recordingFile: java.io.File? = null

    /** What a voice check-in says: typed text, or a clip recorded here or picked. */
    private fun voiceSection(col: LinearLayout, c: Checkin) {
        val clip = c.voiceSource == Checkin.VOICE_CLIP
        col.add(button("Voice: ${if (clip) "Recorded clip" else "Spoken text"}") { pickVoiceSource(c) }.apply {
            tag = "voice-source"
        }, dp(14))
        if (!clip) {
            // Saved as you type, like the name.
            val text = EditText(this).apply {
                setText(c.voiceText)
                hint = "What it says, e.g. Is that Forrest Gump?"
                tag = "voice-text"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val t = s?.toString()?.trim().orEmpty()
                        store.updateCheckin(checkinId) { it.copy(voiceText = t) }
                    }
                })
            }
            col.add(text, dp(8))
            col.add(muted("Read aloud by the phone's text-to-speech voice. Empty: it says the check-in's name.", 13f), dp(2))
        } else {
            val secs = Backgrounds.voiceSeconds(this, checkinId)
            val status = when {
                recorder != null -> "Recording… tap Stop when you're done (30 seconds at most)."
                secs != null -> "Clip: $secs second${if (secs == 1) "" else "s"}"
                else -> "No clip yet. Until there is one, it says the check-in's name."
            }
            col.add(label(status, 15f).apply { tag = "voice-clip-status" }, dp(8))
            val r = row()
            r.addWeighted(button(if (recorder != null) "Stop" else if (secs != null) "Record again" else "Record") {
                if (recorder != null) stopRecording() else startRecording()
            }.apply { tag = "voice-record" })
            r.addWeighted(outlineButton("Choose a file…") { pickMedia("audio/*", multiple = false, REQ_AUDIO) }.apply {
                tag = "voice-pick"
            }, startMargin = dp(8))
            col.add(r, dp(6))
        }
        col.add(styledSwitch("Only through headphones").apply {
            isChecked = c.voiceHeadphonesOnly
            tag = "voice-headphones"
            setOnCheckedChangeListener { _, on -> change { it.copy(voiceHeadphonesOnly = on) } }
        }, dp(10))
        col.add(muted(if (c.voiceHeadphonesOnly) "Stays quiet when no headphones are connected, so it never talks out of your pocket."
            else "Plays through the phone's speaker when no headphones are connected.", 13f), dp(2))
    }

    private fun pickVoiceSource(c: Checkin) {
        val values = listOf(Checkin.VOICE_TEXT, Checkin.VOICE_CLIP)
        val options = listOf(
            DialogOption("Spoken text", "Type a line; the phone reads it out", "voice:text"),
            DialogOption("Recorded clip", "Record it here (you, or anyone handy), or pick an audio file", "voice:clip"),
        )
        pickDialog("Voice", options, values.indexOf(c.voiceSource)) { i -> change { it.copy(voiceSource = values[i]) } }
    }

    private fun playNow() {
        val c = store.checkin(checkinId) ?: return
        if (c.voiceHeadphonesOnly && !VoiceService.headphonesConnected(this)) {
            toast("Connect headphones first, or turn off \"Only through headphones\".")
            return
        }
        VoiceService.play(this, c)
    }

    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        val file = java.io.File(Backgrounds.dir(this, checkinId), "voice-rec.tmp")
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(this) else android.media.MediaRecorder()
        try {
            rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(96_000)
            rec.setAudioSamplingRate(44_100)
            rec.setMaxDuration(MAX_RECORD_MS)
            rec.setOutputFile(file.path)
            rec.setOnInfoListener { _, what, _ ->
                if (what == android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) runOnUiThread { stopRecording() }
            }
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            rec.release()
            toast("Couldn't start recording: ${e.message}")
            return
        }
        recorder = rec
        recordingFile = file
        render()
    }

    /** Stops recording and keeps the clip (a fumbled, too-short one is thrown away). */
    private fun stopRecording(keep: Boolean = true) {
        val rec = recorder ?: return
        recorder = null
        val ok = try { rec.stop(); true } catch (e: Exception) { false } // stop() throws if nothing was recorded
        rec.release()
        val file = recordingFile
        recordingFile = null
        if (keep && ok && file != null && file.length() > 0) {
            file.renameTo(Backgrounds.voiceFile(this, checkinId))
            change { it.copy(voiceSource = Checkin.VOICE_CLIP) }
        } else {
            file?.delete()
            if (keep) toast("Nothing was recorded. Try again, a little longer.")
            render()
        }
    }

    override fun onPause() {
        stopRecording(keep = true)
        super.onPause()
    }

    private fun audioPicked(data: Intent) {
        val uri = SafeUri.picked(this, data.data) ?: return
        val app = applicationContext
        val id = checkinId
        toast("Preparing clip…")
        Thread {
            try {
                Backgrounds.setVoiceFromUri(app, id, uri)
                runOnUiThread { change { it.copy(voiceSource = Checkin.VOICE_CLIP) } }
            } catch (e: Exception) {
                runOnUiThread { toast("Couldn't use that: ${e.message}") }
            }
        }.start()
    }

    private fun pickRunFor(j: Int, ev: Trigger) {
        val values = listOf(5, 10, 15, 20, 30, 45, 60, 75, 90, 120, 150, 180, 240)
        val current = ev.minRunMinutes.coerceAtLeast(1)
        pickDialog("How far into the run", values.map { DialogOption(Trigger.runForText(it), tag = "run-for:$it") },
            values.indexOf(current)) { i -> setEvent(j) { it.copy(minRunMinutes = values[i]) } }
    }

    private fun pickSnooze(c: Checkin) {
        val choices = Checkin.SNOOZE_CHOICES
        val options = choices.map { DialogOption("$it minutes", tag = "snooze:$it") }
        pickDialog("Snooze for", options, choices.indexOf(c.snoozeMinutes)) { i -> change { it.copy(snoozeMinutes = choices[i]) } }
    }

    private fun change(f: (Checkin) -> Checkin) {
        store.updateCheckin(checkinId, f)
        store.checkin(checkinId)?.let { AlarmScheduler.schedule(this, it) }
        render()
    }

    /** Change the time at [index], or add a new one when [index] is null. */
    private fun pickTime(c: Checkin, index: Int?) {
        val start = index?.let { c.times.getOrNull(it) } ?: AlarmTime(((c.times.lastOrNull()?.hour ?: 6) + 1) % 24, 0)
        TimePickerDialog(
            this,
            { _, h, m ->
                change { cur ->
                    val list = cur.times.toMutableList()
                    // A changed time keeps its own days; a new one rings on the check-in's days.
                    if (index != null && index < list.size) list[index] = list[index].copy(hour = h, minute = m)
                    else list += AlarmTime(h, m)
                    cur.copy(times = list.distinct().sorted(), enabled = true)
                }
            },
            start.hour,
            start.minute,
            DateFormat.is24HourFormat(this)
        ).show()
    }

    // ---------------------------------------------------------------- triggers

    private val kinds = listOf(Trigger.TIME, Trigger.ARRIVE, Trigger.LEAVE, Trigger.RUN_START, Trigger.RUN_END, Trigger.RUN_FOR)

    private fun kindText(kind: String) = when (kind) {
        Trigger.ARRIVE -> "When I arrive"
        Trigger.LEAVE -> "When I leave"
        Trigger.RUN_START -> "When I start running"
        Trigger.RUN_END -> "When I finish running"
        Trigger.RUN_FOR -> "Partway into a run"
        else -> "At a set time"
    }

    /**
     * One "Name: value" line of a trigger card; tap the value to change it. A long [name]
     * goes on its own line above the value ([stacked]).
     */
    private fun field(card: LinearLayout, name: String, value: String, tag: String, stacked: Boolean = false, onClick: () -> Unit) {
        val button = outlineButton(value) { onClick() }.apply {
            this.tag = tag
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        if (stacked) {
            card.add(label(name, 14f, color = colorMuted), dp(6))
            card.add(button, dp(2))
            return
        }
        val r = row()
        r.addView(label(name, 14f, color = colorMuted), LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addWeighted(button)
        card.add(r, dp(4))
    }

    /** The card's title line: "Trigger 2" and its ✕. */
    private fun triggerHeader(card: LinearLayout, n: Int, onRemove: () -> Unit) {
        val r = row()
        r.addWeighted(label("Trigger ${n + 1}", 14f, bold = true, color = colorMuted))
        r.addWrapped(textButton("✕", colorMuted) { onRemove() }.apply {
            tag = "trigger-remove:$n"
            contentDescription = "Remove trigger ${n + 1}"
        })
        card.add(r)
    }

    private fun timeTriggerCard(c: Checkin, i: Int, tm: AlarmTime, n: Int): LinearLayout {
        val card = dialogCard().apply { tag = "trigger:$n" }
        triggerHeader(card, n) { change { cur -> cur.copy(times = cur.times.filterIndexed { k, _ -> k != i }) } }
        field(card, "When", kindText(Trigger.TIME), "trigger-when:$n") { pickKind(c, n) }
        field(card, "Time", Days.time(tm.hour, tm.minute), "trigger-time:$n") { pickTime(c, i) }
        field(card, "Days", c.daysFor(tm).describe(), "trigger-days:$n") {
            pickRepeat(c.daysFor(tm)) { rule -> setTime(i) { it.copy(days = rule) } }
        }
        field(card, "Where", Trigger.whereText(tm.where, tm.place).replaceFirstChar { it.uppercase() }, "trigger-where:$n") {
            pickWhere(tm) { where ->
                if (where == Trigger.ANYWHERE) {
                    setTime(i) { it.copy(where = where) }
                } else if (tm.place != null) {
                    setTime(i) { it.copy(where = where) }
                    askLocation(background = true)
                } else {
                    pickPlace { p -> setTime(i) { it.copy(where = where, place = p) }; askLocation(background = true) }
                }
            }
        }
        if (tm.usesLocation) {
            field(card, "Place", placeText(tm.place), "trigger-place:$n") {
                pickPlace { p -> setTime(i) { it.copy(place = p) } }
            }
        }
        return card
    }

    private fun eventTriggerCard(c: Checkin, j: Int, ev: Trigger, n: Int): LinearLayout {
        val card = dialogCard().apply { tag = "trigger:$n" }
        triggerHeader(card, n) { change { cur -> cur.copy(events = cur.events.filterIndexed { k, _ -> k != j }) } }
        field(card, "When", kindText(ev.kind), "trigger-when:$n") { pickKind(c, n) }
        if (ev.isPlace) {
            field(card, "Where", placeText(ev.place), "trigger-place:$n") {
                pickPlace { p -> setEvent(j) { it.copy(place = p) }; askLocation(background = true) }
            }
        }
        if (ev.kind == Trigger.RUN_FOR) {
            field(card, "Run time", "After ${Trigger.runForText(ev.minRunMinutes)}", "trigger-run-for:$n") { pickRunFor(j, ev) }
        }
        if (ev.kind == Trigger.RUN_END) {
            val min = if (ev.minRunMinutes == 0) "Any length" else "${ev.minRunMinutes}+ minutes"
            field(card, "Minimum run duration", min, "trigger-min-run:$n", stacked = true) { pickMinRun(j, ev) }
        }
        val window = Trigger.windowText(ev) { Days.time(it.hour, it.minute) }.ifEmpty { "Anytime" }.replaceFirstChar { it.uppercase() }
        field(card, "Time", window, "trigger-time:$n") { pickWindow(j, ev) }
        field(card, "Days", ev.days.describe(), "trigger-days:$n") {
            pickRepeat(ev.days) { rule -> setEvent(j) { it.copy(days = rule) } }
        }
        card.add(styledSwitch("At most once a day").apply {
            isChecked = ev.oncePerDay
            tag = "trigger-once:$n"
            setOnCheckedChangeListener { _, on -> setEvent(j) { it.copy(oncePerDay = on) } }
        }, dp(6))
        return card
    }

    private fun placeText(p: Place?) = if (p == null) "Choose a place…" else "${p.name} · ${p.radiusM} m"

    private fun setTime(i: Int, f: (AlarmTime) -> AlarmTime) = change { cur ->
        if (i !in cur.times.indices) cur
        else cur.copy(times = cur.times.toMutableList().apply { this[i] = f(this[i]) }.distinct().sorted())
    }

    private fun setEvent(j: Int, f: (Trigger) -> Trigger) = change { cur ->
        if (j !in cur.events.indices) cur else cur.copy(events = cur.events.toMutableList().apply { this[j] = f(this[j]) })
    }

    /** A new trigger: a time an hour after the last one, every day (change anything after). */
    private fun addTrigger(c: Checkin) {
        val last = c.times.lastOrNull()
        val t = AlarmTime(((last?.hour ?: 7) + if (last == null) 0 else 1) % 24, 0, days = c.repeat)
        change { cur -> cur.copy(times = (cur.times + t).distinct().sorted(), enabled = true) }
    }

    /** Changes trigger [n]'s kind, keeping its days (a time becomes an event or back). */
    private fun pickKind(c: Checkin, n: Int) {
        val isTime = n < c.times.size
        val current = if (isTime) Trigger.TIME else c.events[n - c.times.size].kind
        val options = listOf(
            DialogOption("At a set time", "Like an alarm clock", "kind:time"),
            DialogOption("When I arrive at a place", "Uses the phone's location", "kind:arrive"),
            DialogOption("When I leave a place", "Uses the phone's location", "kind:leave"),
            DialogOption("When I start running", "Uses the phone's step sensor", "kind:run-start"),
            DialogOption("When I finish running", "Uses the phone's step sensor", "kind:run-end"),
            DialogOption("Partway into a run", "e.g. 30 minutes in; uses the phone's step sensor", "kind:run-for"),
        )
        pickDialog("Trigger ${n + 1}", options, kinds.indexOf(current)) { k ->
            val kind = kinds[k]
            if (kind == current) return@pickDialog
            var eventIndex = -1
            change { cur ->
                when {
                    isTime && kind != Trigger.TIME -> {
                        val tm = cur.times[n]
                        eventIndex = cur.events.size
                        cur.copy(
                            times = cur.times.filterIndexed { k2, _ -> k2 != n },
                            events = cur.events + Trigger(kind = kind, days = cur.daysFor(tm),
                                minRunMinutes = if (kind == Trigger.RUN_FOR) Trigger.DEFAULT_RUN_FOR else 0,
                                place = if (kind == Trigger.ARRIVE || kind == Trigger.LEAVE) tm.place else null),
                        )
                    }
                    !isTime && kind == Trigger.TIME -> {
                        val j = n - cur.times.size
                        val ev = cur.events[j]
                        val t = AlarmTime(ev.windowStart?.hour ?: 7, ev.windowStart?.minute ?: 0, days = ev.days)
                        cur.copy(times = (cur.times + t).distinct().sorted(), events = cur.events.filterIndexed { k2, _ -> k2 != j })
                    }
                    else -> {
                        val j = n - cur.times.size
                        eventIndex = j
                        cur.copy(events = cur.events.toMutableList().apply {
                            // "Partway into a run" needs a time in; the minimum run length doesn't carry over.
                            this[j] = this[j].copy(kind = kind, minRunMinutes = if (kind == Trigger.RUN_FOR) Trigger.DEFAULT_RUN_FOR else 0)
                        })
                    }
                }
            }
            val now = store.checkin(checkinId) ?: return@pickDialog
            val ev = now.events.getOrNull(eventIndex)
            when {
                ev != null && ev.isPlace && ev.place == null ->
                    pickPlace { p -> setEvent(eventIndex) { it.copy(place = p) }; askLocation(background = true) }
                ev != null && ev.isPlace -> askLocation(background = true)
                ev != null && ev.isMovement -> askActivity()
            }
        }
    }

    private fun pickWhere(tm: AlarmTime, onPick: (String) -> Unit) {
        val values = listOf(Trigger.ANYWHERE, Trigger.AT, Trigger.AWAY)
        val name = tm.place?.name
        val options = listOf(
            DialogOption("Anywhere"),
            DialogOption(if (name != null) "Only when I'm at $name" else "Only when I'm at a place…",
                "Checked when it's due; elsewhere, that time is skipped"),
            DialogOption(if (name != null) "Only when I'm away from $name" else "Only when I'm away from a place…",
                "Checked when it's due; there, that time is skipped"),
        )
        pickDialog("Ring only when", options, values.indexOf(tm.where)) { i -> onPick(values[i]) }
    }

    private fun pickMinRun(j: Int, ev: Trigger) {
        val values = listOf(0, 5, 10, 20, 30, 45, 60)
        pickDialog("Minimum run duration", values.map {
            if (it == 0) DialogOption("Any length", "Rings after every run, even a short one")
            else DialogOption("At least $it minutes", "Shorter runs don't ring it")
        },
            values.indexOf(ev.minRunMinutes)) { i -> setEvent(j) { it.copy(minRunMinutes = values[i]) } }
    }

    private fun pickWindow(j: Int, ev: Trigger) {
        val any = ev.windowStart == null
        pickDialog("Time of day", listOf(DialogOption("Anytime"), DialogOption("Only between…", "Pick a start and an end time")),
            if (any) 0 else 1) { i ->
            if (i == 0) {
                setEvent(j) { it.copy(windowStart = null, windowEnd = null) }
            } else {
                val s0 = ev.windowStart ?: AlarmTime(6, 0)
                val e0 = ev.windowEnd ?: AlarmTime(10, 0)
                TimePickerDialog(this, { _, h, m ->
                    TimePickerDialog(this, { _, h2, m2 ->
                        setEvent(j) { it.copy(windowStart = AlarmTime(h, m), windowEnd = AlarmTime(h2, m2)) }
                    }, e0.hour, e0.minute, DateFormat.is24HourFormat(this)).apply { setTitle("…and") }.show()
                }, s0.hour, s0.minute, DateFormat.is24HourFormat(this)).apply { setTitle("From") }.show()
            }
        }
    }

    // ---------------------------------------------------------------- category

    /** Categories group check-ins on the start page (View → Grouped by category). */
    private fun pickCategory(c: Checkin) {
        val existing = Grouping.categories(store.checkins)
        val options = listOf(DialogOption("None", "Listed under \"${Grouping.OTHER}\" when grouped")) +
            existing.map { DialogOption(it) } +
            DialogOption("New category…", null, "category:new")
        val current = if (c.category.isBlank()) 0 else existing.indexOfFirst { it.equals(c.category, true) } + 1
        pickDialog("Category", options, current) { i ->
            when {
                i == 0 -> change { it.copy(category = "") }
                i <= existing.size -> { val cat = existing[i - 1]; change { it.copy(category = cat) } }
                else -> newCategory()
            }
        }
    }

    private fun newCategory() {
        val input = EditText(this).apply {
            hint = "e.g. Health, Work, Fitness"
            tag = "category-name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val box = column().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        box.add(input)
        AlertDialog.Builder(this)
            .setTitle("New category")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) change { it.copy(category = name) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- triggers

    /** Asks for "physical activity" (the step counter), for running triggers. */
    private fun askActivity() {
        if (!MovementWatch.hasPermission(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION), REQ_ACTIVITY)
        } else {
            store.checkin(checkinId)?.let { AlarmScheduler.schedule(this, it) }
        }
    }

    /** Places already used by any check-in, then "here" and typed coordinates. */
    private fun pickPlace(onPicked: (Place) -> Unit) {
        val known = store.checkins.flatMap { c -> c.times.mapNotNull { it.place } + c.events.mapNotNull { it.place } }
            .distinctBy { Triple(it.name, it.lat, it.lon) }
        val options = known.map { DialogOption(it.name, "${Trigger.coordinates(it)} · ${it.radiusM} m") } + listOf(
            DialogOption("Use my current location…", "Stand where the place is"),
            DialogOption("Enter coordinates…", "Copy them from any maps app"),
        )
        pickDialog("Place", options) { i ->
            when {
                i < known.size -> onPicked(known[i])
                i == known.size -> useCurrentLocation(onPicked)
                else -> enterCoordinates(onPicked)
            }
        }
    }

    private var afterLocationGranted: (() -> Unit)? = null

    private fun useCurrentLocation(onPicked: (Place) -> Unit) {
        if (!LocationTriggers.hasLocation(this)) {
            afterLocationGranted = { useCurrentLocation(onPicked) }
            askLocation(background = false)
            return
        }
        toast("Finding your location…")
        LocationTriggers.locate(this, timeoutMs = 20_000) { loc ->
            if (isFinishing) return@locate
            if (loc == null) {
                toast("Couldn't get a location fix. Try near a window, or enter coordinates.")
            } else {
                namePlace(loc.latitude, loc.longitude, onPicked)
            }
        }
    }

    private fun enterCoordinates(onPicked: (Place) -> Unit) {
        val input = EditText(this).apply {
            hint = "e.g. 40.7128, -74.0060"
            tag = "place-coords"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val box = column().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        box.add(muted("In a maps app, long-press the spot and copy its coordinates.", 13f))
        box.add(input, dp(8))
        AlertDialog.Builder(this)
            .setTitle("Coordinates")
            .setView(box)
            .setPositiveButton("Next") { _, _ ->
                val ll = Place.parseCoordinates(input.text.toString())
                if (ll == null) toast("Those don't look like coordinates (latitude, longitude).") else namePlace(ll.first, ll.second, onPicked)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun namePlace(lat: Double, lon: Double, onPicked: (Place) -> Unit) {
        val box = column().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val name = EditText(this).apply {
            hint = "Name, e.g. Home, Gym, Work"
            tag = "place-name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        box.add(muted(Trigger.coordinates(Place("", lat, lon)), 13f))
        box.add(name, dp(6))
        box.add(label("How close counts as there?", 15f), dp(12))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val ids = Place.RADII.withIndex().associate { (k, it) ->
            val rb = choiceTile(if (it < 1000) "$it m" else "${it / 1000} km").apply {
                id = View.generateViewId()
                tag = "radius:$it"
                isChecked = it == Place.DEFAULT_RADIUS
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            group.addView(rb, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (k > 0) marginStart = dp(6)
            })
            rb.id to it
        }
        box.add(group, dp(2))
        box.add(muted("Phones place you within about 50–200 m, so very small circles can miss.", 12f), dp(4))
        AlertDialog.Builder(this)
            .setTitle("Name this place")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val radius = ids[group.checkedRadioButtonId] ?: Place.DEFAULT_RADIUS
                onPicked(Place(name.text.toString().trim().ifEmpty { "Place" }, lat, lon, radius))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Asks for location access: first "while using the app", then (if [background]) "all the
     * time", which Android grants on a settings screen.
     */
    private fun askLocation(background: Boolean) {
        if (!LocationTriggers.hasLocation(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        if (background && !LocationTriggers.hasBackground(this)) {
            AlertDialog.Builder(this)
                .setTitle("Allow location all the time")
                .setMessage("To notice when you arrive or leave, or check where you are when the alarm is due, Gist " +
                    "needs location while it's closed. On the next screen choose \"Allow all the time\". " +
                    "Your location stays on this phone.")
                .setPositiveButton("Continue") { _, _ ->
                    requestPermissions(arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
                }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        store.checkin(checkinId)?.let { AlarmScheduler.schedule(this, it) } // start watching its place
        render()
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startRecording()
        }
        if (requestCode == REQ_LOCATION && LocationTriggers.hasLocation(this)) {
            val next = afterLocationGranted
            afterLocationGranted = null
            if (next != null) next() else askLocation(background = true)
        }
    }

    // ---------------------------------------------------------------- repeat

    /** Picks a repeat rule starting from [cur]; [onPick] gets the new one. */
    private fun pickRepeat(cur: Repeat, onPick: (Repeat) -> Unit) {
        val current = when {
            cur.kind == Repeat.DAILY -> 0
            cur == Repeat.weekly(Repeat.WEEKDAYS) -> 1
            cur == Repeat.weekly(Repeat.WEEKENDS) -> 2
            cur.kind == Repeat.WEEKLY -> 3
            else -> 4
        }
        val options = listOf(
            DialogOption("Every day"),
            DialogOption("Weekdays (Mon–Fri)"),
            DialogOption("Weekends (Sat & Sun)"),
            DialogOption("Specific days of the week…", "e.g. Mon, Wed, Fri, or every day but Tue"),
            DialogOption("Monthly on certain dates…", "e.g. the 1st and 15th, or the last day"),
        )
        pickDialog("Repeats", options, current) { i ->
            when (i) {
                0 -> onPick(Repeat.EVERY_DAY)
                1 -> onPick(Repeat.weekly(Repeat.WEEKDAYS))
                2 -> onPick(Repeat.weekly(Repeat.WEEKENDS))
                3 -> pickWeekdays(cur, onPick)
                4 -> pickMonthDays(cur, onPick)
            }
        }
    }

    private fun pickWeekdays(cur: Repeat, onPick: (Repeat) -> Unit) {
        val labels = (1..7).map {
            java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        }
        val checked = BooleanArray(7) { i -> cur.kind == Repeat.DAILY || (cur.kind == Repeat.WEEKLY && (i + 1) in cur.days) }
        checklistDialog("Rings on", labels, checked) { ticked ->
            val days = (1..7).filter { ticked[it - 1] }.toSet()
            if (days.isEmpty()) toast("Pick at least one day.") else onPick(Repeat.weekly(days))
        }
    }

    private fun pickMonthDays(cur: Repeat, onPick: (Repeat) -> Unit) {
        val values = (1..31).toList() + Repeat.LAST_DAY
        val labels = (1..31).map { Repeat.ordinal(it) } + "Last day"
        val checked = BooleanArray(values.size) { i -> cur.kind == Repeat.MONTHLY && values[i] in cur.days }
        checklistDialog("Rings each month on", labels, checked, columns = 4,
            message = "Months without a date you pick are skipped; \"Last day\" always rings.") { ticked ->
            val days = values.filterIndexed { i, _ -> ticked[i] }.toSet()
            if (days.isEmpty()) toast("Pick at least one date.") else onPick(Repeat.monthly(days))
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
            REQ_AUDIO -> audioPicked(data)
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
        val kinds = listOf(Background.NONE, Background.PHOTO, Background.SLIDESHOW, Background.VIDEO)
        val options = listOf(
            DialogOption("None", "The app's plain background"),
            DialogOption("One photo…", "Pick a photo"),
            DialogOption("Slideshow of photos…", "Pick up to ${Backgrounds.MAX_PHOTOS}; they fade from one to the next"),
            DialogOption("Video…", "Up to ${Backgrounds.MAX_VIDEO_BYTES / 1024 / 1024} MB; plays muted and loops"),
        )
        pickDialog("Background while ringing", options, kinds.indexOf(c.background.kind)) { i ->
            when (i) {
                0 -> setBackgroundKind(Background.NONE)
                1 -> pickMedia("image/*", multiple = false, REQ_IMAGE)
                2 -> pickMedia("image/*", multiple = true, REQ_IMAGES)
                3 -> pickMedia("video/*", multiple = false, REQ_VIDEO)
            }
        }
    }

    private fun pickInterval(c: Checkin) {
        val values = Background.INTERVALS
        pickDialog("Change photo every", values.map { DialogOption("$it seconds") }, values.indexOf(c.background.intervalSec)) { i ->
            change { it.copy(background = it.background.copy(intervalSec = values[i])) }
        }
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
        }.mapNotNull { SafeUri.picked(this, it) }
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
        private const val REQ_LOCATION = 9
        private const val REQ_BACKGROUND = 10
        private const val REQ_ACTIVITY = 11
        private const val REQ_AUDIO = 12
        private const val REQ_MIC = 13
        private const val MAX_RECORD_MS = 30_000

        fun intent(context: Context, checkinId: String): Intent =
            Intent(context, CheckinEditActivity::class.java).putExtra(EXTRA_ID, checkinId)
    }
}
