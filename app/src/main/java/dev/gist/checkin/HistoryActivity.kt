package dev.gist.checkin

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView

/**
 * Answer statistics for one check-in, shared by the start page and the history page.
 * Answers are saved per time; reports roll them up per day: a question is on track for
 * a day only if every time it was answered that day was on track.
 */
object Stats {
    /** A day's result for [q]: null if never answered that day, false if any time was off track. */
    fun dayResult(q: Question, day: Map<String, Answers>): Boolean? {
        val answers = day.values.mapNotNull { it[q.id] }
        if (answers.isEmpty()) return null
        return answers.all { q.isGood(it) }
    }

    /** Logged days among [days]: days where at least one of [c]'s questions was answered. */
    fun logged(c: Checkin, entries: Map<String, Map<String, Answers>>, days: List<String>) =
        days.mapNotNull { entries[it] }.filter { day -> c.questions.any { q -> dayResult(q, day) != null } }

    /** A logged day is "on track" if no answered question was off track at any time. */
    fun onTrack(c: Checkin, day: Map<String, Answers>): Boolean =
        c.questions.all { q -> dayResult(q, day) != false }

    /** e.g. "Last 7 days: 5 logged, 4 fully on track" */
    fun line(c: Checkin, entries: Map<String, Map<String, Answers>>, n: Int): String {
        val logged = logged(c, entries, Days.lastN(n))
        val clean = logged.count { onTrack(c, it) }
        return "Last $n days: ${logged.size} logged, $clean fully on track"
    }
}

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
        val entries = store.allEntries()
        val qs = c.questions
        val col = column()

        col.add(label(c.name, 26f, bold = true))
        col.add(label("Last 30 days", 16f), dp(2))

        if (qs.isEmpty()) {
            col.add(label("This check-in has no questions, so nothing is logged.", 15f), dp(16))
        } else {
            col.add(label(Stats.line(c, entries, 7), 16f), dp(16))
            col.add(label(Stats.line(c, entries, 30), 16f), dp(2))

            col.add(label("Off track, by question", 18f, bold = true), dp(24))
            val month = Stats.logged(c, entries, Days.lastN(30))
            qs.forEachIndexed { i, q ->
                val answered = month.mapNotNull { Stats.dayResult(q, it) }
                val off = answered.count { !it }
                val counts = if (answered.isEmpty()) "no answers yet" else "$off of ${answered.size} days"
                col.add(label("${i + 1}. ${q.text}\n     $counts", 14f), dp(6))
            }

            col.add(label("Day by day", 18f, bold = true), dp(24))
            val legend = if (c.times.size > 1) {
                "✗ if any of the day's times was off track. Tap a day to fill in or fix one of its times."
            } else {
                "Tap a day to fill it in or fix it."
            }
            col.add(label(legend, 13f).apply { setTextColor(COLOR_MUTED) }, dp(2))
            col.add(grid(c, entries), dp(8))
        }

        val sv = ScrollView(this).apply {
            clipToPadding = false
            addView(col)
        }
        applySystemBarInsets(sv, dp(20))
        setContentView(sv)
    }

    private fun grid(c: Checkin, entries: Map<String, Map<String, Answers>>): HorizontalScrollView {
        val qs = c.questions
        val table = TableLayout(this)

        val header = TableRow(this)
        header.addView(cell(""))
        qs.indices.forEach { header.addView(cell("${it + 1}", bold = true)) }
        table.addView(header)

        for (day in Days.lastN(30)) {
            val slots = entries[day] ?: emptyMap()
            val tr = TableRow(this)
            tr.addView(cell(Days.short(day), gravity = Gravity.START))
            for (q in qs) {
                tr.addView(
                    when (Stats.dayResult(q, slots)) {
                        null -> cell("·", COLOR_MUTED)
                        true -> cell("✓", COLOR_GOOD)
                        false -> cell("✗", COLOR_BAD)
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
    private fun fixDay(c: Checkin, day: String, slots: Map<String, Answers>) {
        // The check-in's times, plus any other time that has answers for it that day.
        val mine = c.questions.map { it.id }.toSet()
        val times = (c.times.map { it.toString() } + slots.filter { (_, a) -> a.keys.any { it in mine } }.keys)
            .distinct()
            .sortedBy { AlarmTime.parse(it) ?: AlarmTime(0, 0) }
        if (times.size == 1) {
            startActivity(CheckinActivity.editIntent(this, c.id, day, times[0]))
            return
        }
        val labels = times.map { t ->
            val answers = slots[t]
            val status = when {
                answers == null || c.questions.none { answers.containsKey(it.id) } -> "not answered"
                c.questions.all { q -> answers[q.id]?.let { q.isGood(it) } != false } -> "✓ on track"
                else -> "✗ off track"
            }
            "${Days.timeOf(t)}  ·  $status"
        }
        AlertDialog.Builder(this)
            .setTitle("${Days.pretty(day)}: which time?")
            .setItems(labels.toTypedArray()) { _, i ->
                startActivity(CheckinActivity.editIntent(this, c.id, day, times[i]))
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    companion object {
        private const val EXTRA_ID = "checkinId"

        fun intent(context: Context, checkinId: String): Intent =
            Intent(context, HistoryActivity::class.java).putExtra(EXTRA_ID, checkinId)
    }
}
