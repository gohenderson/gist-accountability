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

class MultipleTimesTest {
    private val ny = ZoneId.of("America/New_York")
    private fun at(d: Int, h: Int, mi: Int) = ZonedDateTime.of(2026, 11, d, h, mi, 0, 0, ny)
    private val c = Checkin(
        id = "teeth", name = "Brush teeth",
        times = listOf(AlarmTime(7, 30), AlarmTime(13, 0), AlarmTime(22, 15)),
    )

    @Test
    fun nextIsTheSoonestTimeStillAheadToday() {
        assertEquals(at(8, 7, 30).toInstant().toEpochMilli(), AlarmScheduler.nextTriggerMillis(c, at(8, 6, 0)))
        assertEquals(at(8, 13, 0).toInstant().toEpochMilli(), AlarmScheduler.nextTriggerMillis(c, at(8, 7, 30)))
        assertEquals(at(8, 22, 15).toInstant().toEpochMilli(), AlarmScheduler.nextTriggerMillis(c, at(8, 13, 0)))
    }

    @Test
    fun afterTheLastTimeItWrapsToTomorrowsFirst() {
        assertEquals(at(9, 7, 30).toInstant().toEpochMilli(), AlarmScheduler.nextTriggerMillis(c, at(8, 22, 15)))
    }

    @Test
    fun timesParseAndPrintAsHoursAndMinutes() {
        assertEquals(AlarmTime(7, 5), AlarmTime.parse("07:05"))
        assertEquals("07:05", AlarmTime(7, 5).toString())
        assertEquals(null, AlarmTime.parse("25:00"))
        assertEquals(null, AlarmTime.parse("nonsense"))
        assertEquals(listOf(AlarmTime(7, 0), AlarmTime(21, 30)), listOf(AlarmTime(21, 30), AlarmTime(7, 0)).sorted())
    }

    @Test
    fun startPageListsAllTimes() {
        assertTrue(CheckinText.oneLine(c).startsWith("7:30"))
        assertEquals(3, CheckinText.times(c).split(", ").size)
    }
}

class StatsTest {
    private val soda = Question("soda", "Soda?")                    // on track = No
    private val veg = Question("veg", "Vegetables?", yesIsGood = true)
    private val c = Checkin(id = "c", name = "Evening", times = listOf(AlarmTime(21, 30)), questions = listOf(soda, veg))

    private fun answered(vararg a: Pair<String, Boolean>) = SlotResult(SlotResult.ANSWERED, mapOf(*a))
    private val missed = SlotResult(SlotResult.RANG)
    private val skipped = SlotResult(SlotResult.SKIPPED)
    private fun day(vararg slots: Pair<String, SlotResult>): Map<String, SlotResult> = mapOf(*slots)

    @Test
    fun onTrackRespectsWhichAnswerIsGood() {
        assertTrue(Stats.onTrack(c, day("21:30" to answered("soda" to false, "veg" to true))))
        assertFalse(Stats.onTrack(c, day("21:30" to answered("soda" to true, "veg" to true))))
        assertFalse(Stats.onTrack(c, day("21:30" to answered("soda" to false, "veg" to false))))
    }

    @Test
    fun anOffTrackTimeMakesTheWholeDayOffTrackEvenIfAnotherTimeWasOnTrack() {
        val d = day("07:00" to answered("soda" to false), "21:30" to answered("soda" to true))
        assertEquals(Mark.OFF_TRACK, Stats.dayMark(soda, d))
        assertFalse(Stats.onTrack(c, d))
    }

    @Test
    fun aMissedTimeIsAnX() {
        val d = day("07:00" to answered("soda" to false, "veg" to true), "21:30" to missed)
        assertEquals(Mark.OFF_TRACK, Stats.dayMark(soda, d))
        assertEquals(Mark.OFF_TRACK, Stats.dayMark(veg, d))
        assertFalse(Stats.onTrack(c, d))
        assertEquals(Mark.OFF_TRACK, Stats.dayMark(soda, day("21:30" to missed)))
    }

