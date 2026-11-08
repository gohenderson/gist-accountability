package dev.gist.checkin

import android.Manifest
import android.app.Activity
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
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast

/** Check-in settings, question editors, and history. */
class MainActivity : Activity() {
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
        render()
    }

    // ---------------------------------------------------------------- layout

    private fun render() {
        val oldY = scroll?.scrollY ?: 0
        val col = column()
        val checkins = store.checkins
        val entries = store.allEntries()

        col.add(label("Gist", 30f, bold = true))
        col.add(label("Daily check-ins on the gist of your diet.", 15f), dp(2))

        setupWarnings(col)

        col.add(label("Check-ins", 24f, bold = true), dp(28))
        col.add(button("+ Add check-in") { addCheckin() }, dp(8))
        checkins.forEach { checkinRow(col, it) }
        if (checkins.isEmpty()) {
            col.add(label("No check-ins yet.", 14f).apply { setTextColor(COLOR_MUTED) }, dp(8))
        }

        if (checkins.isNotEmpty()) {
            col.add(label("Last 30 days", 24f, bold = true), dp(36))
            col.add(label("Tap a day to fill it in or fix it.", 13f), dp(4))
            checkins.forEach { c ->
                col.add(label(c.name, 20f, bold = true), dp(24))
                summary(col, c, entries)
                history(col, c, entries)
            }
            col.add(button("Export CSV") { exportCsv() }, dp(20))
        }

        val sv = ScrollView(this).apply {
            clipToPadding = false
            addView(col)
        }
        applySystemBarInsets(sv, dp(20))
        setContentView(sv)
        scroll = sv
        sv.post { sv.scrollTo(0, oldY) }
    }

    /** One line per check-in: name, time + steps, and an Edit button. */
    private fun checkinRow(col: LinearLayout, c: Checkin) {
        val r = row()
        val text = column()
        text.add(label(c.name, 19f, bold = true))
        text.add(label(CheckinText.oneLine(c), 14f).apply {
            if (!c.enabled) setTextColor(COLOR_MUTED)
        }, dp(2))
        r.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(button("Edit") { startActivity(CheckinEditActivity.intent(this, c.id)) })
        r.isClickable = true
        r.setOnClickListener { startActivity(CheckinEditActivity.intent(this, c.id)) }
        col.add(r, dp(12))
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
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            items += "Turn off battery optimization (some phones kill alarms)" to {
                openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            }
        }
        if (items.isEmpty()) return
        col.add(label("Setup needed", 20f, bold = true), dp(28))
        items.forEach { (text, action) -> col.add(button(text, action), dp(6)) }
    }

    private fun summary(col: LinearLayout, c: Checkin, entries: Map<String, Map<String, Boolean>>) {
        val qs = c.questions
        if (qs.isEmpty()) return
        // A day counts as "logged" for this check-in if any of its questions were answered.
        fun loggedIn(days: List<String>) =
            days.mapNotNull { entries[it] }.filter { e -> qs.any { e.containsKey(it.id) } }

        fun line(n: Int): String {
            val logged = loggedIn(Days.lastN(n))
            val clean = logged.count { e -> qs.all { q -> e[q.id]?.let { q.isGood(it) } ?: true } }
            return "Last $n days: ${logged.size} logged, $clean fully on track"
        }
        col.add(label(line(7), 16f), dp(6))
        col.add(label(line(30), 16f), dp(2))

        val month = loggedIn(Days.lastN(30))
        qs.forEachIndexed { i, q ->
            val answered = month.mapNotNull { it[q.id] }
            if (answered.isEmpty()) return@forEachIndexed
            val off = answered.count { !q.isGood(it) }
            col.add(label("${i + 1}. off track $off of ${answered.size} days", 14f), dp(2))
        }
    }

    private fun history(col: LinearLayout, c: Checkin, entries: Map<String, Map<String, Boolean>>) {
        val qs = c.questions
        if (qs.isEmpty()) return
        val table = TableLayout(this)

        val header = TableRow(this)
        header.addView(cell(""))
        qs.indices.forEach { header.addView(cell("${it + 1}", bold = true)) }
        table.addView(header)

        for (day in Days.lastN(30)) {
            val e = entries[day]
            val tr = TableRow(this)
            tr.addView(cell(Days.short(day), gravity = Gravity.START))
            for (q in qs) {
                val a = e?.get(q.id)
                tr.addView(
                    when {
                        a == null -> cell("·", COLOR_MUTED)
                        q.isGood(a) -> cell("✓", COLOR_GOOD)
                        else -> cell("✗", COLOR_BAD)
                    }
                )
            }
            tr.isClickable = true
            tr.setOnClickListener { startActivity(CheckinActivity.editIntent(this, c.id, day)) }
            table.addView(tr)
        }

        val hs = HorizontalScrollView(this)
        hs.addView(table)
        col.add(hs, dp(8))
    }

    private fun cell(
        text: String,
        color: Int? = null,
        bold: Boolean = false,
        gravity: Int = Gravity.CENTER
    ): TextView = label(text, 16f, bold).apply {
        setPadding(dp(8), dp(6), dp(8), dp(6))
        this.gravity = gravity
        if (color != null) setTextColor(color)
    }

    // ---------------------------------------------------------------- actions

    private fun addCheckin() {
        val c = Checkin(
            id = Store.newId(),
            name = "Check-in ${store.checkins.size + 1}",
            hour = 12,
            minute = 0,
        )
        store.checkins = store.checkins + c
        AlarmScheduler.schedule(this, c)
        startActivity(CheckinEditActivity.intent(this, c.id))
    }

    private fun exportCsv() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, "gist-log-${Days.today()}.csv")
        startActivityForResult(intent, REQ_EXPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(buildCsv().toByteArray()) }
            toast("Exported")
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    private fun buildCsv(): String {
        val cols = store.checkins.flatMap { c -> c.questions.map { q -> c to q } }
        fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val sb = StringBuilder()
        sb.append("date")
        cols.forEach { (c, q) -> sb.append(',').append(esc("${c.name}: ${q.text}")) }
        sb.append('\n')
        store.allEntries().forEach { (day, e) ->
            sb.append(day)
            cols.forEach { (_, q) ->
                sb.append(',')
                e[q.id]?.let { sb.append(if (it) "yes" else "no") }
            }
            sb.append('\n')
        }
        return sb.toString()
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
        private const val REQ_EXPORT = 2
    }
}
