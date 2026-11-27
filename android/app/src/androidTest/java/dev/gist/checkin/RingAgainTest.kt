package dev.gist.checkin

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime

/** "Ring in…" on a check-in's page, and "Ring again in…" after a ring from one of those times. */
@RunWith(AndroidJUnit4::class)
class RingAgainTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private val basted = Question("basted", "Did you baste it?", yesIsGood = true)

    @Before
    fun setUp() {
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.POST_NOTIFICATIONS")
    }

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    /** The check-in's one-off times that are still ahead. */
    private fun ahead(id: String): List<ZonedDateTime> {
        val now = ZonedDateTime.now()
        return TestEnv.store.checkin(id)!!.times.mapNotNull { QuickTime.moment(it, now) }.filter { it.isAfter(now) }
    }

    private fun minutesFromNow(t: ZonedDateTime) = java.time.Duration.between(ZonedDateTime.now(), t).toMinutes()

    @Test
    fun ringInAddsATimeThatRingsOnce() {
        TestEnv.reset(listOf(TestEnv.checkin("turkey", "Baste the turkey", listOf(basted))))
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, "turkey")).use {
            onView(tagged("ring-in")).perform(scrollTo(), click())
            onView(tagged("ring-in:30")).inRoot(isDialog()).perform(click())
            TestEnv.eventually { assertEquals(1, ahead("turkey").size) }
            assertTrue("about 30 min", minutesFromNow(ahead("turkey").single()) in 28..30)
            // Other…: any number of minutes.
            onView(tagged("ring-in")).perform(scrollTo(), click())
            onView(tagged("ring-in:other")).inRoot(isDialog()).perform(click())
            onView(tagged("quick-other-minutes")).inRoot(isDialog()).perform(replaceText("50"))
            onView(withText("Set")).inRoot(isDialog()).perform(click())
            TestEnv.eventually { assertEquals(2, ahead("turkey").size) }
        }
        val c = TestEnv.store.checkin("turkey")!!
        assertTrue("its daily time stays", c.times.contains(AlarmTime(12, 0)))
        assertEquals(50, TestEnv.store.quickMinutes)
        assertTrue("the alarm is set", AlarmScheduler.nextOpenOccurrence(TestEnv.store, c) != null)
    }

    @Test
    fun aRingFromARingInTimeOffersToRingAgain() {
        // It was set to ring a minute ago, once, and is ringing now.
        val rang = ZonedDateTime.now().minusMinutes(1)
        val once = AlarmTime(rang.hour, rang.minute, days = Repeat.once(rang.toLocalDate()))
        val c = TestEnv.checkin("turkey", "Baste the turkey", listOf(basted)).copy(times = listOf(once))
        TestEnv.reset(listOf(c), listOf(Pending("turkey", TestEnv.today(), once.toString())))
        ActivityScenario.launch<CheckinActivity>(Intent(TestEnv.ctx, CheckinActivity::class.java)).use { scenario ->
            onView(tagged("q:basted:yes")).perform(scrollTo(), click())
            onView(withText("Submit")).perform(scrollTo(), click())
            TestEnv.eventually { onView(tagged("again-title")).check(matches(isDisplayed())) }
            assertTrue("nothing waiting: the alarm is answered", TestEnv.store.pending.isEmpty())
            onView(tagged("again:${QuickTime.DEFAULT}")).check(matches(withText("Again in 30 min"))).perform(click())
            TestEnv.eventually { assertEquals(Lifecycle.State.DESTROYED, scenario.state) }
        }
        val times = TestEnv.store.checkin("turkey")!!.times
        assertEquals("the passed one is cleared, the next one added: $times", 1, times.size)
        assertTrue("in about 30 min", minutesFromNow(ahead("turkey").single()) in 28..30)
    }

    @Test
    fun anEverydayRingClosesAsBefore() {
        TestEnv.reset(listOf(TestEnv.checkin("eve", "Evening", listOf(basted))), listOf(Pending("eve", TestEnv.today(), "12:00")))
        ActivityScenario.launch<CheckinActivity>(Intent(TestEnv.ctx, CheckinActivity::class.java)).use { scenario ->
            onView(tagged("q:basted:yes")).perform(scrollTo(), click())
            onView(withText("Submit")).perform(scrollTo(), click())
            TestEnv.eventually { assertEquals(Lifecycle.State.DESTROYED, scenario.state) }
        }
        assertEquals(listOf(AlarmTime(12, 0)), TestEnv.store.checkin("eve")!!.times)
    }

    @Test
    fun doneClosesWithoutAnotherTime() {
        val rang = ZonedDateTime.now().minusMinutes(1)
        val once = AlarmTime(rang.hour, rang.minute, days = Repeat.once(rang.toLocalDate()))
        val c = TestEnv.checkin("turkey", "Baste the turkey", listOf(basted)).copy(times = listOf(once))
        TestEnv.reset(listOf(c), listOf(Pending("turkey", TestEnv.today(), once.toString())))
        ActivityScenario.launch<CheckinActivity>(Intent(TestEnv.ctx, CheckinActivity::class.java)).use { scenario ->
            onView(tagged("q:basted:yes")).perform(scrollTo(), click())
            onView(withText("Submit")).perform(scrollTo(), click())
            TestEnv.eventually { onView(tagged("again-close")).check(matches(isDisplayed())) }
            onView(tagged("again-close")).perform(click())
            TestEnv.eventually { assertEquals(Lifecycle.State.DESTROYED, scenario.state) }
        }
        assertTrue(ahead("turkey").isEmpty())
    }
}
