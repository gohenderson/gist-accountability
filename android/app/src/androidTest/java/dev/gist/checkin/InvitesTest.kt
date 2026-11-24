package dev.gist.checkin

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.`is`
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Invites: joining a connection or your devices takes a code passed outside the folder (a QR
 * code, or typed), so only whoever saw it can join.
 */
@RunWith(AndroidJUnit4::class)
class InvitesTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private lateinit var dir: File
    private val links get() = Connections(TestEnv.ctx) { FileFolder(File(it)) }
    private val devices get() = Devices(TestEnv.ctx) { FileFolder(File(it)) }
    private val f get() = FileFolder(dir)

    @Before
    fun setUp() {
        clear()
        dir = File(TestEnv.ctx.cacheDir, "invites-${System.nanoTime()}").apply { mkdirs() }
        links.myName = "Alex"
    }

    @After
    fun tearDown() {
        clear()
        dir.deleteRecursively()
        TestEnv.reset(emptyList())
    }

    private fun clear() {
        TestEnv.ctx.getSharedPreferences("gist-links", Context.MODE_PRIVATE).edit().clear().commit()
        TestEnv.ctx.getSharedPreferences("gist-devices", Context.MODE_PRIVATE).edit().clear().commit()
        Store(TestEnv.ctx).tracking = false
    }

    /** Someone joins [scope] as [side], proving [token] at [at]. */
    private fun hello(side: String, pub: String, token: ByteArray, scope: String, at: Long = System.currentTimeMillis()) =
        LinkHello(side, side, pub, LinkInvites.proof(token, scope, side, pub, at), at)

    @Test
    fun codesRoundTripHoweverTheyreTyped() {
        val inv = LinkInvite('c', LinkInvites.fingerprint("key"), LinkInvites.newToken())
        assertTrue(inv.code, inv.code.matches(Regex("[A-Z2-7]{4}(-[A-Z2-7]{1,4})+")))
        assertTrue(inv.qr.startsWith("GIST:"))
        for (text in listOf(inv.code, inv.qr, inv.code.lowercase(), " " + inv.code.replace("-", " ") + " ", inv.code.replace("-", ""))) {
            val back = LinkInvite.parse(text)
            assertNotNull(text, back)
            assertEquals('c', back!!.kind)
            assertArrayEquals(inv.fingerprint, back.fingerprint)
            assertArrayEquals(inv.token, back.token)
        }
        assertEquals('d', LinkInvite.parse(LinkInvite('d', inv.fingerprint, inv.token).code)!!.kind)
        assertNull("too short", LinkInvite.parse(inv.code.dropLast(5)))
        assertNull("not one", LinkInvite.parse("hello there"))
        assertNull("another kind", LinkInvite.parse(LinkInvite('x', inv.fingerprint, inv.token).code))
    }

    @Test
    fun aProofNeedsTheTokenAndTime() {
        val token = LinkInvites.newToken()
        val pub = LinkCrypto.newKeyPair().first
        val h = hello("jess", pub, token, "link1", at = 1000)
        assertTrue(LinkInvites.proves(h, token, "link1", 2000))
        assertFalse("too late", LinkInvites.proves(h, token, "link1", 999))
        assertFalse("another token", LinkInvites.proves(h, LinkInvites.newToken(), "link1", 2000))
        assertFalse("another connection", LinkInvites.proves(h, token, "link2", 2000))
        assertFalse("someone else's key", LinkInvites.proves(h.copy(key = LinkCrypto.newKeyPair().first), token, "link1", 2000))
        assertFalse("no proof", LinkInvites.proves(h.copy(proof = null), token, "link1", 2000))
        assertFalse("a later time claimed", LinkInvites.proves(h.copy(joinedAt = 500), token, "link1", 2000))
    }

    @Test
    fun onlyTheOneWithTheInviteIsLetIn() {
        val link = links.invite(dir.path)
        val (inv, until) = links.invitation(link)!!
        assertEquals('c', inv.kind)
        assertTrue(until > System.currentTimeMillis() + 14 * 60_000)
        assertArrayEquals("the same until it's used", inv.token, links.invitation(link)!!.first.token)
        assertArrayEquals(LinkInvites.fingerprint(link.publicKey), inv.fingerprint)
        assertFalse("the token never goes in the folder", dir.walkTopDown().filter { it.isFile }
            .any { it.readText(Charsets.ISO_8859_1).contains(java.util.Base64.getEncoder().encodeToString(inv.token)) })

        // Someone who saw only the folder: no proof, or a guessed token. Not let in.
        val eve = LinkCrypto.newKeyPair().first
        LinkProtocol.writeHello(f, LinkHello("eve", "Eve", eve))
        LinkProtocol.writeHello(f, hello("mallory", LinkCrypto.newKeyPair().first, LinkInvites.newToken(), link.id))
        links.sync()
        assertFalse(links.all.single().connected)

        // Jess, with the invite: let in, and the invite's used up.
        val jess = LinkCrypto.newKeyPair().first
        LinkProtocol.writeHello(f, hello("jess", jess, inv.token, link.id))
        links.sync()
        val l = links.all.single()
        assertTrue(l.connected)
        assertEquals("jess", l.peer)
        assertEquals(jess, l.peerKey)
        assertNull(l.inviteToken)
        assertNull("nothing more to show", links.invitation(l))

        // Later hellos don't change who it's with; a new name does show.
        LinkProtocol.writeHello(f, hello("jess2", LinkCrypto.newKeyPair().first, inv.token, link.id))
        LinkProtocol.rename(f, "jess", "Jess", jess)
        links.sync()
        assertEquals("jess", links.all.single().peer)
        assertEquals("Jess", links.all.single().peerName)
    }

    @Test
    fun anExpiredInviteIsntAcceptedButCanBeRenewed() {
        val then = System.currentTimeMillis() - LinkInvites.VALID_MS - 60_000
        val link = links.invite(dir.path, now = then)
        val old = links.invitation(link, now = then)!!.first
        LinkProtocol.writeHello(f, hello("jess", LinkCrypto.newKeyPair().first, old.token, link.id))
        links.sync()
        assertFalse("too late", links.all.single().connected)
        // Shown again: a new invite.
        val (fresh, until) = links.invitation(links.all.single())!!
        assertFalse(fresh.token.contentEquals(old.token))
        assertTrue(until > System.currentTimeMillis())
        LinkProtocol.writeHello(f, hello("jess", LinkCrypto.newKeyPair().first, fresh.token, link.id))
        links.sync()
        assertTrue(links.all.single().connected)
    }

    @Test
    fun joiningChecksTheInviteIsFromThatFolder() {
        val jess = LinkCrypto.newKeyPair().first
        LinkProtocol.invite(f, "link-j", LinkHello("jess", "Jess", jess))
        val wrong = LinkInvite('c', LinkInvites.fingerprint(LinkCrypto.newKeyPair().first), LinkInvites.newToken())
        val (none, problem) = links.join(dir.path, wrong)
        assertNull(none)
        assertEquals(Connections.JoinProblem.NOT_THEIR_INVITE, problem)
        assertTrue(links.all.isEmpty())
        assertEquals("no hello written", listOf("jess"), LinkProtocol.sides(f, ""))
    }

    @Test
    fun olderConnectionsStillLetInWhoeverJoins() {
        // Made before invites: no token kept, no proof needed.
        val link = links.invite(dir.path)
        val prefs = TestEnv.ctx.getSharedPreferences("gist-links", Context.MODE_PRIVATE)
        val raw = prefs.all.entries.first { it.value is String && (it.value as String).contains(link.id) }
        val text = raw.value as String
        val all = org.json.JSONArray(text)
        for (i in 0 until all.length()) all.getJSONObject(i).apply { remove("inviteToken"); remove("inviteExpires"); remove("needsProof") }
        prefs.edit().putString(raw.key, all.toString()).commit()
        LinkProtocol.writeHello(f, LinkHello("jess", "Jess", LinkCrypto.newKeyPair().first))
        links.sync()
        assertTrue(links.all.single().connected)
    }

    @Test
    fun devicesLetInOnlyTheOneWithTheirInvite() {
        devices.setUp(dir.path)
        val group = JSONObject(String(File(dir, Devices.MARKER).readBytes())).getString("group")
        val (inv, _) = devices.invitation()!!
        assertEquals('d', inv.kind)
        // A device without the invite gets no key.
        LinkProtocol.writeHello(f, LinkHello("eve", "Eve", LinkCrypto.newKeyPair().first))
        devices.sync()
        assertNull(f.read("from-${devices.side}/key-eve.gist"))
        // The tablet with it does; then it's used up.
        LinkProtocol.writeHello(f, hello("tab", LinkCrypto.newKeyPair().first, inv.token, group))
        devices.sync()
        assertNotNull(f.read("from-${devices.side}/key-tab.gist"))
        LinkProtocol.writeHello(f, hello("tab2", LinkCrypto.newKeyPair().first, inv.token, group))
        devices.sync()
        assertNull("used once", f.read("from-${devices.side}/key-tab2.gist"))
        assertFalse("a new invite next time", devices.invitation()!!.first.token.contentEquals(inv.token))
    }

    @Test
    fun aJoiningDeviceTakesTheKeyOnlyFromItsInviter() {
        val group = "g1"
        f.write(Devices.MARKER, JSONObject().put("format", Devices.FORMAT).put("group", group).toString().toByteArray())
        val tablet = LinkCrypto.newKeyPair()
        val eve = LinkCrypto.newKeyPair()
        LinkProtocol.writeHello(f, LinkHello("tab", "Tablet", tablet.first))
        LinkProtocol.writeHello(f, LinkHello("eve", "Eve", eve.first))
        val inv = LinkInvite('d', LinkInvites.fingerprint(tablet.first), LinkInvites.newToken())
        assertNull(devices.join(dir.path, inv))
        val phone = LinkProtocol.hello(f, devices.side)!!
        assertTrue(LinkInvites.proves(phone, inv.token, group, Long.MAX_VALUE))
        // Eve offers a key of her own: ignored.
        val evil = ByteArray(32) { 7 }
        val eveName = "from-eve/key-${devices.side}.gist"
        f.write(eveName, LinkCrypto.seal(LinkCrypto.sharedKey(eve.second, phone.key, group), "$group/$eveName", evil))
        devices.sync()
        assertFalse(devices.ready)
        // The tablet's: taken.
        val key = ByteArray(32) { 1 }
        val name = "from-tab/key-${devices.side}.gist"
        f.write(name, LinkCrypto.seal(LinkCrypto.sharedKey(tablet.second, phone.key, group), "$group/$name", key))
        devices.sync()
        assertTrue(devices.ready)
        // An invite that isn't from anyone there.
        Devices(TestEnv.ctx) { FileFolder(File(it)) }.leave()
        assertEquals(Devices.JoinProblem.NOT_THEIR_INVITE,
            devices.join(dir.path, LinkInvite('d', LinkInvites.fingerprint(LinkCrypto.newKeyPair().first), inv.token)))
    }

    @Test
    fun aWaitingConnectionShowsItsInvite() {
        val link = links.invite(dir.path)
        val code = links.invitation(link)!!.first.code
        ActivityScenario.launch<ConnectionsActivity>(ConnectionsActivity.intent(TestEnv.ctx)).use {
            onView(withTagValue(`is`("link-invite:${link.id}"))).perform(scrollTo(), click())
            onView(withTagValue(`is`("invite-code"))).inRoot(isDialog()).check(matches(withText(code)))
            onView(withTagValue(`is`("invite-qr"))).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("Done")).inRoot(isDialog()).perform(click())
        }
        assertNotEquals("", code)
    }
}
