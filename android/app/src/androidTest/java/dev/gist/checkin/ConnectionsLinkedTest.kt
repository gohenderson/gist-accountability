package dev.gist.checkin

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
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
 * Connections, step 2: adding what's shared with you to your own list (your alarms; their
 * name and description), who can do a shared task, and "done" going back to its owner. The
 * test plays Jess, the other copy, through the protocol.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionsLinkedTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private lateinit var dir: File
    private val links get() = Connections(TestEnv.ctx) { FileFolder(File(it)) }
    private val store get() = Store(TestEnv.ctx)

    /** Jess's side of the connection: her id, keys and what she's published. */
    private lateinit var link: Link
    private lateinit var key: ByteArray
    private val jess = "jess"
    private var jessSeq = 0
    private var jessSent = mapOf<String, String>()

    @Before
    fun setUp() {
        TestEnv.ctx.getSharedPreferences("gist-links", Context.MODE_PRIVATE).edit().clear().commit()
        dir = File(TestEnv.ctx.cacheDir, "link-${System.nanoTime()}").apply { mkdirs() }
        links.myName = "Alex"
    }

    @After
    fun tearDown() {
        TestEnv.ctx.getSharedPreferences("gist-links", Context.MODE_PRIVATE).edit().clear().commit()
        dir.deleteRecursively()
        TestEnv.reset(emptyList())
    }

    /** You invite, Jess joins. */
    private fun connect(mine: List<Checkin> = emptyList()) {
        TestEnv.reset(mine)
        link = links.invite(dir.path)
        val (pub, priv) = LinkCrypto.newKeyPair()
        val inv = links.invitation(link)!!.first
        val now = System.currentTimeMillis()
        LinkProtocol.writeHello(FileFolder(dir), LinkHello(jess, "Jess", pub, LinkInvites.proof(inv.token, link.id, jess, pub, now), now))
        key = LinkCrypto.sharedKey(priv, link.publicKey, link.id)
        links.sync()
        link = links.all.single()
    }

    /** Jess publishes [items] (her whole share now). */
    private fun jessShares(vararg items: Checkin, done: Map<String, Long> = emptyMap()) {
        val now = items.associate { it.id to store.checkinToJson(it).toString() }.toMutableMap()
        if (done.isNotEmpty()) now[Connections.DONE_KEY] = JSONObject(done as Map<*, *>).toString()
        val (set, removed) = LinkProtocol.diff(jessSent, now)
        jessSeq++
        LinkProtocol.publish(FileFolder(dir), link.id, jess, key, jessSeq, set, removed, now)
        jessSent = now
        links.sync()
    }

    /** What you've published to Jess, as she reads it. */
    private fun whatJessReads(): Map<String, String> =
        LinkProtocol.receive(FileFolder(dir), link.id, link.side, key, 0, emptyMap()).second

    private fun herTask(id: String, name: String, who: String = Store.LINK_ANYONE) =
        Library.task(id).copy(name = name, description = "Notes for $name", enabled = true, linkedAssignee = who)

    @Test
    fun addingSomethingSharedKeepsTheirNameAndDescription() {
        connect()
        jessShares(herTask("t", "Call the plumber"), TestEnv.checkin("c", "Her morning"))
        val shared = links.shared(link).associateBy { it.id }
        val mine = links.addToMyList(link, shared.getValue("t"))!!
        assertNull("not twice", links.addToMyList(link, shared.getValue("t")))
        assertEquals("${link.id}/t", mine.linkedFrom)
        assertEquals("Call the plumber", mine.name)
        assertTrue("on your list, with your own id", store.checkin(mine.id) != null && mine.id != "t")
        assertEquals(setOf("t"), links.added(link).keys)

        // Your own settings stay yours; her name and description follow hers.
        store.updateCheckin(mine.id) { it.copy(commitment = Checkin.STRICT, category = "Mine") }
        jessShares(herTask("t", "Call the plumber about the leak"), TestEnv.checkin("c", "Her morning"))
        val after = store.checkin(mine.id)!!
        assertEquals("Call the plumber about the leak", after.name)
        assertEquals("Notes for Call the plumber about the leak", after.description)
        assertEquals(Checkin.STRICT, after.commitment)
        assertEquals("Mine", after.category)

        // She stops sharing it: it stays, marked.
        jessShares(TestEnv.checkin("c", "Her morning"))
        assertTrue(store.checkin(mine.id)!!.linkedGone)
    }

    @Test
    fun whoCanDoItIsSentAsTheySeeIt() {
        val t = Library.task("t").copy(name = "Mow", enabled = true)
        connect(listOf(t))
        fun seen(assignee: String): String {
            store.updateCheckin("t") { it.copy(sharedWith = setOf(link.id), assignee = assignee) }
            links.sync()
            return JSONObject(whatJessReads().getValue("t")).optString("linkedAssignee")
        }
        assertEquals(Store.LINK_ANYONE, seen(""))
        assertEquals(Store.LINK_OWNER, seen(Store.ASSIGN_ME))
        assertEquals(Store.LINK_YOU, seen(link.id))
        assertEquals(Store.LINK_OTHER, seen("someone-else"))
        assertFalse("who it's assigned to among yours stays yours", JSONObject(whatJessReads().getValue("t")).has("assignee"))
    }

    @Test
    fun finishingTheirTaskTellsThem() {
        connect()
        jessShares(herTask("t", "Take out the trash"), herTask("o", "Her own thing", Store.LINK_OWNER))
        val shared = links.shared(link).associateBy { it.id }
        assertFalse("only she can do hers", shared.getValue("o").youCanDo)
        val mine = links.addToMyList(link, shared.getValue("t"))!!
        store.completeTask(mine.id, at = 1234L)
        links.sync()
        assertEquals(1234L, JSONObject(whatJessReads().getValue(Connections.DONE_KEY)).getLong("t"))

        // She marks it done too: nothing else changes here.
        jessShares(herTask("t", "Take out the trash").copy(enabled = false, doneAt = 1234L, doneBy = "Alex"),
            herTask("o", "Her own thing", Store.LINK_OWNER))
        assertEquals(1234L, store.checkin(mine.id)!!.doneAt)
    }

    @Test
    fun theirDoneIsDoneHereToo() {
        connect()
        jessShares(herTask("t", "Buy milk"))
        val mine = links.addToMyList(link, links.shared(link).single())!!
        jessShares(herTask("t", "Buy milk").copy(enabled = false, doneAt = 999L))
        val after = store.checkin(mine.id)!!
        assertTrue(after.taskDone)
        assertEquals("Jess", after.doneBy)
        assertFalse("not echoed back as yours", whatJessReads().containsKey(Connections.DONE_KEY))
        // She puts it back: back on your list.
        jessShares(herTask("t", "Buy milk"))
        assertFalse(store.checkin(mine.id)!!.taskDone)
    }

    @Test
    fun aTaskYouSharedIsDoneWhenTheyFinishIt() {
        val anyone = Library.task("a").copy(name = "Water the plants", enabled = true)
        val onlyMe = Library.task("m").copy(name = "My taxes", enabled = true, assignee = Store.ASSIGN_ME)
        connect(listOf(anyone, onlyMe))
        store.updateCheckin("a") { it.copy(sharedWith = setOf(link.id)) }
        store.updateCheckin("m") { it.copy(sharedWith = setOf(link.id)) }
        links.sync()
        jessShares(done = mapOf("a" to 555L, "m" to 556L))
        val a = store.checkin("a")!!
        assertTrue(a.taskDone)
        assertEquals(555L, a.doneAt)
        assertEquals("Jess", a.doneBy)
        assertFalse("only you can finish yours", store.checkin("m")!!.taskDone)
        // It goes back to her done, so it's done on her side too.
        assertEquals(555L, JSONObject(whatJessReads().getValue("a")).getLong("doneAt"))
    }

    @Test
    fun theStartPageOffersToAddAndShowsWhoItsFrom() {
        connect(listOf(TestEnv.checkin("c", "Daily")))
        jessShares(herTask("t", "Call the plumber"), herTask("o", "Her own thing", Store.LINK_OWNER))
        TestEnv.store.startTab = Store.TAB_TASKS
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(tagged("assigned:${link.id}:t")).perform(scrollTo()).check(matches(withText("anyone can do it")))
            onView(tagged("assigned:${link.id}:o")).perform(scrollTo()).check(matches(withText("only Jess can do it")))
            onView(tagged("add-shared:${link.id}:o")).check(doesNotExist())
            onView(tagged("add-shared:${link.id}:t")).perform(scrollTo(), click())
            // Now it's on your list (and not under From Jess).
            TestEnv.eventually { onView(tagged("shared:${link.id}:t")).check(doesNotExist()) }
        }
        val mine = links.added(link).getValue("t")
        TestEnv.store.expandedCheckin = mine.id
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(tagged("linked:${mine.id}")).perform(scrollTo()).check(matches(withText("From Jess · anyone can do it")))
        }
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, mine.id)).use {
            onView(tagged("checkin-name")).check(matches(not(isEnabled())))
            onView(tagged("checkin-description")).check(matches(not(isEnabled())))
            onView(tagged("linked-note")).check(matches(isDisplayed()))
            onView(tagged("share-with")).check(doesNotExist())
        }
    }

    @Test
    fun whoCanDoItOnASharedTasksPage() {
        connect(listOf(Library.task("t").copy(name = "Mow", enabled = true)))
        store.updateCheckin("t") { it.copy(sharedWith = setOf(link.id)) }
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, "t")).use {
            onView(tagged("assignee")).perform(scrollTo()).check(matches(withText("Who can do it: anyone")))
            onView(tagged("assignee")).perform(click())
            onView(tagged("assign:${link.id}")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(scrollTo(), click())
            onView(tagged("assignee")).perform(scrollTo()).check(matches(withText("Who can do it: only Jess")))
        }
        assertEquals(link.id, store.checkin("t")!!.assignee)
        assertNotNull(store.checkin("t"))
    }
}