    @Test
    fun skippedTimesDontCountEitherWay() {
        val d = day("07:00" to answered("soda" to false, "veg" to true), "21:30" to skipped)
        assertEquals(Mark.ON_TRACK, Stats.dayMark(soda, d))
        assertTrue(Stats.onTrack(c, d))
        assertEquals(Mark.SKIPPED, Stats.dayMark(soda, day("07:00" to skipped, "21:30" to skipped)))
        assertEquals(Mark.NONE, Stats.dayMark(soda, day()))
    }

    @Test
    fun loggedCountsDaysThatRangButNotSkippedOnlyDays() {
        val results = mapOf(
            "2026-11-07" to day("21:30" to answered("soda" to false)),
            "2026-11-06" to day("21:30" to skipped),
            "2026-11-05" to day("21:30" to missed),
        )
        val logged = Stats.logged(c, results, listOf("2026-11-07", "2026-11-06", "2026-11-05", "2026-11-04"))
        assertEquals(2, logged.size)
    }
}

class OccurrenceTest {
    private val ny = ZoneId.of("America/New_York")
    private fun at(d: Int, h: Int, mi: Int) = ZonedDateTime.of(2026, 11, d, h, mi, 0, 0, ny)
    private val teeth = Checkin(id = "t", name = "Teeth", times = listOf(AlarmTime(7, 30), AlarmTime(22, 15)))

    @Test
    fun aClosedRingIsPassedOverForTheNextOne() {
        val now = at(8, 6, 0)
        val next = AlarmScheduler.nextOccurrence(teeth, now) { it.timeKey == "07:30" && it.day == "2026-11-08" }!!
        assertEquals("22:15", next.timeKey)
        assertEquals("2026-11-08", next.day)
        assertEquals(at(8, 22, 15).toInstant().toEpochMilli(), next.at)
    }

    @Test
    fun aboutYesterdayCheckinsCountForThePreviousDay() {
        val morning = Checkin(id = "m", name = "Morning", times = listOf(AlarmTime(7, 0)), aboutYesterday = true)
        val next = AlarmScheduler.nextOccurrence(morning, at(8, 6, 0))!!
        assertEquals("2026-11-07", next.day)
    }

    @Test
    fun occurrencesAreSoonestFirstAndAllInTheFuture() {
        val now = at(8, 12, 0)
        val list = AlarmScheduler.occurrences(teeth, now)
        assertEquals("22:15", list.first().timeKey)
        assertTrue(list.zipWithNext().all { (a, b) -> a.at < b.at })
        assertTrue(list.all { it.at > now.toInstant().toEpochMilli() })
    }
}

class CheckinTextTest {
    @Test
    fun stepsAreListedInTheOrderTheyRun() {
        val c = Checkin(
            id = "c", name = "Wake", times = listOf(AlarmTime(7, 0)),
            questions = listOf(Question("q", "Q?")),
            mathCount = 3, scanCode = "X", scanLabel = "Sink",
        )
        assertEquals("solve 3 math → scan Sink → answer 1 question", CheckinText.steps(c))
    }

    @Test
    fun noStepsMeansJustDismiss() {
        assertEquals("tap Dismiss", CheckinText.steps(Checkin(id = "c", name = "Plain")))
    }
}

class RepeatTest {
    private val ny = ZoneId.of("America/New_York")
    private fun at(y: Int, m: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, m, d, h, mi, 0, 0, ny)
    private fun date(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)

    @Test
    fun weekdaysSkipTheWeekend() {
        val r = Repeat.weekly(Repeat.WEEKDAYS)
        assertTrue(r.matches(date(2026, 11, 6)))   // Friday
        assertFalse(r.matches(date(2026, 11, 7)))  // Saturday
        assertFalse(r.matches(date(2026, 11, 8)))  // Sunday
        assertTrue(r.matches(date(2026, 11, 9)))   // Monday
    }

    @Test
    fun monthlyOnTheFirstAndTheLastDay() {
        val r = Repeat.monthly(setOf(1, Repeat.LAST_DAY))
        assertTrue(r.matches(date(2026, 12, 1)))
        assertTrue(r.matches(date(2026, 11, 30)))
        assertTrue(r.matches(date(2028, 2, 29)))   // leap year
        assertTrue(r.matches(date(2027, 2, 28)))
        assertFalse(r.matches(date(2026, 11, 15)))
    }

