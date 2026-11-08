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
import org.junit.Assert.assertNotNull
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
            repeat = Repeat.monthly(setOf(1, Repeat.LAST_DAY)),
        )
        TestEnv.store.checkins = listOf(c)
        assertEquals(listOf(c), Store(TestEnv.ctx).checkins)
    }

    @Test
    fun repeatRulesRoundTrip() {
        for (r in listOf(Repeat.EVERY_DAY, Repeat.weekly(setOf(1, 3, 5)), Repeat.monthly(setOf(1, Repeat.LAST_DAY)))) {
            assertEquals(r, Repeat.fromJson(r.toJson()))
        }
        assertEquals(Repeat.EVERY_DAY, Repeat.fromJson(null))
    }

    @Test
    fun missingScanCodeStaysNull() {
        TestEnv.reset(listOf(TestEnv.checkin("x")))
        assertNull(Store(TestEnv.ctx).checkin("x")!!.scanCode)
    }

    @Test
    fun eachTimeKeepsItsOwnResult() {
        TestEnv.reset(emptyList())
        val s = TestEnv.store
        s.saveAnswers("2026-11-07", "eve", "07:00", mapOf("soda" to false))
        s.saveAnswers("2026-11-07", "eve", "21:30", mapOf("soda" to true))
        s.saveAnswers("2026-11-07", "other", "07:00", mapOf("latefood" to true)) // another check-in, same time
        assertEquals(mapOf("soda" to false), s.result("2026-11-07", "eve", "07:00")!!.answers)
        assertEquals(mapOf("soda" to true), s.result("2026-11-07", "eve", "21:30")!!.answers)
        assertEquals(mapOf("latefood" to true), s.result("2026-11-07", "other", "07:00")!!.answers)
        s.saveAnswers("2026-11-07", "eve", "21:30", mapOf("soda" to false)) // fixing overwrites only that time
        assertEquals(mapOf("soda" to false), s.result("2026-11-07", "eve", "21:30")!!.answers)
    }

    @Test
    fun ringingDoesntOverwriteAFinishedOrSkippedTime() {
        TestEnv.reset(emptyList())
        val s = TestEnv.store
        s.markSkipped("2026-11-07", "t", "22:15")
        s.markRang("2026-11-07", "t", "22:15")
        assertEquals(SlotResult.SKIPPED, s.result("2026-11-07", "t", "22:15")!!.status)
        assertTrue(s.isClosed("2026-11-07", "t", "22:15"))

        s.markRang("2026-11-07", "t", "07:30")
        assertEquals(SlotResult.RANG, s.result("2026-11-07", "t", "07:30")!!.status)
        assertFalse("rang but unfinished isn't closed", s.isClosed("2026-11-07", "t", "07:30"))

        s.clearResult("2026-11-07", "t", "22:15")
        assertNull(s.result("2026-11-07", "t", "22:15"))
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

/** After an update, a switched-off full-screen permission is reported right away. */
@RunWith(AndroidJUnit4::class)
class PermissionAfterUpdateTest {
    private val pkg get() = TestEnv.ctx.packageName
    private val nm get() = TestEnv.ctx.getSystemService(android.app.NotificationManager::class.java)

    @After
    fun tearDown() {
        TestEnv.shell("appops set $pkg USE_FULL_SCREEN_INTENT allow")
        TestEnv.reset(emptyList())
    }

    private fun appUpdated() = BootReceiver().onReceive(
        TestEnv.ctx, android.content.Intent(android.content.Intent.ACTION_MY_PACKAGE_REPLACED)
    )

    @Test
    fun aLostFullScreenPermissionPostsAFixItNotification() {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        TestEnv.reset(emptyList())
        TestEnv.shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        TestEnv.shell("appops set $pkg USE_FULL_SCREEN_INTENT deny")
        TestEnv.eventually { assertFalse(nm.canUseFullScreenIntent()) }
        appUpdated()
        TestEnv.eventually { assertTrue(nm.activeNotifications.any { it.id == Notif.ID_PERMISSION }) }
    }

    @Test
    fun noNotificationWhenThePermissionIsStillOn() {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        TestEnv.reset(emptyList())
        TestEnv.shell("appops set $pkg USE_FULL_SCREEN_INTENT allow")
        appUpdated()
        assertFalse(nm.activeNotifications.any { it.id == Notif.ID_PERMISSION })
    }

    @Test
    fun broadcastsFromOtherAppsAreIgnored() {
        TestEnv.reset(emptyList())
        TestEnv.shell("appops set $pkg USE_FULL_SCREEN_INTENT deny")
        BootReceiver().onReceive(TestEnv.ctx, android.content.Intent("com.example.SPOOFED"))
        assertFalse(nm.activeNotifications.any { it.id == Notif.ID_PERMISSION })
    }
}

/** Only picker documents from other apps are opened. */
@RunWith(AndroidJUnit4::class)
class SafeUriTest {
    @Test
    fun acceptsPickerDocumentsAndRejectsEverythingElse() {
        val ctx = TestEnv.ctx
        val ok = listOf(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fgist-backup.yaml",
            "content://com.android.providers.downloads.documents/document/msf%3A42",
            "content://com.android.providers.media.documents/document/image%3A7",
        )
        for (u in ok) assertNotNull(u, SafeUri.picked(ctx, android.net.Uri.parse(u)))
        val bad = listOf(
            "file:///data/data/${ctx.packageName}/shared_prefs/gist.xml",
            "content://${ctx.packageName}.provider/anything",
            "content://evil.provider/data/data/${ctx.packageName}/files/x",
            "content://evil.provider/document/../../data/data/${ctx.packageName}/files/x",
            "content://evil.provider",
        )
        for (u in bad) assertNull(u, SafeUri.picked(ctx, android.net.Uri.parse(u)))
    }
}
