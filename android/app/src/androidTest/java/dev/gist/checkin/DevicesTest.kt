package dev.gist.checkin

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Your own devices kept in step, and read receipts / cleaning up. The test plays your
 * tablet (another device) through the protocol, in a plain folder.
 */
@RunWith(AndroidJUnit4::class)
class DevicesTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private lateinit var dir: File
    private val devices get() = Devices(TestEnv.ctx) { FileFolder(File(it)) }
    private val store get() = Store(TestEnv.ctx)
    private val tab = "tablet"
    private val tabKeys = LinkCrypto.newKeyPair()
    private var tabSeq = 0
    private var tabSent = mapOf<String, String>()

    @Before
    fun setUp() {
        clear()
        dir = File(TestEnv.ctx.cacheDir, "devices-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        clear()
        dir.deleteRecursively()
        TestEnv.reset(emptyList())
    }

    private fun clear() {
        TestEnv.ctx.getSharedPreferences("gist-devices", Context.MODE_PRIVATE).edit().clear().commit()
        store.tracking = false
        store.ringHere = true
    }

    private val f get() = FileFolder(dir)
    private fun group() = JSONObject(String(File(dir, Devices.MARKER).readBytes())).getString("group")

    /** The tablet joins the devices this phone set up, and gets the key once the phone lets it in. */
    private fun tabletJoins(): ByteArray {
        val inv = devices.invitation()!!.first
        val now = System.currentTimeMillis()
        LinkProtocol.writeHello(f, LinkHello(tab, "Tablet", tabKeys.first,
            LinkInvites.proof(inv.token, group(), tab, tabKeys.first, now), now))
        devices.sync()
        val phone = LinkProtocol.hello(f, devices.side)!!
        val name = "from-${devices.side}/key-$tab.gist"
        return LinkCrypto.open(LinkCrypto.sharedKey(tabKeys.second, phone.key, group()), "${group()}/$name", f.read(name)!!)!!
    }

    /** The tablet publishes [items] (everything it has now). */
    private fun tabletHas(key: ByteArray, items: Map<String, String>) {
        val (set, removed) = LinkProtocol.diff(tabSent, items)
        tabSeq++
        LinkProtocol.publish(f, group(), tab, key, tabSeq, set, removed, items)
        tabSent = items
    }

    private fun phoneHas(key: ByteArray) = LinkProtocol.receive(f, group(), devices.side, key, 0, emptyMap()).second

    private fun json(c: Checkin) = store.checkinToJson(c).toString()

    @Test
    fun aJoiningDeviceIsLetInAndGetsEverything() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Daily")))
        devices.setUp(dir.path)
        assertTrue(devices.ready)
        assertTrue(store.tracking)
        val key = tabletJoins()
        assertEquals(32, key.size)
        devices.sync()
        val mine = phoneHas(key)
        assertEquals("Daily", JSONObject(mine.getValue("c:c")).getString("name"))
        assertEquals(listOf("Tablet"), devices.others().map { it.second })
        assertEquals(LinkCrypto.code(tabKeys.first, LinkProtocol.hello(f, devices.side)!!.key), devices.others().single().third)
    }

    @Test
    fun joiningTakesInWhatTheOtherDeviceHas() {
        // The tablet set up; this phone joins.
        TestEnv.reset(listOf(TestEnv.checkin("p", "Phone's own")))
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        f.write(Devices.MARKER, JSONObject().put("format", Devices.FORMAT).put("group", "g1").toString().toByteArray())
        LinkProtocol.writeHello(f, LinkHello(tab, "Tablet", tabKeys.first))
        tabletHas(key, mapOf("c:t" to json(TestEnv.checkin("t", "Tablet's").copy(updatedAt = 5L))))
        assertNull(devices.join(dir.path, LinkInvite('d', LinkInvites.fingerprint(tabKeys.first), LinkInvites.newToken())))
        assertFalse("waiting to be let in", devices.ready)
        devices.sync()
        assertFalse(devices.ready)
        // The tablet lets it in.
        val phone = LinkProtocol.hello(f, devices.side)!!
        val name = "from-$tab/key-${devices.side}.gist"
        f.write(name, LinkCrypto.seal(LinkCrypto.sharedKey(tabKeys.second, phone.key, "g1"), "g1/$name", key))
        assertTrue(devices.sync())
        assertTrue(devices.ready)
        assertEquals(setOf("p", "t"), store.checkins.map { it.id }.toSet())
    }

    @Test
    fun theLaterEditWinsAndDeletionsGoEverywhere() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha"), TestEnv.checkin("b", "Beta")))
        devices.setUp(dir.path)
        // Edits are stamped once syncing's on.
        store.updateCheckin("a") { it.copy(name = "Alpha 2") }
        val stamped = store.checkin("a")!!.updatedAt!!
        val key = tabletJoins()
        // An older edit from the tablet loses; a newer one wins.
        tabletHas(key, mapOf("c:a" to json(store.checkin("a")!!.copy(name = "Old", updatedAt = stamped - 1000))))
        devices.sync()
        assertEquals("Alpha 2", store.checkin("a")!!.name)
        tabletHas(key, mapOf("c:a" to json(store.checkin("a")!!.copy(name = "Alpha 3", updatedAt = stamped + 1000))))
        devices.sync()
        assertEquals("Alpha 3", store.checkin("a")!!.name)
        // Deleted on the tablet: gone here too.
        tabletHas(key, mapOf("d:b" to JSONObject().put("at", System.currentTimeMillis() + 1000).toString()))
        devices.sync()
        assertNull(store.checkin("b"))
        // Deleted here: the tablet hears.
        AlarmScheduler.cancel(TestEnv.ctx, "a")
        store.checkins = store.checkins.filter { it.id != "a" }
        devices.sync()
        assertNotNull(phoneHas(key)["d:a"])
    }

    @Test
    fun answeredOnAnotherDeviceStopsWaitingHere() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Daily")))
        devices.setUp(dir.path)
        val key = tabletJoins()
        val day = Days.today()
        store.markRang(day, "c", "12:00")
        store.addPending(Pending("c", day, "12:00"))
        // The tablet answered it.
        val answered = JSONObject().put("c", JSONObject().put("12:00",
            JSONObject().put("s", SlotResult.DONE).put("a", JSONObject()).put("early", false).put("w", System.currentTimeMillis())))
        tabletHas(key, mapOf("r:$day" to answered.toString()))
        assertTrue(devices.sync())
        assertEquals(SlotResult.DONE, store.result(day, "c", "12:00")!!.status)
        assertTrue("not waiting here any more", store.pending.none { it.checkinId == "c" })
        // An older "rang" from elsewhere doesn't undo an answer.
        tabletHas(key, mapOf("r:$day" to JSONObject().put("c", JSONObject().put("12:00",
            JSONObject().put("s", SlotResult.RANG).put("a", JSONObject()).put("w", System.currentTimeMillis() + 5000))).toString()))
        devices.sync()
        assertEquals(SlotResult.DONE, store.result(day, "c", "12:00")!!.status)
    }

    @Test
    fun readChangesAreCleanedOut() {
        val key = LinkCrypto.hkdf("s".toByteArray(), "l".toByteArray(), "i".toByteArray(), 32)
        for (n in 1..3) LinkProtocol.publish(f, "l", "me", key, n, mapOf("x" to """{"n":$n}"""), emptyList(), mapOf("x" to """{"n":$n}"""))
        assertEquals("nobody's read anything", 0, LinkProtocol.cleanUp(f, "me", listOf("you")))
        LinkProtocol.writeReceipts(f, "you", mapOf("me" to 2))
        assertEquals(2, LinkProtocol.readUpTo(f, "you", "me"))
        assertEquals(2, LinkProtocol.cleanUp(f, "me", listOf("you")))
        assertEquals(listOf("000003.gist", "snapshot.gist"), f.names("from-me").filter { !it.startsWith(".") && it.endsWith(".gist") }.sorted())
        // Someone new (or far behind) still catches up, from the snapshot.
        val (seq, items) = LinkProtocol.receive(f, "l", "me", key, 0, emptyMap())
        assertEquals(3, seq)
        assertEquals(3, JSONObject(items.getValue("x")).getInt("n"))
        // Two readers: only what both have read goes.
        LinkProtocol.publish(f, "l", "me", key, 4, mapOf("x" to """{"n":4}"""), emptyList(), mapOf("x" to """{"n":4}"""))
        LinkProtocol.writeReceipts(f, "you", mapOf("me" to 4))
        LinkProtocol.writeReceipts(f, "other", mapOf("me" to 3))
        assertEquals(1, LinkProtocol.cleanUp(f, "me", listOf("you", "other")))
        assertTrue(f.names("from-me").contains("000004.gist"))
    }

    @Test
    fun whenThisDeviceDoesntRingItWaitsAndCanBeDismissed() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Daily")))
        store.ringHere = false
        val day = Days.today()
        store.markRang(day, "c", "12:00")
        store.addPending(Pending("c", day, "12:00"))
        TestEnv.store.expandedCheckin = "c"
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(tagged("waiting:c")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("waiting-dismiss:c")).perform(scrollTo(), click())
        }
        assertEquals(SlotResult.DISMISSED, store.result(day, "c", "12:00")!!.status)
        assertTrue(store.pending.none { it.checkinId == "c" })
    }

    @Test
    fun theConnectionsPageSetsRingingForThisDevice() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Daily")))
        ActivityScenario.launch<ConnectionsActivity>(ConnectionsActivity.intent(TestEnv.ctx)).use {
            onView(tagged("devices-setup")).check(matches(isDisplayed()))
            onView(tagged("ring-here")).perform(scrollTo(), click())
            onView(withText(org.hamcrest.Matchers.startsWith("Check-ins don't ring here"))).perform(scrollTo())
                .check(matches(isDisplayed()))
        }
        assertFalse(store.ringHere)
    }

    private fun setting(v: String, at: Long) = JSONObject().put("v", v).put("at", at).toString()
    private fun order(ids: List<String>, at: Long) = JSONObject().put("ids", org.json.JSONArray(ids)).put("at", at).toString()
    private fun ids(json: String) = JSONObject(json).getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } }

    @Test
    fun theOrderAndSettingsGoToDevicesThatJoin() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha"), TestEnv.checkin("b", "Beta")))
        store.theme = Store.THEME_DARK
        store.setSortFor(Store.TAB_TASKS, Grouping.SORTS.last())
        store.collapsed = setOf("Home")
        store.ringHere = false
        store.startTab = Store.TAB_TASKS
        devices.setUp(dir.path)
        val key = tabletJoins()
        devices.sync()
        val mine = phoneHas(key)
        assertEquals(listOf("a", "b"), ids(mine.getValue("o:list")))
        assertEquals(Store.THEME_DARK.toString(), JSONObject(mine.getValue("s:theme")).getString("v"))
        assertEquals(JSONObject.quote(Grouping.SORTS.last()), JSONObject(mine.getValue("s:sort.tasks")).getString("v"))
        assertEquals("[\"Home\"]", JSONObject(mine.getValue("s:collapsedGroups")).getString("v"))
        assertFalse("per device", mine.keys.any { it.contains("ringHere") || it.contains("startTab") || it.contains("expanded") })
        assertFalse("not changed: not sent", mine.containsKey("s:pauseSeconds"))
    }

    @Test
    fun theLaterReorderWins() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha"), TestEnv.checkin("b", "Beta"), TestEnv.checkin("c", "Gamma")))
        devices.setUp(dir.path)
        val key = tabletJoins()
        // Reordered here: sent.
        store.reorder(listOf("c", "a", "b"))
        val at = store.orderAt
        devices.sync()
        assertEquals(listOf("c", "a", "b"), ids(phoneHas(key).getValue("o:list")))
        // An edit or a new one isn't a reorder.
        store.updateCheckin("a") { it.copy(name = "Alpha 2") }
        store.checkins = store.checkins + TestEnv.checkin("n", "New")
        assertEquals(at, store.orderAt)
        // An older order from the tablet: ignored.
        tabletHas(key, mapOf("o:list" to order(listOf("a", "b", "c"), at - 1000)))
        devices.sync()
        assertEquals(listOf("c", "a", "b", "n"), store.checkins.map { it.id })
        // A later one, with a check-in this phone hasn't heard of yet: taken; the rest keep their order after.
        tabletHas(key, mapOf(
            "o:list" to order(listOf("t", "b", "c", "a"), at + 1000),
            "c:t" to json(TestEnv.checkin("t", "Tablet's").copy(updatedAt = at + 1000)),
        ))
        assertTrue(devices.sync())
        assertEquals(listOf("t", "b", "c", "a", "n"), store.checkins.map { it.id })
        assertEquals("theirs, not now", at + 1000, store.orderAt)
        assertEquals("Alpha 2", store.checkins.first { it.id == "a" }.name)
    }

    @Test
    fun theLaterChangeOfEachSettingWins() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha")))
        devices.setUp(dir.path)
        val key = tabletJoins()
        store.theme = Store.THEME_LIGHT
        store.pauseSeconds = Store.PAUSE_CHOICES.last()
        val now = System.currentTimeMillis()
        // The tablet changed the theme earlier (loses) and the sort and folded groups later (win).
        // Ring on this device, and anything not shared, is never taken.
        tabletHas(key, mapOf(
            "s:theme" to setting(Store.THEME_DARK.toString(), now - 60_000),
            "s:sort" to setting(JSONObject.quote(Grouping.SORTS.last()), now + 60_000),
            "s:collapsedGroups" to setting("[\"Work\",\"Home\"]", now + 60_000),
            "s:ringHere" to setting("false", now + 60_000),
            "s:startTab" to setting(JSONObject.quote(Store.TAB_BACKLOG), now + 60_000),
        ))
        assertTrue(devices.sync())
        assertEquals(Store.THEME_LIGHT, store.theme)
        assertEquals(Store.PAUSE_CHOICES.last(), store.pauseSeconds)
        assertEquals(Grouping.SORTS.last(), store.sortFor(Store.TAB_CHECKINS))
        assertEquals(setOf("Work", "Home"), store.collapsed)
        assertTrue(store.ringHere)
        assertEquals(Store.TAB_CHECKINS, store.startTab)
        // Taken in, not changed here: sent on with the tablet's time, not a new one.
        devices.sync()
        assertEquals(now + 60_000, JSONObject(phoneHas(key).getValue("s:sort")).getLong("at"))
        // Nothing new: nothing changes.
        assertFalse(devices.sync())
    }

    @Test
    fun aJoiningDeviceTakesTheOthersOrderAndSettings() {
        TestEnv.reset(listOf(TestEnv.checkin("p", "Phone's own"), TestEnv.checkin("t", "Tablet's")))
        store.theme = Store.THEME_DARK // set before joining: not sent, the others' wins
        store.reorder(listOf("p", "t"))
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        f.write(Devices.MARKER, JSONObject().put("format", Devices.FORMAT).put("group", "g1").toString().toByteArray())
        LinkProtocol.writeHello(f, LinkHello(tab, "Tablet", tabKeys.first))
        tabletHas(key, mapOf(
            "o:list" to order(listOf("t", "p"), 5L),
            "s:theme" to setting(Store.THEME_LIGHT.toString(), 5L),
        ))
        assertNull(devices.join(dir.path, LinkInvite('d', LinkInvites.fingerprint(tabKeys.first), LinkInvites.newToken())))
        val phone = LinkProtocol.hello(f, devices.side)!!
        val name = "from-$tab/key-${devices.side}.gist"
        f.write(name, LinkCrypto.seal(LinkCrypto.sharedKey(tabKeys.second, phone.key, "g1"), "g1/$name", key))
        assertTrue(devices.sync())
        assertEquals(listOf("t", "p"), store.checkins.map { it.id })
        assertEquals(Store.THEME_LIGHT, store.theme)
    }
}
