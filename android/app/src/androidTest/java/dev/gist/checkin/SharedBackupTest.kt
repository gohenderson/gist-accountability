package dev.gist.checkin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Imports the sample backups in the repository's shared/backups folder (added to this test's
 * assets by Gradle). The iOS app's tests import the same files, so a backup made on one
 * phone imports on the other.
 */
@RunWith(AndroidJUnit4::class)
class SharedBackupTest {
    @After
    fun tearDown() = TestEnv.reset(emptyList())

    private fun sample(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).bufferedReader().use { it.readText() }

    @Test
    fun everyFeatureImports() {
        TestEnv.reset(emptyList())
        val contents = Backup.parse(TestEnv.ctx, sample("every-feature.yaml"))
        Backup.import(TestEnv.ctx, contents, contents.checkins.map { Backup.ImportChoice(it.id, Backup.Mode.NEW, true) })
        val store = TestEnv.store
        assertEquals(listOf("wake01", "gym01", "run01"), store.checkins.map { it.id })

        // Times with their own days, a monthly time with a place condition.
        val wake = store.checkin("wake01")!!
        assertEquals(listOf("04:00", "04:45", "09:00"), wake.times.map { it.toString() })
        assertEquals(Repeat(Repeat.WEEKLY, setOf(2)), wake.times[0].days)
        assertEquals(Repeat(Repeat.WEEKLY, setOf(1, 3, 4, 5, 6, 7)), wake.times[1].days)
        assertEquals(Repeat(Repeat.MONTHLY, setOf(1, 15, Repeat.LAST_DAY)), wake.times[2].days)
        assertEquals(Trigger.AT, wake.times[2].where)
        assertEquals("Home", wake.times[2].place!!.name)
        assertEquals(200, wake.times[2].place!!.radiusM)
        // Every kind of step, in order.
        assertEquals(listOf(Steps.MATH, Steps.SCAN, Steps.QUESTIONS), wake.steps.map { it.kind })
        assertEquals(10, wake.steps[0].mathCount)
        assertEquals(4, wake.steps[0].mathLevel)
        assertEquals("GIST-SINK-0001", wake.steps[1].scanCode)
        assertEquals("Bathroom sink", wake.steps[1].scanLabel)
        assertEquals(listOf("soda", "water"), wake.questions.map { it.id })
        assertFalse(wake.questions[0].yesIsGood)
        assertTrue(wake.questions[1].yesIsGood)
        assertEquals("Future you says thanks", wake.background.caption)
        assertEquals("Morning routine", wake.category)
        assertTrue(wake.counted)

        // A soft check-in set off by arriving somewhere, in a time window.
        val gym = store.checkin("gym01")!!
        assertTrue(gym.soft)
        assertEquals(15, gym.snoozeMinutes)
        assertTrue(gym.times.isEmpty())
        val arrive = gym.events.single()
        assertEquals(Trigger.ARRIVE, arrive.kind)
        assertEquals("Gym", arrive.place!!.name)
        assertEquals(Repeat(Repeat.WEEKLY, setOf(1, 3, 5)), arrive.days)
        assertEquals("05:00", arrive.windowStart.toString())
        assertEquals("10:00", arrive.windowEnd.toString())

        // A voice line partway into a run.
        val run = store.checkin("run01")!!
        assertTrue(run.voice)
        assertFalse(run.enabled)
        assertEquals("Is that Forrest Gump?", run.voiceText)
        assertFalse(run.voiceHeadphonesOnly)
        val runFor = run.events.single()
        assertEquals(Trigger.RUN_FOR, runFor.kind)
        assertEquals(60, runFor.minRunMinutes)
        assertFalse(runFor.oncePerDay)

        // History: each status, and a soft result kept out of the stats.
        val answered = store.results("2026-11-08", "wake01")["04:45"]!!
        assertEquals(SlotResult.ANSWERED, answered.status)
        assertEquals(mapOf("soda" to false, "water" to true), answered.answers)
        assertEquals(SlotResult.RANG, store.results("2026-11-09", "wake01")["04:45"]!!.status)
        assertEquals(SlotResult.SKIPPED, store.results("2026-11-10", "wake01")["04:00"]!!.status)
        val dismissed = store.results("2026-11-08", "gym01")["07:12"]!!
        assertEquals(SlotResult.DISMISSED, dismissed.status)
        assertTrue(dismissed.soft)
        assertTrue("soft: no stats", store.allResults("gym01").isEmpty())
        assertNull(store.checkin("missing"))
    }

    @Test
    fun everyFeatureSurvivesARoundTrip() {
        // Import the sample, export it again, and import that: nothing is lost on the way.
        TestEnv.reset(emptyList())
        val first = Backup.parse(TestEnv.ctx, sample("every-feature.yaml"))
        Backup.import(TestEnv.ctx, first, first.checkins.map { Backup.ImportChoice(it.id, Backup.Mode.NEW, true) })
        val before = TestEnv.store.checkins
        val exported = Backup.export(TestEnv.ctx)
        TestEnv.reset(emptyList())
        val again = Backup.parse(TestEnv.ctx, exported)
        Backup.import(TestEnv.ctx, again, again.checkins.map { Backup.ImportChoice(it.id, Backup.Mode.NEW, true) })
        assertEquals(before, TestEnv.store.checkins)
        assertEquals(first.days, again.days)
    }
}
