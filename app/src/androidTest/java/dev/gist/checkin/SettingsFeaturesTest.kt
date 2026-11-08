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
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
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
    fun exportThenImportRestoresCheckinsAnswersPhotosAndVideo() {
        val q = Question("soda", "Soda?")
        val slideshow = TestEnv.checkin("wake", "Starting the Day", listOf(q), mathCount = 3).copy(
            soundUri = Checkin.SILENT,
            repeat = Repeat.weekly(Repeat.WEEKDAYS),
            background = Background(Background.SLIDESHOW, intervalSec = 5, caption = "Up and at 'em"),
        )
        val withVideo = TestEnv.checkin("vid", "Video").copy(background = Background(Background.VIDEO, caption = "Go"))
        val plain = TestEnv.checkin("eve", "Evening").copy(background = Background(caption = "Just a caption"))
        TestEnv.reset(listOf(slideshow, withVideo, plain), entries = mapOf("2026-11-06" to mapOf("soda" to true)))
        Backgrounds.savePhoto(TestEnv.ctx, "wake", 0, solidPhoto(Color.RED))
        Backgrounds.savePhoto(TestEnv.ctx, "wake", 1, solidPhoto(Color.GREEN))
        val videoBytes = testVideoBytes()
        Backgrounds.videoFile(TestEnv.ctx, "vid").writeBytes(videoBytes)

        val backup = Backup.export(TestEnv.ctx)
        assertTrue("backup should be YAML with a comment header", backup.startsWith("# Gist backup"))
        assertTrue("block-style YAML", backup.contains("\nformat: gist-backup\n"))
        assertTrue("caption is readable in the file", backup.contains("Up and at 'em") || backup.contains("Up and at ''em"))

        // A "new phone": different data, no media.
        Backgrounds.deleteAll(TestEnv.ctx)
        TestEnv.reset(listOf(TestEnv.checkin("other", "Something else")), listOf(Pending("other", "2026-11-07")))

        val contents = Backup.parse(TestEnv.ctx, backup)
        assertEquals(3, contents.checkins.size)
        assertEquals(1, contents.days)
        Backup.restore(TestEnv.ctx, contents)

        val s = TestEnv.store
        assertEquals(listOf(slideshow, withVideo, plain), s.checkins)
        assertEquals(mapOf("soda" to true), s.entry("2026-11-06"))
        assertTrue("waiting list is cleared on restore", s.pending.isEmpty())
        val photos = Backgrounds.photos(TestEnv.ctx, "wake")
        assertEquals(2, photos.size)
        assertEquals(64, BitmapFactory.decodeFile(photos[0].path).width)
        assertTrue("video restored", Backgrounds.videoFile(TestEnv.ctx, "vid").readBytes().contentEquals(videoBytes))
    }

    @Test
    fun oldJsonBackupsStillImport() {
        TestEnv.reset(emptyList())
        val photo = java.io.ByteArrayOutputStream().also { solidPhoto(Color.BLUE).compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        val json = """
            {"format": "gist-backup", "version": 2, "exportedAt": "2026-11-01T00:00:00Z",
             "checkins": [{"id": "old", "name": "Old one", "times": ["07:00"], "hasBackground": true, "questions": []}],
             "entries": {},
             "backgrounds": {"old": "${android.util.Base64.encodeToString(photo, android.util.Base64.NO_WRAP)}"}}
        """.trimIndent()
        Backup.restore(TestEnv.ctx, Backup.parse(TestEnv.ctx, json))
        val c = TestEnv.store.checkin("old")!!
        assertEquals(Background.PHOTO, c.background.kind)
        assertEquals(listOf(AlarmTime(7, 0)), c.times)
        assertEquals(1, Backgrounds.photos(TestEnv.ctx, "old").size)
    }

    @Test
    fun handEditedYamlImports() {
        TestEnv.reset(emptyList())
        val yaml = """
            # edited by hand
            format: gist-backup
            version: 3
            checkins:
              - id: walk
                name: Walk
                times: ["08:15", "18:00"]
                repeat: {kind: weekly, days: [6, 7]}
                enabled: True
                questions:
                  - {id: walked, text: Did you walk?, yesIsGood: true}
        """.trimIndent()
        Backup.restore(TestEnv.ctx, Backup.parse(TestEnv.ctx, yaml))
        val c = TestEnv.store.checkin("walk")!!
        assertEquals(listOf(AlarmTime(8, 15), AlarmTime(18, 0)), c.times)
        assertEquals(Repeat.weekly(Repeat.WEEKENDS), c.repeat)
        assertEquals("Did you walk?", c.questions.single().text)
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

/** The small MP4 in androidTest/assets. */
fun testVideoBytes(): ByteArray =
    TestEnv.instrumentation.context.assets.open("test-video.mp4").use { it.readBytes() }

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

    private fun ring(c: Checkin, setup: () -> Unit = {}) {
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.POST_NOTIFICATIONS")
        TestEnv.reset(listOf(c))
        setup()
        AlarmScheduler.scheduleTest(TestEnv.ctx, c.id, 1_000)
        TestEnv.eventually(20_000) { assertTrue("alarm didn't start", AlarmService.running) }
    }

    private fun alarmScreen() = ActivityScenario.launch<CheckinActivity>(Intent(TestEnv.ctx, CheckinActivity::class.java))

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
    fun backgroundPhotoAndCaptionShowBehindTheRingingScreen() {
        ring(TestEnv.checkin("b", "Photo test").copy(background = Background(Background.PHOTO, caption = "You've got this"))) {
            Backgrounds.savePhoto(TestEnv.ctx, "b", 0, solidPhoto(Color.BLUE))
        }
        alarmScreen().use {
            onView(withText("Start check-in")).check(matches(isDisplayed()))
            onView(tagged("ringing-background")).check(matches(isDisplayed()))
            onView(tagged("ringing-caption")).check(matches(withText("You've got this")))
        }
    }

    @Test
    fun captionAloneShowsWithoutAPicture() {
        ring(TestEnv.checkin("b", "Caption test").copy(background = Background(caption = "Hydrate!")))
        alarmScreen().use {
            onView(tagged("ringing-caption")).check(matches(withText("Hydrate!")))
            onView(tagged("ringing-background")).check(doesNotExist())
        }
    }

    @Test
    fun slideshowCyclesThroughThePhotos() {
        ring(TestEnv.checkin("b", "Slides").copy(background = Background(Background.SLIDESHOW, intervalSec = 1))) {
            Backgrounds.savePhoto(TestEnv.ctx, "b", 0, solidPhoto(Color.RED))
            Backgrounds.savePhoto(TestEnv.ctx, "b", 1, solidPhoto(Color.GREEN))
        }
        alarmScreen().use {
            // Every second it moves on: we should see both photos, then the first again.
            for (n in listOf(2, 1, 2)) {
                TestEnv.eventually(6_000) {
                    onView(tagged("ringing-background")).check(matches(withContentDescription("Background photo $n of 2")))
                }
            }
        }
    }

    @Test
    fun videoPlaysBehindTheRingingScreen() {
        ring(TestEnv.checkin("v", "Video").copy(background = Background(Background.VIDEO))) {
            Backgrounds.videoFile(TestEnv.ctx, "v").writeBytes(testVideoBytes())
        }
        alarmScreen().use {
            onView(tagged("ringing-video")).check(matches(isDisplayed()))
            TestEnv.eventually(10_000) { assertTrue("video should be playing", CheckinActivity.videoPlaying) }
            // The alarm sound is still the one playing (the video is muted).
            assertTrue(AlarmService.running)
        }
    }

    @Test
    fun backgroundCanBeSetToNoneOnTheEditPage() {
        TestEnv.reset(listOf(TestEnv.checkin("b", "Photo test").copy(background = Background(Background.PHOTO, caption = "Keep me"))))
        Backgrounds.savePhoto(TestEnv.ctx, "b", 0, solidPhoto(Color.GREEN))
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, "b")).use {
            onView(tagged("background-preview")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Background: photo")).perform(scrollTo(), click())
            onView(withText("None")).inRoot(isDialog()).perform(click())
            onView(withText("Background: none")).perform(scrollTo()).check(matches(isDisplayed()))
        }
        val bg = TestEnv.store.checkin("b")!!.background
        assertEquals(Background.NONE, bg.kind)
        assertEquals("caption is kept", "Keep me", bg.caption)
        assertTrue(Backgrounds.photos(TestEnv.ctx, "b").isEmpty())
    }

    @Test
    fun slideshowIntervalAndCaptionAreSavedFromTheEditPage() {
        TestEnv.reset(listOf(TestEnv.checkin("b", "Slides").copy(background = Background(Background.SLIDESHOW))))
        Backgrounds.savePhoto(TestEnv.ctx, "b", 0, solidPhoto(Color.RED))
        Backgrounds.savePhoto(TestEnv.ctx, "b", 1, solidPhoto(Color.GREEN))
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, "b")).use {
            onView(withText("Background: slideshow · 2 photos · every 10 s")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("slideshow-interval")).perform(scrollTo(), click())
            onView(withText("30 seconds")).inRoot(isDialog()).perform(click())
            onView(tagged("background-caption")).perform(scrollTo(), replaceText("Rise and shine"), closeSoftKeyboard())
        }
        val bg = TestEnv.store.checkin("b")!!.background
        assertEquals(30, bg.intervalSec)
        assertEquals("Rise and shine", bg.caption)
    }
}
