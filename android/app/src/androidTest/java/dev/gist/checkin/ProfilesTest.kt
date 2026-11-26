package dev.gist.checkin

import android.app.PendingIntent
import android.content.Context
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Profiles: separate setups on one phone, all of whose alarms ring. */
@RunWith(AndroidJUnit4::class)
class ProfilesTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private val ctx get() = TestEnv.ctx
    private lateinit var work: Profiles.Profile
    private val workCtx: Context get() = Profiles.scoped(ctx, work.id)

    @Before
    fun setUp() {
        TestEnv.shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        TestEnv.reset(listOf(TestEnv.checkin("home", "Home one")))
        work = Profiles.add(ctx, "Work")
    }

    @After
    fun tearDown() {
        Store(workCtx).checkins.forEach { AlarmScheduler.cancel(workCtx, it.id) }
        TestEnv.reset(emptyList())
    }

    @Test
    fun aNewProfileStartsEmptyAndKeepsItsOwnThings() {
        assertEquals(listOf("Personal", "Work"), Profiles.all(ctx).map { it.name })
        val w = Store(workCtx)
        assertTrue("starts empty", w.checkins.isEmpty())
        w.checkins = listOf(TestEnv.checkin("job", "Job one"))
        w.pauseSeconds = Store.PAUSE_CHOICES.last()
        assertEquals(listOf("home"), TestEnv.store.checkins.map { it.id })
        assertEquals(listOf("job"), Store(workCtx).checkins.map { it.id })
        assertNotEquals(TestEnv.store.pauseSeconds, Store(workCtx).pauseSeconds)
        // Its own Gist folder and devices.
        assertNotEquals(Profiles.prefsName(GistFolder.PREFS, Profiles.DEFAULT), Profiles.prefsName(GistFolder.PREFS, work.id))
        assertEquals(GistFolder.PREFS, Profiles.prefsName(GistFolder.PREFS, Profiles.DEFAULT)) // the first one's are where they were
        // Its own files (photos, clips).
        assertEquals(ctx.filesDir, Profiles.filesDir(ctx))
        assertEquals(File(ctx.filesDir, "profiles/${work.id}"), Profiles.filesDir(workCtx))
    }

    @Test
    fun theSameCheckinInTwoProfilesHasTwoAlarms() {
        // Same id in both (a backup imported twice, say): each profile's alarm is its own.
        TestEnv.reset(listOf(TestEnv.checkin("c")))
        work = Profiles.add(ctx, "Work")
        Store(workCtx).checkins = listOf(TestEnv.checkin("c"))
        Profiles.scheduleAll(ctx)
        fun pi(c: Context) = PendingIntent.getBroadcast(
            c, (Profiles.hash(c, "c") and 0x3FFFFFFF) * 2,
            Profiles.distinct(Intent(c, AlarmReceiver::class.java), c, "ring/c/false"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        assertNotNull("personal's alarm", pi(ctx))
        assertNotNull("work's alarm", pi(workCtx))
        AlarmScheduler.cancel(workCtx, "c")
        assertNull("work's cancelled", pi(workCtx))
        assertNotNull("personal's still set", pi(ctx))
    }

    @Test
    fun anotherProfilesAlarmRingsAndIsAnsweredInIt() {
        val q = Question("calls", "Calls done?")
        Store(workCtx).checkins = listOf(TestEnv.checkin("job", "Job one", listOf(q)))
        assertEquals(Profiles.DEFAULT, Profiles.current(ctx)) // on Personal
        AlarmScheduler.scheduleTest(workCtx, "job", 1_000, practice = false)
        TestEnv.eventually(20_000) { assertTrue("work's alarm rings", AlarmService.running) }
        TestEnv.eventually(10_000) { assertTrue("alarm screen opens", CheckinActivity.visible) }
        onView(tagged("alarm-profile")).check(matches(withText("Work")))
        onView(tagged("start-checkin")).perform(click())
        onView(tagged("q:calls:yes")).perform(click())
        onView(withText("Submit")).perform(scrollTo(), click())
        TestEnv.eventually(10_000) { assertFalse("alarm stops", AlarmService.running) }
        // Saved in Work; Personal untouched, and still the one you're on.
        val w = Store(workCtx)
        assertTrue(w.pending.isEmpty())
        assertEquals(true, w.results(Days.today(), "job").values.single().answers["calls"])
        assertTrue(TestEnv.store.results(Days.today(), "job").isEmpty())
        assertEquals(Profiles.DEFAULT, Profiles.current(ctx))
    }

    @Test
    fun theStartPageSwitchesAndShowsOtherProfilesWaiting() {
        Store(workCtx).checkins = listOf(TestEnv.checkin("job", "Job one").copy(soundUri = Checkin.QUIET))
        Store(workCtx).addPending(Pending("job", Days.today(), "12:00"))
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(tagged("profile")).check(matches(withText("Personal ▾")))
            onView(tagged("profile-waiting:${work.id}")).check(matches(isDisplayed()))
            onView(tagged("profile-switch:${work.id}")).perform(click())
            TestEnv.eventually(10_000) { assertEquals(work.id, Profiles.current(ctx)) }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(tagged("profile")).check(matches(withText("Work ▾")))
            onView(tagged("checkin-title:job")).check(matches(isDisplayed()))
            onView(tagged("checkin-title:home")).check(doesNotExist())
        }
    }

    @Test
    fun deletingAProfileClearsItAndItsAlarms() {
        Store(workCtx).checkins = listOf(TestEnv.checkin("job"))
        AlarmScheduler.schedule(workCtx, Store(workCtx).checkin("job")!!)
        File(Profiles.filesDir(workCtx), "x").writeText("x")
        assertFalse("not the first", Profiles.remove(ctx, Profiles.DEFAULT))
        Profiles.setCurrent(ctx, work.id)
        assertFalse("not the one you're on", Profiles.remove(ctx, work.id))
        Profiles.setCurrent(ctx, Profiles.DEFAULT)
        assertTrue(Profiles.remove(ctx, work.id))
        assertEquals(listOf("Personal"), Profiles.all(ctx).map { it.name })
        assertFalse(File(ctx.filesDir, "profiles/${work.id}").exists())
        assertTrue("its check-ins are gone", Store(Profiles.scoped(ctx, work.id)).checkins.isEmpty())
        assertNull(PendingIntent.getBroadcast(
            workCtx, (Profiles.hash(workCtx, "job") and 0x3FFFFFFF) * 2,
            Profiles.distinct(Intent(workCtx, AlarmReceiver::class.java), workCtx, "ring/job/false"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ))
        assertEquals(listOf("home"), TestEnv.store.checkins.map { it.id })
    }
}
