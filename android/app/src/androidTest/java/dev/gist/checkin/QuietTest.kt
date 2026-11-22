package dev.gist.checkin

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "No alarm (just a reminder)": a silent notification and a waiting card, no alarm screen. */
@RunWith(AndroidJUnit4::class)
class QuietTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    @After
    fun tearDown() {
        TestEnv.ctx.getSystemService(NotificationManager::class.java).cancelAll()
        TestEnv.reset(emptyList())
    }

    private fun ring(id: String, day: String, time: String) =
        AlarmReceiver().onReceive(TestEnv.ctx, Intent(TestEnv.ctx, AlarmReceiver::class.java)
            .putExtra(AlarmScheduler.EXTRA_CHECKIN, id)
            .putExtra(AlarmScheduler.EXTRA_DAY, day)
            .putExtra(AlarmScheduler.EXTRA_TIME, time))

    @Test
    fun remindsWithoutAnAlarm() {
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.POST_NOTIFICATIONS")
        val day = TestEnv.today()
        val c = TestEnv.checkin("q", "Call John").copy(soundUri = Checkin.QUIET, commitment = Checkin.SOFT, task = true)
        TestEnv.reset(listOf(c))
        ring("q", day, "12:00")

        Thread.sleep(1500)
        assertFalse("no alarm", AlarmService.running)
        assertTrue("it waits", TestEnv.store.pending.any { it.checkinId == "q" })
        assertTrue("but not for the alarm screen", TestEnv.store.alarmPending.isEmpty())
        val shown = TestEnv.ctx.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue("a reminder notification", shown.any { it.notification.extras.getString("android.title") == "Call John" })

        // The start page shows it waiting (and doesn't jump to the alarm screen); dismissing a soft task completes it.
        TestEnv.store.startTab = Store.TAB_TASKS
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(tagged("waiting:q")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("waiting-dismiss:q")).perform(click())
        }
        assertTrue(TestEnv.store.pending.none { it.checkinId == "q" })
        assertEquals(SlotResult.DISMISSED, TestEnv.store.result(day, "q", "12:00")?.status)
        assertTrue(TestEnv.store.checkin("q")!!.taskDone)
        TestEnv.eventually {
            val left = TestEnv.ctx.getSystemService(NotificationManager::class.java).activeNotifications
            assertTrue("the reminder is cleared", left.none { it.notification.extras.getString("android.title") == "Call John" })
        }
    }
}
