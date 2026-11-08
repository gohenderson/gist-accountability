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
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
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
        checkins.forEach { checkinRow(col, it, entries) }
        if (checkins.isEmpty()) {
            col.add(label("No check-ins yet.", 14f).apply { setTextColor(COLOR_MUTED) }, dp(8))
        }

        col.add(button("Settings · backup · dark mode") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, dp(36))

        val sv = ScrollView(this).apply {
            clipToPadding = false
            addView(col)
        }
        applySystemBarInsets(sv, dp(20))
        setContentView(sv)
        scroll = sv
        sv.post { sv.scrollTo(0, oldY) }
    }

    /** Per check-in: name, time + steps, a 7-day stat, and History / Edit buttons. */
    private fun checkinRow(col: LinearLayout, c: Checkin, entries: Map<String, Map<String, Boolean>>) {
        col.add(label(c.name, 19f, bold = true), dp(20))
        col.add(label(CheckinText.oneLine(c), 14f).apply {
            if (!c.enabled) setTextColor(COLOR_MUTED)
        }, dp(2))
        if (c.questions.isNotEmpty()) col.add(label(Stats.line(c, entries, 7), 14f), dp(2))

        val buttons = row()
        if (c.questions.isNotEmpty()) {
            buttons.addView(
                button("History") { startActivity(HistoryActivity.intent(this, c.id)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        buttons.addView(
            button("Edit") { startActivity(CheckinEditActivity.intent(this, c.id)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        col.add(buttons, dp(4))
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
    }
}
