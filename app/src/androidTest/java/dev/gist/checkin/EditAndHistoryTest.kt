package dev.gist.checkin

import android.app.AlarmManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.containsString
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

/** A check-in's edit page: changes persist and the alarm follows. */
@RunWith(AndroidJUnit4::class)
class EditCheckinTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private val first = Question("first", "First question?")
    private val second = Question("second", "Second question?")

    private fun open(id: String): ActivityScenario<CheckinEditActivity> =
        ActivityScenario.launch(CheckinEditActivity.intent(TestEnv.ctx, id))

    private fun questionsPage(id: String): ActivityScenario<StepEditActivity> =
        ActivityScenario.launch(StepEditActivity.intent(TestEnv.ctx, id, Steps.QUESTIONS))

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    @Test
    fun renameSavesAsYouType() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Morning")))
        open("c").use {
            onView(tagged("checkin-name")).perform(replaceText("Starting the Day"), closeSoftKeyboard())
        }
        assertEquals("Starting the Day", TestEnv.store.checkin("c")!!.name)
    }

    @Test
    fun addQuestionThroughTheDialog() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Evening", listOf(first))))
        questionsPage("c").use {
            onView(withText("+ Add question")).perform(scrollTo(), click())
            onView(withHint("e.g. Did you drink alcohol today?")).inRoot(isDialog())
                .perform(replaceText("Did you walk today?"))
            onView(withText("Answering \"Yes\" is on track")).inRoot(isDialog()).perform(click())
            onView(withText("Save")).inRoot(isDialog()).perform(click())
            onView(withText(containsString("Did you walk today?"))).perform(scrollTo()).check(matches(isDisplayed()))
        }
        val qs = TestEnv.store.checkin("c")!!.questions
        assertEquals(listOf("First question?", "Did you walk today?"), qs.map { it.text })
        assertTrue("new question should be on track when answered Yes", qs[1].yesIsGood)
    }

    @Test
    fun moveQuestionUp() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Evening", listOf(first, second))))
        questionsPage("c").use {
            onView(withText("↑")).perform(scrollTo(), click())
        }
        assertEquals(listOf("second", "first"), TestEnv.store.checkin("c")!!.questions.map { it.id })
    }

    @Test
    fun turningTheAlarmOffAndOnCancelsAndReschedulesTheAlarm() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Noon")))
        AlarmScheduler.schedule(TestEnv.ctx, TestEnv.store.checkin("c")!!)
        val am = TestEnv.ctx.getSystemService(AlarmManager::class.java)
        assertNotNull("alarm should be scheduled to start with", am.nextAlarmClock)

        open("c").use {
            onView(withText("Alarm on")).perform(scrollTo(), click())
            assertFalse(TestEnv.store.checkin("c")!!.enabled)
            assertNull("alarm should be cancelled", am.nextAlarmClock)

            onView(withText("Alarm on")).perform(scrollTo(), click())
            assertTrue(TestEnv.store.checkin("c")!!.enabled)
            val next = am.nextAlarmClock
            assertNotNull("alarm should be back", next)
            assertEquals(AlarmScheduler.nextTriggerMillis(12, 0) / 1000, next!!.triggerTime / 1000)
        }
    }

    @Test
    fun removingATimeReschedulesForTheRemainingOnes() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Teeth").copy(times = listOf(AlarmTime(7, 0), AlarmTime(22, 0)))))
        open("c").use {
            onView(tagged("remove-time:0")).perform(scrollTo(), click())
        }
        val c = TestEnv.store.checkin("c")!!
        assertEquals(listOf(AlarmTime(22, 0)), c.times)
        val am = TestEnv.ctx.getSystemService(AlarmManager::class.java)
        assertEquals(AlarmScheduler.nextTriggerMillis(22, 0) / 1000, am.nextAlarmClock!!.triggerTime / 1000)
    }

    @Test
    fun choosingWeekdaysSavesTheRuleAndMovesTheAlarm() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Work")))
        open("c").use {
            onView(tagged("repeat")).perform(scrollTo(), click())
            onView(withText("Weekdays (Mon–Fri)")).inRoot(isDialog()).perform(click())
            onView(withText("Repeats: Mon–Fri")).perform(scrollTo()).check(matches(isDisplayed()))
        }
        val c = TestEnv.store.checkin("c")!!
        assertEquals(Repeat.weekly(Repeat.WEEKDAYS), c.repeat)
        val am = TestEnv.ctx.getSystemService(AlarmManager::class.java)
        assertEquals(AlarmScheduler.nextTriggerMillis(c)!! / 1000, am.nextAlarmClock!!.triggerTime / 1000)
    }

    @Test
    fun monthlyOnTheFirstThroughTheDateList() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Bills")))
        open("c").use {
            onView(tagged("repeat")).perform(scrollTo(), click())
            onView(withText("Monthly on certain dates…")).inRoot(isDialog()).perform(click())
            onView(withText("1st")).inRoot(isDialog()).perform(click())
            onView(withText("Save")).inRoot(isDialog()).perform(click())
            onView(withText("Repeats: monthly on the 1st")).perform(scrollTo()).check(matches(isDisplayed()))
        }
        val c = TestEnv.store.checkin("c")!!
        assertEquals(Repeat.monthly(setOf(1)), c.repeat)
        val next = java.time.Instant.ofEpochMilli(
            TestEnv.ctx.getSystemService(AlarmManager::class.java).nextAlarmClock!!.triggerTime,
        ).atZone(java.time.ZoneId.systemDefault())
        assertEquals(1, next.dayOfMonth)
    }

    @Test
    fun theLastTimeCantBeRemoved() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Single")))
        open("c").use {
            onView(tagged("time:0")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("remove-time:0")).check(doesNotExist())
        }
    }

    @Test
    fun deleteCheckinAfterConfirming() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Doomed"), TestEnv.checkin("d", "Survivor")))
        open("c").use {
            onView(withText("Delete this check-in")).perform(scrollTo(), click())
            onView(withText("Delete")).inRoot(isDialog()).perform(click())
        }
        assertEquals(listOf("d"), TestEnv.store.checkins.map { it.id })
    }
}

