package dev.gist.checkin

import android.app.AlarmManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One check-in, different days per time (4:00 AM Tuesdays, 4:45 AM the other days). */
@RunWith(AndroidJUnit4::class)
class PerTimeDaysTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    @Test
    fun savedAndLoaded() {
        val c = TestEnv.checkin("t", "Wake").copy(times = listOf(
            AlarmTime(4, 0, Repeat.weekly(setOf(2))),
            AlarmTime(4, 45, Repeat.weekly(setOf(1, 3, 4, 5, 6, 7))),
            AlarmTime(21, 0),
        ))
        TestEnv.reset(listOf(c))
        assertEquals(c, Store(TestEnv.ctx).checkin("t"))
    }

    @Test
    fun givingOneTimeItsOwnDaysOnTheEditPage() {
        TestEnv.reset(listOf(TestEnv.checkin("t", "Wake").copy(times = listOf(AlarmTime(4, 0), AlarmTime(4, 45)))))
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, "t")).use {
            // 4:00 AM → Tuesdays only.
            onView(tagged("time-days:0")).perform(scrollTo(), click())
            onView(withText("Specific days of the week…")).inRoot(isDialog()).perform(click())
            for (d in listOf("Monday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")) {
                onView(tagged("check:$d")).inRoot(isDialog()).perform(click()) // untick (all start ticked)
            }
            onView(withText("Save")).inRoot(isDialog()).perform(click())
            // 4:45 AM → every day but Tuesday.
            onView(tagged("time-days:1")).perform(scrollTo(), click())
            onView(withText("Specific days of the week…")).inRoot(isDialog()).perform(click())
            onView(tagged("check:Tuesday")).inRoot(isDialog()).perform(click())
            onView(withText("Save")).inRoot(isDialog()).perform(click())

            onView(tagged("time-days:0")).perform(scrollTo()).check(matches(withText("Tue")))
            onView(tagged("time-days:1")).check(matches(withText("every day but Tue")))
            onView(tagged("repeat")).check(doesNotExist()) // per-time days replace the shared rule
        }
        val c = TestEnv.store.checkin("t")!!
        assertEquals(Repeat.weekly(setOf(2)), c.times[0].days)
        assertEquals(Repeat.weekly(setOf(1, 3, 4, 5, 6, 7)), c.times[1].days)
        // The real alarm is the next of those.
        val am = TestEnv.ctx.getSystemService(AlarmManager::class.java)
        assertEquals(AlarmScheduler.nextTriggerMillis(c)!! / 1000, am.nextAlarmClock!!.triggerTime / 1000)
    }

    @Test
    fun changingATimeKeepsItsDays() {
        TestEnv.reset(listOf(TestEnv.checkin("t", "Wake").copy(times = listOf(AlarmTime(4, 0, Repeat.weekly(setOf(2)))))))
        val c = TestEnv.store.checkin("t")!!
        TestEnv.store.updateCheckin("t") { it.copy(times = listOf(it.times[0].copy(hour = 3, minute = 50))) }
        assertEquals(Repeat.weekly(setOf(2)), TestEnv.store.checkin("t")!!.times[0].days)
        assertNull("other times inherit", AlarmTime(5, 0).days)
        assertEquals(c.id, "t")
    }
}
