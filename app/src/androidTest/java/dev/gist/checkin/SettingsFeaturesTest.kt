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

/** Backup files: choosing what to export, and how each check-in is imported. */
@RunWith(AndroidJUnit4::class)
class BackupTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    @After
    fun tearDown() {
        Backgrounds.deleteAll(TestEnv.ctx)
        TestEnv.reset(emptyList())
    }

    private val q = Question("soda", "Soda?")
    private fun all(contents: Backup.Contents, mode: Backup.Mode = Backup.Mode.NEW, history: Boolean = true) =
        contents.checkins.map { Backup.ImportChoice(it.id, mode, history) }

    @Test
    fun roundTripToANewPhoneWithPhotosVideoAndHistory() {
        val slideshow0 = TestEnv.checkin("wake", "Starting the Day", listOf(q), mathCount = 3)
        val slideshow = slideshow0.copy(
            soundUri = Checkin.SILENT,
            repeat = Repeat.weekly(Repeat.WEEKDAYS),
            steps = slideshow0.steps.reversed(),
            background = Background(Background.SLIDESHOW, intervalSec = 5, caption = "Up and at 'em"),
        )
        val withVideo = TestEnv.checkin("vid", "Video").copy(background = Background(Background.VIDEO, caption = "Go"))
        TestEnv.reset(listOf(slideshow, withVideo), entries = mapOf("2026-11-06" to mapOf("soda" to true)))
        Backgrounds.savePhoto(TestEnv.ctx, "wake", 0, solidPhoto(Color.RED))
        Backgrounds.savePhoto(TestEnv.ctx, "wake", 1, solidPhoto(Color.GREEN))
        val videoBytes = testVideoBytes()
        Backgrounds.videoFile(TestEnv.ctx, "vid").writeBytes(videoBytes)

        val backup = Backup.export(TestEnv.ctx)
        assertTrue("YAML with a comment header", backup.startsWith("# Gist backup"))
        assertTrue("block-style YAML", backup.contains("\nformat: gist-backup\n"))

        // A "new phone".
        Backgrounds.deleteAll(TestEnv.ctx)
        TestEnv.reset(emptyList())
        val contents = Backup.parse(TestEnv.ctx, backup)
        Backup.import(TestEnv.ctx, contents, all(contents))

        val s = TestEnv.store
        assertEquals(listOf(slideshow, withVideo), s.checkins)
        assertEquals(mapOf("soda" to true), s.entry("2026-11-06"))
        assertEquals(2, Backgrounds.photos(TestEnv.ctx, "wake").size)
        assertTrue("video restored", Backgrounds.videoFile(TestEnv.ctx, "vid").readBytes().contentEquals(videoBytes))
        assertTrue("alarms are set", AlarmScheduler.upcoming(TestEnv.ctx, s.checkin("wake")!!) != null)
    }

    @Test
    fun exportOnlyTheChosenCheckinsAndHistory() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "A", listOf(q)), TestEnv.checkin("b", "B", listOf(q)), TestEnv.checkin("c", "C")))
        TestEnv.store.saveAnswers("2026-11-05", "a", "12:00", mapOf("soda" to false))
        TestEnv.store.saveAnswers("2026-11-05", "b", "12:00", mapOf("soda" to true))

        val text = Backup.export(TestEnv.ctx, listOf(Backup.ExportChoice("a", true), Backup.ExportChoice("b", false)))
        val contents = Backup.parse(TestEnv.ctx, text)
        assertEquals(listOf("a", "b"), contents.checkins.map { it.id })
        assertEquals(1, contents.historyDays("a"))
        assertEquals(0, contents.historyDays("b"))
    }

    /** This phone has "Wake" with a day of history; the file has a changed "Wake" with another day. */
    private fun conflict(): Backup.Contents {
        TestEnv.reset(listOf(TestEnv.checkin("wake", "Wake", listOf(q)).copy(times = listOf(AlarmTime(6, 0)))))
        TestEnv.store.saveAnswers("2026-11-06", "wake", "06:00", mapOf("soda" to true))
        val fileVersion = TestEnv.store.checkin("wake")!!.let {
            it.copy(times = listOf(AlarmTime(7, 30)), steps = listOf(Steps.math(2)) + it.steps)
        }
        TestEnv.store.checkins = listOf(fileVersion)
        TestEnv.store.deleteHistory("wake")
        TestEnv.store.saveAnswers("2026-11-07", "wake", "07:30", mapOf("soda" to false))
        val text = Backup.export(TestEnv.ctx)
        // Back to this phone's version.
        TestEnv.reset(listOf(TestEnv.checkin("wake", "Wake", listOf(q)).copy(times = listOf(AlarmTime(6, 0)))))
        TestEnv.store.saveAnswers("2026-11-06", "wake", "06:00", mapOf("soda" to true))
        return Backup.parse(TestEnv.ctx, text)
    }

    private fun days(id: String) = TestEnv.store.allResults(id).keys.toList()

    @Test
    fun updateTakesTheSettingsAndMergesHistory() {
        val contents = conflict()
        Backup.import(TestEnv.ctx, contents, all(contents, Backup.Mode.UPDATE, history = true))
        val c = TestEnv.store.checkins.single()
        assertEquals(listOf(AlarmTime(7, 30)), c.times)
        assertEquals(2, c.steps.first().mathCount)
        assertEquals(listOf("2026-11-06", "2026-11-07"), days("wake"))
    }

    @Test
    fun updateWithoutHistoryKeepsYours() {
        val contents = conflict()
        Backup.import(TestEnv.ctx, contents, all(contents, Backup.Mode.UPDATE, history = false))
        assertEquals(listOf(AlarmTime(7, 30)), TestEnv.store.checkins.single().times)
        assertEquals(listOf("2026-11-06"), days("wake"))
    }

    @Test
    fun replaceSwapsHistoryToo() {
        val contents = conflict()
        Backup.import(TestEnv.ctx, contents, all(contents, Backup.Mode.REPLACE, history = true))
        assertEquals(listOf("2026-11-07"), days("wake"))
        val again = conflict()
        Backup.import(TestEnv.ctx, again, all(again, Backup.Mode.REPLACE, history = false))
        assertEquals(emptyList<String>(), days("wake"))
        assertEquals(listOf(AlarmTime(7, 30)), TestEnv.store.checkins.single().times)
    }

    @Test
    fun copyKeepsYoursAndAddsTheFilesSeparately() {
        val contents = conflict()
        Backup.import(TestEnv.ctx, contents, all(contents, Backup.Mode.COPY, history = true))
        val list = TestEnv.store.checkins
        assertEquals(listOf("Wake", "Wake (imported)"), list.map { it.name })
        assertEquals(listOf(AlarmTime(6, 0)), list[0].times)
        assertEquals(listOf("2026-11-06"), days("wake"))
        assertTrue("new id", list[1].id != "wake")
        assertEquals(listOf("2026-11-07"), days(list[1].id))
    }

    @Test
    fun checkinsNotChosenAreLeftAlone() {
        val contents = conflict()
        Backup.import(TestEnv.ctx, contents, emptyList())
        assertEquals(listOf(AlarmTime(6, 0)), TestEnv.store.checkins.single().times)
        assertEquals(listOf("2026-11-06"), days("wake"))
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
                steps:
                  - kind: questions
                    questions:
                      - {id: walked, text: Did you walk?, yesIsGood: true}
                  - {kind: math, mathCount: 5, mathLevel: 4}
                  - {kind: math, mathCount: 5, mathLevel: 4}
        """.trimIndent()
        val contents = Backup.parse(TestEnv.ctx, yaml)
        Backup.import(TestEnv.ctx, contents, all(contents))
        val c = TestEnv.store.checkin("walk")!!
        assertEquals(listOf(AlarmTime(8, 15), AlarmTime(18, 0)), c.times)
        assertEquals(Repeat.weekly(Repeat.WEEKENDS), c.repeat)
        assertEquals("Did you walk?", c.questions.single().text)
        assertEquals(listOf(Steps.QUESTIONS, Steps.MATH, Steps.MATH), c.steps.map { it.kind })
    }

    @Test
    fun badFilesAreRejectedWithoutChangingAnything() {
        val keep = TestEnv.checkin("keep", "Keep me")
        TestEnv.reset(listOf(keep))
        for (bad in listOf("not a backup at all", "format: something-else", "format: gist-backup\nversion: 2",
            "format: gist-backup\nversion: 99\ncheckins: []", "format: gist-backup\nversion: 3\ncheckins: [{name: no id}]",
            "{unclosed: [")) {
            try {
                Backup.parse(TestEnv.ctx, bad)
                fail("should reject: $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue("message should explain: ${e.message}", !e.message.isNullOrBlank())
            }
        }
        assertEquals(listOf(keep), TestEnv.store.checkins)
    }

    private fun settings() = ActivityScenario.launch<SettingsActivity>(Intent(TestEnv.ctx, SettingsActivity::class.java))

    @Test
    fun exportDialogChoosesCheckinsAndHistory() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha", listOf(q)), TestEnv.checkin("b", "Beta", listOf(q))))
        TestEnv.store.saveAnswers("2026-11-05", "a", "12:00", mapOf("soda" to false))
        var chosen: List<Backup.ExportChoice>? = null
        settings().use { s ->
            s.onActivity { it.showExportChoices { c -> chosen = c } }
            onView(tagged("export:b")).inRoot(isDialog()).perform(click())          // leave Beta out
            onView(tagged("export-history:a")).inRoot(isDialog()).perform(click())  // Alpha without history
            onView(withText("Export…")).inRoot(isDialog()).perform(click())
        }
        assertEquals(listOf(Backup.ExportChoice("a", false)), chosen)
    }

    @Test
    fun importDialogAsksPerCheckin() {
        val contents = conflict()
        settings().use { s ->
            s.onActivity { it.showImportChoices(contents) }
            onView(withText("You already have “Wake”:")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(tagged("import-mode:wake:replace")).inRoot(isDialog()).perform(click())
            onView(tagged("import-history:wake")).inRoot(isDialog()).perform(click()) // don't bring history
            onView(withText("Import")).inRoot(isDialog()).perform(click())
        }
        assertEquals(listOf(AlarmTime(7, 30)), TestEnv.store.checkins.single().times)
        assertEquals(emptyList<String>(), days("wake"))
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
        TestEnv.eventually { assertEquals(Checkin.SILENT, AlarmService.currentSound) }
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
        alarmScreen().use { scenario ->
            onView(tagged("ringing-video")).check(matches(isDisplayed()))
            TestEnv.eventually(15_000) {
                var playing = false
                scenario.onActivity { playing = it.videoIsPlaying }
                assertTrue("video should be playing", playing)
            }
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
