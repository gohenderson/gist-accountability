package dev.gist.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val c = Checkin(id = "c", name = "Evening", times = listOf(AlarmTime(21, 30)), steps = listOf(StepConfig("q", Steps.QUESTIONS, questions = listOf(soda, veg))))

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
            steps = listOf(
                StepConfig("m", Steps.MATH, mathCount = 3),
                StepConfig("s", Steps.SCAN, scanCode = "X", scanLabel = "Sink"),
                StepConfig("q", Steps.QUESTIONS, questions = listOf(Question("q", "Q?"))),
            ),
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
            assertTrue("${t.key} has triggers", c.times.isNotEmpty() || c.events.isNotEmpty())
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
    private fun kinds(c: Checkin) = c.steps.map { it.kind }

    @Test
    fun stepsRunInTheirOrderAndUnsetOnesAreSkipped() {
        val c = base.copy(steps = listOf(
            StepConfig("q", Steps.QUESTIONS, questions = listOf(q)),
            StepConfig("s", Steps.SCAN), // no code yet
            StepConfig("m", Steps.MATH, mathCount = 2),
        ))
        assertEquals(listOf("q", "m"), Steps.active(c).map { it.id })
        assertEquals("answer 1 question → solve 2 math", CheckinText.steps(c))
        assertEquals("Not set up: register a code", Steps.summary(c.steps[1]))
        assertEquals(listOf(q), c.questions)
    }

    @Test
    fun anyKindCanRepeatAsOftenAsYouLike() {
        var c = base
        repeat(100) { c = Steps.add(c, Steps.MATH) }
        assertEquals(100, c.steps.size)
        assertEquals("capped at ${Steps.MAX_STEPS}", 100, Steps.add(c, Steps.SCAN).steps.size)
        assertEquals(100, c.steps.map { it.id }.toSet().size)
        assertTrue(c.steps.all { it.mathCount == 3 })
        assertEquals("solve 3 math → solve 3 math", CheckinText.steps(base.copy(steps = c.steps.take(2))))
    }

    @Test
    fun moveDuplicateAndRemove() {
        var c = base.copy(steps = listOf(
            StepConfig("m", Steps.MATH, mathCount = 1),
            StepConfig("s", Steps.SCAN, scanCode = "X"),
            StepConfig("q", Steps.QUESTIONS, questions = listOf(q)),
        ))
        c = Steps.move(c, 2, 0)
        assertEquals(listOf(Steps.QUESTIONS, Steps.MATH, Steps.SCAN), kinds(c))
        c = Steps.duplicate(c, "m")
        assertEquals(listOf(Steps.QUESTIONS, Steps.MATH, Steps.MATH, Steps.SCAN), kinds(c))
        c = Steps.duplicate(c, "q")
        assertEquals("duplicated questions get their own ids", 2, c.questions.map { it.id }.toSet().size)
        c = Steps.remove(c, "s")
        assertEquals(listOf(Steps.QUESTIONS, Steps.QUESTIONS, Steps.MATH, Steps.MATH), kinds(c))
        c = Steps.update(c, "m") { it.copy(mathCount = 50) }
        assertEquals(50, c.steps.first { it.id == "m" }.mathCount)
    }

    @Test
    fun noStepsMeansDismiss() {
        assertEquals("tap Dismiss", CheckinText.steps(base))
        assertTrue(Steps.active(base).isEmpty())
    }
}

