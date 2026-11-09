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

    @After
    fun tearDown() {
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
    fun endingATestOnTheLockScreenLeavesTheCheckinPageWorking() {
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.POST_NOTIFICATIONS")
        TestEnv.reset(listOf(TestEnv.checkin("t", "Test me", listOf(Question("q", "Q?")))))
        // Open the check-in's page the way the app does (in the app's own task).
        TestEnv.ctx.startActivity(CheckinEditActivity.intent(TestEnv.ctx, "t").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        TestEnv.eventually(10_000) { assertTrue(resumed().any { it is CheckinEditActivity }) }

        // Test, then lock the phone.
        AlarmScheduler.scheduleTest(TestEnv.ctx, "t", 3_000)
        TestEnv.shell("input keyevent KEYCODE_SLEEP")
        TestEnv.eventually(30_000) { assertTrue("the test should ring over the lock screen", CheckinActivity.visible) }

        // End test, still locked.
        val end = device.wait(Until.findObject(By.text("End test")), 10_000)
        assertNotNull("End test button", end)
        end.click()
        TestEnv.eventually(10_000) {
            assertFalse("alarm screen closed", CheckinActivity.visible)
            assertFalse("not ringing", AlarmService.running)
        }

        // Unlock: the check-in's page is back, and it responds.
        TestEnv.readyDevice()
        TestEnv.eventually(15_000) { assertTrue("the check-in's page is in front", resumed().any { it is CheckinEditActivity }) }
        val category = device.wait(Until.findObject(By.text("Category: none")), 10_000)
        assertNotNull("the page is drawn", category)
        category.click()
        assertNotNull("and takes taps (the category dialog opens)", device.wait(Until.findObject(By.text("New category…")), 10_000))
        device.pressBack()
        assertTrue("the dialog closes with Back", device.wait(Until.gone(By.text("New category…")), 10_000))
    }
}
