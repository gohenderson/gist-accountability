package dev.gist.checkin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast

/** "Add a check-in": start from a ready-made one in [Library], or create your own. */
class LibraryActivity : BaseActivity() {
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        render()
    }

    private fun render() {
        val col = column()
        col.add(header("Add a check-in", "Pick one to start from. You can change anything afterwards."))

        val own = card()
        own.add(label("Create your own", 20f, bold = true))
        own.add(muted("Start blank and choose the times, steps and questions yourself.", 14f), dp(2))
        own.add(primaryButton("Start blank") { add(Library.blank(store.checkins, Store.newId())) }.apply {
            tag = "library:blank"
        }, dp(10))
        col.add(own, dp(20))

        for (category in Library.CATEGORIES) {
            col.add(sectionTitle(category), dp(24))
            Library.TEMPLATES.filter { it.category == category }.forEach { col.add(templateCard(it), dp(10)) }
        }
        setContentView(screen(col))
    }

    private fun templateCard(t: Library.Template): LinearLayout {
        val c = t.checkin
        val card = card()
        card.add(label(c.name, 19f, bold = true))
        card.add(muted(CheckinText.oneLine(c), 13f), dp(2))
        card.add(label(t.blurb, 15f), dp(6))
        if (c.questions.isNotEmpty()) {
            card.add(muted(c.questions.joinToString("\n") { "• ${it.text}" }, 13f), dp(6))
        }
        val actions = row().apply { gravity = Gravity.END }
        actions.addWrapped(button("Add") { add(Library.instantiate(t, store.checkins, Store.newId())) }.apply {
            tag = "library:${t.key}"
            contentDescription = "Add ${c.name}"
        })
        card.add(actions, dp(8))
        return card
    }

    /** Saves [c], arms its alarm, and opens it for editing. */
    private fun add(c: Checkin) {
        store.checkins = store.checkins + c
        AlarmScheduler.schedule(this, c)
        Toast.makeText(this, "Added ${c.name}. Adjust anything below.", Toast.LENGTH_SHORT).show()
        startActivity(CheckinEditActivity.intent(this, c.id))
        finish()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, LibraryActivity::class.java)
    }
}
