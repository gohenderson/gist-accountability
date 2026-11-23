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
        TestEnv.checkin(id, name, questions).let {
            it.copy(task = true, category = "Tasks", times = it.times.map { t -> t.copy(days = Repeat.once(date)) })
        }

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
        TestEnv.store.startTab = Store.TAB_TASKS
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
        TestEnv.store.startTab = Store.TAB_TASKS
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
        assertTrue(c.isTask)
        assertEquals(Repeat.once(LocalDate.of(2026, 12, 24)), c.times.single().days)
    }

    @Test
    fun theFirstTasksCarryOver() {
        // Saved before tasks were a flag: the check-in repeated "once" on a date.
        val old = org.json.JSONObject(TestEnv.store.checkinToJson(TestEnv.checkin("t", "Call John")).toString())
            .put("repeat", org.json.JSONObject().put("kind", "once").put("days", org.json.JSONArray()).put("date", "2026-12-24"))
        val c = TestEnv.store.checkinFromJson(old)
        assertTrue(c.isTask)
        assertEquals(Repeat.EVERY_DAY, c.repeat)
        assertEquals(Repeat.once(LocalDate.of(2026, 12, 24)), c.times.single().days)
    }

    @Test
    fun aBacklogTaskIsDoneFromTheStartPage() {
        val backlog = TestEnv.checkin("b", "Fix the fence").copy(task = true, times = emptyList())
        TestEnv.reset(listOf(backlog))
        TestEnv.store.startTab = Store.TAB_TASKS
        TestEnv.store.expandedCheckin = "b"
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(tagged("next:b")).perform(scrollTo()).check(matches(withText("Task · no set time")))
            onView(tagged("task-do:b")).perform(click())
            // No steps: just Dismiss.
            TestEnv.eventually { onView(withText("Dismiss")).perform(click()) }
        }
        TestEnv.eventually { assertTrue(TestEnv.store.checkin("b")!!.taskDone) }
    }

    @Test
    fun newTaskFromTheLibrary() {
        TestEnv.reset(emptyList())
        ActivityScenario.launch<LibraryActivity>(LibraryActivity.intent(TestEnv.ctx)).use {
            onView(tagged("library:task")).perform(scrollTo(), click())
            TestEnv.eventually { TestEnv.openSection(CheckinEditActivity.SECTION_SCHEDULE) }
            TestEnv.eventually { onView(tagged("task-backlog")).check(matches(isDisplayed())) }
        }
        // In the backlog: no triggers, alarm off, soft, no alarm.
        val c = TestEnv.store.checkins.single()
        assertTrue(c.isTask)
        assertEquals("Tasks", c.category)
        assertTrue(c.times.isEmpty() && c.events.isEmpty())
        assertFalse(c.enabled)
        assertTrue(c.soft)
        assertTrue(c.quiet)
    }

    @Test
    fun goingIntoTheBacklogIsStamped() {
        val week = 7L * 24 * 60 * 60 * 1000
        val old = System.currentTimeMillis() - 2 * week
        TestEnv.reset(listOf(
            task("new").copy(enabled = false),                       // new in the backlog: stamped now
            task("restored").copy(enabled = false, backlogAt = old), // its own time is kept
            task("active"),                                          // active: no stamp
        ))
        assertNotNull(TestEnv.store.checkin("new")!!.backlogAt)
        assertEquals(old, TestEnv.store.checkin("restored")!!.backlogAt)
        assertNull(TestEnv.store.checkin("active")!!.backlogAt)
        assertEquals(1, TestEnv.store.tasksBackloggedSince(7))

        // Turning an active task off puts it in the backlog: stamped.
        TestEnv.store.updateCheckin("active") { it.copy(enabled = false) }
        assertNotNull(TestEnv.store.checkin("active")!!.backlogAt)
        assertEquals(2, TestEnv.store.tasksBackloggedSince(7))
        // Picking it (on again) still counts as added this week.
        TestEnv.store.updateCheckin("active") { it.copy(enabled = true) }
        assertEquals(2, TestEnv.store.tasksBackloggedSince(7))
    }

    @Test
    fun eachTabHasASummaryAndTheViewRowIsAlwaysThere() {
        val week = 7L * 24 * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        TestEnv.reset(listOf(
            TestEnv.checkin("c", "Daily"),
            task("d1").copy(enabled = false, doneAt = now - 60_000),
            task("d2").copy(enabled = false, doneAt = now - 2 * week), // too long ago
            task("b").copy(enabled = false),
        ))
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            // Check-ins with no answers yet: a placeholder, and View for just one item.
            onView(tagged("overall-score-none")).check(matches(isDisplayed()))
            onView(tagged("view-mode")).check(matches(isDisplayed()))
            onView(tagged("toggle-all")).check(doesNotExist()) // only for groups
            // Tasks: done this week (no active tasks, and View is still there).
            onView(tagged("tab:tasks")).perform(click())
            onView(tagged("tasks-done-count")).check(matches(withText("1")))
            onView(tagged("summary-text")).check(matches(withText("task done in the last 7 days")))
            onView(tagged("view-mode")).check(matches(isDisplayed()))
            // Backlog: added this week.
            onView(tagged("tab:backlog")).perform(click())
            onView(tagged("backlog-added-count")).check(matches(withText("1")))
            onView(tagged("summary-text")).check(matches(withText("task added to the backlog in the last 7 days")))
        }
    }
}
