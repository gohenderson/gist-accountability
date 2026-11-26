package dev.gist.checkin

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.widget.EditText

/** Switching, adding, renaming and deleting profiles (start page and Settings). */
object ProfileUi {
    /** Opens profile [id]: the start page again, from scratch, in that profile. */
    fun switchTo(activity: Activity, id: String) {
        Profiles.setCurrent(activity, id)
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .putExtra(Profiles.EXTRA, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
    }

    /** Which profile to switch to, or a new one. */
    fun pick(activity: Activity) {
        val all = Profiles.all(activity)
        val current = Profiles.current(activity)
        val options = all.map { p ->
            DialogOption(p.name, waitingText(activity, p.id), "profile:${p.id.ifEmpty { "default" }}")
        } + DialogOption("New profile…", "Starts empty: its own check-ins, tasks, settings and Gist folder.", "new-profile")
        activity.pickDialog("Profile", options, selected = all.indexOfFirst { it.id == current },
            message = "Every profile's alarms ring, whichever one you're on.") { i ->
            if (i < all.size) {
                if (all[i].id != current) switchTo(activity, all[i].id)
            } else {
                add(activity) { switchTo(activity, it.id) }
            }
        }
    }

    /** "2 waiting", for a profile with check-ins waiting to be answered; else null. */
    fun waitingText(activity: Activity, id: String): String? {
        val n = Store(Profiles.scoped(activity, id)).pending.count { !it.practice }
        return if (n > 0) "$n waiting" else null
    }

    fun add(activity: Activity, then: (Profiles.Profile) -> Unit) {
        val field = nameField(activity, "")
        AlertDialog.Builder(activity)
            .setTitle("New profile")
            .setMessage("A separate setup, say for work: its own check-ins and tasks, settings, Gist folder and devices. It starts empty.")
            .setView(activity.column().apply { setPadding(activity.dp(24), activity.dp(8), activity.dp(24), activity.dp(8)); add(field) })
            .setPositiveButton("Create") { _, _ -> then(Profiles.add(activity, field.text.toString())) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun rename(activity: Activity, id: String, then: () -> Unit) {
        val field = nameField(activity, Profiles.name(activity, id))
        AlertDialog.Builder(activity)
            .setTitle("Rename profile")
            .setView(activity.column().apply { setPadding(activity.dp(24), activity.dp(8), activity.dp(24), activity.dp(8)); add(field) })
            .setPositiveButton("Rename") { _, _ ->
                Profiles.rename(activity, id, field.text.toString())
                then()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun delete(activity: Activity, id: String, then: () -> Unit) {
        val name = Profiles.name(activity, id)
        AlertDialog.Builder(activity)
            .setTitle("Delete $name?")
            .setMessage("Its check-ins, tasks, answers and settings are deleted from this phone, and its alarms stop. " +
                "What it synced to its Gist folder stays there.")
            .setPositiveButton("Delete") { _, _ ->
                Profiles.remove(activity, id)
                then()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun nameField(activity: Activity, text: String) = EditText(activity).apply {
        hint = "e.g. Work"
        setText(text)
        setSelection(text.length)
        tag = "profile-name"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        isSingleLine = true
    }
}