    @Test
    fun aDateAMonthDoesntHaveIsSkippedThatMonth() {
        val r = Repeat.monthly(setOf(31))
        assertFalse(r.matches(date(2026, 11, 30)))
        assertTrue(r.matches(date(2026, 12, 31)))
    }

    @Test
    fun nextRingFollowsTheRule() {
        val weekdays = Checkin(id = "w", name = "Work", times = listOf(AlarmTime(7, 0)), repeat = Repeat.weekly(Repeat.WEEKDAYS))
        // Friday evening → Monday morning
        assertEquals(at(2026, 11, 9, 7, 0).toInstant().toEpochMilli(), AlarmScheduler.nextTriggerMillis(weekdays, at(2026, 11, 6, 20, 0)))

        val monthly = Checkin(id = "m", name = "Bills", times = listOf(AlarmTime(9, 0)), repeat = Repeat.monthly(setOf(1)))
        assertEquals(at(2026, 12, 1, 9, 0).toInstant().toEpochMilli(), AlarmScheduler.nextTriggerMillis(monthly, at(2026, 11, 8, 12, 0)))

        val list = AlarmScheduler.occurrences(monthly, at(2026, 11, 8, 12, 0), count = 3)
        assertEquals(listOf("2026-12-01", "2027-01-01", "2027-02-01"), list.map { it.day })
    }

    @Test
    fun descriptionsReadNaturally() {
        assertEquals("every day", Repeat.EVERY_DAY.describe())
        assertEquals("Mon–Fri", Repeat.weekly(Repeat.WEEKDAYS).describe())
        assertEquals("Sat & Sun", Repeat.weekly(Repeat.WEEKENDS).describe())
        assertEquals("monthly on the 1st", Repeat.monthly(setOf(1)).describe())
        assertEquals("monthly on the 1st, 15th and last day", Repeat.monthly(setOf(15, 1, Repeat.LAST_DAY)).describe())
        assertEquals("monthly on the 2nd, 3rd, 11th and 22nd", Repeat.monthly(setOf(2, 3, 11, 22)).describe())
        assertEquals("every day", Repeat.weekly((1..7).toSet()).describe())
    }
}

class LibraryTest {
    @Test
    fun templatesAreWellFormed() {
        val keys = Library.TEMPLATES.map { it.key }
        assertEquals("unique keys", keys.size, keys.toSet().size)
        for (t in Library.TEMPLATES) {
            val c = t.checkin
            assertTrue("${t.key} has a name", c.name.isNotBlank())
            assertTrue("${t.key} has times", c.times.isNotEmpty())
            assertEquals("${t.key} times sorted", c.times.sorted(), c.times)
            val ids = c.questions.map { it.id }
            assertEquals("${t.key} question ids unique", ids.size, ids.toSet().size)
            assertTrue("${t.key} has a blurb", t.blurb.isNotBlank())
        }
    }

    @Test
    fun instantiateGivesANewIdAndAFreeName() {
        val water = Library.template("water")!!
        val first = Library.instantiate(water, emptyList(), "id1")
        assertEquals("id1", first.id)
        assertEquals("Water", first.name)
        assertEquals(water.checkin.times, first.times)
        val second = Library.instantiate(water, listOf(first), "id2")
        assertEquals("Water 2", second.name)
        assertEquals("Water 3", Library.instantiate(water, listOf(first, second), "id3").name)
    }

    @Test
    fun blankIsNumbered() {
        assertEquals("Check-in 1", Library.blank(emptyList(), "x").name)
        assertTrue(Library.blank(emptyList(), "x").questions.isEmpty())
    }
}

class BackgroundTest {
    @Test
    fun descriptions() {
        assertEquals("none", Background().describe(0))
        assertEquals("photo", Background(Background.PHOTO).describe(1))
        assertEquals("slideshow · 4 photos · every 10 s", Background(Background.SLIDESHOW).describe(4))
        assertEquals("slideshow · 1 photo · every 5 s", Background(Background.SLIDESHOW, intervalSec = 5).describe(1))
        assertEquals("video", Background(Background.VIDEO).describe(0))
        assertFalse(Background(caption = "hi").hasMedia)
    }
}

