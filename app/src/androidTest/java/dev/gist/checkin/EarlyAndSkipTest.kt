package dev.gist.checkin

import android.app.AlarmManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
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
import java.time.LocalTime

/** Doing an upcoming check-in early, skipping one, and how missed/skipped times are reported. */
@RunWith(AndroidJUnit4::class)
class EarlyAndSkipTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private val am get() = TestEnv.ctx.getSystemService(AlarmManager::class.java)

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    /** A time two hours from now, so the next ring is safely in the future. */
    private fun inTwoHours(): AlarmTime = LocalTime.now().plusHours(2).let { AlarmTime(it.hour, it.minute) }

    private fun setUp(c: Checkin): Occurrence {
        TestEnv.reset(listOf(c))
        AlarmScheduler.schedule(TestEnv.ctx, c)
        return AlarmScheduler.nextOpenOccurrence(TestEnv.store, c)!!
    }

    private fun startPage() = ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java))

    /** What the AlarmReceiver does when [o]'s alarm goes off. */
    private fun fire(c: Checkin, o: Occurrence) {
        AlarmReceiver().onReceive(
            TestEnv.ctx,
            Intent(TestEnv.ctx, AlarmReceiver::class.java)
                .putExtra(AlarmScheduler.EXTRA_CHECKIN, c.id)
                .putExtra(AlarmScheduler.EXTRA_TIME, o.timeKey)
                .putExtra(AlarmScheduler.EXTRA_DAY, o.day)
        )
    }

    @Test
    fun skipBeforeItRingsThenUndo() {
        val c = TestEnv.checkin("t", "Teeth").copy(times = listOf(inTwoHours()))
        val next = setUp(c)
        assertEquals(next.at / 1000, am.nextAlarmClock!!.triggerTime / 1000)

        startPage().use {
            onView(withText("Skip")).perform(scrollTo(), click())
            assertEquals(SlotResult.SKIPPED, TestEnv.store.result(next.day, "t", next.timeKey)!!.status)
            assertTrue("alarm should move past the skipped ring", am.nextAlarmClock!!.triggerTime > next.at)
            onView(tagged("closed:${next.timeKey}")).perform(scrollTo()).check(matches(withText(containsString("skipped"))))

            onView(withText("Undo")).perform(scrollTo(), click())
            assertNull(TestEnv.store.result(next.day, "t", next.timeKey))
            assertEquals(next.at / 1000, am.nextAlarmClock!!.triggerTime / 1000)
        }
    }

    @Test
    fun aSkippedRingStaysQuietIfItFiresAnyway() {
        val c = TestEnv.checkin("t", "Teeth").copy(times = listOf(inTwoHours()))
        val next = setUp(c)
        TestEnv.store.markSkipped(next.day, "t", next.timeKey)
        fire(c, next)
        assertFalse(AlarmService.running)
        assertTrue(TestEnv.store.pending.isEmpty())
        assertEquals(SlotResult.SKIPPED, TestEnv.store.result(next.day, "t", next.timeKey)!!.status)
    }

    @Test
    fun doItNowRunsTheStepsAndThatRingStaysQuiet() {
        val q = Question("floss", "Did you floss?", yesIsGood = true)
        val c = TestEnv.checkin("t", "Teeth", listOf(q)).copy(times = listOf(inTwoHours()))
        val next = setUp(c)

        startPage().use {
            onView(withText("Do it now")).perform(scrollTo(), click())
            onView(withText(containsString("early"))).check(matches(isDisplayed()))
            onView(tagged("q:floss:yes")).perform(scrollTo(), click())
            onView(withText("Submit")).perform(scrollTo(), click())
        }

        val r = TestEnv.store.result(next.day, "t", next.timeKey)!!
        assertEquals(SlotResult.ANSWERED, r.status)
        assertTrue("marked as done early", r.early)
        assertEquals(mapOf("floss" to true), r.answers)
        assertTrue("alarm should move past the resolved ring", am.nextAlarmClock!!.triggerTime > next.at)

        fire(c, next) // the original time arrives anyway
        assertFalse("shouldn't ring", AlarmService.running)
        assertTrue(TestEnv.store.pending.isEmpty())
    }

    @Test
    fun earlyScanMustReallyBeScanned() {
        // The CI emulator has no camera, so scanning fails, and early there's no math fallback.
        val c = TestEnv.checkin("s", "Brush teeth", scanCode = "CODE").copy(times = listOf(inTwoHours()))
        val next = setUp(c)
        ActivityScenario.launch<CheckinActivity>(CheckinActivity.earlyIntent(TestEnv.ctx, "s", next.day, next.timeKey)).use {
            onView(withText("Go scan: Test spot")).check(matches(isDisplayed()))
            onView(withText(containsString("Not near it?"))).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("scan-fallback")).check(doesNotExist())
        }
        assertNull("nothing is resolved without the scan", TestEnv.store.result(next.day, "s", next.timeKey))
    }

    @Test
    fun aRingThatWasntFinishedIsAnXAndSkippedDaysShowADash() {
        val soda = Question("soda", "Did you drink soda?")
        val c = TestEnv.checkin("eve", "Evening", listOf(soda)).copy(times = listOf(AlarmTime(18, 0), AlarmTime(21, 30)))
        TestEnv.reset(listOf(c))
        val yesterday = LocalDate.now().minusDays(1).toString()
        val twoDaysAgo = LocalDate.now().minusDays(2).toString()
        val s = TestEnv.store
        s.saveAnswers(yesterday, "eve", "18:00", mapOf("soda" to false)) // on track…
        s.markRang(yesterday, "eve", "21:30")                            // …but 9:30 PM was missed
        s.markSkipped(twoDaysAgo, "eve", "18:00")
        s.markSkipped(twoDaysAgo, "eve", "21:30")

        ActivityScenario.launch<HistoryActivity>(HistoryActivity.intent(TestEnv.ctx, "eve")).use {
            onView(withText("Last 7 days: 1 logged, 0 fully on track")).check(matches(isDisplayed()))
            onView(withText("–")).perform(scrollTo()).check(matches(isDisplayed()))

            // The missed time can be filled in afterwards.
            onView(withText(Days.short(yesterday))).perform(scrollTo(), click())
            onView(withText(containsString("missed"))).inRoot(isDialog()).check(matches(isDisplayed()))
        }
        assertNotNull(s.result(yesterday, "eve", "21:30"))
    }
}