class LinksTest {
    @Test
    fun donateButtonPointsAtTheReadmesDonateSection() {
        // Tests run in android/app; the README is at the repository root.
        val readme = listOf("../../README.md", "../README.md", "README.md").map { java.io.File(it) }.firstOrNull { it.exists() }
            ?: java.io.File("../../README.md")
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
        val t = Trigger(Trigger.ARRIVE, home, windowStart = AlarmTime(6, 0), windowEnd = AlarmTime(9, 0),
            days = Repeat.weekly(Repeat.WEEKDAYS))
        val c = Checkin(id = "g", name = "Gym", times = emptyList(), events = listOf(t))
        assertTrue(Trigger.placeEventRings(c, t, entering = true, now = at(7, 0), rangToday = false))
        assertFalse("leaving isn't arriving", Trigger.placeEventRings(c, t, entering = false, now = at(7, 0), rangToday = false))
        assertFalse("outside the window", Trigger.placeEventRings(c, t, true, at(10, 0), false))
        assertFalse("not a weekday", Trigger.placeEventRings(c, t, true, at(7, 0, day = 8), false))
        assertFalse("once a day", Trigger.placeEventRings(c, t, true, at(7, 0), rangToday = true))
        assertTrue("unless allowed more", Trigger.placeEventRings(c, t.copy(oncePerDay = false), true, at(7, 0), true))
        assertFalse("off", Trigger.placeEventRings(c.copy(enabled = false), t, true, at(7, 0), false))
        assertFalse("no place set", Trigger.placeEventRings(c, t.copy(place = null), true, at(7, 0), false))
        assertTrue(Trigger.placeEventRings(c, t.copy(kind = Trigger.LEAVE), entering = false, now = at(8, 0), rangToday = false))
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
        assertFalse("a timed trigger's own where", Trigger.locationAllows(Trigger.AT, home, 5_000.0))
        assertEquals("at Home", Trigger.whereText(Trigger.AT, home))
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
            id = "g", name = "Gym", times = emptyList(),
            events = listOf(Trigger(Trigger.ARRIVE, Place("Gym", 1.0, 2.0), windowStart = AlarmTime(6, 0), windowEnd = AlarmTime(9, 0),
                days = Repeat.weekly(setOf(1, 3, 5)))),
        )
        val text = CheckinText.whenText(c)
        assertTrue(text, text.startsWith("when you arrive at Gym, Mon, Wed, Fri, between "))
        assertTrue(text, text.endsWith(", once a day"))
        // Any mix: a time with a where, plus an event.
        val mixed = c.copy(times = listOf(AlarmTime(5, 0, Repeat.weekly(setOf(2)), Trigger.AWAY, home)))
        val m = CheckinText.whenText(mixed).replace('\u202F', ' ')
        assertTrue(m, m.startsWith("5:00 AM Tue, only when away from Home; when you arrive at Gym"))
        assertEquals("when you leave Work", Trigger.describe(Trigger(Trigger.LEAVE, Place("Work", 1.0, 2.0))))
        assertEquals("only when away from Home", Trigger.describe(Trigger(where = Trigger.AWAY, place = home)))
        assertTrue("no set times for place triggers", AlarmScheduler.occurrences(c).isEmpty())
    }
}

class RunDetectorTest {
    /** Feeds [spm] steps a minute for [seconds], one sample a second, from [t0] with [total] steps. */
    private fun RunDetector.feed(t0: Long, total: Long, seconds: Int, spm: Int, events: MutableList<RunDetector.Event>): Pair<Long, Long> {
        var t = t0
        var steps = total
        for (i in 1..seconds) {
            t += 1000
            steps = total + i.toLong() * spm / 60 // whole steps so far
            steps(t, steps)?.let { events += it }
        }
        return t to steps
    }

    /** Two minutes of running from scratch. */
    fun RunDetector.feedSteps(events: MutableList<RunDetector.Event>) { feed(0, 1000, 120, 165, events) }

    private fun RunDetector.idle(t0: Long, seconds: Int, events: MutableList<RunDetector.Event>): Long {
        var t = t0
        repeat(seconds / 15) { t += 15_000; tick(t)?.let { events += it } }
        return t
    }

    @Test
    fun aFiveMinuteRunStartsAndFinishes() {
        val d = RunDetector()
        val ev = mutableListOf<RunDetector.Event>()
        val (t, _) = d.feed(0, 1000, 300, 165, ev)
        val started = ev.filterIsInstance<RunDetector.Event.Started>().single()
        assertTrue("started after about a minute and a half: ${started.at}", started.at in 0..60_000)
        assertTrue(d.running)
        d.idle(t, 180, ev)
        val done = ev.filterIsInstance<RunDetector.Event.Finished>().single()
        assertFalse(d.running)
        assertTrue("about 5 minutes: ${done.minutes}", done.minutes in 4..5)
    }

    @Test
    fun walkingIsNotRunning() {
        val d = RunDetector()
        val ev = mutableListOf<RunDetector.Event>()
        d.feed(0, 0, 600, 115, ev)
        assertTrue(ev.isEmpty())
    }

    @Test
    fun aShortDashDoesntCount() {
        val d = RunDetector()
        val ev = mutableListOf<RunDetector.Event>()
        val (t, s) = d.feed(0, 0, 40, 170, ev)
        d.feed(t, s, 300, 100, ev)
        assertTrue(ev.isEmpty())
    }

