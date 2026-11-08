package dev.gist.checkin

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Saving and loading on a real device, including upgrading data from the first version. */
@RunWith(AndroidJUnit4::class)
class StoreTest {
    @After
    fun tearDown() = TestEnv.reset(emptyList())

    private fun clearPrefs() =
        TestEnv.ctx.getSharedPreferences("gist", Context.MODE_PRIVATE).edit().clear().commit()

    @Test
    fun freshInstallStartsWithMorningAndEvening() {
        clearPrefs()
        assertEquals(listOf("morning", "evening"), Store(TestEnv.ctx).checkins.map { it.id })
    }

    @Test
    fun checkinsRoundTripWithEveryField() {
        TestEnv.reset(emptyList())
        val c = Checkin(
            id = "x", name = "Starting the Day", times = listOf(AlarmTime(6, 45), AlarmTime(18, 0)), enabled = false,
            aboutYesterday = true, questions = listOf(Question("a", "A?", yesIsGood = true)),
            mathCount = 3, mathLevel = 4, scanCode = "ABC-123", scanLabel = "Sink",
        )
        TestEnv.store.checkins = listOf(c)
        assertEquals(listOf(c), Store(TestEnv.ctx).checkins)
    }

    @Test
    fun missingScanCodeStaysNull() {
        TestEnv.reset(listOf(TestEnv.checkin("x")))
        assertNull(Store(TestEnv.ctx).checkin("x")!!.scanCode)
    }

    @Test
    fun eachTimeKeepsItsOwnAnswers() {
        TestEnv.reset(emptyList())
        val s = TestEnv.store
        s.saveEntry("2026-11-07", "07:00", mapOf("soda" to false))
        s.saveEntry("2026-11-07", "21:30", mapOf("soda" to true))
        s.saveEntry("2026-11-07", "07:00", mapOf("latefood" to true)) // another check-in at the same time
        assertEquals(mapOf("soda" to false, "latefood" to true), s.entry("2026-11-07", "07:00"))
        assertEquals(mapOf("soda" to true), s.entry("2026-11-07", "21:30"))
        s.saveEntry("2026-11-07", "21:30", mapOf("soda" to false)) // fixing an answer overwrites only that time
        assertEquals(mapOf("soda" to false), s.entry("2026-11-07", "21:30"))
        assertEquals(mapOf("soda" to false, "latefood" to true), s.entry("2026-11-07", "07:00"))
    }

    @Test
    fun answersSavedPerDayMoveUnderTheCheckinsFirstTime() {
        clearPrefs()
        TestEnv.ctx.getSharedPreferences("gist", Context.MODE_PRIVATE).edit()
            .putString("checkins", """[{"id":"eve","name":"Evening","times":["18:00","21:30"],"questions":[{"id":"soda","text":"Soda?"}]}]""")
            .putString("entries", """{"2026-11-06":{"soda":true,"unknown":false}}""")
            .commit()
        val s = Store(TestEnv.ctx)
        assertEquals(mapOf("soda" to true), s.entry("2026-11-06", "18:00"))
        assertEquals(mapOf("unknown" to false), s.entry("2026-11-06", "12:00"))
        // Converted once and saved in the new shape.
        val raw = TestEnv.ctx.getSharedPreferences("gist", Context.MODE_PRIVATE).getString("entries", "")!!
        assertTrue(raw, raw.contains("\"18:00\""))
    }

    @Test
    fun waitingListIsDeduplicatedAndPruned() {
        TestEnv.reset(listOf(TestEnv.checkin("a")))
        val s = TestEnv.store
        s.addPending(Pending("a", "2026-11-07"))
        s.addPending(Pending("a", "2026-11-07"))
        s.addPending(Pending("deleted", "2026-11-07"))
        assertEquals(2, s.pending.size)
        s.prunePending()
        assertEquals(listOf(Pending("a", "2026-11-07")), s.pending)
        s.removePending(Pending("a", "2026-11-07"))
        assertTrue(s.pending.isEmpty())
    }

    @Test
    fun checkinsSavedWithASingleTimeLoadAsAOneTimeList() {
        clearPrefs()
        TestEnv.ctx.getSharedPreferences("gist", Context.MODE_PRIVATE).edit()
            .putString("checkins", """[{"id":"old","name":"Morning","hour":7,"minute":15,"enabled":true,"questions":[]}]""")
            .commit()
        assertEquals(listOf(AlarmTime(7, 15)), Store(TestEnv.ctx).checkin("old")!!.times)
    }

    @Test
    fun settingsFromTheFirstVersionAreMigrated() {
        clearPrefs()
        TestEnv.ctx.getSharedPreferences("gist", Context.MODE_PRIVATE).edit()
            .putString("questions", """[{"id":"old","text":"Old question?","yesIsGood":false}]""")
            .putInt("hour", 20)
            .putInt("minute", 15)
            .putBoolean("enabled", false)
            .commit()
        val cs = Store(TestEnv.ctx).checkins
        assertEquals(1, cs.size)
        with(cs[0]) {
            assertEquals("Evening", name)
            assertEquals(listOf(AlarmTime(20, 15)), times)
            assertFalse(enabled)
            assertEquals(listOf("Old question?"), questions.map { it.text })
        }
    }
}

/**
 * End to end with the real alarm: AlarmManager → AlarmReceiver → ringing AlarmService →
 * full-screen alarm screen over a sleeping screen → answer → ringing stops → answers saved.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndAlarmTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUp() {
        val pkg = TestEnv.ctx.packageName
        TestEnv.shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        TestEnv.shell("appops set $pkg USE_FULL_SCREEN_INTENT allow")
    }

    @After
    fun tearDown() {
        device.wakeUp()
        TestEnv.reset(emptyList())
    }

    /**
     * Finds [selector] fresh and taps it, retrying if the screen redraws in between
     * (the pause countdown updates the screen several times a second).
     */
    private fun tap(selector: BySelector) {
        repeat(5) { attempt ->
            val obj = device.wait(Until.findObject(selector), 5_000)
                ?: throw AssertionError("not on screen: $selector")
            try {
                obj.click()
                return
            } catch (e: StaleObjectException) {
                if (attempt == 4) throw e
            }
        }
    }

    @Test
    fun realAlarmRingsShowsTheCheckinAndStopsWhenAnswered() {
        val q = Question("e2e", "End to end question?")
        TestEnv.reset(listOf(TestEnv.checkin("e2e", "E2E Check-in", listOf(q))))

        device.pressHome()
        device.sleep() // screen off: the alarm should wake it and take over the screen
        SystemClock.sleep(1_000)
        AlarmScheduler.scheduleTest(TestEnv.ctx, "e2e", 3_000)

        assertTrue(
            "alarm screen didn't appear within 30 s",
            device.wait(Until.hasObject(By.text("E2E Check-in")), 30_000) == true
        )
        assertTrue("alarm should be ringing", AlarmService.running)
        assertFalse("alarm shouldn't be paused yet", AlarmService.isPaused)
        assertEquals(listOf("e2e"), TestEnv.store.pending.map { it.checkinId })

        tap(By.text("Start check-in"))
        tap(By.text("No"))
        assertTrue("Start check-in should pause the alarm", AlarmService.isPaused)
        tap(By.text("Submit").enabled(true))

        val deadline = SystemClock.uptimeMillis() + 10_000
        while (AlarmService.running && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(200)
        assertFalse("alarm still ringing after answering", AlarmService.running)
        assertTrue(TestEnv.store.pending.isEmpty())
        assertEquals(mapOf("e2e" to false), TestEnv.store.entry(Days.forAlarmNow(false)))
    }
}
