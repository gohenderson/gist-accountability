package dev.gist.checkin

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pausing a ringing alarm while working through the check-in, with a real alarm.
 * The pause is shortened to 3 s so the test doesn't take minutes.
 */
@RunWith(AndroidJUnit4::class)
class AlarmPauseTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private val q = Question("p1", "Pause test question?")

    @Before
    fun setUp() {
        AlarmService.pauseMs = 3_000
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.POST_NOTIFICATIONS")
        TestEnv.reset(listOf(TestEnv.checkin("p", "Pause test", listOf(q))))
        AlarmScheduler.scheduleTest(TestEnv.ctx, "p", 1_000)
        TestEnv.eventually(20_000) { assertTrue("alarm didn't start ringing", AlarmService.running) }
    }

    @After
    fun tearDown() {
        AlarmService.pauseMs = 30_000
        TestEnv.reset(emptyList())
    }

    private fun openAlarmScreen(): ActivityScenario<CheckinActivity> =
        ActivityScenario.launch(Intent(TestEnv.ctx, CheckinActivity::class.java))

    @Test
    fun startCheckinPausesTheAlarmAndShowsTheCountdown() {
        openAlarmScreen().use {
            onView(withText("Start check-in")).check(matches(isDisplayed()))
            assertFalse("should be ringing before Start", AlarmService.isPaused)

            onView(withText("Start check-in")).perform(click())
            assertTrue("Start check-in should pause the alarm", AlarmService.isPaused)
            onView(tagged("pause-bar")).check(matches(isDisplayed()))
            onView(withText(containsString("Alarm"))).check(matches(isDisplayed()))
            onView(withText(q.text)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun eachTapRestartsTheCountdown() {
        openAlarmScreen().use {
            onView(withText("Start check-in")).perform(click())
            SystemClock.sleep(2_000) // ~1 s left
            onView(tagged("q:p1:no")).perform(scrollTo(), click())
            val left = AlarmService.pausedUntil - SystemClock.elapsedRealtime()
            assertTrue("countdown should be back near 3 s after a tap, was $left ms", left > 2_000)
            assertTrue(AlarmService.isPaused)
        }
    }

    @Test
    fun whenTheCountdownRunsOutTheAlarmRingsAgainAndProgressIsKept() {
        openAlarmScreen().use {
            onView(withText("Start check-in")).perform(click())
            onView(tagged("q:p1:no")).perform(scrollTo(), click())

            // No more taps: ringing resumes and the Start screen returns.
            TestEnv.eventually(8_000) { assertFalse("alarm should ring again", AlarmService.isPaused) }
            assertTrue(AlarmService.running)
            TestEnv.eventually { onView(withText("Start check-in")).check(matches(isDisplayed())) }

            // The earlier answer is still selected, so Submit is ready right away.
            onView(withText("Start check-in")).perform(click())
            onView(withText("Submit")).perform(scrollTo(), click())
        }
        TestEnv.eventually { assertFalse("alarm should stop after the check-in", AlarmService.running) }
        assertEquals(mapOf("p1" to false), TestEnv.store.entry(Days.forAlarmNow(false)))
    }
}