class StepsTest {
    private val q = Question("q", "Q?")
    private val base = Checkin(id = "c", name = "C")

    @Test
    fun defaultOrderIsMathScanQuestions() {
        val c = base.copy(mathCount = 2, scanCode = "X", questions = listOf(q))
        assertEquals(listOf(Steps.MATH, Steps.SCAN, Steps.QUESTIONS), Steps.order(c))
        assertEquals("solve 2 math → scan code → answer 1 question", CheckinText.steps(c))
    }

    @Test
    fun savedOrderWinsAndUnsetStepsDontRun() {
        val c = base.copy(mathCount = 2, questions = listOf(q), steps = listOf(Steps.QUESTIONS, Steps.SCAN, Steps.MATH))
        assertEquals(listOf(Steps.QUESTIONS, Steps.SCAN, Steps.MATH), Steps.order(c))
        assertEquals(listOf(Steps.QUESTIONS, Steps.MATH), Steps.active(c)) // no code registered
        assertEquals("answer 1 question → solve 2 math", CheckinText.steps(c))
        assertEquals("Not set up: register a code", Steps.summary(c, Steps.SCAN))
    }

    @Test
    fun moveAddAndRemove() {
        var c = base.copy(mathCount = 1, scanCode = "X", questions = listOf(q))
        c = Steps.move(c, 2, 0)
        assertEquals(listOf(Steps.QUESTIONS, Steps.MATH, Steps.SCAN), Steps.order(c))
        c = Steps.move(c, 0, 2)
        assertEquals(listOf(Steps.MATH, Steps.SCAN, Steps.QUESTIONS), Steps.order(c))
        c = Steps.remove(c, Steps.SCAN)
        assertEquals(null, c.scanCode)
        assertEquals(listOf(Steps.MATH, Steps.QUESTIONS), Steps.order(c))
        assertEquals(listOf(Steps.SCAN), Steps.available(c))
        c = Steps.add(c, Steps.SCAN)
        assertEquals(listOf(Steps.MATH, Steps.QUESTIONS, Steps.SCAN), Steps.order(c))

        val math = Steps.add(base, Steps.MATH)
        assertEquals(3, math.mathCount)
        assertEquals(listOf(Steps.MATH), Steps.active(math))
        assertEquals(emptyList<String>(), Steps.order(Steps.remove(math, Steps.MATH)))
    }

    @Test
    fun noStepsMeansDismiss() {
        assertEquals("tap Dismiss", CheckinText.steps(base))
        assertEquals(Steps.ALL, Steps.available(base))
    }
}

class LinksTest {
    @Test
    fun donateButtonPointsAtTheReadmesDonateSection() {
        val readme = java.io.File("../README.md").takeIf { it.exists() } ?: java.io.File("README.md")
        assertTrue("README.md should be found", readme.exists())
        assertTrue("README needs a '## Donate' heading", readme.readLines().any { it.trim() == "## Donate" })
        assertEquals(Links.REPO + "#donate", Links.DONATE)
    }
}

class TriggerTest {
    private val home = Place("Home", 40.7128, -74.0060, 200)
    private fun at(h: Int, m: Int, day: Int = 9) = LocalDateTime.of(2026, 11, day, h, m) // Nov 9 2026 = Monday

    @Test
    fun distanceIsAboutRight() {
        // One degree of latitude ≈ 111.2 km.
        val d = Trigger.distanceM(40.0, -74.0, 41.0, -74.0)
        assertTrue("got $d", d in 111_000.0..111_400.0)
        assertTrue(home.contains(40.7129, -74.0061))
        assertFalse(home.contains(40.7228, -74.0060)) // ~1.1 km north
    }

    @Test
    fun windowsIncludingOnesAcrossMidnight() {
        val morning = Trigger(windowStart = AlarmTime(6, 0), windowEnd = AlarmTime(9, 0))
        assertTrue(Trigger.inWindow(morning, java.time.LocalTime.of(6, 0)))
        assertTrue(Trigger.inWindow(morning, java.time.LocalTime.of(9, 0)))
        assertFalse(Trigger.inWindow(morning, java.time.LocalTime.of(9, 1)))
        val night = Trigger(windowStart = AlarmTime(22, 0), windowEnd = AlarmTime(2, 0))
        assertTrue(Trigger.inWindow(night, java.time.LocalTime.of(23, 30)))
        assertTrue(Trigger.inWindow(night, java.time.LocalTime.of(1, 0)))
        assertFalse(Trigger.inWindow(night, java.time.LocalTime.of(12, 0)))
        assertTrue(Trigger.inWindow(Trigger(), java.time.LocalTime.of(3, 0)))
    }

