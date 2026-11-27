package dev.gist.checkin

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** The SQLite database: everything round-trips, and an update from the preferences files keeps it all. */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private val ctx get() = TestEnv.ctx

    @After
    fun tearDown() {
        Profiles.setCurrent(ctx, Profiles.DEFAULT)
        Profiles.rename(ctx, Profiles.DEFAULT, Profiles.DEFAULT_NAME)
        Profiles.prefs(ctx, Connections.PREFS).edit().clear().commit()
        TestEnv.reset(emptyList())
        listOf("gist", "gist-links", "gist-profiles", "gist@work1", "gist-links@work1").forEach { ctx.deleteSharedPreferences(it) }
    }

    /** A fresh read from the database, not the cache. */
    private fun reread(c: Context = ctx): Store {
        Db.get(ctx).clearCaches()
        return Store(c)
    }

    private val home = Place("Home", 40.7128, -74.006, 200)

    private fun everything() = Checkin(
        id = "all", name = "Everything", enabled = false, aboutYesterday = true,
        times = listOf(
            AlarmTime(6, 45),
            AlarmTime(9, 15, days = Repeat.once(LocalDate.of(2026, 12, 24))),
            AlarmTime(18, 0, days = Repeat.weekly(setOf(1, 3, 5)), where = Trigger.AT, place = home),
        ),
        repeat = Repeat.monthly(setOf(1, 15, 31, Repeat.LAST_DAY)),
        steps = listOf(
            StepConfig("m1", Steps.MATH, mathCount = 3, mathLevel = 4),
            StepConfig("s1", Steps.SCAN, scanCode = "ABC-123", scanLabel = "Sink"),
            StepConfig("q1", Steps.QUESTIONS, questions = listOf(
                Question("a", "A?", yesIsGood = true),
                Question("w", "Weight?", type = Question.NUMBER),
                Question("n", "Notes?", type = Question.TEXT),
            )),
            StepConfig("p1", Steps.PICK_TASKS, pickCount = 5),
            StepConfig("q2", Steps.QUESTIONS, questions = listOf(Question("a", "Same id, another step?"))),
        ),
        soundUri = "content://media/alarm/7", soundTitle = "Bells",
        background = Background(Background.SLIDESHOW, 5, "Keep going"),
        events = listOf(
            Trigger(Trigger.ARRIVE, place = home, windowStart = AlarmTime(7, 0), windowEnd = AlarmTime(22, 30),
                oncePerDay = false, days = Repeat.weekly(setOf(6, 7))),
            Trigger(Trigger.RUN_FOR, minRunMinutes = 30),
            Trigger(Trigger.LEAVE, place = Place("Gym", -33.9, 151.2, 1000)),
        ),
        category = "Health", commitment = Checkin.VOICE, snoozeMinutes = 20,
        voiceSource = Checkin.VOICE_CLIP, voiceText = "Halfway!", voiceHeadphonesOnly = false,
        task = true, doneAt = 1_700_000_000_000, backlogAt = 1_690_000_000_000,
        description = "Line one\nLine two", timeCommitment = "hours", followsUp = "earlier",
        sharedWith = setOf("link1", "link2"), assignee = "link1", doneBy = "Jess",
        linkedFrom = "link9/theirs", linkedAssignee = Store.LINK_YOU, linkedGone = true, linkedDoneAt = 1_695_000_000_000,
        updatedAt = 1_700_000_000_123,
    )

    @Test
    fun everyCheckinFieldRoundTrips() {
        TestEnv.reset(emptyList())
        val list = listOf(
            everything(),
            TestEnv.checkin("plain"),
            everything().copy(id = "silent", soundUri = Checkin.SILENT, task = false, doneAt = null),
            everything().copy(id = "quiet", soundUri = Checkin.QUIET, sharedWith = emptySet(), linkedFrom = null),
            everything().copy(id = "default-sound", soundUri = null, times = emptyList(), events = emptyList(), steps = emptyList()),
        )
        TestEnv.store.checkins = list
        assertEquals(list, reread().checkins)
    }

    @Test
    fun anEmptyListIsNotTheExamples() {
        TestEnv.reset(emptyList())
        assertTrue(reread().checkins.isEmpty())
        Store(ctx).wipe()
        assertEquals("never saved: the examples", Store.DEFAULT_CHECKINS, reread().checkins)
    }

    @Test
    fun resultsRoundTripAsTheyGoToBackupsAndDevices() {
        TestEnv.reset(listOf(TestEnv.checkin("c")))
        val history = JSONObject("""{
            "2026-11-01": {"12:00": {"s": "answered", "a": {"a": true, "b": false}, "v": {"w": 182.4, "n": "felt good", "e": ""},
                                     "early": true, "w": 1762000000000, "took": {"start": 12, "steps": 40}, "future": {"x": [1, 2]}}},
            "2026-11-02": {"12:00": {"s": "rang", "a": {}, "early": false, "rangAt": 1762100000000},
                           "": {"s": "done", "a": {}, "early": false, "soft": true, "took": {"steps": 5}}}
        }""")
        TestEnv.store.putHistory("c", history, merge = false)
        assertEquals(norm(history), norm(reread().historyJson("c")))
        val r = TestEnv.store.result("2026-11-01", "c", "12:00")!!
        assertEquals(mapOf("a" to true, "b" to false), r.answers)
        assertEquals(mapOf("w" to "182.4", "n" to "felt good", "e" to ""), r.values)
        assertEquals(Took(12, 40), r.took)
        assertEquals(mapOf("a" to true, "b" to false), TestEnv.store.entry("2026-11-01"))
        // "Rang" is a result; the soft one isn't counted.
        assertEquals(setOf("12:00"), TestEnv.store.allResults("c")["2026-11-02"]!!.keys)
        TestEnv.store.clearResult("2026-11-01", "c", "12:00")
        assertNull(TestEnv.store.result("2026-11-01", "c", "12:00"))
        TestEnv.store.deleteHistory("c")
        assertEquals(0, TestEnv.store.historyJson("c").length())
    }

    @Test
    fun theWaitingListKeepsItsOrder() {
        TestEnv.reset(listOf(TestEnv.checkin("a"), TestEnv.checkin("b")))
        val list = listOf(Pending("b", "2026-11-02", "12:00"), Pending("a", "2026-11-01", "12:00"), Pending("a", "2026-11-01", "12:00", practice = true))
        TestEnv.store.pending = list
        assertEquals(list, reread().pending)
        TestEnv.store.removePending(list[0])
        assertEquals(list.drop(1), reread().pending)
    }

    @Test
    fun anUpdateFromThePreferencesFilesKeepsEverything() {
        TestEnv.reset(emptyList())
        val mine = JSONArray().put(Store.checkinJson(everything())).put(Store.checkinJson(TestEnv.checkin("plain")))
        val results = """{"2026-11-01": {"all": {"07:00": {"s": "answered", "a": {"a": true}, "v": {"w": 80}, "early": false}}}}"""
        val pending = """[{"c": "plain", "d": "2026-11-02", "t": "12:00", "p": false}]"""
        ctx.getSharedPreferences("gist-profiles", Context.MODE_PRIVATE).edit()
            .putString("profiles", """[{"id": "", "name": "Home life"}, {"id": "work1", "name": "Work"}]""")
            .putString("current", "work1")
            .commit()
        ctx.getSharedPreferences("gist@work1", Context.MODE_PRIVATE).edit()
            .putString("checkins", JSONArray().put(Store.checkinJson(TestEnv.checkin("job"))).toString())
            .putInt("pauseSeconds", 15)
            .commit()
        ctx.getSharedPreferences("gist-links@work1", Context.MODE_PRIVATE).edit().putString("myName", "Greg at work").commit()
        ctx.getSharedPreferences("gist-links", Context.MODE_PRIVATE).edit().putString("links", "[]").putString("myName", "Greg").commit()
        TestEnv.importLegacy("gist", mapOf(
            "checkins" to mine.toString(), "results" to results, "pendingList" to pending,
            "deleted" to """{"gone": 1700000000000}""", "theme" to Store.THEME_DARK, "collapsedGroups" to setOf("c:Health", "c:"),
            "orderAt" to 1_700_000_000_999L, "ringHere" to false,
        ))

        val s = reread()
        assertEquals(listOf(everything(), TestEnv.checkin("plain")), s.checkins)
        assertEquals(true, s.result("2026-11-01", "all", "07:00")!!.answers["a"])
        assertEquals("80", s.result("2026-11-01", "all", "07:00")!!.values["w"])
        assertEquals(listOf(Pending("plain", "2026-11-02", "12:00")), s.pending)
        assertEquals(mapOf("gone" to 1_700_000_000_000L), s.deleted)
        assertEquals(Store.THEME_DARK, s.theme)
        assertEquals(setOf("c:Health", "c:"), s.collapsed)
        assertEquals(1_700_000_000_999L, s.orderAt)
        assertFalse(s.ringHere)
        assertEquals("Greg", Profiles.prefs(ctx, Connections.PREFS).getString("myName", null))
        assertEquals("[]", Profiles.prefs(ctx, Connections.PREFS).getString("links", null))
        // The other profile, its name, and that it was the one you were on
        assertEquals(listOf("Home life", "Work"), Profiles.all(ctx).map { it.name })
        assertEquals("work1", Profiles.current(ctx))
        val work = Profiles.scoped(ctx, "work1")
        assertEquals(listOf("job"), reread(work).checkins.map { it.id })
        assertEquals(15, Store(work).pauseSeconds)
        assertEquals("Greg at work", Profiles.prefs(work, Connections.PREFS).getString("myName", null))
        // And the old files are gone
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        listOf("gist", "gist-links", "gist-profiles", "gist@work1", "gist-links@work1").forEach {
            assertFalse("$it.xml deleted", File(dir, "$it.xml").exists())
        }
    }

    @Test
    fun aPartThatCantBeReadIsLeftInItsFile() {
        TestEnv.reset(emptyList())
        TestEnv.importLegacy("gist", mapOf(
            "checkins" to JSONArray().put(Store.checkinJson(TestEnv.checkin("kept"))).toString(),
            "results" to "{ not json",
        ))
        assertEquals(listOf("kept"), reread().checkins.map { it.id })
        assertTrue("the file stays for a later version to try again",
            File(File(ctx.applicationInfo.dataDir, "shared_prefs"), "gist.xml").exists())
    }

    @Test
    fun settingsKeepTheirTypes() {
        val p = Profiles.prefs(ctx, "test-types")
        p.edit().clear().putInt("i", 7).putLong("l", 1L shl 40).putBoolean("b", true).putFloat("f", 1.5f)
            .putString("s", "text").putStringSet("set", mutableSetOf("x", "y")).commit()
        Db.get(ctx).clearCaches()
        val q = Profiles.prefs(ctx, "test-types")
        assertEquals(7, q.getInt("i", 0))
        assertEquals(1L shl 40, q.getLong("l", 0))
        assertTrue(q.getBoolean("b", false))
        assertEquals(1.5f, q.getFloat("f", 0f))
        assertEquals("text", q.getString("s", null))
        assertEquals(setOf("x", "y"), q.getStringSet("set", null))
        assertEquals(6, q.all.size)
        q.edit().remove("s").commit()
        Db.get(ctx).clearCaches()
        assertFalse(Profiles.prefs(ctx, "test-types").contains("s"))
        q.edit().clear().commit()
    }

    /** JSON as sorted maps and lists, to compare by content. */
    private fun norm(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().toList().sorted().associateWith { norm(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { norm(v.get(it)) }
        is Number -> java.math.BigDecimal(v.toString()).stripTrailingZeros()
        else -> v
    }
}
