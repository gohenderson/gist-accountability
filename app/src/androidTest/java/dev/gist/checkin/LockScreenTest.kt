package dev.gist.checkin

import android.app.Activity
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test ring from a check-in's page, phone locked, End test on the lock screen, unlock: the
 * check-in's page must be back and working (it used to come back blank and ignore taps).
 */
@RunWith(AndroidJUnit4::class)
class LockScreenTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private val device get() = UiDevice.getInstance(TestEnv.instrumentation)

    companion object {
        private const val PIN = "1357"
    }

    private var pin = false

    @After
    fun tearDown() {
        // Only clear the PIN here: typing it on an unlocked phone would land in whatever is open.
        if (pin) TestEnv.shell("locksettings clear --old $PIN")
        pin = false
        TestEnv.allowOverlay(false)
        TestEnv.readyDevice()
        TestEnv.instrumentation.runOnMainSync {
            for (stage in listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)) {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).forEach { it.finish() }
            }
        }
        TestEnv.reset(emptyList())
    }

    private fun resumed(): List<Activity> {
        var list: List<Activity> = emptyList()
        TestEnv.instrumentation.runOnMainSync {
            list = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList()
        }
        return list
    }

    @Test
    fun endingATestOnTheLockScreenLeavesTheCheckinPageWorking() = endTestWhileLocked(securePin = false, overlay = false)

    /** As on most phones: a PIN lock, and "display over other apps" allowed (Finish setup asks for it). */
    @Test
    fun sameWithAPinLockAndDisplayOverOtherApps() = endTestWhileLocked(securePin = true, overlay = true)

    @Test
    fun sameWithAVideoBackground() = endTestWhileLocked(securePin = true, overlay = true, video = true)

    /** Ended part-way through a math step, with the keyboard up over the lock screen. */
    @Test
    fun sameWhenEndedDuringAMathStep() = endTestWhileLocked(securePin = false, overlay = false, math = true)

    @Test
    fun sameDuringAMathStepWithAPinLock() = endTestWhileLocked(securePin = true, overlay = true, math = true)

    private fun unlockWithPin() {
        TestEnv.shell("input keyevent KEYCODE_WAKEUP")
        Thread.sleep(500)
        TestEnv.shell("input keyevent KEYCODE_MENU") // swipe up to the PIN pad
        Thread.sleep(800)
        TestEnv.shell("input text $PIN")
        TestEnv.shell("input keyevent KEYCODE_ENTER")
        Thread.sleep(1_500)
    }

    private fun endTestWhileLocked(securePin: Boolean, overlay: Boolean, video: Boolean = false, math: Boolean = false) {
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.POST_NOTIFICATIONS")
        if (overlay) TestEnv.allowOverlay(true)
        val c = TestEnv.checkin("t", "Test me", listOf(Question("q", "Q?")), mathCount = if (math) 3 else 0)
        TestEnv.reset(listOf(if (video) c.copy(background = Background(Background.VIDEO)) else c))
        if (video) Backgrounds.videoFile(TestEnv.ctx, "t").writeBytes(testVideoBytes())
        if (securePin) {
            TestEnv.shell("locksettings set-pin $PIN")
            pin = true
        }
        // Open the check-in's page the way the app does (in the app's own task).
        TestEnv.ctx.startActivity(CheckinEditActivity.intent(TestEnv.ctx, "t").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        TestEnv.eventually(10_000) { assertTrue(resumed().any { it is CheckinEditActivity }) }

        // Test, then lock the phone.
        AlarmScheduler.scheduleTest(TestEnv.ctx, "t", 3_000)
        TestEnv.shell("input keyevent KEYCODE_SLEEP")
        TestEnv.eventually(30_000) { assertTrue("the test should ring over the lock screen", CheckinActivity.visible) }

        if (math) {
            // Start the check-in: the math step brings up the keyboard.
            val start = device.wait(Until.findObject(By.text("Start check-in")), 10_000)
            assertNotNull("Start check-in button", start)
            start.click()
            assertNotNull("the math step's answer box", device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000))
            Thread.sleep(1_500) // let the keyboard come up
        }

        // End test, still locked.
        val end = device.wait(Until.findObject(By.text("End test")), 10_000)
        assertNotNull("End test button", end)
        end.click()
        TestEnv.eventually(10_000) {
            assertFalse("alarm screen closed", CheckinActivity.visible)
            assertFalse("not ringing", AlarmService.running)
        }

        // Unlock: the check-in's page is back, and it responds.
        if (securePin) {
            unlockWithPin()
            TestEnv.shell("locksettings clear --old $PIN")
            pin = false
        }
        TestEnv.readyDevice()
        TestEnv.eventually(15_000) { assertTrue("the check-in's page is in front", resumed().any { it is CheckinEditActivity }) }
        val category = device.wait(Until.findObject(By.text("Category: none")), 10_000)
        assertNotNull("the page is drawn", category)
        category.click()
        assertNotNull("and takes taps (the category dialog opens)", device.wait(Until.findObject(By.text("New category…")), 10_000))
        device.pressBack()
        assertTrue("the dialog closes with Back", device.wait(Until.gone(By.text("New category…")), 10_000))
        // And it scrolls.
        val page = device.wait(Until.findObject(By.scrollable(true)), 5_000)
        assertNotNull("a scrollable page", page)
        page.scroll(androidx.test.uiautomator.Direction.DOWN, 1f)
        page.scroll(androidx.test.uiautomator.Direction.DOWN, 1f)
        assertNotNull("scrolled to the bottom", device.wait(Until.findObject(By.text("Delete this check-in")), 5_000))
    }
}
