package dev.gist.checkin

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * Connections: the handshake, encryption, publishing and reading a shared folder. The test
 * plays the other copy of Gist ("Jess") with the protocol directly, in a plain folder.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionsTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private lateinit var dir: File
    private val links get() = Connections(TestEnv.ctx) { FileFolder(File(it)) }

    @Before
    fun setUp() {
        clearLinks()
        dir = File(TestEnv.ctx.cacheDir, "link-${System.nanoTime()}").apply { mkdirs() }
        links.myName = "Alex"
    }

    @After
    fun tearDown() {
        clearLinks()
        dir.deleteRecursively()
        TestEnv.reset(emptyList())
    }

    private fun clearLinks() = Profiles.prefs(TestEnv.ctx, "gist-links").edit().clear().commit()

    /** Jess, the other copy: her side id and keys. */
    private class Peer(val side: String = "jess", val name: String = "Jess") {
        val keys = LinkCrypto.newKeyPair()
        val pub get() = keys.first
        val priv get() = keys.second
    }

    /** Jess joins the connection waiting in [dir]: her hello, and the key she works out. */
    private fun jessJoins(jess: Peer, link: Link): ByteArray {
        val f = FileFolder(dir)
        val inv = links.invitation(link)!!.first
        val now = System.currentTimeMillis()
        LinkProtocol.writeHello(f, LinkHello(jess.side, jess.name, jess.pub,
            LinkInvites.proof(inv.token, link.id, jess.side, jess.pub, now), now))
        val mine = LinkProtocol.otherHello(f, jess.side)!!
        assertEquals(link.side, mine.side)
        assertEquals("Alex", mine.name)
        return LinkCrypto.sharedKey(jess.priv, mine.key, link.id)
    }

    @Test
    fun bothSidesWorkOutTheSameKeyAndCode() {
        val a = LinkCrypto.newKeyPair()
        val b = LinkCrypto.newKeyPair()
        val k1 = LinkCrypto.sharedKey(a.second, b.first, "link1")
        val k2 = LinkCrypto.sharedKey(b.second, a.first, "link1")
        assertArrayEquals(k1, k2)
        assertEquals(32, k1.size)
        assertFalse("another connection, another key", k1.contentEquals(LinkCrypto.sharedKey(a.second, b.first, "link2")))
        assertEquals(LinkCrypto.code(a.first, b.first), LinkCrypto.code(b.first, a.first))
        assertTrue(LinkCrypto.code(a.first, b.first).matches(Regex("[0-9A-F]{4}-[0-9A-F]{4}")))
    }

    @Test
    fun sealedFilesOpenOnlyWithTheKeyWhereTheyWereWritten() {
        val key = LinkCrypto.sharedKey(LinkCrypto.newKeyPair().second, LinkCrypto.newKeyPair().first, "l")
        val sealed = LinkCrypto.seal(key, "l/from-a/000001.gist", "hello".toByteArray())
        assertFalse("it's not readable as is", String(sealed).contains("hello"))
        assertEquals("hello", String(LinkCrypto.open(key, "l/from-a/000001.gist", sealed)!!))
        assertNull("moved to another name", LinkCrypto.open(key, "l/from-a/000002.gist", sealed))
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull("changed", LinkCrypto.open(key, "l/from-a/000001.gist", tampered))
        val other = LinkCrypto.sharedKey(LinkCrypto.newKeyPair().second, LinkCrypto.newKeyPair().first, "l")
        assertNull("someone else's key", LinkCrypto.open(other, "l/from-a/000001.gist", sealed))
    }

    @Test
    fun changesAreNumberedAndASnapshotCatchesUp() {
        val f = FileFolder(dir)
        val key = LinkCrypto.hkdf("s".toByteArray(), "l".toByteArray(), "i".toByteArray(), 32)
        val v1 = mapOf("a" to """{"n":1}""", "b" to """{"n":2}""")
        LinkProtocol.publish(f, "l", "me", key, 1, v1, emptyList(), v1)
        val v2 = mapOf("a" to """{"n":3}""", "c" to """{"n":4}""")
        val (set, removed) = LinkProtocol.diff(v1, v2)
        assertEquals(mapOf("a" to """{"n":3}""", "c" to """{"n":4}"""), set)
        assertEquals(listOf("b"), removed)
        LinkProtocol.publish(f, "l", "me", key, 2, set, removed, v2)

        fun norm(m: Map<String, String>) = m.mapValues { JSONObject(it.value).toString() }
        // From the start, step by step.
        val (seq, got) = LinkProtocol.receive(f, "l", "me", key, 0, emptyMap())
        assertEquals(2, seq)
        assertEquals(norm(v2), norm(got))
        // Up to date: nothing new.
        assertEquals(2, LinkProtocol.receive(f, "l", "me", key, 2, got).first)
        // A missing change (cleaned out): the snapshot catches up.
        File(dir, "from-me/000001.gist").delete()
        val (seq2, got2) = LinkProtocol.receive(f, "l", "me", key, 0, emptyMap())
        assertEquals(2, seq2)
        assertEquals(norm(v2), norm(got2))
        // The wrong key reads nothing.
        val wrong = LinkCrypto.hkdf("x".toByteArray(), "l".toByteArray(), "i".toByteArray(), 32)
        assertEquals(0 to emptyMap<String, String>(), LinkProtocol.receive(f, "l", "me", wrong, 0, emptyMap()))
    }

    @Test
    fun inviteThenJoinThenShareBothWays() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Daily"), Library.task("t").copy(name = "Fix the fence", enabled = true)))
        val link = links.invite(dir.path)
        assertTrue(File(dir, LinkProtocol.MARKER).isFile)
        assertFalse(link.connected)
        assertNull("nothing to share yet", links.all.single().code)

        // Jess joins; the next sync notices.
        val jess = Peer()
        val key = jessJoins(jess, link)
        links.sync()
        val connected = links.all.single()
        assertTrue(connected.connected)
        assertEquals("Jess", connected.peerName)
        assertEquals(LinkCrypto.code(jess.pub, connected.publicKey), connected.code)
        assertArrayEquals(key, connected.key())

        // Share the task with her: she reads it (and only it).
        TestEnv.store.updateCheckin("t") { it.copy(sharedWith = setOf(link.id)) }
        links.sync()
        val (seq, got) = LinkProtocol.receive(FileFolder(dir), link.id, link.side, key, 0, emptyMap())
        assertTrue(seq >= 1)
        assertEquals(setOf("t"), got.keys)
        assertEquals("Fix the fence", JSONObject(got["t"]!!).getString("name"))
        assertFalse("who else it's shared with stays private", JSONObject(got["t"]!!).has("sharedWith"))
        assertFalse("nothing on disk is readable", File(dir, "from-${link.side}").listFiles()!!
            .filter { it.name.endsWith(".gist") }.any { it.readText(Charsets.ISO_8859_1).contains("fence") })

        // Rename it: one more change.
        TestEnv.store.updateCheckin("t") { it.copy(name = "Fix the gate") }
        links.sync()
        val (seq2, got2) = LinkProtocol.receive(FileFolder(dir), link.id, link.side, key, seq, got)
        assertEquals(seq + 1, seq2)
        assertEquals("Fix the gate", JSONObject(got2["t"]!!).getString("name"))
        // Nothing changed: nothing published.
        links.sync()
        assertEquals(seq2, links.all.single().sentSeq)

        // She shares a task with you: you see it, read-only, in what she shares.
        val hers = Store(TestEnv.ctx).checkinToJson(Library.task("h").copy(name = "Call the plumber", enabled = true)).toString()
        LinkProtocol.publish(FileFolder(dir), link.id, jess.side, key, 1, mapOf("h" to hers), emptyList(), mapOf("h" to hers))
        assertTrue("what she shares changed", links.sync())
        assertEquals(listOf("Call the plumber"), links.shared(links.all.single()).map { it.name })
        assertNull("it isn't added to your own list", TestEnv.store.checkin("h"))

        // Stop sharing: she reads it gone.
        TestEnv.store.updateCheckin("t") { it.copy(sharedWith = emptySet()) }
        links.sync()
        assertTrue(LinkProtocol.receive(FileFolder(dir), link.id, link.side, key, seq2, got2).second.isEmpty())
    }

    @Test
    fun joiningReadsTheInvite() {
        // Jess invited; you join the same folder.
        val jess = Peer()
        LinkProtocol.invite(FileFolder(dir), "link-j", LinkHello(jess.side, jess.name, jess.pub))
        val inv = LinkInvite('c', LinkInvites.fingerprint(jess.pub), LinkInvites.newToken())
        val link = links.join(dir.path, inv).first!!
        assertEquals("link-j", link.id)
        assertTrue(link.connected)
        assertEquals("Jess", link.peerName)
        val mine = LinkProtocol.hello(FileFolder(dir), link.side)!!
        assertTrue("it proves it had the invite", LinkInvites.proves(mine, inv.token, "link-j", Long.MAX_VALUE))
        assertEquals(Connections.JoinProblem.ALREADY_YOURS, links.join(dir.path, inv).second)
        assertEquals(Connections.JoinProblem.NOT_A_CONNECTION, links.join(File(dir, "empty").apply { mkdirs() }.path, inv).second)
        assertNotEquals(link.side, jess.side)
    }

    @Test
    fun pathsCantLeaveTheFolder() {
        val f = FileFolder(dir)
        for (bad in listOf("../x", "from-a/../../x", "a//b", "/abs", "a\\b", "..")) {
            assertTrue(bad, runCatching { f.read(bad) }.isFailure)
            assertTrue(bad, runCatching { f.write(bad, ByteArray(1)) }.isFailure)
        }
        // A hello claiming a side that isn't a plain id is ignored.
        File(dir, "from-x").mkdirs()
        File(dir, "from-x/hello.json").writeText("""{"side":"../../evil","name":"E","key":"k"}""")
        assertNull(LinkProtocol.otherHello(f, "me"))
        assertNull(LinkProtocol.hello(f, "../evil"))
    }

    @Test
    fun removingAConnectionStopsSharing() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Daily")))
        val link = links.invite(dir.path)
        TestEnv.store.updateCheckin("c") { it.copy(sharedWith = setOf(link.id)) }
        links.remove(link.id)
        assertTrue(links.all.isEmpty())
        assertTrue(TestEnv.store.checkin("c")!!.sharedWith.isEmpty())
    }

    /** A connection to Jess that shares [items] with you. */
    private fun connectedTo(vararg items: Checkin): Link {
        val link = links.invite(dir.path)
        val jess = Peer()
        val key = jessJoins(jess, link)
        val store = Store(TestEnv.ctx)
        val all = items.associate { it.id to store.checkinToJson(it).toString() }
        LinkProtocol.publish(FileFolder(dir), link.id, jess.side, key, 1, all, emptyList(), all)
        links.sync()
        return links.all.single()
    }

    @Test
    fun theStartPageShowsWhatTheyShareOnTheRightTab() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Daily")))
        val link = connectedTo(
            TestEnv.checkin("hc", "Her morning").copy(description = "Stretch first"),
            Library.task("ht").copy(name = "Call the plumber", enabled = true),
        )
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(tagged("from:${link.id}")).perform(scrollTo()).check(matches(withText("From Jess")))
            onView(tagged("shared:${link.id}:hc")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Stretch first")).check(matches(isDisplayed()))
            onView(tagged("shared:${link.id}:ht")).check(doesNotExist()) // a task: on the Tasks tab
            onView(tagged("tab:tasks")).perform(click())
            onView(tagged("shared:${link.id}:ht")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("shared:${link.id}:hc")).check(doesNotExist())
        }
        // Hidden: not on the start page.
        links.setShown(link.id, false)
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(tagged("from:${link.id}")).check(doesNotExist())
        }
    }

    @Test
    fun theConnectionsPageShowsTheCodeAndCounts() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Daily")))
        val link = connectedTo(TestEnv.checkin("hc", "Her morning"))
        TestEnv.store.updateCheckin("c") { it.copy(sharedWith = setOf(link.id)) }
        ActivityScenario.launch<ConnectionsActivity>(ConnectionsActivity.intent(TestEnv.ctx)).use {
            onView(tagged("link:${link.id}:name")).check(matches(withText("Jess")))
            onView(tagged("link:${link.id}:type")).check(matches(withText("Person · Manual")))
            onView(tagged("link:${link.id}:extra")).check(matches(withText("You share 1 item · they share 1 item")))
            onView(tagged("link:${link.id}")).perform(scrollTo(), click())
            onView(tagged("link-code:${link.id}")).inRoot(isDialog()).check(matches(withText(org.hamcrest.Matchers.startsWith("Code ${link.code}"))))
            onView(tagged("link-shown:${link.id}")).inRoot(isDialog()).perform(click())
        }
        assertFalse(links.get(link.id)!!.shown)
    }

    @Test
    fun shareWithOnACheckinsPage() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Daily")))
        val link = connectedTo()
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, "c")).use {
            onView(tagged("share-with")).perform(scrollTo()).check(matches(withText("Shared with: nobody")))
            onView(tagged("share-with")).perform(click())
            onView(withText("Jess")).inRoot(isDialog()).perform(click())
            onView(withText("Done")).inRoot(isDialog()).perform(click())
            onView(tagged("share-with")).perform(scrollTo()).check(matches(withText("Shared with: Jess")))
        }
        assertEquals(setOf(link.id), TestEnv.store.checkin("c")!!.sharedWith)
    }
}