/** The start page's on/off switch. */
@RunWith(AndroidJUnit4::class)
class StartPageTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    @Test
    fun cardsAreOnlyAsTallAsTheirContentSoSeveralFitOnScreen() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "First check-in"), TestEnv.checkin("b", "Second check-in")))
        TestEnv.allowOverlay(true) // one less "Finish setup" item, as on a set-up phone
        try {
            ActivityScenario.launch<MainActivity>(android.content.Intent(TestEnv.ctx, MainActivity::class.java)).use {
                // No scrolling: both should be on screen right away.
                onView(withText("First check-in")).check(matches(isCompletelyDisplayed()))
                onView(withText("Second check-in")).check(matches(isCompletelyDisplayed()))
            }
        } finally {
            TestEnv.allowOverlay(false)
        }
    }

    @Test
    fun switchOnTheNameRowTurnsTheAlarmOffAndOn() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Noon")))
        AlarmScheduler.schedule(TestEnv.ctx, TestEnv.store.checkin("c")!!)
        val am = TestEnv.ctx.getSystemService(AlarmManager::class.java)

        ActivityScenario.launch<MainActivity>(android.content.Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(tagged("toggle:c")).perform(click())
            assertFalse(TestEnv.store.checkin("c")!!.enabled)
            assertNull("alarm should be cancelled", am.nextAlarmClock)
            onView(withText(containsString("Off ·"))).check(matches(isDisplayed()))

            onView(tagged("toggle:c")).perform(click())
            assertTrue(TestEnv.store.checkin("c")!!.enabled)
            assertNotNull("alarm should be set again", am.nextAlarmClock)
        }
    }
}

/** The History page: stats are right, and fixing a day updates them. */
@RunWith(AndroidJUnit4::class)
class HistoryTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    @Test
    fun fixingADayUpdatesTheStats() {
        val soda = Question("soda", "Did you drink soda today?")
        val yesterday = LocalDate.now().minusDays(1).toString()
        val twoDaysAgo = LocalDate.now().minusDays(2).toString()
        TestEnv.reset(
            listOf(TestEnv.checkin("eve", "Evening", listOf(soda))),
            entries = mapOf(yesterday to mapOf("soda" to true), twoDaysAgo to mapOf("soda" to false)),
        )

        ActivityScenario.launch<HistoryActivity>(HistoryActivity.intent(TestEnv.ctx, "eve")).use {
            onView(withText("Last 7 days: 2 logged, 1 fully on track")).check(matches(isDisplayed()))
            onView(withText(containsString("1 of 2 days"))).perform(scrollTo()).check(matches(isDisplayed()))

            onView(withText(Days.short(yesterday))).perform(scrollTo(), click())
            onView(withText("Edit: Evening")).check(matches(isDisplayed()))
            onView(tagged("q:soda:no")).perform(scrollTo(), click())
            onView(withText("Submit")).perform(scrollTo(), click())

            onView(withText("Last 7 days: 2 logged, 2 fully on track")).check(matches(isDisplayed()))
        }
        assertEquals(mapOf("soda" to false), TestEnv.store.entry(yesterday))
    }
}
