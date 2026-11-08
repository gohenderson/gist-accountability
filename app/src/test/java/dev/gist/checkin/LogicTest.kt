package dev.gist.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** Evaluates MathGen's problem text ("a × b + c × d") independently of MathGen. */
private fun evaluate(text: String): Long =
    text.removeSuffix(" = ?").split(" + ").sumOf { term ->
        term.split(" × ").map { it.trim().toLong() }.reduce { a, b -> a * b }
    }

class MathGenTest {
    @Test
    fun everyLevelProducesProblemsWhoseAnswerIsCorrect() {
        val rnd = Random(42)
        for (level in 1..4) {
            repeat(2000) {
                val p = MathGen.make(level, rnd)
                assertEquals("level $level: ${p.text}", evaluate(p.text), p.answer)
                assertTrue("level $level answer should be positive: ${p.text}", p.answer > 0)
            }
        }
    }

    @Test
    fun harderLevelsUseMoreOperations() {
        val rnd = Random(1)
        assertFalse(MathGen.make(1, rnd).text.contains("×"))
        assertTrue(MathGen.make(2, rnd).text.contains("×"))
        assertEquals(2, MathGen.make(4, rnd).text.count { it == '×' })
    }

    @Test
    fun levelNameClampsOutOfRangeLevels() {
        assertEquals("Easy", MathGen.levelName(0))
        assertEquals("Hard", MathGen.levelName(3))
        assertEquals("Very hard", MathGen.levelName(99))
    }
}

class DaysTest {
    @Test
    fun alarmBefore4amCountsForThePreviousDay() {
        assertEquals("2026-11-07", Days.forAlarmNow(false, LocalDateTime.of(2026, 11, 8, 3, 59)))
        assertEquals("2026-11-08", Days.forAlarmNow(false, LocalDateTime.of(2026, 11, 8, 4, 0)))
        assertEquals("2026-11-08", Days.forAlarmNow(false, LocalDateTime.of(2026, 11, 8, 21, 30)))
    }

    @Test
    fun aboutYesterdayShiftsBackOneMoreDay() {
        assertEquals("2026-11-07", Days.forAlarmNow(true, LocalDateTime.of(2026, 11, 8, 7, 0)))
        assertEquals("2026-11-06", Days.forAlarmNow(true, LocalDateTime.of(2026, 11, 8, 1, 0)))
    }

    @Test
    fun aboutYesterdayCrossesMonthAndYearBoundaries() {
        assertEquals("2026-02-28", Days.forAlarmNow(true, LocalDateTime.of(2026, 3, 1, 7, 0)))
        assertEquals("2025-12-31", Days.forAlarmNow(true, LocalDateTime.of(2026, 1, 1, 7, 0)))
    }

    @Test
    fun lastNIsMostRecentFirst() {
        assertEquals(
            listOf("2026-03-01", "2026-02-28", "2026-02-27"),
            Days.lastN(3, LocalDate.of(2026, 3, 1))
        )
    }
}

class NextTriggerTest {
    private val ny = ZoneId.of("America/New_York")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, s, 0, ny)
    private fun millis(z: ZonedDateTime) = z.toInstant().toEpochMilli()

    @Test
    fun ringsLaterTodayIfTheTimeHasNotPassed() {
        assertEquals(millis(at(2026, 11, 7, 7, 0)), AlarmScheduler.nextTriggerMillis(7, 0, at(2026, 11, 7, 6, 59)))
    }

    @Test
    fun ringsTomorrowIfTheTimeIsNowOrPassed() {
        assertEquals(millis(at(2026, 11, 8, 7, 0)), AlarmScheduler.nextTriggerMillis(7, 0, at(2026, 11, 7, 7, 0)))
        assertEquals(millis(at(2026, 11, 8, 7, 0)), AlarmScheduler.nextTriggerMillis(7, 0, at(2026, 11, 7, 22, 0)))
    }

    @Test
    fun keepsLocalTimeAcrossDaylightSavingChanges() {
        // US clocks spring forward on 2026-03-08 and fall back on 2026-11-01.
        assertEquals(millis(at(2026, 3, 8, 7, 0)), AlarmScheduler.nextTriggerMillis(7, 0, at(2026, 3, 7, 23, 0)))
        assertEquals(millis(at(2026, 11, 1, 7, 0)), AlarmScheduler.nextTriggerMillis(7, 0, at(2026, 10, 31, 23, 0)))
    }

    @Test
    fun aTimeInsideTheSkippedHourStillRingsSoonAfter() {
        val now = at(2026, 3, 8, 0, 0)
        val next = AlarmScheduler.nextTriggerMillis(2, 30, now) // 2:30 doesn't exist that night
        assertTrue(next > millis(now))
        assertTrue(next <= millis(at(2026, 3, 8, 4, 0)))
    }
}

class StatsTest {
    private val soda = Question("soda", "Soda?")                    // on track = No
    private val veg = Question("veg", "Vegetables?", yesIsGood = true)
    private val c = Checkin(id = "c", name = "Evening", hour = 21, minute = 30, questions = listOf(soda, veg))

    @Test
    fun onTrackRespectsWhichAnswerIsGood() {
        assertTrue(Stats.onTrack(c, mapOf("soda" to false, "veg" to true)))
        assertFalse(Stats.onTrack(c, mapOf("soda" to true, "veg" to true)))
        assertFalse(Stats.onTrack(c, mapOf("soda" to false, "veg" to false)))
    }

    @Test
    fun unansweredQuestionsDoNotCountAgainstADay() {
        assertTrue(Stats.onTrack(c, mapOf("soda" to false)))
    }

    @Test
    fun loggedOnlyCountsDaysWithThisCheckinsAnswers() {
        val entries = mapOf(
            "2026-11-07" to mapOf("soda" to false),
            "2026-11-06" to mapOf("someOtherCheckinsQuestion" to true),
        )
        val logged = Stats.logged(c, entries, listOf("2026-11-07", "2026-11-06", "2026-11-05"))
        assertEquals(1, logged.size)
    }
}

class CheckinTextTest {
    @Test
    fun stepsAreListedInTheOrderTheyRun() {
        val c = Checkin(
            id = "c", name = "Wake", hour = 7, minute = 0,
            questions = listOf(Question("q", "Q?")),
            mathCount = 3, scanCode = "X", scanLabel = "Sink",
        )
        assertEquals("solve 3 math → scan Sink → answer 1 question", CheckinText.steps(c))
    }

    @Test
    fun noStepsMeansJustDismiss() {
        assertEquals("tap Dismiss", CheckinText.steps(Checkin(id = "c", name = "Plain", hour = 7, minute = 0)))
    }
}
