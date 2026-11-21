package dev.gist.checkin

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** One-time tasks: ringing once, completing, Done tasks, putting back, backups. */
@RunWith(AndroidJUnit4::class)
class TaskTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    private val yes = Question("called", "Did you get through?", yesIsGood = true)

    private fun task(id: String = "t", name: String = "Call John", questions: List<Question> = emptyList(),
                     date: LocalDate = LocalDate.now()) =
        TestEnv.checkin(id, name, questions).copy(repeat = Repeat.once(date), category = "Tasks")

    @Test
    fun doingATaskCompletesIt() {
        val day = TestEnv.today()
        TestEnv.reset(listOf(task(questions = listOf(yes))), listOf(Pending("t", day, "12:00")))
        ActivityScenario.launch<CheckinActivity>(Intent(TestEnv.ctx, CheckinActivity::class.java)).use {
            onView(tagged("q:called:yes")).perform(scrollTo(), click())
            onView(withText("Submit")).perform(scrollTo(), click())
        }
        val t = TestEnv.store.checkin("t")!!
        assertTrue(t.taskDone)
        assertFalse("done tasks are off", t.enabled)
        assertNotNull(t.doneAt)
        assertTrue("nothing waiting", TestEnv.store.pending.none { it.checkinId == "t" })
    }

    @Test
    fun doneTasksLeaveTheListAndCanBePutBack() {
        val done = task().copy(enabled = false, doneAt = System.currentTimeMillis() - 60_000)
        TestEnv.reset(listOf(done, TestEnv.checkin("daily", "Daily")))
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(withText("Call John")).check(doesNotExist())
            onView(tagged("done-tasks")).perform(scrollTo()).check(matches(withText("Done tasks (1)"))).perform(click())
            onView(tagged("done-task:t")).check(matches(isDisplayed()))
            onView(tagged("reopen:t")).perform(click())
        }
        val t = TestEnv.store.checkin("t")!!
        assertFalse(t.taskDone)
        assertTrue(t.enabled)
        assertNull(t.doneAt)
    }

    @Test
    fun aTaskCountsNowhereInTheOverallScore() {
        // Only a task (answered off track): no overall score at all.
        val t = task(questions = listOf(yes))
        TestEnv.reset(listOf(t))
        TestEnv.store.saveAnswers(TestEnv.today(), "t", "12:00", mapOf("called" to false))
        // That completed it; put it back so it would show on the start page.
        TestEnv.store.reopenTask("t")
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(tagged("overall-score")).check(doesNotExist())
            onView(withText("Call John")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun backupsSayVersion5OnlyWithATask() {
        TestEnv.reset(listOf(TestEnv.checkin("daily", "Daily")))
        assertTrue(Backup.export(TestEnv.ctx).contains("version: 4"))
        TestEnv.reset(listOf(TestEnv.checkin("daily", "Daily"), task(date = LocalDate.of(2026, 12, 24))))
        val text = Backup.export(TestEnv.ctx)
        assertTrue(text.contains("version: 5"))
        // And it comes back as a task, on its date.
        val c = Backup.parse(TestEnv.ctx, text).checkins.single { it.name == "Call John" }
        assertEquals(Repeat.once(LocalDate.of(2026, 12, 24)), c.repeat)
    }

    @Test
    fun newTaskFromTheLibrary() {
        TestEnv.reset(emptyList())
        ActivityScenario.launch<LibraryActivity>(LibraryActivity.intent(TestEnv.ctx)).use {
            onView(tagged("library:task")).perform(scrollTo(), click())
            TestEnv.eventually { onView(tagged("task-date")).check(matches(isDisplayed())) }
        }
        val c = TestEnv.store.checkins.single()
        assertTrue(c.isTask)
        assertEquals("Tasks", c.category)
        assertNotNull("its alarm is set", AlarmScheduler.nextTriggerMillis(c))
    }
}