    @Test
    fun aShortStopDoesntEndTheRun() {
        val d = RunDetector()
        val ev = mutableListOf<RunDetector.Event>()
        var (t, s) = d.feed(0, 0, 180, 165, ev)
        t = d.idle(t, 45, ev) // waiting at a light
        d.feed(t, s, 180, 165, ev)
        assertEquals(1, ev.size) // just the start
        assertTrue(d.running)
    }

    @Test
    fun runTriggersFollowTheirConditions() {
        val now = LocalDateTime.of(2026, 11, 9, 7, 0)
        val c = Checkin(id = "a", name = "A", times = emptyList())
        val start = Trigger(Trigger.RUN_START)
        assertTrue(Trigger.runEventRings(c, start, null, now, false))
        assertFalse(Trigger.runEventRings(c, start, 20, now, false))
        assertFalse("once a day", Trigger.runEventRings(c, start, null, now, true))
        val end = Trigger(Trigger.RUN_END, minRunMinutes = 10)
        assertFalse(Trigger.runEventRings(c, end, null, now, false))
        assertFalse("too short", Trigger.runEventRings(c, end, 9, now, false))
        assertTrue(Trigger.runEventRings(c, end, 10, now, false))
        assertFalse("Monday", Trigger.runEventRings(c, end.copy(days = Repeat.weekly(Repeat.WEEKENDS)), 30, now, false))
        assertEquals("when you finish a run of 10+ min", Trigger.describe(end))
        assertTrue("no set times", AlarmScheduler.occurrences(c.copy(events = listOf(end))).isEmpty())
    }

    @Test
    fun partwayIntoARunRingsOnceItsTimeIsReached() {
        val now = LocalDateTime.of(2026, 11, 9, 7, 0)
        val c = Checkin(id = "a", name = "A", times = emptyList())
        val at30 = Trigger(Trigger.RUN_FOR, minRunMinutes = 30, oncePerDay = false)
        assertTrue(at30.isMovement)
        assertFalse("not yet", Trigger.runProgressRings(c, at30, 29, now, false))
        assertTrue(Trigger.runProgressRings(c, at30, 30, now, false))
        assertTrue("a little late is fine", Trigger.runProgressRings(c, at30, 31, now, false))
        assertTrue("not once a day: every run", Trigger.runProgressRings(c, at30, 30, now, true))
        assertFalse("once a day", Trigger.runProgressRings(c, at30.copy(oncePerDay = true), 30, now, true))
        assertFalse("off", Trigger.runProgressRings(c.copy(enabled = false), at30, 30, now, false))
        assertFalse("start / finish don't use it", Trigger.runEventRings(c, at30, null, now, false) || Trigger.runEventRings(c, at30, 40, now, false))
        assertFalse("other kinds don't", Trigger.runProgressRings(c, Trigger(Trigger.RUN_START), 30, now, false))
        assertEquals("30 minutes into a run", Trigger.describe(at30))
        assertEquals("1 hour into a run", Trigger.describe(at30.copy(minRunMinutes = 60)))
        assertEquals("2 hours 30 minutes", Trigger.runForText(150))
        assertEquals("1 minute", Trigger.runForText(1))
    }

    @Test
    fun theDetectorKnowsWhenTheRunStarted() {
        val d = RunDetector()
        assertNull(d.runStartedAt)
        val ev = mutableListOf<RunDetector.Event>()
        RunDetectorTest().run { d.feedSteps(ev) }
        val started = ev.filterIsInstance<RunDetector.Event.Started>().single()
        assertEquals(started.at, d.runStartedAt)
    }
}

class SortTest {
    private fun c(id: String, name: String = id) = Checkin(id = id, name = name)
    private val list = listOf(c("a", "walk"), c("b", "Bed"), c("c", "Coffee"), c("d", "ab"))

    @Test
    fun myOrderIsUntouched() {
        assertEquals(list, Grouping.sort(list, Grouping.SORT_CUSTOM, { 1L }, { 0.5 }))
    }

    @Test
    fun nextAlarmIsSoonestFirstWithNoTimeLastInYourOrder() {
        val next = mapOf("a" to 300L, "b" to null, "c" to 100L, "d" to null)
        val sorted = Grouping.sort(list, Grouping.SORT_NEXT, { next[it.id] }, { null })
        assertEquals(listOf("c", "a", "b", "d"), sorted.map { it.id })
    }

    @Test
    fun needsAttentionIsLowestScoreFirstWithNoScoreLast() {
        val score = mapOf("a" to 0.9, "b" to 0.2, "c" to null, "d" to 0.2)
        val sorted = Grouping.sort(list, Grouping.SORT_ATTENTION, { null }, { score[it.id] })
        assertEquals("ties keep your order", listOf("b", "d", "a", "c"), sorted.map { it.id })
    }

