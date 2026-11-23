package dev.gist.checkin

import android.app.Activity
import android.widget.Toast

/**
 * "Create follow-up": a new task that continues a task (sand the pool patch → replace the
 * liner), so you add the next step when you get to it instead of planning every step up front.
 * Asks each time whether it goes to the backlog or today's list, then opens its page to rename it.
 */
object FollowUp {
    /** A task was just done outside its alarm screen (Dismiss on the start page): offer a follow-up. */
    fun offer(activity: Activity, task: Checkin, then: () -> Unit = {}) {
        android.app.AlertDialog.Builder(activity)
            .setTitle("✓ Task done")
            .setMessage("${task.name}\n\nIs there a next step? A follow-up starts as a copy of this task.")
            .setPositiveButton("Create follow-up") { _, _ -> ask(activity, task, then) }
            .setNegativeButton("Close") { _, _ -> then() }
            .show()
    }

    /** Asks backlog or today, creates the follow-up to [source], and opens its page. [then] runs after. */
    fun ask(activity: Activity, source: Checkin, then: () -> Unit = {}) {
        activity.pickDialog(
            "Follow up \"${source.name}\"",
            listOf(
                DialogOption("Add to backlog", "Pick it for a day later", "follow-up:backlog"),
                DialogOption("Add to today", "On your Tasks list now", "follow-up:today"),
            ),
            message = "A new task named \"(Follow-Up) ${source.name.removePrefix(Store.FOLLOW_UP_PREFIX)}\", with this one's " +
                "description, category and settings, but no set time. Its page opens so you can change it.",
        ) { i ->
            val store = Store(activity)
            val c = store.createFollowUp(source.id, active = i == 1) ?: return@pickDialog
            AlarmScheduler.schedule(activity, c)
            Toast.makeText(activity, if (i == 1) "Follow-up added to today" else "Follow-up added to the backlog",
                Toast.LENGTH_SHORT).show()
            activity.startActivity(CheckinEditActivity.intent(activity, c.id))
            then()
        }
    }
}
