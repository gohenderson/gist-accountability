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
import android.util.TypedValue
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
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
        if (store.alarmPending.isNotEmpty()) startActivity(CheckinActivity.alarmIntent(this))
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
        val oldY = scroll?.pageScrollY ?: 0
        val col = column()
        val all = store.checkins
        val open = all.filter { !it.taskDone } // done tasks have their own page
        // Tabs: Check-ins, Tasks (active) or Backlog (tasks that are off).
        val tab = store.startTab
        val checkins = open.filter {
            when (tab) {
                Store.TAB_TASKS -> it.isTask && !it.inBacklog
                Store.TAB_BACKLOG -> it.inBacklog
                else -> !it.isTask
            }
        }
        val doneTasks = if (tab == Store.TAB_TASKS) all.count { it.taskDone } else 0

        // Title, with Settings, About and Donate across from it.
        val top = row().apply { gravity = android.view.Gravity.TOP }
        top.addWeighted(header("Gist", todayLong()))
        top.addWrapped(iconButton("⚙️", "Settings", "settings") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        top.addWrapped(iconButton("❓", "About Gist", "about") { startActivity(AboutActivity.intent(this)) })
        top.addWrapped(iconButton("💲", "Donate", "donate") { openLink(Links.DONATE) })
        col.add(top)

        // Scores: each check-in's on-track share over the last week; groups and the page
        // average them (off check-ins aren't counted).
        val scores = checkins.associate { it.id to Score.of(it, store.allResults(it.id)) }
        // Tasks are one-offs, so they don't count toward the score.
        fun rollUp(list: List<Checkin>) = Score.average(list.filter { it.enabled && !it.isTask }.map { scores[it.id] })
        // A summary per tab, always there so the tabs stay put when you switch.
        val box = row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        fun summary(big: String, color: Int, tag: String, text: String) {
            box.addWrapped(label(big, 40f, bold = true, color = color).apply { this.tag = tag })
            box.addWeighted(muted(text, 14f).apply { this.tag = "summary-text" }, startMargin = dp(12))
        }
        val days = Score.DAYS
        when (tab) {
            Store.TAB_TASKS -> store.tasksDoneSince(days).let { n ->
                summary("$n", colorGood, "tasks-done-count", "${if (n == 1) "task" else "tasks"} done in the last $days days")
            }
            Store.TAB_BACKLOG -> store.tasksBackloggedSince(days).let { n ->
                summary("$n", colorPrimary, "backlog-added-count",
                    "${if (n == 1) "task" else "tasks"} added to the backlog in the last $days days")
            }
            else -> when (val overall = rollUp(checkins)) {
                null -> summary("–", colorMuted, "overall-score-none",
                    "on track over the last $days days: shows once you've answered some check-ins")
                else -> summary(Score.percent(overall), scoreColor(overall), "overall-score",
                    "on track over the last $days days")
            }
        }
        col.add(box, dp(12))

        setupWarnings(col)

        col.add(tabs(tab), dp(28))
        val grouped = store.view == Grouping.BY_CATEGORY
        val groups = if (grouped) Grouping.byCategory(checkins, store.groupOrder) else emptyList()
        run {
            // View (grouping and sort), and expand / collapse all (groups and check-ins);
            // always shown, so the list starts at the same place on every tab.
            val controls = row()
            controls.addWrapped(textButton("View: ${Grouping.sortName(store.sort)} ▾") { pickView() }.apply {
                tag = "view-mode"
                contentDescription = "View: ${if (grouped) "by category, " else ""}${Grouping.sortName(store.sort)}"
            })
            val keys = groups.map { groupKey(tab, it.first) }.toSet()
            val ids = checkins.map { it.id }.toSet()
            val allFolded = Grouping.allFolded(keys, store.collapsed, ids, store.collapsedCheckins)
            controls.addWrapped(textButton(if (allFolded) "Expand all" else "Collapse all") {
                if (allFolded) {
                    store.collapsed = store.collapsed - keys
                    store.collapsedCheckins = store.collapsedCheckins - ids
                } else {
                    store.collapsed = store.collapsed + keys
                    store.collapsedCheckins = store.collapsedCheckins + ids
                }
                render()
            }.apply { tag = "toggle-all" })
            col.add(controls, dp(4))
        }
        if (grouped) {
            // Each group can be dragged above / below the others by its ≡, and tapped to fold.
            val outer = ReorderList(this) { from, to ->
                store.groupOrder = Grouping.moveGroup(groups.map { it.first }, from, to)
                render()
            }
            for ((category, members) in groups) {
                val key = groupKey(tab, category)
                val folded = key in store.collapsed
                val section = column()
                // The group's color shows its score; the number is only read out (it's at the top).
                val groupScore = rollUp(members)
                val head = row().apply {
                    isClickable = true
                    tag = "group-header:$category"
                    contentDescription = "$category, ${if (folded) "collapsed" else "expanded"}" +
                        (groupScore?.let { ", ${Score.percent(it)} on track" } ?: "")
                    setPadding(0, dp(6), 0, dp(6))
                    setOnClickListener {
                        store.collapsed = if (folded) store.collapsed - key else store.collapsed + key
                        render()
                    }
                }
                val handle = label("≡", 22f, color = colorMuted).apply {
                    includeFontPadding = false
                    setLineSpacing(0f, 1f)
                    setPadding(0, 0, dp(12), 0)
                    tag = "drag-group:$category"
                    contentDescription = "Drag to reorder the $category group"
                }
                if (groups.size > 1) head.addWrapped(handle)
                val groupColor = groupScore?.let { scoreColor(it) } ?: colorPrimary
                head.addWeighted(label(category, 15f, bold = true, color = groupColor).apply { tag = "group:$category" })
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
            val empty = when {
                open.isEmpty() -> "No check-ins yet. Add one to get started."
                tab == Store.TAB_TASKS -> "No active tasks. Tap + for a new one, or pick some from the Backlog."
                tab == Store.TAB_BACKLOG -> "The backlog is empty. Tap + to add a task to it."
                else -> "No check-ins here: they're all tasks. Tap + to add one."
            }
            col.add(card().apply { add(muted(empty, 15f)) }, dp(12))
        }

        if (doneTasks > 0) {
            col.add(outlineButton("Done tasks ($doneTasks)") {
                startActivity(DoneTasksActivity.intent(this))
            }.apply { tag = "done-tasks" }, dp(20))
        }

        val sv = screen(col)
        setContentView(sv)
        scroll = sv
        sv.restoreScroll(oldY)
    }

    /**
     * One check-in: name with its on/off switch, times and steps, last-7-days stat, the next
     * ring (with Do it now / Skip), anything already done early or skipped, and History / Edit.
     */
    /**
     * [members] as cards, in the chosen sort. In My order they can be dragged up and down by
     * their ≡ handle (within this list); sorted any other way there are no handles.
     */
    private fun checkinList(all: List<Checkin>, unsorted: List<Checkin>): ReorderList {
        val how = store.sort
        val members = Grouping.sort(unsorted, how,
            next = { c -> if (c.enabled) AlarmScheduler.nextOpenOccurrence(store, c)?.at else null },
            score = { c -> if (c.enabled) Score.of(c, store.allResults(c.id)) else null })
        val list = ReorderList(this) { from, to ->
            store.reorder(Grouping.reorderWithin(all.map { it.id }, members.map { it.id }, from, to))
            render()
        }
        members.forEach { c ->
            val handle = label("≡", 24f, color = colorMuted).apply {
                // No font padding or extra line height: the handle mustn't make the card taller.
                includeFontPadding = false
                setLineSpacing(0f, 1f)
                setPadding(0, 0, dp(12), 0)
                tag = "drag-checkin:${c.id}"
                contentDescription = "Drag to reorder ${c.name}"
            }
            val h = if (members.size > 1 && how == Grouping.SORT_CUSTOM) handle else null
            val score = if (c.enabled) Score.of(c, store.allResults(c.id)) else null
            val folded = c.id in store.collapsedCheckins
            val v = if (folded) checkinRow(c, h, score) else checkinCard(c, h, score)
            list.addRow(v, handle, dp(10))
        }
        return list
    }

    /**
     * View: group by category or not, and the sort (My order, Next alarm, Needs attention,
     * Name). Each tap applies it and closes the menu.
     */
    private fun pickView() {
        lateinit var dialog: android.app.AlertDialog
        val sorts = Grouping.SORTS
        val details = listOf(
            "As you've dragged them",
            "Soonest first; ones without a set time go last",
            "Lowest on-track score first; ones without a score go last",
            "A to Z",
        )
        val body = dialogBody {
            add(checkTile("By category", store.view == Grouping.BY_CATEGORY,
                "A section per category; set a check-in's category on its page").apply {
                tag = "view:category"
                setOnCheckedChangeListener { _, on ->
                    store.view = if (on) Grouping.BY_CATEGORY else Grouping.UNGROUPED
                    dialog.dismiss()
                    render()
                }
            })
            add(sectionTitle("Sort"), dp(14))
            sorts.forEachIndexed { i, how ->
                add(optionTile(DialogOption(Grouping.sortName(how), details[i], "sort:$how"), how == store.sort) {
                    store.sort = how
                    dialog.dismiss()
                    render()
                }, dp(8))
            }
        }
        dialog = android.app.AlertDialog.Builder(this)
            .setTitle("View")
            .setView(body)
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
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

    /**
     * Check-ins, Tasks and Backlog as tabs (one at a time), then + to add one. Check-ins are
     * everything that isn't a task (they ring whichever tab is open); Tasks are active tasks
     * (with the Done tasks button); Backlog is tasks that are off.
     */
    private fun tabs(current: String): LinearLayout {
        val r = row().apply { gravity = Gravity.CENTER_VERTICAL }
        for ((key, label) in listOf(Store.TAB_CHECKINS to "Check-ins", Store.TAB_TASKS to "Tasks", Store.TAB_BACKLOG to "Backlog")) {
            val on = key == current
            val b = toggleTab(label, on).apply {
                tag = "tab:$key"
                contentDescription = "$label tab${if (on) ", selected" else ""}"
                setOnClickListener {
                    if (!on) {
                        store.startTab = key
                        render()
                    }
                }
            }
            r.addWeighted(b, startMargin = if (r.childCount == 0) 0 else dp(8))
        }
        r.addWrapped(primaryButton("+") { add(current) }.apply {
            tag = "add"
            contentDescription = when (current) {
                Store.TAB_TASKS -> "New task"
                Store.TAB_BACKLOG -> "New backlog task"
                else -> "Add check-in"
            }
            minWidth = dp(48)
            minimumWidth = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(dp(12), 0, dp(12), 0)
        }, dp(8))
        return r
    }

    /** The color for a score, between the off-track red and the on-track green. */
    private fun scoreColor(score: Double) = Score.color(score, colorOf(R.color.bad), colorOf(R.color.good))

    /**
     * What's left of a check-in's title: a dot filled with its score's color (an empty ring
     * until it has a score). Tasks have none: they're on their own tabs.
     */
    private fun marker(score: Double?): View = View(this).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (score != null) setColor(scoreColor(score)) else setStroke(dp(2), colorMuted)
        }
        layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
    }

    /** Folds or unfolds a check-in's card. */
    private fun toggleFolded(c: Checkin) {
        val folded = store.collapsedCheckins
        store.collapsedCheckins = if (c.id in folded) folded - c.id else folded + c.id
        render()
    }

    /**
     * A check-in's title line: drag handle, its dot (a check-in's, in its score's color; the
     * number is only read out, and shown on its page; tasks have none), name, chevron and
     * on/off switch. Tapping it
     * folds the card to this line (plus the next ring) or unfolds it again.
     */
    private fun titleRow(c: Checkin, handle: View?, score: Double?, folded: Boolean, size: Float): LinearLayout = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        tag = "checkin-header:${c.id}"
        contentDescription = "${if (c.isTask) "Task: " else ""}${c.name}, ${if (folded) "collapsed" else "expanded"}" +
            (score?.let { ", ${Score.percent(it)} on track" } ?: "")
        setOnClickListener { toggleFolded(c) }
        handle?.let { addWrapped(it) }
        if (!c.isTask) {
            val m = marker(score).apply { tag = "marker:${c.id}" }
            addView(m, (m.layoutParams as LinearLayout.LayoutParams).apply { marginEnd = dp(10) })
        }
        addWeighted(label(c.name, size, bold = true).apply {
            maxLines = if (folded) 1 else 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            tag = "checkin-title:${c.id}"
        })
        addWrapped(label(if (folded) "▸" else "▾", 18f, color = colorMuted).apply { tag = "checkin-chevron:${c.id}" },
            startMargin = dp(6))
        addWrapped(alarmSwitch(c), startMargin = dp(8))
    }

    /** A folded check-in: its title line and when it rings next. Tap the title to unfold. */
    private fun checkinRow(c: Checkin, handle: View?, score: Double?): LinearLayout = card().apply {
        tag = "row:${c.id}"
        isClickable = true
        setOnClickListener { toggleFolded(c) } // the "Next" line unfolds it too
        add(titleRow(c, handle, score, folded = true, size = TITLE_SIZE))
        val sub = if (c.enabled) "Next: ${CheckinText.next(c)}" else "Off"
        add(muted(sub, 13f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; tag = "next-line:${c.id}" })
    }

    private fun checkinCard(c: Checkin, handle: View? = null, score: Double? = null): LinearLayout {
        val card = card()
        card.tag = "card:${c.id}"

        card.add(titleRow(c, handle, score, folded = false, size = TITLE_SIZE))
        card.add(muted(CheckinText.oneLine(c)), dp(2))

        if (c.soft) {
            card.add(muted("Soft alarm · not counted in stats", 13f).apply { tag = "soft-note:${c.id}" }, dp(4))
        } else if (c.voice) {
            val what = if (c.voiceSource == Checkin.VOICE_CLIP) "plays your clip" else "says \"${VoiceService.spokenText(c)}\""
            card.add(muted("Voice · $what · not counted in stats", 13f).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                tag = "voice-note:${c.id}"
            }, dp(4))
        } else if (c.questions.isNotEmpty()) {
            val results = store.allResults(c.id)
            card.add(muted(Stats.line(c, results, 7), 13f), dp(4))
        }

        // A "no alarm" check-in that came due waits here (and in its notification).
        if (c.quiet) {
            for (p in store.pending.filter { it.checkinId == c.id && !it.practice }) {
                val box = column().apply {
                    background = rounded(colorOf(R.color.primary_container), 14)
                    setPadding(dp(14), dp(10), dp(10), dp(10))
                }
                val r = row()
                r.addWeighted(label("Waiting · ${Days.timeOf(p.time).ifEmpty { "now" }}", 15f).apply { tag = "waiting:${c.id}" })
                if (c.soft) {
                    r.addWrapped(textButton("Dismiss", colorMuted) {
                        SoftAlarm.dismiss(this, p)
                        render()
                    }.apply { tag = "waiting-dismiss:${c.id}" })
                }
                r.addWrapped(button("Do it") {
                    startActivity(CheckinActivity.editIntent(this, c.id, p.day, p.time))
                }.apply { tag = "waiting-do:${c.id}" }, dp(4))
                box.add(r)
                card.add(box, dp(10))
            }
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
                if (!c.voice) r.addWrapped(button("Do it now") { doItNow(c, next) }, dp(4)) // nothing to do for a voice
                box.add(r)
                card.add(box, dp(10))
            } else if (c.isTask && !c.voice) {
                card.add(taskDoBox(c), dp(10))
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

        if (!c.enabled && c.isTask && !c.voice) card.add(taskDoBox(c), dp(10))

        // Right-aligned with gravity. (An empty spacer view here would grab all the height
        // it's offered, which stretched the card to fill the screen.)
        val actions = row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        if (c.questions.isNotEmpty() && !c.soft) {
            actions.addWrapped(textButton("History") { startActivity(HistoryActivity.intent(this, c.id)) })
        }
        actions.addWrapped(textButton("Edit") { startActivity(CheckinEditActivity.intent(this, c.id)) }, dp(4))
        card.add(actions, dp(6))
        return card
    }

    /**
     * A task with no ring coming (in the backlog, its alarm off, or its time has passed):
     * do it from here.
     */
    private fun taskDoBox(c: Checkin): LinearLayout {
        val box = column().apply {
            background = rounded(colorOf(R.color.surface_variant), 14)
            setPadding(dp(14), dp(10), dp(10), dp(10))
        }
        val r = row()
        val passed = c.enabled && c.times.isNotEmpty()
        val text = when {
            passed -> "Its time has passed"
            c.inBacklog -> "In the backlog"
            c.times.isEmpty() && c.events.isEmpty() -> "Task · no set time"
            else -> "Task · alarm off"
        }
        r.addWeighted(label(text, 15f, color = if (passed) colorWarn else colorText).apply { tag = "next:${c.id}" })
        r.addWrapped(button("Do it now") {
            startActivity(CheckinActivity.earlyIntent(this, c.id, Days.today(), AlarmScheduler.nearestTime(c).toString()))
        }.apply { tag = "task-do:${c.id}" }, dp(4))
        box.add(r)
        return box
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

    /**
     * +: on Check-ins, the library (ready-made check-ins, your own, or a task); on Tasks, a new
     * active task; on Backlog, a new task in the backlog. Either opens it to name.
     */
    private fun add(tab: String) {
        if (tab == Store.TAB_CHECKINS) {
            startActivity(LibraryActivity.intent(this))
            return
        }
        val c = Library.task(Store.newId()).let { if (tab == Store.TAB_TASKS) it.copy(enabled = true) else it }
        store.checkins = store.checkins + c
        AlarmScheduler.schedule(this, c)
        startActivity(CheckinEditActivity.intent(this, c.id))
    }

    // ---------------------------------------------------------------- helpers

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** A group's fold state is kept per tab (the Check-ins tab keeps the original keys). */
    private fun groupKey(tab: String, category: String) =
        if (tab == Store.TAB_CHECKINS) Grouping.key(category) else "$tab/${Grouping.key(category)}"

    /** A small emoji button for the top of the page; [name] is read out. */
    private fun iconButton(emoji: String, name: String, tag: String, onClick: () -> Unit) =
        textButton(emoji) { onClick() }.apply {
            this.tag = tag
            contentDescription = name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            minWidth = dp(44)
            minimumWidth = dp(44)
            setPadding(dp(6), paddingTop, dp(6), paddingBottom)
        }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            toast("No browser found. The page is $url")
        }
    }

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
        /** Check-in cards, folded or not: the name's size. */
        private const val TITLE_SIZE = 18f
        private const val REQ_NOTIF = 1
        private const val REQ_LOCATION = 2
        private const val REQ_ACTIVITY = 3
    }
}
