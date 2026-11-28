package dev.gist.checkin

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Connections page: + New connection walks through who it's with, creating or connecting,
 * and how; the side that scans sees who and what it's connecting to, and confirms first.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionsWizardTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private val ctx get() = TestEnv.ctx
    private lateinit var dir: File
    private val links get() = Connections(ctx)

    @Before
    fun setUp() {
        TestEnv.shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        TestEnv.reset(listOf(TestEnv.checkin("a", "Morning pills"), TestEnv.checkin("b", "Baste the turkey"),
            TestEnv.checkin("c", "Evening walk"), TestEnv.checkin("d", "Water the plants")))
        clear()
        dir = File(ctx.cacheDir, "wizard-${System.nanoTime()}").apply { mkdirs() }
        GistFolder(ctx).root = dir.path
    }

    @After
    fun tearDown() {
        clear()
        GistFolder(ctx).root = null
        dir.deleteRecursively()
        TestEnv.reset(emptyList())
    }

    private fun clear() {
        Profiles.prefs(ctx, "gist-links").edit().clear().commit()
        Profiles.prefs(ctx, "gist-devices").edit().clear().commit()
        Profiles.prefs(ctx, "gist-easy").edit().clear().commit()
        GistFolder(ctx).waiting = null
    }

    private fun page() = ActivityScenario.launch<ConnectionsActivity>(ConnectionsActivity.intent(ctx))

    private fun tap(key: String) = onView(tagged(key)).inRoot(isDialog()).perform(click())

    private val withSyncthing get() = SyncthingEngine.shared(ctx).available

    @Test
    fun aPersonManuallyThenCancelTheCode() {
        links.myName = "Alex"
        page().use {
            onView(tagged("new-connection")).check(matches(isDisplayed()))
            onView(tagged("no-connections")).check(matches(isDisplayed()))
            onView(tagged("new-connection")).perform(click())
            tap("new-person")
            tap("new-create")
            onView(tagged("invite-who")).inRoot(isDialog()).perform(replaceText("Jess"))
            onView(withText("Next")).inRoot(isDialog()).perform(click())
            if (withSyncthing) tap("new-manual")
            onView(tagged("invite-qr")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(tagged("invite-code")).inRoot(isDialog()).check(matches(isDisplayed()))
            val l = links.all.single()
            assertTrue("People/Jess", l.folder.endsWith("People/Jess"))
            onView(withText("Done")).inRoot(isDialog()).perform(click())
            // In the list, waiting for her.
            onView(tagged("link:${l.id}:name")).check(matches(withText("Waiting for Jess to join")))
            onView(tagged("link:${l.id}:type")).check(matches(withText("Person · Manual · code open")))
            onView(tagged("link:${l.id}")).perform(click())
            onView(withText("Cancel the code")).inRoot(isDialog()).perform(click())
            TestEnv.eventually { assertTrue("cancelled at once", links.all.isEmpty()) }
            onView(tagged("link:${l.id}")).check(doesNotExist())
        }
    }

    @Test
    fun myDeviceManuallyThenCancelTheCode() {
        var shown = ""
        page().use {
            onView(tagged("new-connection")).perform(click())
            tap("new-device")
            tap("new-create")
            if (withSyncthing) tap("new-manual")
            onView(tagged("invite-qr")).inRoot(isDialog()).check(matches(isDisplayed()))
            shown = onViewText("invite-code")
            assertNotNull("My devices set up here", Devices(ctx).folder)
            onView(withText("Cancel the code")).inRoot(isDialog()).perform(click())
        }
        assertNotEquals("the code shown no longer works", shown, Devices(ctx).invitation()!!.first.code)
    }

    /** The text of the view tagged [key] in a dialog. */
    private fun onViewText(key: String): String {
        var text = ""
        onView(tagged(key)).inRoot(isDialog()).check { v, _ -> text = (v as android.widget.TextView).text.toString() }
        return text
    }

    @Test
    fun aManualCodeIsConfirmedWhenItsFolderArrivesAndNoWithdrawsIt() {
        // Alex invited (manually) in a folder that has arrived here as People/Alex.
        val alex = Connections(ctx) { FileFolder(File(it)) }
        val theirs = File(dir, "People/Alex").apply { mkdirs() }
        // (Alex's side of it, made as Alex's phone would: a connection with its invite, and its preview.)
        val l = alex.invite(theirs.path)
        val inv = alex.invitation(l)!!.first
        Handshake.putPreview(FileFolder(theirs), inv.token, ConnectPreview(name = "Alex", device = "Alex's phone"))
        Profiles.prefs(ctx, "gist-links").edit().clear().commit() // this phone is the other one
        links.myName = "Sam"
        page().use {
            onView(tagged("new-connection")).perform(click())
            tap("new-person")
            tap("new-connect")
            onView(withText("Type the code")).inRoot(isDialog()).perform(click())
            onView(tagged("invite-field")).inRoot(isDialog()).perform(replaceText(inv.code))
            onView(withText("Next")).inRoot(isDialog()).perform(click())
            // Its folder's here: what Alex said, before anything's saved.
            onView(tagged("confirm-who")).inRoot(isDialog()).check(matches(withText("Alex")))
            onView(tagged("confirm-device")).inRoot(isDialog()).check(matches(withText("On Alex's phone")))
            onView(tagged("confirm-how")).inRoot(isDialog()).check(matches(withText("Manual connection")))
            onView(tagged("confirm-control")).inRoot(isDialog()).check(matches(withText(containsString("Read-only both ways"))))
            onView(withText("Not this one")).inRoot(isDialog()).perform(click())
        }
        assertNull("nothing saved", GistFolder(ctx).waiting)
        assertTrue(links.all.isEmpty())
        // The "no" is in the folder, and only the code could have made it: Alex's phone withdraws the code.
        assertTrue(Handshake.declined(FileFolder(theirs), inv.token))
        assertTrue(!Handshake.declined(FileFolder(theirs), LinkInvites.newToken()))
    }

    @Test
    fun aManualCodeWaitsForItsFolderBeforeAsking() {
        links.myName = "Sam"
        val inv = LinkInvite('c', LinkInvites.fingerprint("their-key"), LinkInvites.newToken())
        page().use {
            onView(tagged("new-connection")).perform(click())
            tap("new-person")
            tap("new-connect")
            onView(withText("Type the code")).inRoot(isDialog()).perform(click())
            onView(tagged("invite-field")).inRoot(isDialog()).perform(replaceText(inv.code))
            onView(withText("Next")).inRoot(isDialog()).perform(click())
            TestEnv.eventually { onView(tagged("join-waiting")).check(matches(isDisplayed())) }
        }
        assertEquals("c:${inv.code}", GistFolder(ctx).waiting)
        assertTrue("not confirmed: never joined in the background", !GistFolder(ctx).waitingConfirmed)
        assertTrue(EasyConnect.finishJoins(ctx).not())
    }

    @Test
    fun theSideThatShowedAManualCodeWithdrawsItOnANo() {
        links.myName = "Alex"
        val l = links.invite(File(dir, "People/Jess").apply { mkdirs() }.path)
        val inv = links.invitation(l)!!.first
        Handshake.decline(FileFolder(File(dir, "People/Jess")), inv.token)
        assertEquals(listOf("Jess"), Handshake.withdrawDeclined(ctx))
        assertTrue("withdrawn", links.all.isEmpty())
    }

    @Test
    fun aPreviewOnlyOpensWithItsCode() {
        val token = LinkInvites.newToken()
        val p = ConnectPreview(name = "Alex", device = "Pixel", sample = listOf("Morning pills"), count = 2)
        assertEquals(p, Handshake.open(token, Handshake.seal(token, p)))
        assertNull(Handshake.open(LinkInvites.newToken(), Handshake.seal(token, p)))
    }

    @Test
    fun whatThisSideShows() {
        val p = ConnectPreview.of(ctx, 'd')
        assertEquals(4, p.count)
        assertEquals(listOf("Morning pills", "Baste the turkey", "Evening walk"), p.sample)
        assertEquals(p, ConnectPreview.decode(p.encode()))
        // A person's shows no check-ins: nothing is shared yet.
        assertTrue(ConnectPreview.of(ctx, 'c').sample.isEmpty())
    }
}
