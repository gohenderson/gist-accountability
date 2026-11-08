package dev.gist.checkin

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.hamcrest.Matchers.equalTo
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Shared setup for UI tests. Tests run inside the app's own process, so they can use Store directly. */
object TestEnv {
    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val ctx: Context get() = instrumentation.targetContext
    val store: Store get() = Store(ctx)

    fun today(): String = Days.today()

    /** Screen on, unlocked, no system dialog in front: otherwise test screens never get focus. */
    fun readyDevice() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
    }

    /** Wipe all app state, cancel alarms, stop any ringing, then install the given state. */
    fun reset(
        checkins: List<Checkin>,
        pending: List<Pending> = emptyList(),
        entries: Map<String, Map<String, Boolean>> = emptyMap(),
    ) {
        readyDevice()
        Store(ctx).checkins.forEach { AlarmScheduler.cancel(ctx, it.id) }
        ctx.stopService(Intent(ctx, AlarmService::class.java))
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
        ctx.getSharedPreferences("gist", Context.MODE_PRIVATE).edit().clear().commit()
        val s = Store(ctx)
        s.checkins = checkins
        s.pending = pending
        // Answers go to the first check-in at 12:00 (TestEnv.checkin's time).
        checkins.firstOrNull()?.let { c -> entries.forEach { (day, answers) -> s.saveAnswers(day, c.id, "12:00", answers) } }
    }

    fun checkin(
        id: String,
        name: String = id,
        questions: List<Question> = emptyList(),
        mathCount: Int = 0,
        mathLevel: Int = 1,
        scanCode: String? = null,
        aboutYesterday: Boolean = false,
    ) = Checkin(
        id = id, name = name, times = listOf(AlarmTime(12, 0)), enabled = true,
        aboutYesterday = aboutYesterday, questions = questions,
        mathCount = mathCount, mathLevel = mathLevel,
        scanCode = scanCode, scanLabel = if (scanCode != null) "Test spot" else "",
    )

    /** Grants or resets "Display over other apps" (what lets an alarm come back to the front). */
    fun allowOverlay(allow: Boolean) {
        shell("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW ${if (allow) "allow" else "default"}")
    }

    /** Runs a shell command as the `shell` user (can grant permissions, take screenshots…). */
    fun shell(cmd: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    /** Retries [block] until it stops throwing or [timeoutMs] passes (for async UI updates). */
    fun eventually(timeoutMs: Long = 5_000, block: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            try {
                block()
                return
            } catch (e: Throwable) {
                if (SystemClock.uptimeMillis() > deadline) throw e
                SystemClock.sleep(200)
            }
        }
    }
}

/** Matches the invisible test tags the app puts on a few views (e.g. "q:soda:yes"). */
fun tagged(tag: String): Matcher<View> = withTagValue(equalTo<Any>(tag))

/** Reads a TextView's current text. */
fun textOf(matcher: Matcher<View>): String {
    var text = ""
    onView(matcher).check { view, noView ->
        if (noView != null) throw noView
        text = (view as android.widget.TextView).text.toString()
    }
    return text
}

/** On failure, saves a screenshot to /data/local/tmp/gist-test-failures/ (CI pulls it). */
class FailureScreenshot : TestWatcher() {
    override fun failed(e: Throwable?, description: Description) {
        val name = "${description.testClass.simpleName}-${description.methodName}"
        try {
            TestEnv.shell("mkdir -p /data/local/tmp/gist-test-failures")
            TestEnv.shell("screencap -p /data/local/tmp/gist-test-failures/$name.png")
        } catch (ignored: Exception) {
            // best effort
        }
    }
}
