package dev.gist.checkin

import android.content.Context
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
 * Connections on your own devices: each has one home device that reads and publishes for it;
 * the others see it (and what the other person shares) through device sync, and can take it
 * over. The test plays your tablet (through the devices folder) and Jess (through the
 * connection's folder).
 */
@RunWith(AndroidJUnit4::class)
class ConnectionsOnDevicesTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private lateinit var devDir: File
    private lateinit var linkDir: File
    private val ctx get() = TestEnv.ctx
    private val devices get() = Devices(ctx) { FileFolder(File(it)) }
    private val links get() = Connections(ctx) { FileFolder(File(it)) }
    private val store get() = Store(ctx)
    private val dev get() = FileFolder(devDir)
    private val lf get() = FileFolder(linkDir)
    private val tab = "tablet"
    private val tabKeys = LinkCrypto.newKeyPair()
    private var tabSeq = 0
    private var tabSent = mapOf<String, String>()
    private val jessKeys = LinkCrypto.newKeyPair()

    @Before
    fun setUp() {
        clear()
        devDir = File(ctx.cacheDir, "devices-${System.nanoTime()}").apply { mkdirs() }
        linkDir = File(ctx.cacheDir, "link-${System.nanoTime()}").apply { mkdirs() }
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha")))
        links.myName = "Alex"
    }

    @After
    fun tearDown() {
        clear()
        devDir.deleteRecursively()
        linkDir.deleteRecursively()
        TestEnv.reset(emptyList())
    }

    private fun clear() {
        ctx.getSharedPreferences("gist-devices", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("gist-links", Context.MODE_PRIVATE).edit().clear().commit()
        store.tracking = false
    }

    private fun group() = JSONObject(String(File(devDir, Devices.MARKER).readBytes())).getString("group")

    /** This phone sets up your devices; the tablet joins and is let in. The devices' key. */
    private fun withTablet(): ByteArray {
        devices.setUp(devDir.path)
        val inv = devices.invitation()!!.first
        val now = System.currentTimeMillis()
        LinkProtocol.writeHello(dev, LinkHello(tab, "Tablet", tabKeys.first, LinkInvites.proof(inv.token, group(), tab, tabKeys.first, now), now))
        devices.sync()
        val phone = LinkProtocol.hello(dev, devices.side)!!
        val name = "from-${devices.side}/key-$tab.gist"
        return Devices.keyOf(LinkCrypto.open(LinkCrypto.sharedKey(tabKeys.second, phone.key, group()), "${group()}/$name", dev.read(name)!!)!!)!!
    }

    private fun tabletHas(key: ByteArray, items: Map<String, String>) {
        val (set, removed) = LinkProtocol.diff(tabSent, items)
        tabSeq++
        LinkProtocol.publish(dev, group(), tab, key, tabSeq, set, removed, items)
        tabSent = items
    }

    private fun phoneHas(key: ByteArray) = LinkProtocol.receive(dev, group(), devices.side, key, 0, emptyMap()).second

    /** A connection kept on this phone, with Jess (joined and connected); the key between them. */
    private fun connectedWithJess(): Pair<Link, ByteArray> {
        val link = links.invite(linkDir.path)
        val inv = links.invitation(link)!!.first
        val now = System.currentTimeMillis()
        LinkProtocol.writeHello(lf, LinkHello("jess", "Jess", jessKeys.first, LinkInvites.proof(inv.token, link.id, "jess", jessKeys.first, now), now))
        links.sync()
        val l = links.get(link.id)!!
        assertTrue(l.connected)
        return l to LinkCrypto.sharedKey(jessKeys.second, l.publicKey, l.id)
    }

    private fun jessShares(link: Link, key: ByteArray, seq: Int, items: Map<String, Checkin>) {
        val m = items.mapValues { store.checkinToJson(it.value).toString() }
        LinkProtocol.publish(lf, link.id, "jess", key, seq, m, emptyList(), m)
    }

    /** A connection as the tablet keeps it: what it publishes to your devices. */
    private fun keptOnTablet(id: String, at: Long, shared: Map<String, Checkin> = emptyMap(), peerName: String = "Jess"): String {
        val keys = LinkCrypto.newKeyPair()
        val link = JSONObject().put("id", id).put("folder", "").put("side", "tabside").put("publicKey", keys.first)
            .put("privateKey", keys.second).put("peer", "jess").put("peerName", peerName).put("peerKey", jessKeys.first)
            .put("gotSeq", 3).put("homeAt", at)
        return JSONObject().put("at", at).put("home", tab).put("homeName", "Tablet").put("link", link)
            .put("shared", JSONObject().apply { shared.forEach { (k, c) -> put(k, store.checkinToJson(c)) } }).toString()
    }

    @Test
    fun aConnectionKeptHereGoesToYourOtherDevices() {
        val (link, jessKey) = connectedWithJess()
        jessShares(link, jessKey, 1, mapOf("j1" to TestEnv.checkin("j1", "Jess's walk")))
        links.sync()
        val key = withTablet()
        devices.sync()
        val o = JSONObject(phoneHas(key).getValue("l:${link.id}"))
        assertEquals(devices.side, o.getString("home"))
        assertEquals(link.homeAt, o.getLong("at"))
        assertEquals("Jess", o.getJSONObject("link").getString("peerName"))
        assertEquals("its keys, so another device can take over", link.privateKey, o.getJSONObject("link").getString("privateKey"))
        assertEquals("", o.getJSONObject("link").getString("folder"))
        assertEquals("Jess's walk", o.getJSONObject("shared").getJSONObject("j1").getString("name"))
        // Your name is shared by your devices too.
        assertEquals(JSONObject.quote("Alex"), JSONObject(phoneHas(key).getValue("s:linkName")).getString("v"))
    }

    @Test
    fun aConnectionKeptOnAnotherDeviceShowsHere() {
        val key = withTablet()
        tabletHas(key, mapOf("l:L1" to keptOnTablet("L1", 5, mapOf("j1" to TestEnv.checkin("j1", "Jess's walk")))))
        assertTrue(devices.sync())
        val l = links.all.single()
        assertFalse(l.isHere)
        assertEquals("Tablet", l.homeName)
        assertTrue(l.connected)
        assertEquals("Jess", l.peerName)
        assertTrue("not read or published here", links.here.isEmpty())
        assertEquals(listOf("Jess's walk"), links.shared(l).map { it.name })
        // Shown or not is up to this device.
        links.setShown("L1", false)
        assertFalse(links.all.single().shown)
        // Adding one of theirs puts it in your list (which reaches the tablet like any change).
        val added = links.addToMyList(l, links.shared(l).single())!!
        assertEquals("L1/j1", added.linkedFrom)
        devices.sync()
        assertTrue(phoneHas(key).containsKey("c:${added.id}"))
        // Not published back by this device.
        assertFalse(phoneHas(key).containsKey("l:L1"))
        // Sharing one of yours with it: in your check-in, so it reaches the tablet.
        store.updateCheckin("a") { it.copy(sharedWith = setOf("L1")) }
        devices.sync()
        assertTrue(JSONObject(phoneHas(key).getValue("c:a")).toString().contains("L1"))
    }

    @Test
    fun aConnectionRemovedOnItsHomeGoesEverywhere() {
        val key = withTablet()
        tabletHas(key, mapOf("l:L1" to keptOnTablet("L1", 5, mapOf("j1" to TestEnv.checkin("j1", "Walk")))))
        devices.sync()
        assertEquals(1, links.all.size)
        tabletHas(key, mapOf("l:L1" to JSONObject().put("gone", true).put("at", System.currentTimeMillis()).toString()))
        assertTrue(devices.sync())
        assertTrue(links.all.isEmpty())
    }

    @Test
    fun removingOneKeptHereTellsYourOtherDevices() {
        val (link, _) = connectedWithJess()
        val key = withTablet()
        links.remove(link.id)
        devices.sync()
        assertTrue(JSONObject(phoneHas(key).getValue("l:${link.id}")).getBoolean("gone"))
    }

    @Test
    fun anotherDeviceTakingItOverStopsThisOne() {
        val (link, jessKey) = connectedWithJess()
        val key = withTablet()
        // An older claim: ignored.
        tabletHas(key, mapOf("l:${link.id}" to keptOnTablet(link.id, link.homeAt - 1000)))
        devices.sync()
        assertTrue(links.get(link.id)!!.isHere)
        // A later one: the tablet keeps it now; this phone stops reading and publishing for it.
        tabletHas(key, mapOf("l:${link.id}" to keptOnTablet(link.id, link.homeAt + 1000, mapOf("j1" to TestEnv.checkin("j1", "Walk")))))
        assertTrue(devices.sync())
        val l = links.get(link.id)!!
        assertFalse(l.isHere)
        assertEquals("Tablet", l.homeName)
        assertTrue(links.here.isEmpty())
        val before = lf.names("from-${link.side}").sorted()
        store.updateCheckin("a") { it.copy(sharedWith = setOf(link.id)) }
        jessShares(link, jessKey, 1, mapOf("j2" to TestEnv.checkin("j2", "New")))
        links.sync()
        assertEquals("nothing published here", before, lf.names("from-${link.side}").sorted())
        assertEquals("what the tablet says they share", listOf("Walk"), links.shared(l).map { it.name })
        devices.sync()
        assertFalse(phoneHas(key).containsKey("l:${link.id}"))
    }

    @Test
    fun takingItOverCarriesOnFromTheFolder() {
        // Kept on this phone first (so the folder has its changes), then as if on the tablet.
        store.updateCheckin("a") { it.copy(sharedWith = emptySet()) }
        val (link, jessKey) = connectedWithJess()
        store.updateCheckin("a") { it.copy(sharedWith = setOf(link.id)) }
        jessShares(link, jessKey, 1, mapOf("j1" to TestEnv.checkin("j1", "Walk")))
        links.sync()
        val seq = links.get(link.id)!!.sentSeq
        assertTrue(seq >= 2)
        val item = JSONObject(links.deviceItems("tabside", "Tablet").getValue("l:${link.id}")).put("home", tab).put("at", link.homeAt)
        ctx.getSharedPreferences("gist-links", Context.MODE_PRIVATE).edit().clear().commit()
        links.myName = "Alex"
        assertTrue(links.takeDeviceItems(mapOf("l:${link.id}" to item.toString()), "phone"))
        assertFalse(links.get(link.id)!!.isHere)

        // Not its folder: refused.
        assertEquals(Connections.AdoptProblem.NOT_ITS_FOLDER, links.adopt(link.id, File(ctx.cacheDir, "x-${System.nanoTime()}").apply { mkdirs() }.path))
        assertNull(links.adopt(link.id, linkDir.path))
        val l = links.get(link.id)!!
        assertTrue(l.isHere)
        assertEquals("carries on from the folder", seq, l.sentSeq)
        assertTrue(l.homeAt > link.homeAt)
        assertEquals(Connections.AdoptProblem.NOT_KEPT_ELSEWHERE, links.adopt(link.id, linkDir.path))
        // Nothing new to say: nothing published; then a change goes out as the next one.
        links.sync()
        assertEquals(seq, links.get(link.id)!!.sentSeq)
        store.checkins = store.checkins + TestEnv.checkin("b", "Beta").copy(sharedWith = setOf(link.id))
        links.sync()
        assertEquals(seq + 1, links.get(link.id)!!.sentSeq)
        val (_, jessSees) = LinkProtocol.receive(lf, link.id, link.side, jessKey, 0, emptyMap())
        assertEquals(setOf("a", "b"), jessSees.keys - Connections.DONE_KEY)
        assertNotNull(lf.read("from-${link.side}/${String.format("%06d", seq + 1)}.gist"))
    }
}
