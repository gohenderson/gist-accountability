package dev.gist.checkin

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Tasks you've done, most recent first, so you can check whether you did something. Each can
 * be opened, put back on the list, or deleted.
 */
class DoneTasksActivity : BaseActivity() {
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val done = store.checkins.filter { it.taskDone }.sortedByDescending { it.doneAt }
        val col = column()
        col.add(header("Done tasks", if (done.size == 1) "1 task" else "${done.size} tasks"))
        if (done.isEmpty()) {
            col.add(card().apply { add(muted("Nothing here yet. Tasks you finish show up here.", 15f)) }, dp(20))
        }
        done.forEachIndexed { i, c -> col.add(taskCard(c), if (i == 0) dp(20) else dp(10)) }
        setContentView(screen(col))
    }

    private fun taskCard(c: Checkin): LinearLayout {
        val card = card().apply { tag = "done-task:${c.id}" }
        card.add(label(c.name, 19f, bold = true))
        card.add(label("✓ Done ${CheckinText.doneText(c.doneAt!!)}", 15f, color = colorGood), dp(4))
        c.repeat.date?.let { card.add(muted("Was set for ${Repeat.dateText(it)}", 13f), dp(2)) }
        store.checkin(c.followsUp ?: "")?.let { card.add(muted("Follows: ${it.name}", 13f).apply { tag = "follows:${c.id}" }, dp(2)) }
        store.followUpsOf(c.id).forEach {
            card.add(muted("Followed by: ${it.name}", 13f).apply { tag = "followed-by:${c.id}" }, dp(2))
        }
        // Its answers, if it asked anything.
        val answers = store.allResults(c.id).values.flatMap { it.values }
            .filter { it.status == SlotResult.ANSWERED }
            .flatMap { r -> c.questions.mapNotNull { q -> answerText(q, r)?.let { "${q.text} $it" } } }
        answers.forEach { card.add(muted(it, 13f), dp(2)) }
        val r = row()
        r.addWeighted(outlineButton("Put back") {
            store.reopenTask(c.id)
            AlarmScheduler.schedule(this, store.checkin(c.id)!!)
            toast("${c.name} is back on the list.")
            render()
        }.apply { tag = "reopen:${c.id}" })
        r.addWeighted(outlineButton("Open") { startActivity(CheckinEditActivity.intent(this, c.id)) }, startMargin = dp(8))
        r.addWeighted(textButton("Delete", colorOf(R.color.bad)) { confirmDelete(c) }.apply { tag = "delete:${c.id}" },
            startMargin = dp(8))
        card.add(button("Create follow-up") { FollowUp.ask(this, c) }.apply { tag = "follow-up:${c.id}" }, dp(10))
        card.add(r, dp(8))
        return card
    }

    private fun answerText(q: Question, r: SlotResult): String? = when {
        q.takesValue -> r.values[q.id]?.ifBlank { "(left empty)" }
        else -> r.answers[q.id]?.let { if (it) "Yes" else "No" }
    }

    private fun confirmDelete(c: Checkin) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${c.name}\"?")
            .setMessage("It's removed from Done tasks for good.")
            .setPositiveButton("Delete") { _, _ ->
                AlarmScheduler.cancel(this, c.id)
                Backgrounds.delete(this, c.id)
                store.checkins = store.checkins.filter { it.id != c.id }
                store.prunePending()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        fun intent(context: Context): Intent = Intent(context, DoneTasksActivity::class.java)
    }
}
