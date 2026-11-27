package dev.gist.checkin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.Toast

/** Appearance, the alarm pause, and profiles (the rest is in the menu: [NavDrawer]). */
class SettingsActivity : BaseActivity() {
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        pictureFor = savedInstanceState?.getString(STATE_PICTURE_FOR)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pictureFor?.let { outState.putString(STATE_PICTURE_FOR, it) }
    }

    private fun render() {
        val col = column()
        col.add(header("Settings"))

        // Appearance
        val look = card()
        look.add(sectionTitle("Appearance"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val options = listOf(
            Store.THEME_SYSTEM to "System",
            Store.THEME_LIGHT to "Light",
            Store.THEME_DARK to "Dark",
        )
        val ids = options.mapIndexed { i, (mode, text) ->
            val rb = choiceTile(text).apply {
                id = View.generateViewId()
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                isChecked = store.theme == mode
            }
            group.addView(rb, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
            rb.id to mode
        }.toMap()
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = ids[checkedId] ?: return@setOnCheckedChangeListener
            if (mode != store.theme) {
                store.theme = mode
                recreate()
            }
        }
        look.add(group, dp(10))
        col.add(look, dp(20))

        // Alarm
        val alarm = card()
        alarm.add(sectionTitle("While you check in"))
        alarm.add(label("After each tap, the alarm stays quiet for:", 15f), dp(6))
        val pauseGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val pauseIds = Store.PAUSE_CHOICES.mapIndexed { i, sec ->
            val rb = choiceTile("$sec s").apply {
                id = View.generateViewId()
                tag = "pause:$sec"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                isChecked = store.pauseSeconds == sec
            }
            pauseGroup.addView(rb, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
            rb.id to sec
        }.toMap()
        pauseGroup.setOnCheckedChangeListener { _, checkedId -> pauseIds[checkedId]?.let { store.pauseSeconds = it } }
        alarm.add(pauseGroup, dp(10))
        alarm.add(muted("If the countdown runs out before you finish, the alarm rings again. Applies to every check-in.", 13f), dp(8))
        col.add(alarm, dp(12))

        col.add(profilesCard(), dp(12))
        if (SyncthingEngine.shared(this).available) col.add(syncthingCard(), dp(12))

        setContentView(screen(col))
    }

    /** What the Easy Connect experiment last said (kept across re-renders). */
    private var syncthingStatus: String? = null

    /**
     * The Easy Connect experiment (debug builds with Syncthing in them only): start and stop
     * Syncthing inside Gist, and see that it runs on this phone. It shares nothing yet.
     */
    private fun syncthingCard() = card().apply {
        tag = "syncthing-experiment"
        val engine = SyncthingEngine.shared(this@SettingsActivity)
        add(sectionTitle("Easy Connect (experiment)"))
        add(label("Syncthing running inside Gist. Debug builds only, and it doesn't share anything yet: " +
            "this checks it starts, answers and stops on this phone.", 15f), dp(6))
        val status = muted(syncthingStatus ?: if (engine.running) "Running" else "Stopped", 13f).apply {
            tag = "syncthing-status"
            setTextIsSelectable(true)
        }
        add(status, dp(6))
        val button = outlineButton(if (engine.running) "Stop Syncthing" else "Start Syncthing") {}.apply { tag = "syncthing-toggle" }
        button.setOnClickListener {
            button.isEnabled = false
            status.text = if (engine.running) "Stopping…" else "Starting…"
            Thread {
                val text = try {
                    if (engine.running) {
                        engine.stop()
                        "Stopped"
                    } else {
                        val t0 = System.currentTimeMillis()
                        engine.start()
                        "Running Syncthing ${engine.version()}, started in ${System.currentTimeMillis() - t0} ms\n" +
                            "Device ID: ${engine.deviceId()}\n" +
                            "Program: ${engine.binary.length() / (1024 * 1024)} MB"
                    }
                } catch (e: Exception) {
                    "Couldn't: ${e.message}"
                }
                runOnUiThread {
                    syncthingStatus = text
                    render()
                }
            }.start()
        }
        add(button, dp(10))
    }

    /**
     * Profiles: separate setups (say Personal and Work), each with everything its own. You
     * see one at a time; all of them ring.
     */
    private fun profilesCard() = card().apply {
        tag = "profiles"
        add(sectionTitle("Profiles"))
        add(label("Separate setups, say Personal and Work: each has its own check-ins and tasks, answers, settings, " +
            "Gist folder and devices. Every profile's alarms ring, whichever one you're on. Tap a profile's " +
            "picture to change it (it stays on this phone).", 15f), dp(6))
        val current = profileId
        for (p in Profiles.all(this@SettingsActivity)) {
            val key = p.id.ifEmpty { "default" }
            val r = row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            // Its picture (or first letter): tap to choose a picture.
            r.addWrapped(AvatarView(this@SettingsActivity, p.id, p.name, 40).apply {
                tag = "profile-picture:$key"
                contentDescription = "${p.name}'s picture. Change it"
                isClickable = true
                setOnClickListener { pictureChoices(p.id) }
            })
            val name = if (p.id == current) "${p.name} (this one)" else p.name
            r.addWeighted(label(name, 16f, bold = p.id == current).apply { tag = "profile-row:$key" }, startMargin = dp(12))
            if (p.id != current) {
                r.addWrapped(textButton("Switch") { ProfileUi.switchTo(this@SettingsActivity, p.id) }.apply { tag = "profile-switch:$key" })
            }
            r.addWrapped(textButton("Rename") { ProfileUi.rename(this@SettingsActivity, p.id) { render() } }.apply { tag = "profile-rename:$key" })
            if (p.id != current && p.id != Profiles.DEFAULT) {
                r.addWrapped(textButton("Delete", colorBad) { ProfileUi.delete(this@SettingsActivity, p.id) { render() } }.apply { tag = "profile-delete:$key" })
            }
            add(r, dp(8))
        }
        add(outlineButton("New profile…") {
            ProfileUi.add(this@SettingsActivity) { ProfileUi.switchTo(this@SettingsActivity, it.id) }
        }.apply { tag = "new-profile" }, dp(12))
    }

    // ---------------------------------------------------------------- profile pictures

    /** The profile whose picture is being chosen. */
    private var pictureFor: String? = null

    /** Choose a picture for profile [id], or remove the one it has. */
    private fun pictureChoices(id: String) {
        if (!ProfilePicture.has(this, id)) return pickPicture(id)
        val options = listOf(
            DialogOption("Choose a picture…", tag = "picture-choose"),
            DialogOption("Remove the picture", "Shows the first letter of the name instead.", "picture-remove"),
        )
        pickDialog("${Profiles.name(this, id)}'s picture", options) { i ->
            if (i == 0) pickPicture(id) else {
                ProfilePicture.remove(this, id)
                render()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pickPicture(id: String) {
        pictureFor = id
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
            REQ_PICTURE
        )
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = pictureFor
        val uri = data?.data
        if (requestCode != REQ_PICTURE || resultCode != RESULT_OK || id == null || uri == null) return
        pictureFor = null
        try {
            ProfilePicture.set(this, id, uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't use that picture: ${e.message}", Toast.LENGTH_LONG).show()
        }
        render()
    }

    companion object {
        private const val REQ_PICTURE = 21
        private const val STATE_PICTURE_FOR = "pictureFor"
    }
}
