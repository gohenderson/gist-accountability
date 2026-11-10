package dev.gist.checkin

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
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Voice check-ins: they speak (or play a clip) instead of ringing, and stay out of the stats. */
@RunWith(AndroidJUnit4::class)
class VoiceTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    @Before
    fun setUp() {
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.POST_NOTIFICATIONS")
        VoiceService.testHeadphones = true // emulators have none
        VoiceService.lastPlayed = null
        VoiceService.lastSkipped = null
    }

    @After
    fun tearDown() {
        VoiceService.testHeadphones = null
        Backgrounds.deleteAll(TestEnv.ctx)
        TestEnv.reset(emptyList())
    }

    private fun voice(id: String = "v", text: String = "Is that Forrest Gump?") =
        TestEnv.checkin(id, "Gump", emptyList()).copy(commitment = Checkin.VOICE, voiceText = text)

    /** A real (not practice) firing of [c]'s alarm in a second. */
    private fun fire(c: Checkin) {
        TestEnv.reset(listOf(c))
        AlarmScheduler.scheduleTest(TestEnv.ctx, c.id, 1_000, practice = false)
    }

    @Test
    fun itSpeaksInsteadOfRinging() {
        val before = VoiceService.plays
        fire(voice())
        TestEnv.eventually(20_000) { assertEquals("Is that Forrest Gump?", VoiceService.lastPlayed) }
        assertEquals(before + 1, VoiceService.plays)
        assertFalse("no alarm", AlarmService.running)
        assertTrue("nothing waiting", TestEnv.store.pending.isEmpty())
        val r = TestEnv.onlyResult("v")
        assertEquals(SlotResult.DONE, r.status)
        assertTrue("not counted", r.soft)
        assertTrue(TestEnv.store.allResults("v").isEmpty())
    }

    @Test
    fun itStaysQuietWithoutHeadphones() {
        VoiceService.testHeadphones = false
        val before = VoiceService.plays
        fire(voice())
        TestEnv.eventually(20_000) { assertEquals("v", VoiceService.lastSkipped) }
        assertEquals(before, VoiceService.plays)
    }

    @Test
    fun withHeadphonesOnlyOffItUsesTheSpeaker() {
        VoiceService.testHeadphones = false
        fire(voice().copy(voiceHeadphonesOnly = false))
        TestEnv.eventually(20_000) { assertEquals("Is that Forrest Gump?", VoiceService.lastPlayed) }
    }

    @Test
    fun aRecordedClipPlays() {
        TestEnv.reset(listOf(voice().copy(voiceSource = Checkin.VOICE_CLIP)))
        Backgrounds.voiceFile(TestEnv.ctx, "v").writeBytes(beep())
        assertEquals(1, Backgrounds.voiceSeconds(TestEnv.ctx, "v"))
        AlarmScheduler.scheduleTest(TestEnv.ctx, "v", 1_000, practice = false)
        TestEnv.eventually(20_000) { assertEquals(VoiceService.CLIP, VoiceService.lastPlayed) }
    }

    @Test
    fun milestonesFireOncePerRun() {
        val c = voice().copy(times = emptyList(), events = listOf(Trigger(Trigger.RUN_FOR, minRunMinutes = 30, oncePerDay = false)))
        TestEnv.reset(listOf(c))
        val fired = mutableSetOf<String>()
        val before = VoiceService.plays
        MovementWatch.onRunProgress(TestEnv.ctx, 29, fired)
        Thread.sleep(3_000)
        assertEquals("not yet", before, VoiceService.plays)
        MovementWatch.onRunProgress(TestEnv.ctx, 30, fired)
        TestEnv.eventually(20_000) { assertEquals(before + 1, VoiceService.plays) }
        MovementWatch.onRunProgress(TestEnv.ctx, 31, fired)
        Thread.sleep(3_000)
        assertEquals("once per run", before + 1, VoiceService.plays)
        // The next run (the service clears the set when a run starts) hears it again.
        fired.clear()
        MovementWatch.onRunProgress(TestEnv.ctx, 30, fired)
        TestEnv.eventually(20_000) { assertEquals(before + 2, VoiceService.plays) }
    }

    @Test
    fun theClipGoesIntoBackups() {
        TestEnv.reset(listOf(voice().copy(voiceSource = Checkin.VOICE_CLIP)))
        val clip = beep()
        Backgrounds.voiceFile(TestEnv.ctx, "v").writeBytes(clip)
        val text = Backup.export(TestEnv.ctx)
        TestEnv.reset(emptyList())
        Backgrounds.deleteAll(TestEnv.ctx)
        val contents = Backup.parse(TestEnv.ctx, text)
        Backup.import(TestEnv.ctx, contents, contents.checkins.map { Backup.ImportChoice(it.id, Backup.Mode.NEW, true) })
        val c = TestEnv.store.checkins.single()
        assertTrue(c.voice)
        assertEquals(Checkin.VOICE_CLIP, c.voiceSource)
        assertEquals("Is that Forrest Gump?", c.voiceText)
        assertTrue(clip.contentEquals(Backgrounds.voiceFile(TestEnv.ctx, c.id).readBytes()))
    }

    @Test
    fun setUpOnTheCheckinPage() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Run cheer")))
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.ACTIVITY_RECOGNITION")
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, "c")).use {
            onView(tagged("commitment")).perform(scrollTo(), click())
            onView(tagged("commitment:voice")).inRoot(isDialog()).perform(click())
            onView(tagged("commitment")).perform(scrollTo()).check(matches(withText("Commitment: Voice")))
            onView(tagged("add-step")).check(doesNotExist()) // no steps: it just talks
            onView(tagged("background-kind")).check(doesNotExist()) // and doesn't ring
            onView(tagged("voice-text")).perform(scrollTo(), replaceText("Ahhh, he's training for Boston"), closeSoftKeyboard())
            onView(tagged("test-voice")).perform(scrollTo()).check(matches(isDisplayed()))
            // A "partway into a run" trigger, 2 hours in.
            onView(tagged("trigger-when:0")).perform(scrollTo(), click())
            onView(tagged("kind:run-for")).inRoot(isDialog()).perform(click())
            TestEnv.eventually(5_000) { assertEquals(Trigger.RUN_FOR, TestEnv.store.checkin("c")!!.events.single().kind) }
        }
        val c = TestEnv.store.checkin("c")!!
        assertTrue(c.voice)
        assertEquals("Ahhh, he's training for Boston", c.voiceText)
        assertEquals("starts at 30 minutes", 30, c.events.single().minRunMinutes)
        assertTrue(c.times.isEmpty())
    }

    @Test
    fun theRunTimeIsPicked() {
        val c = voice().copy(times = emptyList(), events = listOf(Trigger(Trigger.RUN_FOR, minRunMinutes = 30)))
        TestEnv.reset(listOf(c))
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.ACTIVITY_RECOGNITION")
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, "v")).use {
            onView(tagged("trigger-run-for:0")).perform(scrollTo()).check(matches(withText("After 30 minutes")))
            onView(tagged("trigger-run-for:0")).perform(click())
            onView(tagged("run-for:120")).inRoot(isDialog()).perform(scrollTo(), click())
            onView(tagged("trigger-run-for:0")).perform(scrollTo()).check(matches(withText("After 2 hours")))
            onView(tagged("trigger-summary")).check(matches(withText(org.hamcrest.Matchers.containsString("2 hours into a run"))))
        }
        assertEquals(120, TestEnv.store.checkin("v")!!.events.single().minRunMinutes)
    }

    @Test
    fun theStartPageSaysWhatItSays() {
        TestEnv.allowOverlay(true)
        TestEnv.reset(listOf(voice()))
        ActivityScenario.launch<MainActivity>(android.content.Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(tagged("voice-note:v")).perform(scrollTo())
                .check(matches(withText("Voice · says \"Is that Forrest Gump?\" · not counted in stats")))
            onView(withText("Do it now")).check(doesNotExist())
        }
        TestEnv.allowOverlay(false)
    }

    @Test
    fun theLibraryHasARunCheer() {
        val t = Library.TEMPLATES.first { it.key == "run-cheer" }
        assertTrue(t.checkin.voice)
        assertNotNull(t.checkin.events.singleOrNull { it.kind == Trigger.RUN_FOR && it.minRunMinutes == 30 })
    }

    /** One second of a 440 Hz tone as a WAV file. */
    private fun beep(): ByteArray {
        val rate = 16_000
        val pcm = ByteBuffer.allocate(rate * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until rate) pcm.putShort((sin(2 * PI * 440 * i / rate) * 8000).toInt().toShort())
        val data = pcm.array()
        val out = ByteArrayOutputStream()
        fun s(x: String) = out.write(x.toByteArray(Charsets.US_ASCII))
        fun i32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun i16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        s("RIFF"); i32(36 + data.size); s("WAVE")
        s("fmt "); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
        s("data"); i32(data.size); out.write(data)
        return out.toByteArray()
    }
}
