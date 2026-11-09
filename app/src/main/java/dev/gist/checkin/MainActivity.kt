package dev.gist.checkin

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast

/** Check-in settings, question editors, and history. */
class MainActivity : BaseActivity() {
    private lateinit var store: Store
    private var scroll: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        Notif.ensureChannels(this)
        AlarmScheduler.scheduleAll(this)
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onResume() {
        super.onResume()
        // An unanswered alarm always takes priority.
        store.prunePending()
        if (store.pending.isNotEmpty()) startActivity(CheckinActivity.alarmIntent(this))
        render()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQ_LOCATION || requestCode == REQ_ACTIVITY) AlarmScheduler.scheduleAll(this) // start watching
        render()
    }

    // ---------------------------------------------------------------- layout

    private fun render() {
        val oldY = scroll?.scrollY ?: 0
        val col = column()
        val checkins = store.checkins

        col.add(header("Gist", todayLong()))

        // Scores: each check-in's on-track share over the last week; groups and the page
        // average them (off check-ins aren't counted).
        val scores = checkins.associate { it.id to Score.of(it, store.allResults(it.id)) }
        fun rollUp(list: List<Checkin>) = Score.average(list.filter { it.enabled }.map { scores[it.id] })
        rollUp(checkins)?.let { overall ->
            val box = row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            box.addWrapped(label(Score.percent(overall), 40f, bold = true, color = scoreColor(overall)).apply {
                tag = "overall-score"
            })
            box.addWeighted(muted("on track over the last ${Score.DAYS} days, across your check-ins", 14f), startMargin = dp(12))
            col.add(box, dp(12))
        }

        setupWarnings(col)

        val bar = row()
        bar.addWeighted(sectionTitle("Check-ins"))
        bar.addWrapped(primaryButton("+ Add check-in") { addCheckin() })
        col.add(bar, dp(28))
        val grouped = store.view == Grouping.BY_CATEGORY
        val groups = if (grouped) Grouping.byCategory(checkins, store.groupOrder) else emptyList()
        if (checkins.size > 1) {
            // View options: grouping, layout, and (when grouped) expand / collapse all.
            val controls = row()
            controls.addWrapped(textButton("${if (grouped) "Grouped by category" else "Ungrouped"} ▾") { pickView() }.apply {
                tag = "view-mode"
            })
            controls.addWrapped(textButton("${if (store.style == Grouping.COMPACT) "Compact" else "Cards"} ▾") { pickStyle() }.apply {
                tag = "style-mode"
            })
            if (grouped && groups.size > 1) {
                val keys = groups.map { Grouping.key(it.first) }.toSet()
                val allCollapsed = keys.all { it in store.collapsed }
                controls.addWrapped(textButton(if (allCollapsed) "Expand all" else "Collapse all") {
                    store.collapsed = if (allCollapsed) store.collapsed - keys else store.collapsed + keys
                    render()
                }.apply { tag = "toggle-all-groups" })
            }
            col.add(controls, dp(4))
        }
        if (grouped) {
            // Each group can be dragged above / below the others by its ≡, and tapped to fold.
            val outer = ReorderList(this) { from, to ->
                store.groupOrder = Grouping.moveGroup(groups.map { it.first }, from, to)
                render()
            }
            for ((category, members) in groups) {
                val key = Grouping.key(category)
                val folded = key in store.collapsed
                val section = column()
                val head = row().apply {
                    isClickable = true
                    tag = "group-header:$category"
                    contentDescription = "$category, ${if (folded) "collapsed" else "expanded"}"
                    setPadding(0, dp(6), 0, dp(6))
                    setOnClickListener {
                        store.collapsed = if (folded) store.collapsed - key else store.collapsed + key
                        render()
                    }
                }
                val handle = label("≡", 22f, color = colorMuted).apply {
                    setPadding(0, 0, dp(12), 0)
                    tag = "drag-group:$category"
                    contentDescription = "Drag to reorder the $category group"
                }
                if (groups.size > 1) head.addWrapped(handle)
                val groupScore = rollUp(members)
                val groupColor = groupScore?.let { scoreColor(it) } ?: colorPrimary
                head.addWeighted(label(category, 15f, bold = true, color = groupColor).apply { tag = "group:$category" })
                groupScore?.let {
                    head.addWrapped(label(Score.percent(it), 15f, bold = true, color = groupColor).apply {
                        tag = "group-score:$category"
                    }, startMargin = dp(8))
                }
                head.addWrapped(label(if (folded) "▸" else "▾", 18f, color = groupColor).apply { tag = "group-chevron:$category" },
                    startMargin = dp(8))
                section.add(head)
                if (!folded) section.add(checkinList(checkins, members), dp(6))
                outer.addRow(section, handle, dp(18))
            }
            col.add(outer, dp(10))
        } else if (checkins.isNotEmpty()) {
            col.add(checkinList(checkins, checkins), dp(12))
        }
        if (checkins.isEmpty()) {
            col.add(card().apply { add(muted("No check-ins yet. Add one to get started.", 15f)) }, dp(12))
        }

        col.add(outlineButton("Settings · backup · dark mode") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, dp(28))
        col.add(textButton("About Gist · Donate") { startActivity(AboutActivity.intent(this)) }.apply {
            tag = "about"
        }, dp(4))

        val sv = screen(col)
        setContentView(sv)
        scroll = sv
        sv.post { sv.scrollTo(0, oldY) }
    }

    /**
     * One check-in: name with its on/off switch, times and steps, last-7-days stat, the next
     * ring (with Do it now / Skip), anything already done early or skipped, and History / Edit.
     */
    /** [members] as cards that can be dragged up and down by their ≡ handle (within this list). */
    private fun checkinList(all: List<Checkin>, members: List<Checkin>): ReorderList {
        val list = ReorderList(this) { from, to ->
            store.reorder(Grouping.reorderWithin(all.map { it.id }, members.map { it.id }, from, to))
            render()
        }
        members.forEach { c ->
            val handle = label("≡", 24f, color = colorMuted).apply {
                setPadding(0, 0, dp(12), 0)
                tag = "drag-checkin:${c.id}"
                contentDescription = "Drag to reorder ${c.name}"
            }
            val h = if (members.size > 1) handle else null
            val score = if (c.enabled) Score.of(c, store.allResults(c.id)) else null
            val v = if (store.style == Grouping.COMPACT) checkinRow(c, h, score) else checkinCard(c, h, score)
            list.addRow(v, handle, if (store.style == Grouping.COMPACT) dp(6) else dp(12))
        }
        return list
    }

    private fun pickView() {
        val values = listOf(Grouping.UNGROUPED, Grouping.BY_CATEGORY)
        val options = listOf(
            DialogOption("Ungrouped", "One list, in your order", "view:ungrouped"),
            DialogOption("Grouped by category", "A section per category; set a check-in's category on its page", "view:category"),
        )
        pickDialog("View", options, values.indexOf(store.view)) { i ->
            store.view = values[i]
            render()
        }
    }

    private fun pickStyle() {
        val values = listOf(Grouping.CARDS, Grouping.COMPACT)
        val options = listOf(
            DialogOption("Cards", "Everything at a glance: next ring, Skip / Do it now, stats", "style:cards"),
            DialogOption("Compact", "One line each: name, next ring and on / off. Tap to open", "style:compact"),
        )
        pickDialog("Layout", options, values.indexOf(store.style)) { i ->
            store.style = values[i]
            render()
        }
    }

    private fun alarmSwitch(c: Checkin) = styledSwitch().apply {
        isChecked = c.enabled
        tag = "toggle:${c.id}"
        contentDescription = "${c.name} alarm on"
        setOnCheckedChangeListener { _, on ->
            store.updateCheckin(c.id) { it.copy(enabled = on) }
            store.checkin(c.id)?.let { AlarmScheduler.schedule(this@MainActivity, it) }
            render()
        }
    }

    /** The compact layout: one line per check-in. Tap it to open the check-in's page. */
    /** The color for a score, between the off-track red and the on-track green. */
    private fun scoreColor(score: Double) = Score.color(score, colorOf(R.color.bad), colorOf(R.color.good))

    /** [base] with a border in the score's color (or as is, without a score yet). */
    private fun scoreBackground(base: Int, radius: Int, score: Double?) = rounded(base, radius).apply {
        if (score != null) setStroke(dp(2), scoreColor(score))
    }

    private fun scoreLabel(c: Checkin, score: Double?): View? = score?.let {
        label(Score.percent(it), 15f, bold = true, color = scoreColor(it)).apply {
            tag = "score:${c.id}"
            contentDescription = "${Score.percent(it)} on track"
        }
    }

    private fun checkinRow(c: Checkin, handle: View?, score: Double?): LinearLayout = row().apply {
        background = scoreBackground(colorOf(R.color.surface_variant), 14, score)
        setPadding(dp(14), dp(10), dp(10), dp(10))
        isClickable = true
        tag = "row:${c.id}"
        setOnClickListener { startActivity(CheckinEditActivity.intent(this@MainActivity, c.id)) }
        handle?.let { addWrapped(it) }
        val text = column()
        text.add(label(c.name, 16f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val sub = if (c.enabled) "Next: ${CheckinText.next(c)}" else "Off"
        text.add(muted(sub, 13f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; tag = "next-line:${c.id}" }, dp(2))
        addWeighted(text)
        scoreLabel(c, score)?.let { addWrapped(it, startMargin = dp(8)) }
        addWrapped(alarmSwitch(c), startMargin = dp(4))
    }

    private fun checkinCard(c: Checkin, handle: View? = null, score: Double? = null): LinearLayout {
        val card = card()
        if (score != null) card.background = scoreBackground(colorOf(R.color.surface), 20, score)

        val titleRow = row()
        handle?.let { titleRow.addWrapped(it) }
        titleRow.addWeighted(label(c.name, 20f, bold = true))
        scoreLabel(c, score)?.let { titleRow.addWrapped(it, startMargin = dp(8)) }
        titleRow.addWrapped(alarmSwitch(c), startMargin = dp(4))
        card.add(titleRow)
        card.add(muted(CheckinText.oneLine(c)), dp(2))

        if (c.questions.isNotEmpty()) {
            val results = store.allResults(c.id)
            card.add(muted(Stats.line(c, results, 7), 13f), dp(4))
        }

        if (c.enabled) {
            // Rings coming up that were already handled (done early / skipped), with Undo for skips.
            val soon = AlarmScheduler.occurrences(c).filter { it.at - System.currentTimeMillis() < 24 * 3600_000L }
            for (o in soon) {
                val r = store.result(o.day, c.id, o.timeKey) ?: continue
                val line = row()
                val (text, fg, bg) = if (r.status == SlotResult.SKIPPED) {
                    Triple("${whenText(o)} · skipped", colorMuted, colorOf(R.color.surface_variant))
                } else {
                    Triple("${whenText(o)} · done early ✓", colorOf(R.color.good), colorOf(R.color.good_container))
                }
                line.addWeighted(chip(text, fg, bg).apply { tag = "closed:${o.timeKey}" })
                if (r.status == SlotResult.SKIPPED) {
                    line.addWrapped(textButton("Undo") {
                        store.clearResult(o.day, c.id, o.timeKey)
                        AlarmScheduler.schedule(this, c)
                        render()
                    })
                }
                card.add(line, dp(8))
            }

            val next = AlarmScheduler.upcoming(this, c)
            if (next != null) {
                val box = column().apply {
                    background = rounded(colorOf(R.color.surface_variant), 14)
                    setPadding(dp(14), dp(10), dp(10), dp(10))
                }
                val r = row()
                r.addWeighted(label("Next · ${whenText(next)}", 15f).apply { tag = "next:${c.id}" })
                r.addWrapped(textButton("Skip", colorMuted) { skip(c, next) })
                r.addWrapped(button("Do it now") { doItNow(c, next) }, dp(4))
                box.add(r)
                card.add(box, dp(10))
            }
            if (c.events.isNotEmpty()) {
                val box = column().apply {
                    background = rounded(colorOf(R.color.surface_variant), 14)
                    setPadding(dp(14), dp(10), dp(10), dp(10))
                }
                c.events.forEachIndexed { i, t ->
                    val icon = if (t.isPlace) "📍" else "🏃"
                    box.add(label("$icon Rings ${CheckinText.event(t)}", 15f).apply {
                        tag = if (i == 0 && next == null) "next:${c.id}" else "event:${c.id}:$i"
                    }, if (i == 0) 0 else dp(4))
                }
                if (c.placeEvents.isNotEmpty() && !LocationTriggers.hasBackground(this)) {
                    box.add(label("Needs location \"all the time\" (see Finish setup)", 13f, color = colorWarn), dp(2))
                }
                if (c.runEvents.isNotEmpty() && !MovementWatch.canWatch(this)) {
                    box.add(label(MovementWatch.problem(this), 13f, color = colorWarn), dp(2))
                }
                card.add(box, dp(10))
            }
        }

        // Right-aligned with gravity. (An empty spacer view here would grab all the height
        // it's offered, which stretched the card to fill the screen.)
        val actions = row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        if (c.questions.isNotEmpty()) {
            actions.addWrapped(textButton("History") { startActivity(HistoryActivity.intent(this, c.id)) })
        }
        actions.addWrapped(textButton("Edit") { startActivity(CheckinEditActivity.intent(this, c.id)) }, dp(4))
        card.add(actions, dp(6))
        return card
    }

    /** "today 9:30 PM", "tomorrow 7:00 AM", "Wed 7:00 AM", "Dec 1 7:00 AM" */
    private fun whenText(o: Occurrence): String = CheckinText.at(o.at)

    /** Skip the next ring (only possible before it rings). */
    private fun skip(c: Checkin, o: Occurrence) {
        store.markSkipped(o.day, c.id, o.timeKey)
        AlarmScheduler.schedule(this, c)
        Toast.makeText(this, "Skipped ${whenText(o)}", Toast.LENGTH_SHORT).show()
        render()
    }

    /** Resolve the next ring now: same steps (a scan must really be scanned), no alarm. */
    private fun doItNow(c: Checkin, o: Occurrence) {
        startActivity(CheckinActivity.earlyIntent(this, c.id, o.day, o.timeKey))
    }

    private fun setupWarnings(col: LinearLayout) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            items += "Allow notifications (needed for the alarm screen)" to {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        }
        if (Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            items += "Allow full-screen alarms (shows over the lock screen)" to {
                openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && !AlarmScheduler.canExact(this)) {
            items += "Allow alarms & reminders (rings on time)" to {
                openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            }
        }
        if (LocationTriggers.anyUseLocation(this) && !LocationTriggers.hasBackground(this)) {
            items += "Allow location all the time (for place-based check-ins)" to {
                if (!LocationTriggers.hasLocation(this)) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
                } else {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_LOCATION)
                }
            }
        }
        if (MovementWatch.anyUse(this) && !MovementWatch.hasPermission(this)) {
            items += "Allow physical activity (to notice when you run)" to {
                requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQ_ACTIVITY)
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            items += "Allow display over other apps (brings a ringing alarm back if you leave it)" to {
                openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            }
        }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            items += "Turn off battery optimization (some phones kill alarms)" to {
                openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            }
        }
        if (items.isEmpty()) return
        val box = card(colorOf(R.color.warn_container))
        box.add(label("Finish setup", 17f, bold = true, color = colorWarn))
        box.add(muted("So alarms ring on time, show over the lock screen, and can't be escaped.", 13f), dp(2))
        items.forEach { (text, action) -> box.add(outlineButton(text, action), dp(8)) }
        col.add(box, dp(20))
    }

    // ---------------------------------------------------------------- actions

    private fun addCheckin() {
        startActivity(LibraryActivity.intent(this))
    }

    // ---------------------------------------------------------------- helpers

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_NOTIF = 1
        private const val REQ_LOCATION = 2
        private const val REQ_ACTIVITY = 3
    }
}
