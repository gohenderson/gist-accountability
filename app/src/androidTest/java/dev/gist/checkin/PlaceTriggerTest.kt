package dev.gist.checkin

import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
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
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

/** Place triggers: arriving / leaving, the conditions, and "only when at / away from" for timed ones. */
@RunWith(AndroidJUnit4::class)
class PlaceTriggerTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private val gym = Place("Gym", 40.7128, -74.0060, 200)
    private val pkg get() = TestEnv.ctx.packageName

    @Before
    fun setUp() {
        TestEnv.shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        TestEnv.shell("pm grant $pkg android.permission.ACCESS_FINE_LOCATION")
        TestEnv.shell("pm grant $pkg android.permission.ACCESS_COARSE_LOCATION")
        TestEnv.shell("pm grant $pkg android.permission.ACCESS_BACKGROUND_LOCATION")
        LocationTriggers.ignoreSettling = true
    }

    @After
    fun tearDown() {
        LocationTriggers.ignoreSettling = false
        LocationTriggers.locateOverride = null
        TestEnv.reset(emptyList())
    }

    private fun arriveCheckin(window: Pair<AlarmTime, AlarmTime>? = null) = TestEnv.checkin("g", "Gym").copy(
        times = emptyList(),
        events = listOf(Trigger(Trigger.ARRIVE, gym, windowStart = window?.first, windowEnd = window?.second)),
    )

    private fun proximity(entering: Boolean) = ProximityReceiver().onReceive(
        TestEnv.ctx,
        Intent().putExtra(AlarmScheduler.EXTRA_CHECKIN, "g").putExtra(LocationManager.KEY_PROXIMITY_ENTERING, entering),
    )

    private fun quietFor(ms: Long) {
        SystemClock.sleep(ms)
        assertFalse("should not ring", AlarmService.running)
        assertTrue(TestEnv.store.pending.isEmpty())
    }

    @Test
    fun arrivingRingsAndOnlyOnceADay() {
        TestEnv.reset(listOf(arriveCheckin()))
        proximity(entering = true)
        TestEnv.eventually(15_000) { assertTrue("should ring on arrival", AlarmService.running) }
        val p = TestEnv.store.pending.single()
        assertEquals("g", p.checkinId)
        assertTrue("saved under the time it rang", AlarmTime.parse(p.time) != null)

        // Stop it, then arrive again the same day: once a day means no second ring.
        TestEnv.ctx.stopService(Intent(TestEnv.ctx, AlarmService::class.java))
        TestEnv.store.pending = emptyList()
        TestEnv.eventually { assertFalse(AlarmService.running) }
        proximity(entering = true)
        quietFor(3_000)
    }

    @Test
    fun leavingDoesntRingAnArrivalTrigger() {
        TestEnv.reset(listOf(arriveCheckin()))
        proximity(entering = false)
        quietFor(3_000)
    }

    @Test
    fun outsideTheTimeWindowNothingRings() {
        val now = LocalTime.now()
        val start = now.plusHours(2)
        val end = now.plusHours(3)
        TestEnv.reset(listOf(arriveCheckin(AlarmTime(start.hour, start.minute) to AlarmTime(end.hour, end.minute))))
        proximity(entering = true)
        quietFor(3_000)
    }

    private fun locatedAt(lat: Double, lon: Double) {
        LocationTriggers.locateOverride = { Location("test").apply { latitude = lat; longitude = lon } }
    }

    private fun alarmFires(c: Checkin) = AlarmReceiver().onReceive(
        TestEnv.ctx,
        Intent().putExtra(AlarmScheduler.EXTRA_CHECKIN, c.id).putExtra(AlarmScheduler.EXTRA_TIME, "12:00")
            .putExtra(AlarmScheduler.EXTRA_DAY, TestEnv.today()),
    )

    @Test
    fun onlyWhenAtAPlace_skipsWhenYoureElsewhere() {
        val c = TestEnv.checkin("h", "At home").copy(times = listOf(AlarmTime(12, 0, where = Trigger.AT, place = gym)))
        TestEnv.reset(listOf(c))
        locatedAt(41.0, -74.0) // ~32 km away
        alarmFires(c)
        quietFor(2_000)
        assertEquals(SlotResult.SKIPPED, TestEnv.store.result(TestEnv.today(), "h", "12:00")?.status)
    }

    @Test
    fun onlyWhenAtAPlace_ringsWhenYoureThere() {
        val c = TestEnv.checkin("h", "At home").copy(times = listOf(AlarmTime(12, 0, where = Trigger.AT, place = gym)))
        TestEnv.reset(listOf(c))
        locatedAt(40.7129, -74.0061)
        alarmFires(c)
        TestEnv.eventually(15_000) { assertTrue("should ring", AlarmService.running) }
    }

    @Test
    fun onlyWhenAway_ringsWhenYoureElsewhere() {
        val c = TestEnv.checkin("h", "Away").copy(times = listOf(AlarmTime(12, 0, where = Trigger.AWAY, place = gym)))
        TestEnv.reset(listOf(c))
        locatedAt(41.0, -74.0)
        alarmFires(c)
        TestEnv.eventually(15_000) { assertTrue("should ring", AlarmService.running) }
    }

    @Test
    fun thePlaceIsWatchedWhileOnAndNotWhenOff() {
        val c = arriveCheckin()
        TestEnv.reset(listOf(c))
        AlarmScheduler.schedule(TestEnv.ctx, c)
        assertTrue("watching the place", LocationTriggers.isRegistered(TestEnv.ctx, "g"))
        val off = c.copy(enabled = false)
        TestEnv.store.checkins = listOf(off)
        AlarmScheduler.schedule(TestEnv.ctx, off)
        assertFalse("stopped watching", LocationTriggers.isRegistered(TestEnv.ctx, "g"))
    }

    @Test
    fun choosingArriveAndTypingCoordinatesOnTheEditPage() {
        TestEnv.reset(listOf(TestEnv.checkin("g", "Gym")))
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, "g")).use {
            onView(tagged("trigger-when:0")).perform(scrollTo(), click())
            onView(withText("When I arrive at a place")).inRoot(isDialog()).perform(click())
            onView(withText("Enter coordinates…")).inRoot(isDialog()).perform(click())
            onView(tagged("place-coords")).inRoot(isDialog()).perform(replaceText("40.7128, -74.0060"))
            onView(withText("Next")).inRoot(isDialog()).perform(click())
            onView(tagged("place-name")).inRoot(isDialog()).perform(replaceText("Gym"))
            onView(tagged("radius:500")).inRoot(isDialog()).perform(click())
            onView(withText("Save")).inRoot(isDialog()).perform(click())
            onView(tagged("trigger-place:0")).perform(scrollTo()).check(matches(withText("Gym · 500 m")))
            onView(tagged("trigger-summary")).perform(scrollTo())
                .check(matches(withText(containsString("when you arrive at Gym"))))
        }
        val t = TestEnv.store.checkin("g")!!.events.single()
        assertEquals(Trigger.ARRIVE, t.kind)
        assertEquals(Place("Gym", 40.7128, -74.006, 500), t.place)
        assertTrue("watching it", LocationTriggers.isRegistered(TestEnv.ctx, "g"))
    }

    @Test
    fun twoPlacesOnOneCheckinAreBothWatched() {
        val home = Place("Home", 40.70, -74.00, 200)
        val c = TestEnv.checkin("g", "Two").copy(times = emptyList(), events = listOf(
            Trigger(Trigger.ARRIVE, gym), Trigger(Trigger.LEAVE, home),
        ))
        TestEnv.reset(listOf(c))
        AlarmScheduler.schedule(TestEnv.ctx, c)
        assertTrue(LocationTriggers.isRegistered(TestEnv.ctx, "g"))
        // Leaving home (the second trigger) rings.
        ProximityReceiver().onReceive(TestEnv.ctx, Intent()
            .putExtra(AlarmScheduler.EXTRA_CHECKIN, "g").putExtra(LocationTriggers.EXTRA_INDEX, 1)
            .putExtra(LocationManager.KEY_PROXIMITY_ENTERING, false))
        TestEnv.eventually(15_000) { assertTrue("leaving home should ring", AlarmService.running) }
    }

    @Test
    fun anOldSingleTriggerCheckinIsConverted() {
        TestEnv.reset(emptyList())
        val legacy = """[{"id":"old","name":"Gym","times":["12:00"],"repeat":{"kind":"weekly","days":[1,3,5]},
            "trigger":{"kind":"arrive","place":{"name":"Gym","lat":40.7128,"lon":-74.006,"radiusM":200},"oncePerDay":true}}]"""
        TestEnv.ctx.getSharedPreferences("gist", android.content.Context.MODE_PRIVATE).edit().putString("checkins", legacy).commit()
        val c = Store(TestEnv.ctx).checkin("old")!!
        assertTrue("an event replaced the times", c.times.isEmpty())
        assertEquals(Trigger.ARRIVE, c.events.single().kind)
        assertEquals(Repeat.weekly(setOf(1, 3, 5)), c.events.single().days)
    }
}