    @Test
    fun nameIgnoresCase() {
        assertEquals(listOf("d", "b", "c", "a"), Grouping.sort(list, Grouping.SORT_NAME, { null }, { null }).map { it.id })
        assertEquals("Needs attention", Grouping.sortName(Grouping.SORT_ATTENTION))
        assertEquals("My order", Grouping.sortName("nonsense"))
    }
}

class CommitmentTest {
    @Test
    fun onlyStrictCheckinsCount() {
        val c = Checkin(id = "a", name = "Cheer")
        assertTrue(c.counted)
        assertFalse(c.soft || c.voice)
        assertTrue(c.copy(commitment = Checkin.SOFT).soft)
        assertFalse(c.copy(commitment = Checkin.SOFT).counted)
        assertTrue(c.copy(commitment = Checkin.VOICE).voice)
        assertFalse(c.copy(commitment = Checkin.VOICE).counted)
    }

    @Test
    fun aVoiceSaysItsLineOrItsName() {
        val c = Checkin(id = "a", name = "30 minutes in", commitment = Checkin.VOICE)
        assertEquals("30 minutes in", VoiceService.spokenText(c))
        assertEquals("Is that Forrest Gump?", VoiceService.spokenText(c.copy(voiceText = "  Is that Forrest Gump? ")))
        assertEquals("speaks a line", CheckinText.steps(c))
        assertEquals("plays your clip", CheckinText.steps(c.copy(voiceSource = Checkin.VOICE_CLIP)))
    }
}

class GroupingLogicTest {
    private fun c(id: String, cat: String = "") = Checkin(id = id, name = id, category = cat)

    @Test
    fun categoriesAToZWithUncategorizedLast() {
        val groups = Grouping.byCategory(listOf(c("a", "work"), c("b"), c("c", "Health"), c("d", "Work"), c("e", "health ")))
        assertEquals(listOf("Health", "work", "Other"), groups.map { it.first })
        assertEquals(listOf("c", "e"), groups[0].second.map { it.id })
        assertEquals(listOf("a", "d"), groups[1].second.map { it.id })
        assertEquals(listOf("Health", "work"), Grouping.categories(listOf(c("a", "work"), c("c", "Health"), c("d", "Work"))))
    }

    @Test
    fun reorderingInsideAGroupLeavesTheRestAlone() {
        val all = listOf("a", "x", "b", "y", "c")
        assertEquals(listOf("c", "x", "a", "y", "b"), Grouping.reorderWithin(all, listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("y", "a", "x", "b", "c"), Grouping.reorderWithin(all, all, 3, 0))
        assertEquals(all, Grouping.reorderWithin(all, listOf("a", "b"), 0, 5))
    }
}

class GroupOrderTest {
    private fun c(id: String, cat: String = "") = Checkin(id = id, name = id, category = cat)
    private val list = listOf(c("e", "Evening"), c("m", "Morning"), c("f", "Fitness"), c("x"))

    @Test
    fun yourOrderFirstThenAToZThenOther() {
        assertEquals(listOf("Evening", "Fitness", "Morning", "Other"), Grouping.byCategory(list).map { it.first })
        assertEquals(listOf("Morning", "Evening", "Fitness", "Other"),
            Grouping.byCategory(list, listOf("morning", "evening")).map { it.first })
        assertEquals("Other can be placed too", listOf("Other", "Evening", "Fitness", "Morning"),
            Grouping.byCategory(list, listOf("other")).map { it.first })
    }

    @Test
    fun movingAGroupGivesTheNewOrder() {
        assertEquals(listOf("morning", "evening", "fitness", "other"),
            Grouping.moveGroup(listOf("Evening", "Fitness", "Morning", "Other"), 2, 0))
        assertEquals(listOf("evening"), Grouping.moveGroup(listOf("Evening"), 0, 3))
        assertEquals("health", Grouping.key(" Health "))
    }

