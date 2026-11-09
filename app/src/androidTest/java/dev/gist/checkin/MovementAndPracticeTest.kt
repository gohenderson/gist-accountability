package dev.gist.checkin

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
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

/** Running triggers: starting / finishing a run rings the right check-ins. */
@RunWith(AndroidJUnit4::class)
class MovementTriggerTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()
    private val pkg get() = TestEnv.ctx.packageName

    @Before
    fun setUp() {
        TestEnv.shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        TestEnv.shell("pm grant $pkg android.permission.ACTIVITY_RECOGNITION")
    }

    @After
    fun tearDown() {
        TestEnv.reset(emptyList())
        TestEnv.ctx.stopService(Intent(TestEnv.ctx, MovementService::class.java))
    }

    private fun quietFor(ms: Long) {
        SystemClock.sleep(ms)
        assertFalse("should not ring", AlarmService.running)
    }

    @Test
    fun startingARunRingsAStartTrigger() {
        TestEnv.reset(listOf(TestEnv.checkin("r", "Run").copy(times = emptyList(), events = listOf(Trigger(Trigger.RUN_START)))))
        MovementWatch.onRun(TestEnv.ctx, finishedMinutes = null)
        TestEnv.eventually(15_000) { assertTrue("should ring", AlarmService.running) }
        assertEquals("r", TestEnv.store.pending.single().checkinId)
    }

    @Test
    fun finishingRingsOnlyAfterALongEnoughRun() {
        TestEnv.reset(listOf(TestEnv.checkin("r", "After").copy(times = emptyList(), events = listOf(Trigger(Trigger.RUN_END, minRunMinutes = 10)))))
        MovementWatch.onRun(TestEnv.ctx, finishedMinutes = null) // a start isn't a finish
        MovementWatch.onRun(TestEnv.ctx, finishedMinutes = 6)    // too short
        quietFor(3_000)
        MovementWatch.onRun(TestEnv.ctx, finishedMinutes = 12)
        TestEnv.eventually(15_000) { assertTrue("should ring", AlarmService.running) }
    }

    @Test
    fun theStepWatcherRunsOnlyWhileNeeded() {
        val c = TestEnv.checkin("r", "Run").copy(times = emptyList(), events = listOf(Trigger(Trigger.RUN_START)))
        TestEnv.reset(listOf(c))
        AlarmScheduler.schedule(TestEnv.ctx, c)
        if (!MovementWatch.hasSensor(TestEnv.ctx)) {
            // Emulators often have no step counter: then it says so instead of watching.
            assertTrue(MovementWatch.problem(TestEnv.ctx).contains("no step sensor"))
            assertFalse(MovementService.running)
            return
        }
        TestEnv.eventually(10_000) { assertTrue("watching steps", MovementService.running) }
        TestEnv.store.checkins = listOf(c.copy(enabled = false))
        AlarmScheduler.schedule(TestEnv.ctx, c.copy(enabled = false))
        TestEnv.eventually(10_000) { assertFalse("stopped", MovementService.running) }
    }

    @Test
    fun choosingFinishRunningAndAMinimumOnTheEditPage() {
        TestEnv.reset(listOf(TestEnv.checkin("r", "After")))
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, "r")).use {
            onView(tagged("trigger-when:0")).perform(scrollTo(), click())
            onView(tagged("kind:run-end")).inRoot(isDialog()).perform(click())
            onView(tagged("trigger-min-run:0")).perform(scrollTo(), click())
            onView(withText("At least 20 minutes")).inRoot(isDialog()).perform(click())
            onView(tagged("trigger-summary")).perform(scrollTo())
                .check(matches(withText(containsString("when you finish a run of 20+ min"))))
        }
        val c = TestEnv.store.checkin("r")!!
        assertTrue("the time became the run trigger", c.times.isEmpty())
        val t = c.events.single()
        assertEquals(Trigger.RUN_END, t.kind)
        assertEquals(20, t.minRunMinutes)
    }
}

/** A test ring can be stopped part-way. */
@RunWith(AndroidJUnit4::class)
class EndTestTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    @Test
    fun endTestStopsATestRingWithoutFinishingIt() {
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.POST_NOTIFICATIONS")
        TestEnv.reset(listOf(TestEnv.checkin("t", "Long one", listOf(Question("q", "Q?")), mathCount = 10, mathLevel = 4)))
        AlarmScheduler.scheduleTest(TestEnv.ctx, "t", 1_000)
        TestEnv.eventually(20_000) { assertTrue("test ring didn't start", AlarmService.running) }
        ActivityScenario.launch<CheckinActivity>(Intent(TestEnv.ctx, CheckinActivity::class.java)).use {
            onView(withText("Start check-in")).perform(click())
            onView(withText("Problem 1 of 10")).check(matches(isDisplayed()))
            onView(tagged("end-test")).perform(scrollTo(), click())
        }
        TestEnv.eventually(10_000) { assertFalse("alarm stopped", AlarmService.running) }
        assertTrue(TestEnv.store.pending.isEmpty())
        assertTrue("nothing logged", TestEnv.store.allResults("t").isEmpty())
    }
}
