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
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** A check-in with two times: each time keeps its own answers; History rolls them up per day. */
@RunWith(AndroidJUnit4::class)
class PerTimeResultsTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private val soda = Question("soda", "Did you drink soda?")
    private val twice = TestEnv.checkin("eve", "Evening", listOf(soda))
        .copy(times = listOf(AlarmTime(18, 0), AlarmTime(21, 30)))

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    @Test
    fun bothTimesOnTheSameDayAreSavedSeparately() {
        val today = TestEnv.today()
        TestEnv.reset(listOf(twice), listOf(Pending("eve", today, "18:00"), Pending("eve", today, "21:30")))
        ActivityScenario.launch<CheckinActivity>(Intent(TestEnv.ctx, CheckinActivity::class.java)).use {
            onView(withText(containsString("6:00"))).check(matches(isDisplayed()))
            onView(tagged("q:soda:no")).perform(scrollTo(), click())
            onView(withText("Submit")).perform(scrollTo(), click())

            onView(withText(containsString("9:30"))).check(matches(isDisplayed()))
            onView(tagged("q:soda:yes")).perform(scrollTo(), click())
            onView(withText("Submit")).perform(scrollTo(), click())
        }
        val s = TestEnv.store
        assertEquals(mapOf("soda" to false), s.result(today, "eve", "18:00")!!.answers)
        assertEquals(mapOf("soda" to true), s.result(today, "eve", "21:30")!!.answers)
    }

    @Test
    fun historyShowsAnXIfAnyTimeThatDayWasOffTrack() {
        val yesterday = LocalDate.now().minusDays(1).toString()
        TestEnv.reset(listOf(twice))
        TestEnv.store.saveAnswers(yesterday, "eve", "18:00", mapOf("soda" to false)) // on track
        TestEnv.store.saveAnswers(yesterday, "eve", "21:30", mapOf("soda" to true))  // off track

        ActivityScenario.launch<HistoryActivity>(HistoryActivity.intent(TestEnv.ctx, "eve")).use {
            onView(withText("Last 7 days: 1 logged, 0 fully on track")).check(matches(isDisplayed()))
            onView(withText(containsString("1 of 1 days"))).perform(scrollTo()).check(matches(isDisplayed()))

            // Fix the 9:30 PM answer: tapping the day asks which time.
            onView(withText(Days.short(yesterday))).perform(scrollTo(), click())
            onView(withText(containsString("9:30"))).inRoot(isDialog()).perform(click())
            onView(tagged("q:soda:no")).perform(scrollTo(), click())
            onView(withText("Submit")).perform(scrollTo(), click())

            onView(withText("Last 7 days: 1 logged, 1 fully on track")).check(matches(isDisplayed()))
        }
        assertEquals(mapOf("soda" to false), TestEnv.store.result(yesterday, "eve", "18:00")!!.answers)
        assertEquals(mapOf("soda" to false), TestEnv.store.result(yesterday, "eve", "21:30")!!.answers)
    }
}
