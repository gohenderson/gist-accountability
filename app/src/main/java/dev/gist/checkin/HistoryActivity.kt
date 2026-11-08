package dev.gist.checkin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView

/** Answer statistics for one check-in, shared by the start page and the history page. */
object Stats {
    /** Logged days among [days]: days where at least one of [c]'s questions was answered. */
    fun logged(c: Checkin, entries: Map<String, Map<String, Boolean>>, days: List<String>) =
        days.mapNotNull { entries[it] }.filter { e -> c.questions.any { e.containsKey(it.id) } }

    /** A logged day is "on track" if every answered question got its on-track answer. */
    fun onTrack(c: Checkin, day: Map<String, Boolean>): Boolean =
        c.questions.all { q -> day[q.id]?.let { q.isGood(it) } ?: true }

    /** e.g. "Last 7 days: 5 logged, 4 fully on track" */
    fun line(c: Checkin, entries: Map<String, Map<String, Boolean>>, n: Int): String {
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
                val answered = month.mapNotNull { it[q.id] }
                val off = answered.count { !q.isGood(it) }
                val counts = if (answered.isEmpty()) "no answers yet" else "$off of ${answered.size} days"
                col.add(label("${i + 1}. ${q.text}\n     $counts", 14f), dp(6))
            }

            col.add(label("Day by day", 18f, bold = true), dp(24))
            col.add(label("Tap a day to fill it in or fix it.", 13f).apply { setTextColor(COLOR_MUTED) }, dp(2))
            col.add(grid(c, entries), dp(8))
        }

        val sv = ScrollView(this).apply {
            clipToPadding = false
            addView(col)
        }
        applySystemBarInsets(sv, dp(20))
        setContentView(sv)
    }

    private fun grid(c: Checkin, entries: Map<String, Map<String, Boolean>>): HorizontalScrollView {
        val qs = c.questions
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

        return HorizontalScrollView(this).apply { addView(table) }
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
