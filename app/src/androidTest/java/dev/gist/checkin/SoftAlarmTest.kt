package dev.gist.checkin

import android.app.NotificationManager
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
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Soft alarms: snooze, dismiss without the steps, can be left, and stay out of the stats. */
@RunWith(AndroidJUnit4::class)
class SoftAlarmTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private val q = Question("soda", "Soda?")

    @Before
    fun setUp() {
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.POST_NOTIFICATIONS")
    }

    @After
    fun tearDown() {
        AlarmScheduler.testSnoozeMs = null
        TestEnv.allowOverlay(false)
        TestEnv.reset(emptyList())
    }

    private fun soft(id: String = "s", questions: List<Question> = listOf(q)) =
        TestEnv.checkin(id, "Soft one", questions).copy(commitment = Checkin.SOFT)

    /** A real (not practice) ring of [c], with its alarm screen in front. */
    private fun ringFor(c: Checkin) {
        TestEnv.reset(listOf(c))
        AlarmScheduler.scheduleTest(TestEnv.ctx, c.id, 1_000, practice = false)
        TestEnv.eventually(20_000) { assertTrue("alarm didn't start", AlarmService.running) }
        TestEnv.eventually(10_000) { assertTrue("alarm screen should open", CheckinActivity.visible) }
    }

    private fun waitUntilQuiet() {
        TestEnv.eventually(10_000) { assertFalse("alarm should stop", AlarmService.running) }
        assertTrue(TestEnv.store.pending.isEmpty())
    }

    @Test
    fun dismissStopsItWithoutTheSteps() {
        ringFor(soft())
        onView(tagged("start-checkin")).check(matches(isDisplayed())) // the steps are still there
        onView(tagged("snooze")).check(matches(withText("Snooze 10 min")))
        onView(tagged("dismiss-alarm")).perform(scrollTo(), click())
        waitUntilQuiet()
        val r = TestEnv.store.results(TestEnv.today(), "s").values.single()
        assertEquals(SlotResult.DISMISSED, r.status)
        assertTrue("logged as soft", r.soft)
        assertTrue("no stats", TestEnv.store.allResults("s").isEmpty())
    }

    @Test
    fun withoutStepsDismissIsTheMainButton() {
        ringFor(soft(questions = emptyList()))
        onView(tagged("start-checkin")).check(doesNotExist())
        onView(tagged("dismiss-alarm")).check(matches(withText("Dismiss")))
        onView(tagged("dismiss-alarm")).perform(scrollTo(), click())
        waitUntilQuiet()
    }

    @Test
    fun theStepsCanStillBeDoneOrAbandoned() {
        ringFor(soft())
        onView(tagged("start-checkin")).perform(click())
        onView(tagged("q:soda:no")).check(matches(isDisplayed()))
        onView(tagged("dismiss-alarm")).check(matches(isDisplayed())) // optional: can still dismiss
        onView(tagged("q:soda:no")).perform(click())
        onView(withText("Submit")).perform(scrollTo(), click())
        waitUntilQuiet()
        // Done, but a soft alarm's answers still don't count.
        assertTrue(TestEnv.store.results(TestEnv.today(), "s").values.single().soft)
        assertNull(Score.of(TestEnv.store.checkin("s")!!, TestEnv.store.allResults("s")))
    }

    @Test
    fun snoozeRingsTheSameTimeAgainLater() {
        AlarmScheduler.testSnoozeMs = 4_000
        ringFor(soft())
        val first = TestEnv.store.pending.single()
        onView(tagged("snooze")).perform(scrollTo(), click())
        waitUntilQuiet()
        TestEnv.eventually(20_000) { assertTrue("snooze should ring again", AlarmService.running) }
        assertEquals(listOf(first), TestEnv.store.pending)
    }

    @Test
    fun strictAlarmsHaveNoSnoozeOrDismiss() {
        ringFor(TestEnv.checkin("t", "Strict one", listOf(q)))
        onView(tagged("start-checkin")).check(matches(isDisplayed()))
        onView(tagged("snooze")).check(doesNotExist())
        onView(tagged("dismiss-alarm")).check(doesNotExist())
        val n = TestEnv.ctx.getSystemService(NotificationManager::class.java).activeNotifications
            .first { it.id == Notif.ID_ALARM }.notification
        assertTrue("no notification buttons", n.actions.isNullOrEmpty())
    }

    @Test
    fun theNotificationCanSnoozeOrDismissIt() {
        ringFor(soft())
        val n = TestEnv.ctx.getSystemService(NotificationManager::class.java).activeNotifications
            .first { it.id == Notif.ID_ALARM }.notification
        assertEquals(listOf("Snooze 10 min", "Dismiss"), n.actions.map { it.title.toString() })
        val p = TestEnv.store.pending.single()
        SoftAlarmReceiver().onReceive(TestEnv.ctx, SoftAlarmReceiver.intent(TestEnv.ctx, SoftAlarmReceiver.ACTION_DISMISS, p))
        waitUntilQuiet()
        assertEquals(SlotResult.DISMISSED, TestEnv.store.results(TestEnv.today(), "s").values.single().status)
        TestEnv.eventually(10_000) { assertFalse("the alarm screen closes too", CheckinActivity.visible) }
    }

    @Test
    fun aSoftAlarmCanBeLeft() {
        TestEnv.allowOverlay(true)
        ringFor(soft())
        UiDevice.getInstance(TestEnv.instrumentation).pressHome()
        Thread.sleep(4_000)
        assertFalse("a soft alarm doesn't pull itself back", CheckinActivity.visible)
        assertTrue("still ringing until snoozed or dismissed", AlarmService.running)
    }

    @Test
    fun softResultsStayOutOfTheStatsEvenAfterSwitchingToStrict() {
        TestEnv.reset(listOf(soft()))
        val days = Days.lastN(3)
        TestEnv.store.saveAnswers(days[0], "s", "12:00", mapOf("soda" to true)) // off track, but soft
        assertTrue(TestEnv.store.allResults("s").isEmpty())
        TestEnv.store.updateCheckin("s") { it.copy(commitment = Checkin.STRICT) }
        TestEnv.store.saveAnswers(days[1], "s", "12:00", mapOf("soda" to false)) // no soda: on track
        assertEquals("only the strict day counts", listOf(days[1]), TestEnv.store.allResults("s").keys.toList())
        assertEquals(1.0, Score.of(TestEnv.store.checkin("s")!!, TestEnv.store.allResults("s"))!!, 0.0)
        assertEquals(2, TestEnv.store.allResults("s", includeSoft = true).size)
    }

    @Test
    fun theStartPageLeavesSoftCheckinsOutOfTheScores() {
        TestEnv.allowOverlay(true)
        TestEnv.reset(listOf(TestEnv.checkin("a", "Strict", listOf(q)), soft()))
        TestEnv.store.saveAnswers(TestEnv.today(), "a", "12:00", mapOf("soda" to true)) // off track: 0%
        TestEnv.store.updateCheckin("s") { it.copy(commitment = Checkin.STRICT) }
        TestEnv.store.saveAnswers(Days.lastN(2)[0], "s", "12:00", mapOf("soda" to false)) // on track, counted only while strict
        TestEnv.store.updateCheckin("s") { it.copy(commitment = Checkin.SOFT) }
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(tagged("soft-note:s")).perform(scrollTo()).check(matches(withText("Soft alarm · not counted in stats")))
            onView(tagged("overall-score")).check(matches(withText("0%")))
        }
        // The soft one has no score while it's soft, so it isn't in the average.
        assertNull(Score.of(TestEnv.store.checkin("s")!!, TestEnv.store.allResults("s")))
    }

    @Test
    fun commitmentAndSnoozeAreSetOnTheCheckinPage() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Pick", listOf(q))))
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, "c")).use {
            onView(tagged("commitment")).perform(scrollTo()).check(matches(withText("Commitment: Strict")))
            onView(tagged("snooze-length")).check(doesNotExist())
            onView(tagged("commitment")).perform(click())
            onView(tagged("commitment:soft")).inRoot(isDialog()).perform(click())
            onView(tagged("commitment")).perform(scrollTo()).check(matches(withText("Commitment: Soft")))
            onView(tagged("snooze-length")).perform(scrollTo(), click())
            onView(tagged("snooze:15")).inRoot(isDialog()).perform(click())
            onView(tagged("snooze-length")).perform(scrollTo()).check(matches(withText("Snooze: 15 minutes")))
        }
        val c = TestEnv.store.checkin("c")!!
        assertTrue(c.soft)
        assertEquals(15, c.snoozeMinutes)
        // And it survives a backup round trip.
        val back = TestEnv.store.checkinFromJson(TestEnv.store.checkinToJson(c))
        assertTrue(back.soft)
        assertEquals(15, back.snoozeMinutes)
    }
}