    @Test
    fun everythingIsFoldedOnlyWhenEveryGroupAndCheckinIs() {
        val groups = setOf("morning", "evening")
        val ids = setOf("a", "b")
        assertTrue(Grouping.allFolded(groups, groups, ids, ids))
        assertTrue("extra ids don't matter", Grouping.allFolded(groups, groups + "gone", ids, ids + "gone"))
        assertFalse("a group is open", Grouping.allFolded(groups, setOf("morning"), ids, ids))
        assertFalse("a check-in is open", Grouping.allFolded(groups, groups, ids, setOf("a")))
        assertTrue("ungrouped: just the check-ins", Grouping.allFolded(emptySet(), emptySet(), ids, ids))
        assertFalse(Grouping.allFolded(emptySet(), emptySet(), ids, emptySet()))
    }
}

class ScoreTest {
    private val q = Question("soda", "Soda?") // "No" is on track
    private val c = Checkin(id = "c", name = "C", steps = listOf(StepConfig("q", Steps.QUESTIONS, questions = listOf(q))))
    private val days = listOf("2026-11-09", "2026-11-08", "2026-11-07", "2026-11-06")
    private fun answered(v: Boolean) = mapOf("21:30" to SlotResult(SlotResult.ANSWERED, mapOf("soda" to v)))

    @Test
    fun shareOfLoggedDaysOnTrack() {
        val results = mapOf(
            "2026-11-09" to answered(false),                                      // on track
            "2026-11-08" to answered(true),                                       // off track
            "2026-11-07" to mapOf("21:30" to SlotResult(SlotResult.RANG)),        // missed = off
            "2026-11-06" to mapOf("21:30" to SlotResult(SlotResult.SKIPPED)),     // doesn't count
        )
        assertEquals(1.0 / 3, Score.of(c, results, days)!!, 1e-9)
        assertEquals(null, Score.of(c, emptyMap(), days))
        assertEquals("33%", Score.percent(1.0 / 3))
    }

    @Test
    fun averagesIgnoreUnknowns() {
        assertEquals(0.75, Score.average(listOf(1.0, 0.5, null))!!, 1e-9)
        assertEquals(null, Score.average(listOf(null, null)))
    }

    @Test
    fun colorsRunFromRedToGreen() {
        val bad = 0xFFD2423F.toInt()
        val good = 0xFF23955E.toInt()
        assertEquals(bad, Score.color(0.0, bad, good))
        assertEquals(good, Score.color(1.0, bad, good))
        val mid = Score.hsv(Score.color(0.5, bad, good))[0]
        assertTrue("midway is yellow-ish: $mid", mid in 50f..100f)
        val h70 = Score.hsv(Score.color(0.7, bad, good))[0]
        assertTrue("closer to green the more on track", h70 > mid)
        assertEquals(bad, Score.color(-1.0, bad, good))
    }
}

class PerTimeDaysTest {
    private val ny = ZoneId.of("America/New_York")
    private fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, ny) // Nov 9 2026 = Monday
    private val track = Checkin(
        id = "t", name = "Wake",
        times = listOf(
            AlarmTime(4, 0, days = Repeat.weekly(setOf(2))),                  // Tuesday: track run
            AlarmTime(4, 45, days = Repeat.weekly(setOf(1, 3, 4, 5, 6, 7))),  // every other day
        ),
    )

    @Test
    fun eachTimeRingsOnItsOwnDays() {
        val next = AlarmScheduler.occurrences(track, at(9, 20, 0), count = 4).map {
            java.time.Instant.ofEpochMilli(it.at).atZone(ny).let { z -> "${z.dayOfWeek.name.take(3)} ${it.timeKey}" }
        }
        assertEquals(listOf("TUE 04:00", "WED 04:45", "THU 04:45", "FRI 04:45"), next)
    }

    @Test
    fun timesWithoutTheirOwnDaysFollowTheCheckin() {
        val c = Checkin(id = "c", name = "C", repeat = Repeat.weekly(Repeat.WEEKDAYS),
            times = listOf(AlarmTime(7, 0), AlarmTime(9, 0, days = Repeat.weekly(Repeat.WEEKENDS))))
        val days = AlarmScheduler.occurrences(c, at(13, 12, 0), count = 3).map { it.timeKey } // Friday noon
        assertEquals(listOf("09:00", "09:00", "07:00"), days) // Sat 9, Sun 9, Mon 7
        assertEquals(Repeat.weekly(Repeat.WEEKDAYS), c.daysFor(c.times[0]))
    }

    @Test
    fun describedPerTime() {
        assertEquals("4:00 AM Tue; 4:45 AM every day but Tue", CheckinText.times(track).replace(' ', ' '))
        assertEquals("every day but Tue", Repeat.weekly(setOf(1, 3, 4, 5, 6, 7)).describe())
        assertTrue("same time, different days aren't the same", AlarmTime(4, 0) != AlarmTime(4, 0, Repeat.weekly(setOf(2))))
    }
}
