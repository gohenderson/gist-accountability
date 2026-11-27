package dev.gist.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** "Ring in…" and "Ring again in…": one-off times a while from now. */
class QuickTimeTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun at(y: Int, mo: Int, d: Int, h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(y, mo, d, h, m, s, 0, zone)

    @Test
    fun aWhileFromNowToTheNearestMinute() {
        val now = at(2026, 11, 26, 14, 7, 20)
        assertEquals(AlarmTime(14, 37, days = Repeat.once(LocalDate.of(2026, 11, 26))), QuickTime.at(30, now))
        assertEquals(AlarmTime(14, 38, days = Repeat.once(LocalDate.of(2026, 11, 26))), QuickTime.at(30, now.withSecond(40)))
    }

    @Test
    fun pastMidnightIsTomorrow() {
        assertEquals(AlarmTime(0, 20, days = Repeat.once(LocalDate.of(2026, 11, 27))), QuickTime.at(45, at(2026, 11, 26, 23, 35)))
    }

    @Test
    fun addingClearsPassedOneOffTimesOnly() {
        val now = at(2026, 11, 26, 14, 0)
        val today = LocalDate.of(2026, 11, 26)
        val daily = AlarmTime(7, 0)
        val passed = AlarmTime(13, 30, days = Repeat.once(today))
        val later = AlarmTime(18, 0, days = Repeat.once(today))
        val c = Checkin(id = "turkey", name = "Baste", times = listOf(daily, passed, later), enabled = false)
        val (changed, t) = QuickTime.added(c, 30, now)
        assertEquals(AlarmTime(14, 30, days = Repeat.once(today)), t)
        assertEquals(listOf(daily, t, later), changed.times)
        assertTrue("turned on", changed.enabled)
    }

    @Test
    fun noSecondTimeWhenItAlreadyRingsThen() {
        val now = at(2026, 11, 26, 6, 30)
        val c = Checkin(id = "wake", name = "Wake", times = listOf(AlarmTime(7, 0)), enabled = true)
        val (changed, t) = QuickTime.added(c, 30, now)
        assertNull(t)
        assertEquals(c.times, changed.times)
    }

    @Test
    fun aRingWasOneOffWhenItsTimeWasOnce() {
        val now = at(2026, 11, 26, 14, 31)
        val today = LocalDate.of(2026, 11, 26)
        val c = Checkin(id = "turkey", name = "Baste", times = listOf(AlarmTime(12, 0), AlarmTime(14, 30, days = Repeat.once(today))))
        assertTrue(QuickTime.wasOneOff(c, "14:30", now))
        assertFalse("a daily time", QuickTime.wasOneOff(c, "12:00", now))
        assertFalse("not yet", QuickTime.wasOneOff(c, "14:30", at(2026, 11, 26, 14, 0)))
        assertFalse("days ago", QuickTime.wasOneOff(c, "14:30", at(2026, 11, 28, 14, 31)))
    }

    @Test
    fun saysHowLong() {
        assertEquals("30 min", QuickTime.text(30))
        assertEquals("1 hour", QuickTime.text(60))
        assertEquals("2 hours", QuickTime.text(120))
        assertEquals("1 h 30 min", QuickTime.text(90))
    }
}
