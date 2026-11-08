package dev.gist.checkin

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.RingtoneManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private fun solidPhoto(color: Int): Bitmap =
    Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

/** Backup file: a full round trip, and bad files change nothing. */
@RunWith(AndroidJUnit4::class)
class BackupTest {
    @After
    fun tearDown() {
        Backgrounds.deleteAll(TestEnv.ctx)
        TestEnv.reset(emptyList())
    }

    @Test
    fun exportThenImportRestoresCheckinsAnswersAndPhotos() {
        val q = Question("soda", "Soda?")
        val withPhoto = TestEnv.checkin("wake", "Starting the Day", listOf(q), mathCount = 3)
            .copy(hasBackground = true, soundUri = Checkin.SILENT)
        val plain = TestEnv.checkin("eve", "Evening")
        TestEnv.reset(listOf(withPhoto, plain), entries = mapOf("2026-11-06" to mapOf("soda" to true)))
        Backgrounds.save(TestEnv.ctx, "wake", solidPhoto(Color.RED))

        val backup = Backup.export(TestEnv.ctx)

        // A "new phone": different data, no photos.
        Backgrounds.deleteAll(TestEnv.ctx)
        TestEnv.reset(listOf(TestEnv.checkin("other", "Something else")), listOf(Pending("other", "2026-11-07")))

        val contents = Backup.parse(TestEnv.ctx, backup)
        assertEquals(2, contents.checkins.size)
        assertEquals(1, contents.days)
        Backup.restore(TestEnv.ctx, contents)

        val s = TestEnv.store
        assertEquals(listOf(withPhoto, plain), s.checkins)
        assertEquals(mapOf("soda" to true), s.entry("2026-11-06"))
        assertTrue("waiting list is cleared on restore", s.pending.isEmpty())
        val photo = BitmapFactory.decodeFile(Backgrounds.file(TestEnv.ctx, "wake").path)
        assertNotNull("photo should be restored", photo)
        assertEquals(64, photo.width)
    }

    @Test
    fun badFilesAreRejectedWithoutChangingAnything() {
        val keep = TestEnv.checkin("keep", "Keep me")
        TestEnv.reset(listOf(keep))
        for (bad in listOf("not json at all", """{"format":"something-else"}""", """{"format":"gist-backup","version":1}""",
            """{"format":"gist-backup","version":99,"checkins":[]}""")) {
            try {
                Backup.parse(TestEnv.ctx, bad)
                fail("should reject: $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue("message should explain: ${e.message}", !e.message.isNullOrBlank())
            }
        }
        assertEquals(listOf(keep), TestEnv.store.checkins)
    }
}

/** Light / Dark / System applies to screens. */
@RunWith(AndroidJUnit4::class)
class ThemeTest {
    @After
    fun tearDown() = TestEnv.reset(emptyList()) // also resets the theme to System

    private fun nightModeOf(): Int {
        var mode = -1
        ActivityScenario.launch<SettingsActivity>(Intent(TestEnv.ctx, SettingsActivity::class.java)).use { s ->
            s.onActivity { mode = it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK }
        }
        return mode
    }

    @Test
    fun darkAndLightOverrideThePhoneSetting() {
        TestEnv.reset(emptyList())
        TestEnv.store.theme = Store.THEME_DARK
        assertEquals(Configuration.UI_MODE_NIGHT_YES, nightModeOf())
        TestEnv.store.theme = Store.THEME_LIGHT
        assertEquals(Configuration.UI_MODE_NIGHT_NO, nightModeOf())
    }

    @Test
    fun choosingDarkInSettingsSavesIt() {
        TestEnv.reset(emptyList())
        ActivityScenario.launch<SettingsActivity>(Intent(TestEnv.ctx, SettingsActivity::class.java)).use {
            onView(withText("Dark")).perform(click())
        }
        assertEquals(Store.THEME_DARK, TestEnv.store.theme)
    }
}

/** Per-check-in sound and background photo, with a real ringing alarm. */
@RunWith(AndroidJUnit4::class)
class SoundAndBackgroundTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    @After
    fun tearDown() {
        Backgrounds.deleteAll(TestEnv.ctx)
        TestEnv.reset(emptyList())
    }

    private fun ring(c: Checkin) {
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.POST_NOTIFICATIONS")
        TestEnv.reset(listOf(c))
        if (c.hasBackground) Backgrounds.save(TestEnv.ctx, c.id, solidPhoto(Color.BLUE))
        AlarmScheduler.scheduleTest(TestEnv.ctx, c.id, 1_000)
        TestEnv.eventually(20_000) { assertTrue("alarm didn't start", AlarmService.running) }
    }

    @Test
    fun checkinsOwnSoundIsPlayed() {
        val custom = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()
        ring(TestEnv.checkin("s", "Sound test").copy(soundUri = custom, soundTitle = "Test sound"))
        TestEnv.eventually { assertEquals(custom, AlarmService.currentSound) }
    }

    @Test
    fun silentMeansNoSound() {
        ring(TestEnv.checkin("s", "Silent test").copy(soundUri = Checkin.SILENT))
        assertEquals(Checkin.SILENT, AlarmService.currentSound)
        assertTrue("still an active alarm (vibrating)", AlarmService.running)
    }

    @Test
    fun missingSoundFallsBackToADefault() {
        ring(TestEnv.checkin("s", "Fallback test").copy(soundUri = "content://media/internal/audio/media/999999"))
        TestEnv.eventually {
            val playing = AlarmService.currentSound
            assertNotNull("something should play", playing)
            assertFalse(playing!!.endsWith("/999999"))
        }
    }

    @Test
    fun backgroundPhotoShowsBehindTheRingingScreen() {
        ring(TestEnv.checkin("b", "Photo test").copy(hasBackground = true))
        ActivityScenario.launch<CheckinActivity>(Intent(TestEnv.ctx, CheckinActivity::class.java)).use {
            onView(withText("Start check-in")).check(matches(isDisplayed()))
            onView(tagged("ringing-background")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun backgroundPhotoCanBeRemovedOnTheEditPage() {
        TestEnv.reset(listOf(TestEnv.checkin("b", "Photo test").copy(hasBackground = true)))
        Backgrounds.save(TestEnv.ctx, "b", solidPhoto(Color.GREEN))
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, "b")).use {
            onView(tagged("background-preview")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Background photo: change or remove")).perform(scrollTo(), click())
            onView(withText("Remove")).inRoot(isDialog()).perform(click())
            onView(withText("Background photo: none")).perform(scrollTo()).check(matches(isDisplayed()))
        }
        assertFalse(TestEnv.store.checkin("b")!!.hasBackground)
        assertFalse(Backgrounds.file(TestEnv.ctx, "b").exists())
    }
}
