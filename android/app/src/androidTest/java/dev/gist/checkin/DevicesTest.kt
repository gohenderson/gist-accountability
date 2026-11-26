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
        Backgrounds.deleteAll(TestEnv.ctx)
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
        return Devices.keyOf(LinkCrypto.open(LinkCrypto.sharedKey(tabKeys.second, phone.key, group()), "${group()}/$name", f.read(name)!!)!!)!!
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

    // ------------------------------------------------------------ photos, videos and clips

    private val ctx get() = TestEnv.ctx
    private fun sha(b: ByteArray) = DeviceMedia.sha256(b)
    private fun media(files: Map<String, ByteArray>, at: Long) =
        JSONObject().put("files", JSONObject(files.mapValues { sha(it.value) } as Map<*, *>)).put("at", at).toString()

    /** The tablet puts [bytes] in the folder, as the devices share files. */
    private fun tabletUploads(key: ByteArray, bytes: ByteArray) {
        val blob = DeviceMedia.blobName(sha(bytes))
        f.write("from-$tab/$blob", LinkCrypto.seal(key, "${group()}/$blob", bytes))
    }

    @Test
    fun photosVideosAndClipsGoToOtherDevices() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha")))
        val photo = "photo bytes".toByteArray()
        val voice = "voice bytes".toByteArray()
        Backgrounds.photoFile(ctx, "a", 0).writeBytes(photo)
        Backgrounds.voiceFile(ctx, "a").writeBytes(voice)
        File(Backgrounds.dir(ctx, "a"), "video.tmp").writeBytes("half copied".toByteArray())
        devices.setUp(dir.path)
        val key = tabletJoins()
        devices.sync()
        val m = JSONObject(phoneHas(key).getValue("m:a"))
        val files = m.getJSONObject("files")
        assertEquals(setOf("photo-0.jpg", "voice.m4a"), files.keys().asSequence().toSet())
        assertEquals(sha(photo), files.getString("photo-0.jpg"))
        // The files are in the folder once each, encrypted.
        for (b in listOf(photo, voice)) {
            val blob = DeviceMedia.blobName(sha(b))
            val sealed = f.read("from-${devices.side}/$blob")!!
            assertFalse(String(sealed, Charsets.ISO_8859_1).contains(String(b)))
            assertEquals(String(b), String(LinkCrypto.open(key, "${group()}/$blob", sealed)!!))
        }
        // Nothing changed: not stamped again or sent twice.
        val at = m.getLong("at")
        devices.sync()
        assertEquals(at, JSONObject(phoneHas(key).getValue("m:a")).getLong("at"))
    }

    @Test
    fun mediaFromAnotherDeviceWaitsForItsFiles() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha")))
        devices.setUp(dir.path)
        val key = tabletJoins()
        val video = ByteArray(200_000) { (it % 251).toByte() }
        val now = System.currentTimeMillis()
        tabletHas(key, mapOf("m:a" to media(mapOf("video.mp4" to video), now + 1000)))
        devices.sync()
        assertFalse("not in the folder yet", Backgrounds.hasVideo(ctx, "a"))
        // A file that doesn't match its hash: not taken.
        val blob = DeviceMedia.blobName(sha(video))
        f.write("from-$tab/$blob", LinkCrypto.seal(key, "${group()}/$blob", "not it".toByteArray()))
        devices.sync()
        assertFalse(Backgrounds.hasVideo(ctx, "a"))
        // The real one arrives.
        tabletUploads(key, video)
        assertTrue(devices.sync())
        assertTrue(video.contentEquals(Backgrounds.videoFile(ctx, "a").readBytes()))
        assertEquals("taken in, sent on with the tablet's time", now + 1000, JSONObject(phoneHas(key).getValue("m:a")).getLong("at"))
        // Removed on the tablet later: removed here.
        tabletHas(key, mapOf("m:a" to media(emptyMap(), now + 2000)))
        assertTrue(devices.sync())
        assertFalse(Backgrounds.hasVideo(ctx, "a"))
    }

    @Test
    fun theLaterMediaChangeWins() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha")))
        val mine = "mine".toByteArray()
        Backgrounds.photoFile(ctx, "a", 0).writeBytes(mine)
        devices.setUp(dir.path)
        val key = tabletJoins()
        devices.sync()
        val at = JSONObject(phoneHas(key).getValue("m:a")).getLong("at")
        // Older on the tablet: ignored.
        val older = "older".toByteArray()
        tabletUploads(key, older)
        tabletHas(key, mapOf("m:a" to media(mapOf("photo-0.jpg" to older, "photo-1.jpg" to older), at - 1000)))
        devices.sync()
        assertEquals(listOf("mine"), Backgrounds.photos(ctx, "a").map { String(it.readBytes()) })
        // Changed here: stamped, and the new file goes up.
        val newer = "newer".toByteArray()
        Backgrounds.photoFile(ctx, "a", 0).writeBytes(newer)
        devices.sync()
        val m = JSONObject(phoneHas(key).getValue("m:a"))
        assertTrue(m.getLong("at") > at)
        assertEquals(sha(newer), m.getJSONObject("files").getString("photo-0.jpg"))
        assertNotNull(f.read("from-${devices.side}/${DeviceMedia.blobName(sha(newer))}"))
    }

    @Test
    fun filesNothingUsesAreCleanedUpOnceRead() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha")))
        val first = "first".toByteArray()
        Backgrounds.photoFile(ctx, "a", 0).writeBytes(first)
        devices.setUp(dir.path)
        tabletJoins()
        devices.sync()
        val oldBlob = "from-${devices.side}/${DeviceMedia.blobName(sha(first))}"
        assertNotNull(f.read(oldBlob))
        Backgrounds.photoFile(ctx, "a", 0).writeBytes("second".toByteArray())
        devices.sync()
        assertNotNull("the tablet hasn't read the change yet", f.read(oldBlob))
        val seq = TestEnv.ctx.getSharedPreferences("gist-devices", Context.MODE_PRIVATE).getInt("sentSeq", 0)
        LinkProtocol.writeReceipts(f, tab, mapOf(devices.side to seq))
        devices.sync()
        assertNull(f.read(oldBlob))
        assertNotNull(f.read("from-${devices.side}/${DeviceMedia.blobName(sha("second".toByteArray()))}"))
    }

    @Test
    fun aCheckinDeletedElsewhereTakesItsMediaWithIt() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha"), TestEnv.checkin("b", "Beta")))
        Backgrounds.photoFile(ctx, "a", 0).writeBytes("photo".toByteArray())
        devices.setUp(dir.path)
        val key = tabletJoins()
        devices.sync()
        tabletHas(key, mapOf("d:a" to JSONObject().put("at", System.currentTimeMillis() + 1000).toString()))
        assertTrue(devices.sync())
        assertTrue(Backgrounds.photos(ctx, "a").isEmpty())
        assertFalse(phoneHas(key).containsKey("m:a"))
    }

    // ------------------------------------------------------------ leaving and removing

    private val devPrefs get() = TestEnv.ctx.getSharedPreferences("gist-devices", Context.MODE_PRIVATE)

    /** Another device [s] joins with an invite from this phone and is let in: the key it gets. */
    private fun deviceJoins(s: String, keys: Pair<String, String>): ByteArray {
        val inv = devices.invitation()!!.first
        val now = System.currentTimeMillis()
        LinkProtocol.writeHello(f, LinkHello(s, s, keys.first, LinkInvites.proof(inv.token, group(), s, keys.first, now), now))
        devices.sync()
        val phone = LinkProtocol.hello(f, devices.side)!!
        val name = "from-${devices.side}/key-$s.gist"
        return Devices.keyOf(LinkCrypto.open(LinkCrypto.sharedKey(keys.second, phone.key, group()), "${group()}/$name", f.read(name)!!)!!)!!
    }

    /** This phone's latest snapshot, opened with [key] (null if it can't be). */
    private fun snapshotWith(key: ByteArray): ByteArray? {
        val name = "from-${devices.side}/snapshot.gist"
        return LinkCrypto.open(key, "${group()}/$name", f.read(name)!!)
    }

    /** The new key sealed for [s] by [from], as [s] opens it: (generation, key, removed). */
    private fun rekeyFor(s: String, keys: Pair<String, String>, from: String): Triple<Int, ByteArray, Set<String>>? {
        val name = "from-$from/rekey-$s.gist"
        val sealed = f.read(name) ?: return null
        val h = LinkProtocol.hello(f, from)!!
        val o = JSONObject(String(LinkCrypto.open(LinkCrypto.sharedKey(keys.second, h.key, group()), "${group()}/$name", sealed)!!))
        val rm = o.getJSONArray("removed").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        return Triple(o.getInt("epoch"), java.util.Base64.getDecoder().decode(o.getString("key")), rm)
    }

    @Test
    fun leavingTakesThisDevicesFilesOutOfTheFolder() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha")))
        devices.setUp(dir.path)
        tabletJoins()
        devices.sync()
        val me = devices.side
        assertTrue(File(dir, "from-$me").exists())
        devices.leave()
        assertFalse(File(dir, "from-$me").exists())
        assertNull(devices.folder)
        assertFalse(store.tracking)
        assertEquals("your data stays", listOf("a"), store.checkins.map { it.id })
    }

    @Test
    fun aDeviceThatLeftIsNoLongerListedOrWaitedFor() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha")))
        devices.setUp(dir.path)
        val key = tabletJoins()
        assertEquals(listOf(tab), devices.others().map { it.first })
        // The tablet left: its files are gone from the folder (an empty folder may linger).
        File(dir, "from-$tab").listFiles()!!.forEach { it.delete() }
        assertTrue(devices.others().isEmpty())
        store.updateCheckin("a") { it.copy(name = "Alpha 2") }
        devices.sync()
        assertTrue("still syncing", JSONObject(phoneHas(key).getValue("c:a")).toString().contains("Alpha 2"))
    }

    @Test
    fun removingADeviceSwitchesTheOthersToANewKey() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha")))
        devices.setUp(dir.path)
        val old = tabletJoins()
        val lostKeys = LinkCrypto.newKeyPair()
        assertTrue(old.contentEquals(deviceJoins("lost", lostKeys)))
        assertEquals(setOf(tab, "lost"), devices.others().map { it.first }.toSet())

        devices.remove("lost")
        assertFalse("its files leave the folder", File(dir, "from-lost").exists())
        assertEquals(listOf(tab), devices.others().map { it.first })
        val (gen, key, removed) = rekeyFor(tab, tabKeys, devices.side)!!
        assertEquals(1, gen)
        assertFalse(key.contentEquals(old))
        assertEquals(setOf("lost"), removed)
        assertNull("none for the removed one", f.read("from-${devices.side}/rekey-lost.gist"))

        // Everything is published again with the new key; the old one can't read it.
        devices.sync()
        assertTrue(phoneHas(key).containsKey("c:a"))
        assertNull(snapshotWith(old))

        // It comes back (its copy of the folder syncs again): still not listed, let in or read.
        val inv = devices.invitation()!!.first
        val now = System.currentTimeMillis()
        LinkProtocol.writeHello(f, LinkHello("lost", "lost", lostKeys.first, LinkInvites.proof(inv.token, group(), "lost", lostKeys.first, now), now))
        LinkProtocol.publish(f, group(), "lost", old, 1, mapOf("c:z" to json(TestEnv.checkin("z", "Sneaky"))), emptyList(),
            mapOf("c:z" to json(TestEnv.checkin("z", "Sneaky"))))
        devices.sync()
        assertEquals(listOf(tab), devices.others().map { it.first })
        assertNull(f.read("from-${devices.side}/key-lost.gist"))
        assertNull(store.checkins.firstOrNull { it.id == "z" })
    }

    @Test
    fun aNewKeyFromAnotherDeviceIsTaken() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha")))
        devices.setUp(dir.path)
        val old = tabletJoins()
        deviceJoins("lost", LinkCrypto.newKeyPair())
        devices.sync()
        // The tablet removes "lost": a new key for this phone, and its own items with it.
        val key = ByteArray(32) { 9 }
        val phone = LinkProtocol.hello(f, devices.side)!!
        val name = "from-$tab/rekey-${devices.side}.gist"
        val body = JSONObject().put("epoch", 1).put("key", java.util.Base64.getEncoder().encodeToString(key))
            .put("removed", org.json.JSONArray(listOf("lost")))
        f.write(name, LinkCrypto.seal(LinkCrypto.sharedKey(tabKeys.second, phone.key, group()), "${group()}/$name", body.toString().toByteArray()))
        tabletHas(key, mapOf("c:t" to json(TestEnv.checkin("t", "From the tablet").copy(updatedAt = 5))))
        assertTrue(devices.sync())
        assertEquals("From the tablet", store.checkins.first { it.id == "t" }.name)
        assertEquals(listOf(tab), devices.others().map { it.first })
        assertEquals(1, devPrefs.getInt("epoch", 0))
        assertTrue("published again with it", phoneHas(key).containsKey("c:a"))
        assertNull(snapshotWith(old))
    }

    @Test
    fun twoDevicesRemovingAtOnceEndWithAKeyWithoutEither() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha")))
        devices.setUp(dir.path)
        tabletJoins()
        deviceJoins("lostone", LinkCrypto.newKeyPair())
        deviceJoins("losttwo", LinkCrypto.newKeyPair())
        // The tablet removes one (and seals its key for this phone); this phone removes the other.
        val phone = LinkProtocol.hello(f, devices.side)!!
        val name = "from-$tab/rekey-${devices.side}.gist"
        val body = JSONObject().put("epoch", 1).put("key", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 }))
            .put("removed", org.json.JSONArray(listOf("losttwo")))
        f.write(name, LinkCrypto.seal(LinkCrypto.sharedKey(tabKeys.second, phone.key, group()), "${group()}/$name", body.toString().toByteArray()))
        devices.remove("lostone")
        devices.sync()
        // Whichever key won, the one in use now leaves both out: the tablet gets it.
        val (gen, _, removed) = rekeyFor(tab, tabKeys, devices.side)!!
        assertEquals(2, gen)
        assertEquals(setOf("lostone", "losttwo"), removed)
        assertEquals(2, devPrefs.getInt("epoch", 0))
        assertEquals(listOf(tab), devices.others().map { it.first })
    }
}