    @Test
    fun arrivingRingsOnlyWhenEveryConditionHolds() {
        val c = Checkin(
            id = "g", name = "Gym",
            trigger = Trigger(Trigger.ARRIVE, home, windowStart = AlarmTime(6, 0), windowEnd = AlarmTime(9, 0)),
            repeat = Repeat.weekly(Repeat.WEEKDAYS),
        )
        assertTrue(Trigger.placeEventRings(c, entering = true, now = at(7, 0), rangToday = false))
        assertFalse("leaving isn't arriving", Trigger.placeEventRings(c, entering = false, now = at(7, 0), rangToday = false))
        assertFalse("outside the window", Trigger.placeEventRings(c, true, at(10, 0), false))
        assertFalse("not a weekday", Trigger.placeEventRings(c, true, at(7, 0, day = 8), false))
        assertFalse("once a day", Trigger.placeEventRings(c, true, at(7, 0), rangToday = true))
        assertTrue("unless allowed more", Trigger.placeEventRings(c.copy(trigger = c.trigger.copy(oncePerDay = false)), true, at(7, 0), true))
        assertFalse("off", Trigger.placeEventRings(c.copy(enabled = false), true, at(7, 0), false))
        assertFalse("no place set", Trigger.placeEventRings(c.copy(trigger = c.trigger.copy(place = null)), true, at(7, 0), false))
        val leave = c.copy(trigger = c.trigger.copy(kind = Trigger.LEAVE))
        assertTrue(Trigger.placeEventRings(leave, entering = false, now = at(8, 0), rangToday = false))
    }

    @Test
    fun timedCheckinsCanRequireBeingAtOrAwayFromAPlace() {
        val atHome = Trigger(where = Trigger.AT, place = home)
        assertTrue(Trigger.locationAllows(atHome, 50.0))
        assertFalse(Trigger.locationAllows(atHome, 5_000.0))
        assertTrue("unknown location still rings", Trigger.locationAllows(atHome, null))
        val away = Trigger(where = Trigger.AWAY, place = home)
        assertTrue(Trigger.locationAllows(away, 5_000.0))
        assertFalse(Trigger.locationAllows(away, 50.0))
        assertTrue(Trigger.locationAllows(Trigger(), 5_000.0))
    }

    @Test
    fun coordinatesParseFromMapsApps() {
        assertEquals(40.7128 to -74.006, Place.parseCoordinates("40.7128, -74.0060"))
        assertEquals(51.5 to -0.12, Place.parseCoordinates("(51.5,-0.12)"))
        assertEquals(1.0 to 2.0, Place.parseCoordinates("1 2"))
        assertEquals(null, Place.parseCoordinates("91, 0"))
        assertEquals(null, Place.parseCoordinates("Main Street"))
        assertEquals(null, Place.parseCoordinates("1, 2, 3"))
    }

    @Test
    fun descriptions() {
        val c = Checkin(
            id = "g", name = "Gym", repeat = Repeat.weekly(setOf(1, 3, 5)),
            trigger = Trigger(Trigger.ARRIVE, Place("Gym", 1.0, 2.0), windowStart = AlarmTime(6, 0), windowEnd = AlarmTime(9, 0)),
        )
        val text = CheckinText.whenText(c)
        assertTrue(text, text.startsWith("when you arrive at Gym, Mon, Wed, Fri, between "))
        assertTrue(text, text.endsWith(", once a day"))
        assertEquals("when you leave Work", Trigger.describe(Trigger(Trigger.LEAVE, Place("Work", 1.0, 2.0))))
        assertEquals("only when away from Home", Trigger.describe(Trigger(where = Trigger.AWAY, place = home)))
        assertTrue("no set times for place triggers", AlarmScheduler.occurrences(c).isEmpty())
    }
}
