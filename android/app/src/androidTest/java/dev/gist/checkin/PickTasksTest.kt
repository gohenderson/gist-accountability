package dev.gist.checkin

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The backlog (tasks that are off), and the step that picks today's tasks from it. */
@RunWith(AndroidJUnit4::class)
class PickTasksTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    private fun backlogTask(id: String, name: String) = Library.task(id).copy(name = name)

    private fun planner(count: Int) = TestEnv.checkin("plan", "Plan the day")
        .copy(steps = listOf(StepConfig("s", Steps.PICK_TASKS, pickCount = count)))

    @Test
    fun picksExactlyTheNumberAndTurnsThemOn() {
        val day = TestEnv.today()
        TestEnv.reset(listOf(planner(2), backlogTask("a", "Call John"), backlogTask("b", "Fix the fence"),
            backlogTask("c", "Buy batteries")), listOf(Pending("plan", day, "12:00")))
        ActivityScenario.launch<CheckinActivity>(Intent(TestEnv.ctx, CheckinActivity::class.java)).use {
            onView(withText("Pick 2 tasks to do today")).check(matches(isDisplayed()))
            onView(tagged("pick-go")).perform(scrollTo()).check(matches(not(isEnabled()))).check(matches(withText("Pick 2 more")))
            onView(tagged("pick:a")).perform(scrollTo(), click())
            onView(tagged("pick:b")).perform(scrollTo(), click())
            onView(tagged("pick:c")).perform(scrollTo(), click())
            onView(tagged("pick-go")).perform(scrollTo()).check(matches(withText("Untick 1")))
            onView(tagged("pick:c")).perform(scrollTo(), click())
            onView(tagged("pick-go")).perform(scrollTo()).check(matches(isEnabled())).perform(click())
        }
        assertTrue(TestEnv.store.checkin("a")!!.enabled)
        assertTrue(TestEnv.store.checkin("b")!!.enabled)
        assertTrue(TestEnv.store.checkin("c")!!.inBacklog)
        assertEquals(listOf("c"), TestEnv.store.backlog.map { it.id })
    }

    @Test
    fun activeTasksCountTowardTheNumber() {
        val day = TestEnv.today()
        val active = Library.task("x").copy(name = "Already on", enabled = true)
        TestEnv.reset(listOf(planner(3), active, backlogTask("a", "Call John"), backlogTask("b", "Fix the fence"),
            backlogTask("c", "Buy batteries")), listOf(Pending("plan", day, "12:00")))
        ActivityScenario.launch<CheckinActivity>(Intent(TestEnv.ctx, CheckinActivity::class.java)).use {
            // 1 active of 3: pick 2 more.
            onView(withText("Pick 2 more tasks to do today")).check(matches(isDisplayed()))
            onView(tagged("pick:a")).perform(scrollTo(), click())
            onView(tagged("pick:b")).perform(scrollTo(), click())
            onView(tagged("pick-go")).perform(scrollTo(), click())
        }
        assertEquals(listOf("c"), TestEnv.store.backlog.map { it.id })
    }

    @Test
    fun enoughActiveTasksSkipsIt() {
        val day = TestEnv.today()
        val on = (1..2).map { Library.task("x$it").copy(name = "On $it", enabled = true) }
        TestEnv.reset(listOf(planner(2)) + on + backlogTask("a", "Call John"), listOf(Pending("plan", day, "12:00")))
        ActivityScenario.launch<CheckinActivity>(Intent(TestEnv.ctx, CheckinActivity::class.java)).use {
            // Its only step is satisfied, so there's nothing to pick: just Dismiss.
            onView(tagged("pick-go")).check(doesNotExist())
            onView(withText("Dismiss")).perform(scrollTo(), click())
        }
        assertTrue("nothing taken from the backlog", TestEnv.store.checkin("a")!!.inBacklog)
    }

    @Test
    fun anEmptyBacklogJustContinues() {
        val day = TestEnv.today()
        TestEnv.reset(listOf(planner(3)), listOf(Pending("plan", day, "12:00")))
        ActivityScenario.launch<CheckinActivity>(Intent(TestEnv.ctx, CheckinActivity::class.java)).use {
            onView(tagged("pick-continue")).perform(scrollTo(), click())
        }
        TestEnv.eventually { assertTrue(TestEnv.store.pending.isEmpty()) }
    }

    @Test
    fun aTestRingChangesNothing() {
        val day = TestEnv.today()
        TestEnv.reset(listOf(planner(1), backlogTask("a", "Call John")), listOf(Pending("plan", day, "12:00", practice = true)))
        ActivityScenario.launch<CheckinActivity>(Intent(TestEnv.ctx, CheckinActivity::class.java)).use {
            onView(tagged("pick:a")).perform(scrollTo(), click())
            onView(tagged("pick-go")).perform(scrollTo(), click())
        }
        assertTrue(TestEnv.store.checkin("a")!!.inBacklog)
    }

    @Test
    fun theBacklogIsHiddenUntilTicked() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Daily"), backlogTask("b", "Fix the fence"),
            Library.task("t").copy(name = "Call John", enabled = true)))
        TestEnv.store.collapsedCheckins = setOf("c", "b", "t")
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(tagged("checkin-title:t")).check(matches(isDisplayed())) // active task
            onView(tagged("checkin-title:b")).check(doesNotExist()) // backlog
            onView(tagged("backlog-note")).check(matches(withText("1 task in the backlog · show")))

            onView(tagged("view-mode")).perform(scrollTo(), click())
            onView(tagged("view:backlog")).inRoot(isDialog()).perform(scrollTo(), click())
            onView(tagged("checkin-title:b")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("backlog-note")).check(doesNotExist())

            // Without Tasks, Backlog can't be ticked (and shows nothing).
            onView(tagged("view-mode")).perform(scrollTo(), click())
            onView(tagged("view:tasks")).inRoot(isDialog()).perform(scrollTo(), click())
            onView(tagged("checkin-title:b")).check(doesNotExist())
            onView(tagged("view-mode")).perform(scrollTo(), click())
            onView(tagged("view:backlog")).inRoot(isDialog()).perform(scrollTo()).check(matches(not(isEnabled())))
        }
        assertFalse(TestEnv.store.showTasks)
    }
}
