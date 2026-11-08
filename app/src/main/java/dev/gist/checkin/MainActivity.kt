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
        if (requestCode == REQ_LOCATION) AlarmScheduler.scheduleAll(this) // start watching places
        render()
    }

    // ---------------------------------------------------------------- layout

    private fun render() {
        val oldY = scroll?.scrollY ?: 0
        val col = column()
        val checkins = store.checkins

        col.add(header("Gist", todayLong()))

        setupWarnings(col)

        val bar = row()
        bar.addWeighted(sectionTitle("Check-ins"))
        bar.addWrapped(primaryButton("+ Add check-in") { addCheckin() })
        col.add(bar, dp(28))
        checkins.forEach { col.add(checkinCard(it), dp(12)) }
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
    private fun checkinCard(c: Checkin): LinearLayout {
        val card = card()

        val titleRow = row()
        titleRow.addWeighted(label(c.name, 20f, bold = true))
        titleRow.addWrapped(styledSwitch().apply {
            isChecked = c.enabled
            tag = "toggle:${c.id}"
            contentDescription = "${c.name} alarm on"
            setOnCheckedChangeListener { _, on ->
                store.updateCheckin(c.id) { it.copy(enabled = on) }
                store.checkin(c.id)?.let { AlarmScheduler.schedule(this@MainActivity, it) }
                render()
            }
        })
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
            } else if (c.trigger.isPlace) {
                val box = column().apply {
                    background = rounded(colorOf(R.color.surface_variant), 14)
                    setPadding(dp(14), dp(10), dp(10), dp(10))
                }
                box.add(label("📍 Rings ${CheckinText.whenText(c)}", 15f).apply { tag = "next:${c.id}" })
                if (!LocationTriggers.hasBackground(this)) {
                    box.add(label("Needs location \"all the time\" (see Finish setup)", 13f, color = colorWarn), dp(2))
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
    }
}
