package dev.gist.checkin

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Create follow-up": the next task in a chain (sand the patch → replace the liner). */
@RunWith(AndroidJUnit4::class)
class FollowUpTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    private fun patch() = Library.task("patch").copy(
        name = "Sand the pool patch",
        description = "Use the 120 grit.",
        category = "Pool",
        timeCommitment = "hours",
        enabled = true,
        steps = listOf(StepConfig("s1", Steps.MATH, mathCount = 2)),
    )

    @Test
    fun aFollowUpCopiesTheTaskButNotItsTimes() {
        val src = patch().copy(times = listOf(AlarmTime(9, 0, Repeat.once(java.time.LocalDate.now().plusDays(1)))))
        TestEnv.reset(listOf(TestEnv.checkin("other", "Other"), src, TestEnv.checkin("last", "Last")))
        val f = TestEnv.store.createFollowUp("patch", active = false, newId = "f1")!!
        assertEquals("(Follow-Up) Sand the pool patch", f.name)
        assertEquals("Use the 120 grit.", f.description)
        assertEquals("Pool", f.category)
        assertEquals("hours", f.timeCommitment)
        assertEquals(src.commitment, f.commitment)
        assertEquals(Checkin.QUIET, f.soundUri)
        assertEquals(Steps.MATH, f.steps.single().kind)
        assertFalse("the steps get their own ids", f.steps.single().id == "s1")
        assertTrue("no set time", f.times.isEmpty() && f.events.isEmpty())
        assertTrue(f.isTask)
        assertTrue("in the backlog", f.inBacklog)
        assertEquals("patch", f.followsUp)
        assertNull(f.doneAt)
        // Right after the task it follows.
        assertEquals(listOf("other", "patch", "f1", "last"), TestEnv.store.checkins.map { it.id })
        assertEquals(listOf("f1"), TestEnv.store.followUpsOf("patch").map { it.id })
        // Saved (and so in backups).
        assertEquals("patch", TestEnv.store.checkin("f1")!!.followsUp)
        // Today's list, and a follow-up of a follow-up keeps one prefix.
        val g = TestEnv.store.createFollowUp("f1", active = true, newId = "f2")!!
        assertTrue(g.enabled)
        assertEquals("(Follow-Up) Sand the pool patch", g.name)
        assertEquals("f1", g.followsUp)
    }

    @Test
    fun finishingATaskOffersAFollowUpThatOpensItsPage() {
        TestEnv.reset(listOf(patch().copy(steps = emptyList())))
        TestEnv.store.startTab = Store.TAB_TASKS
        TestEnv.store.expandedCheckin = "patch"
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(tagged("task-do:patch")).perform(scrollTo(), click())
            TestEnv.eventually { onView(withText("Dismiss")).perform(click()) }
            // Done: the task-done screen offers a follow-up.
            TestEnv.eventually { onView(tagged("task-done-title")).check(matches(isDisplayed())) }
            onView(tagged("create-follow-up")).perform(click())
            onView(tagged("follow-up:today")).inRoot(isDialog()).perform(click())
            // Its page opens, named so it's plain it's the new one.
            TestEnv.eventually {
                onView(tagged("checkin-name")).check(matches(withText("(Follow-Up) Sand the pool patch")))
            }
            onView(tagged("follows")).check(matches(withText("Follows: Sand the pool patch")))
        }
        assertTrue(TestEnv.store.checkin("patch")!!.taskDone)
        val f = TestEnv.store.followUpsOf("patch").single()
        assertTrue("on today's list", f.enabled && f.isTask && !f.taskDone)
    }

    @Test
    fun closingTheTaskDoneScreenAddsNothing() {
        TestEnv.reset(listOf(patch().copy(steps = emptyList())))
        TestEnv.store.startTab = Store.TAB_TASKS
        TestEnv.store.expandedCheckin = "patch"
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(tagged("task-do:patch")).perform(scrollTo(), click())
            TestEnv.eventually { onView(withText("Dismiss")).perform(click()) }
            TestEnv.eventually { onView(tagged("task-done-close")).perform(click()) }
            TestEnv.eventually { onView(tagged("tab:tasks")).check(matches(isDisplayed())) }
        }
        assertEquals(listOf("patch"), TestEnv.store.checkins.map { it.id })
    }

    @Test
    fun doneTasksCanBeFollowedUpAndShowTheChain() {
        TestEnv.reset(listOf(patch()))
        TestEnv.store.completeTask("patch")
        TestEnv.store.createFollowUp("patch", active = false, newId = "liner")
        TestEnv.store.updateCheckin("liner") { it.copy(name = "Replace the liner") }
        ActivityScenario.launch<DoneTasksActivity>(DoneTasksActivity.intent(TestEnv.ctx)).use {
            onView(tagged("followed-by:patch")).check(matches(withText("Followed by: Replace the liner")))
            onView(tagged("follow-up:patch")).perform(scrollTo(), click())
            onView(tagged("follow-up:backlog")).inRoot(isDialog()).perform(click())
            TestEnv.eventually { onView(tagged("checkin-name")).check(matches(isDisplayed())) }
        }
        assertEquals(2, TestEnv.store.followUpsOf("patch").size)
    }
}
