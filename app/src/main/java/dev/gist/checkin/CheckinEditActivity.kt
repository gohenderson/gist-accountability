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
        col.add(button("Category: ${c.category.ifBlank { "none" }}") { pickCategory(c) }.apply { tag = "category" }, dp(8))

        // Schedule
        col = section(page, "Schedule")
        val sw = styledSwitch("Alarm on").apply {
            isChecked = c.enabled
            setOnCheckedChangeListener { _, on -> change { it.copy(enabled = on) } }
        }
        col.add(sw, dp(4))
        val t = c.trigger
        val kindText = when (t.kind) {
            Trigger.ARRIVE -> "when I arrive at a place"
            Trigger.LEAVE -> "when I leave a place"
            Trigger.RUN_START -> "when I start running"
            Trigger.RUN_END -> "when I finish running"
            else -> "at set times"
        }
        col.add(button("Rings $kindText") { pickTriggerKind(c) }.apply { tag = "trigger-kind" }, dp(8))
        if (t.isEvent) {
            if (t.isPlace) col.add(placeButton(t.place) { pickPlace { p -> setTrigger { it.copy(place = p) } } }, dp(4))
            if (t.kind == Trigger.RUN_END) {
                val min = if (t.minRunMinutes == 0) "any length" else "at least ${t.minRunMinutes} min"
                col.add(button("Run: $min") { pickMinRun(c) }.apply { tag = "trigger-min-run" }, dp(4))
            }
            if (t.isMovement) {
                col.add(muted("Noticed from your step rate (running is about 140+ steps a minute), so it works " +
                    "with the phone in a pocket or armband, and on a treadmill. A run counts after about a " +
                    "minute, and ends about 1½ minutes after you stop or slow to a walk.", 13f), dp(6))
                if (!MovementWatch.canWatch(this)) {
                    col.add(label(MovementWatch.problem(this), 14f, color = colorWarn), dp(6))
                    if (MovementWatch.hasSensor(this)) {
                        col.add(outlineButton("Allow physical activity") { askActivity() }.apply { tag = "allow-activity" }, dp(4))
                    }
                }
            }
            col.add(label("Only when all of these hold:", 15f), dp(12))
            col.add(button("Days: ${c.repeat.describe()}") { pickRepeat(c) }.apply { tag = "repeat" }, dp(4))
            val window = Trigger.windowText(t) { Days.time(it.hour, it.minute) }.ifEmpty { "any time of day" }
            col.add(button("Time: $window") { pickWindow(c) }.apply { tag = "trigger-window" }, dp(4))
            col.add(styledSwitch("At most once a day").apply {
                isChecked = t.oncePerDay
                tag = "trigger-once"
                setOnCheckedChangeListener { _, on -> setTrigger { it.copy(oncePerDay = on) } }
            }, dp(8))
            col.add(label(if (c.enabled) "Rings ${CheckinText.whenText(c)}" else "Alarm is off", 14f).apply {
                tag = "trigger-summary"
            }, dp(6))
        } else {
            col.add(button("Repeats: ${c.repeat.describe()}") { pickRepeat(c) }.apply { tag = "repeat" }, dp(8))
            col.add(label("Rings at:", 15f), dp(12))
            c.times.forEachIndexed { i, tm ->
                val r = row()
                r.addView(
                    button(Days.time(tm.hour, tm.minute)) { pickTime(c, i) }.apply { tag = "time:$i" },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                if (c.times.size > 1) {
                    r.addView(button("✕") { removeTime(i) }.apply {
                        tag = "remove-time:$i"
                        contentDescription = "Remove ${Days.time(tm.hour, tm.minute)}"
                    })
                }
                col.add(r, dp(4))
            }
            col.add(button("+ Add time") { pickTime(c, null) }, dp(4))
            val whereText = if (t.where == Trigger.ANYWHERE || t.place == null) "anywhere" else Trigger.describe(t)
            col.add(button("Where: $whereText") { pickWhere(c) }.apply { tag = "trigger-where" }, dp(8))
            if (t.where != Trigger.ANYWHERE) {
                col.add(placeButton(t.place) { pickPlace { p -> setTrigger { it.copy(place = p) } } }, dp(4))
            }
            col.add(label(if (c.enabled) "Next: ${CheckinText.next(c)}" else "Alarm is off", 14f), dp(4))
        }
        if (c.enabled && t.usesLocation && !LocationTriggers.hasBackground(this)) {
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
        if (c.steps.isEmpty()) {
            col.add(muted("No steps: the alarm just shows a Dismiss button.", 13f), dp(2))
        } else {
            col.add(muted("Steps run from top to bottom. Drag ≡ to change the order; tap a step to set it up.", 13f), dp(2))
            val list = ReorderList(this) { from, to -> change { Steps.move(it, from, to) } }
            c.steps.forEachIndexed { i, st -> stepRow(list, i, st) }
            col.add(list, dp(10))
        }
        col.add(button("+ Add step") { addStep(c) }.apply { tag = "add-step" }, dp(10))

        // Test & delete
        col = section(page, "Try it")
        col.add(button("Test: ring in 10 seconds") {
            AlarmScheduler.scheduleTest(this, checkinId)
            toast("Ringing in 10 seconds. Lock your phone to see the full-screen version. Nothing is logged, and the next real alarm still rings.")
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

    private fun setTrigger(f: (Trigger) -> Trigger) = change { it.copy(trigger = f(it.trigger)) }

    private fun pickMinRun(c: Checkin) {
        val values = listOf(0, 5, 10, 20, 30, 45, 60)
        pickDialog("Only after a run of", values.map { DialogOption(if (it == 0) "Any length" else "At least $it minutes") },
            values.indexOf(c.trigger.minRunMinutes)) { i -> setTrigger { it.copy(minRunMinutes = values[i]) } }
    }

    /** Asks for "physical activity" (the step counter), for running triggers. */
    private fun askActivity() {
        if (!MovementWatch.hasPermission(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION), REQ_ACTIVITY)
        } else {
            store.checkin(checkinId)?.let { AlarmScheduler.schedule(this, it) }
        }
    }

    private fun placeButton(p: Place?, onClick: () -> Unit) =
        button(if (p == null) "Place: choose…" else "Place: ${p.name} · ${p.radiusM} m") { onClick() }.apply {
            tag = "trigger-place"
        }

    private fun pickTriggerKind(c: Checkin) {
        val kinds = listOf(Trigger.TIME, Trigger.ARRIVE, Trigger.LEAVE, Trigger.RUN_START, Trigger.RUN_END)
        val options = listOf(
            DialogOption("At set times", "Like an alarm clock, on the days you choose", "kind:time"),
            DialogOption("When I arrive at a place", "Uses the phone's location", "kind:arrive"),
            DialogOption("When I leave a place", "Uses the phone's location", "kind:leave"),
            DialogOption("When I start running", "Uses the phone's step sensor", "kind:run-start"),
            DialogOption("When I finish running", "Uses the phone's step sensor", "kind:run-end"),
        )
        pickDialog("Rings", options, kinds.indexOf(c.trigger.kind)) { i ->
            val kind = kinds[i]
            setTrigger { it.copy(kind = kind) }
            when {
                kind == Trigger.ARRIVE || kind == Trigger.LEAVE -> afterLocationChoice()
                kind == Trigger.RUN_START || kind == Trigger.RUN_END -> askActivity()
            }
        }
    }

    private fun pickWhere(c: Checkin) {
        val values = listOf(Trigger.ANYWHERE, Trigger.AT, Trigger.AWAY)
        val options = listOf(
            DialogOption("Anywhere"),
            DialogOption("Only when I'm at a place", "Checked when it's due; elsewhere, that time is skipped"),
            DialogOption("Only when I'm away from a place", "Checked when it's due; there, that time is skipped"),
        )
        pickDialog("Ring only when", options, values.indexOf(c.trigger.where)) { i ->
            setTrigger { it.copy(where = values[i]) }
            if (values[i] != Trigger.ANYWHERE) afterLocationChoice()
        }
    }

    /** After choosing something that needs a place: pick one if none, then ask for location access. */
    private fun afterLocationChoice() {
        val c = store.checkin(checkinId) ?: return
        if (c.trigger.place == null) {
            pickPlace { p -> setTrigger { it.copy(place = p) }; askLocation(background = true) }
        } else {
            askLocation(background = true)
        }
    }

    private fun pickWindow(c: Checkin) {
        val any = c.trigger.windowStart == null
        pickDialog("Time of day", listOf(DialogOption("Any time of day"), DialogOption("Only between…", "Pick a start and an end time")),
            if (any) 0 else 1) { i ->
            if (i == 0) {
                setTrigger { it.copy(windowStart = null, windowEnd = null) }
            } else {
                val s0 = c.trigger.windowStart ?: AlarmTime(6, 0)
                val e0 = c.trigger.windowEnd ?: AlarmTime(10, 0)
                TimePickerDialog(this, { _, h, m ->
                    TimePickerDialog(this, { _, h2, m2 ->
                        setTrigger { it.copy(windowStart = AlarmTime(h, m), windowEnd = AlarmTime(h2, m2)) }
                    }, e0.hour, e0.minute, DateFormat.is24HourFormat(this)).apply { setTitle("…and") }.show()
                }, s0.hour, s0.minute, DateFormat.is24HourFormat(this)).apply { setTitle("From") }.show()
            }
        }
    }

    /** Places already used by any check-in, then "here" and typed coordinates. */
    private fun pickPlace(onPicked: (Place) -> Unit) {
        val known = store.checkins.mapNotNull { it.trigger.place }.distinctBy { Triple(it.name, it.lat, it.lon) }
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
        if (requestCode == REQ_LOCATION && LocationTriggers.hasLocation(this)) {
            val next = afterLocationGranted
            afterLocationGranted = null
            if (next != null) next() else askLocation(background = true)
        }
    }

    // ---------------------------------------------------------------- repeat

    private fun pickRepeat(c: Checkin) {
        val r = c.repeat
        val current = when {
            r.kind == Repeat.DAILY -> 0
            r == Repeat.weekly(Repeat.WEEKDAYS) -> 1
            r == Repeat.weekly(Repeat.WEEKENDS) -> 2
            r.kind == Repeat.WEEKLY -> 3
            else -> 4
        }
        val options = listOf(
            DialogOption("Every day"),
            DialogOption("Weekdays (Mon–Fri)"),
            DialogOption("Weekends (Sat & Sun)"),
            DialogOption("Specific days of the week…", "e.g. Mon, Wed, Fri"),
            DialogOption("Monthly on certain dates…", "e.g. the 1st and 15th, or the last day"),
        )
        pickDialog("Repeats", options, current) { i ->
            when (i) {
                0 -> change { it.copy(repeat = Repeat.EVERY_DAY) }
                1 -> change { it.copy(repeat = Repeat.weekly(Repeat.WEEKDAYS)) }
                2 -> change { it.copy(repeat = Repeat.weekly(Repeat.WEEKENDS)) }
                3 -> pickWeekdays(c)
                4 -> pickMonthDays(c)
            }
        }
    }

    private fun pickWeekdays(c: Checkin) {
        val labels = (1..7).map {
            java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        }
        val checked = BooleanArray(7) { i -> c.repeat.kind == Repeat.WEEKLY && (i + 1) in c.repeat.days }
        checklistDialog("Rings on", labels, checked) { ticked ->
            val days = (1..7).filter { ticked[it - 1] }.toSet()
            if (days.isEmpty()) toast("Pick at least one day.") else change { it.copy(repeat = Repeat.weekly(days)) }
        }
    }

    private fun pickMonthDays(c: Checkin) {
        val values = (1..31).toList() + Repeat.LAST_DAY
        val labels = (1..31).map { Repeat.ordinal(it) } + "Last day"
        val checked = BooleanArray(values.size) { i -> c.repeat.kind == Repeat.MONTHLY && values[i] in c.repeat.days }
        checklistDialog("Rings each month on", labels, checked, columns = 4,
            message = "Months without a date you pick are skipped; \"Last day\" always rings.") { ticked ->
            val days = values.filterIndexed { i, _ -> ticked[i] }.toSet()
            if (days.isEmpty()) {
                toast("Pick at least one date.")
            } else {
                change { it.copy(repeat = Repeat.monthly(days)) }
            }
        }
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

        fun intent(context: Context, checkinId: String): Intent =
            Intent(context, CheckinEditActivity::class.java).putExtra(EXTRA_ID, checkinId)
    }
}
