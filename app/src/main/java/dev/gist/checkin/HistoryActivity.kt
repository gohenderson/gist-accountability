package dev.gist.checkin

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView

/** One check-in's last 30 days: summary, per-question counts, and a tappable day grid. */
class HistoryActivity : BaseActivity() {
    private lateinit var store: Store
    private lateinit var checkinId: String

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
        render() // also refreshes after fixing a day
    }

    private fun render() {
        val c = store.checkin(checkinId) ?: return finish()
        val results = store.allResults(c.id)
        val qs = c.questions
        val col = column()

        col.add(header(c.name, "History · last 30 days"))

        if (qs.isEmpty()) {
            col.add(card().apply { add(muted("This check-in has no questions, so there's nothing to chart.", 15f)) }, dp(20))
            setContentView(screen(col))
            return
        }

        // Summary
        val summary = card()
        summary.add(sectionTitle("Summary"))
        for (n in listOf(7, 30)) {
            val logged = Stats.logged(c, results, Days.lastN(n))
            val clean = logged.count { Stats.onTrack(c, it) }
            val r = row()
            r.addWeighted(label(Stats.line(c, results, n), 15f))
            r.addWrapped(chip(if (logged.isEmpty()) "–" else "${clean * 100 / logged.size}%",
                if (logged.isNotEmpty() && clean == logged.size) colorGood else colorText,
                colorOf(R.color.surface_variant)))
            summary.add(r, dp(8))
        }
        col.add(summary, dp(20))

        // Per question
        val perQ = card()
        perQ.add(sectionTitle("Off track, by question"))
        val month = Days.lastN(30).mapNotNull { results[it] }
        qs.forEachIndexed { i, q ->
            val marks = month.map { Stats.dayMark(q, it) }.filter { it == Mark.ON_TRACK || it == Mark.OFF_TRACK }
            val off = marks.count { it == Mark.OFF_TRACK }
            perQ.add(label("${i + 1}. ${q.text}", 15f), dp(12))
            val counts = if (marks.isEmpty()) "no answers yet" else "$off of ${marks.size} days"
            val r = row()
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = maxOf(1, marks.size)
                progress = off
                progressTintList = android.content.res.ColorStateList.valueOf(colorBad)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(colorOf(R.color.good_container))
            }
            r.addWeighted(bar)
            r.addWrapped(muted(counts, 13f), dp(10))
            perQ.add(r, dp(2))
        }
        col.add(perQ, dp(12))

        // Day by day
        val days = card()
        days.add(sectionTitle("Day by day"))
        days.add(muted(
            if (c.times.size > 1) "✗ if any of the day's times was off track or missed. Tap a day to fill in or fix one of its times."
            else "✗ = off track or missed · – = skipped. Tap a day to fill it in or fix it.",
            13f
        ), dp(4))
        days.add(grid(c, results), dp(10))
        col.add(days, dp(12))

        setContentView(screen(col))
    }

    private fun grid(c: Checkin, results: Map<String, Map<String, SlotResult>>): HorizontalScrollView {
        val qs = c.questions
        val table = TableLayout(this)

        val header = TableRow(this)
        header.addView(cell("", null, null))
        qs.indices.forEach { header.addView(cell("${it + 1}", colorMuted, null, bold = true)) }
        table.addView(header)

        for (day in Days.lastN(30)) {
            val slots = results[day] ?: emptyMap()
            val tr = TableRow(this)
            tr.addView(cell(Days.short(day), colorText, null, gravity = Gravity.START))
            for (q in qs) {
                tr.addView(
                    when (Stats.dayMark(q, slots)) {
                        Mark.NONE -> cell("·", colorMuted, null)
                        Mark.ON_TRACK -> cell("✓", colorGood, colorOf(R.color.good_container))
                        Mark.OFF_TRACK -> cell("✗", colorBad, colorOf(R.color.bad_container))
                        Mark.SKIPPED -> cell("–", colorMuted, colorOf(R.color.surface_variant))
                    }
                )
            }
            tr.isClickable = true
            tr.setOnClickListener { fixDay(c, day, slots) }
            table.addView(tr)
        }

        return HorizontalScrollView(this).apply { addView(table) }
    }

    /** Opens a day's answers for editing; asks which time first if there's more than one. */
    private fun fixDay(c: Checkin, day: String, slots: Map<String, SlotResult>) {
        val times = (c.times.map { it.toString() } + slots.keys)
            .distinct()
            .sortedBy { AlarmTime.parse(it) ?: AlarmTime(0, 0) }
        if (times.size == 1) {
            startActivity(CheckinActivity.editIntent(this, c.id, day, times[0]))
            return
        }
        val labels = times.map { t -> "${Days.timeOf(t)}  ·  ${Stats.describe(c, slots[t])}" }
        AlertDialog.Builder(this)
            .setTitle("${Days.pretty(day)}: which time?")
            .setItems(labels.toTypedArray()) { _, i ->
                startActivity(CheckinActivity.editIntent(this, c.id, day, times[i]))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun cell(text: String, fg: Int?, bg: Int?, bold: Boolean = false, gravity: Int = Gravity.CENTER): TextView =
        label(text, 15f, bold, fg).apply {
            this.gravity = gravity
            if (bg != null) background = rounded(bg, 8)
            minWidth = if (gravity == Gravity.CENTER) dp(32) else 0
            setPadding(dp(8), dp(5), dp(8), dp(5))
            layoutParams = TableRow.LayoutParams().apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
        }

    companion object {
        private const val EXTRA_ID = "checkinId"

        fun intent(context: Context, checkinId: String): Intent =
            Intent(context, HistoryActivity::class.java).putExtra(EXTRA_ID, checkinId)
    }
}
