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
    fun theScanningSideSeesWhatItsConnectingToAndCanSayNo() {
        assumeTrue("automatic connections in this build", withSyncthing)
        // What another of your devices shows: an automatic code, with its name and some of its check-ins.
        val token = LinkInvites.newToken()
        val code = EasyConnect.Code(LinkInvite('d', LinkInvites.fingerprint("their-key"), token),
            EasyConnect.Code.deviceId("A".repeat(56))!!, "gist-devices-test")
        val preview = ConnectPreview(name = "Alex", device = "Alex's tablet", sample = listOf("Morning pills", "Baste the turkey"), count = 7)
        page().use { scenario ->
            scenario.onActivity { a ->
                ConnectionsActivity::class.java.getDeclaredMethod("scanned", String::class.java).apply { isAccessible = true }
                    .invoke(a, ConnectPreview.withPreview(code.qr, preview))
            }
            onView(tagged("confirm-who")).inRoot(isDialog()).check(matches(withText("Alex's tablet")))
            onView(tagged("confirm-how")).inRoot(isDialog()).check(matches(withText("Automatic connection")))
            onView(tagged("confirm-control")).inRoot(isDialog()).check(matches(withText(containsString("Everything"))))
            onView(tagged("confirm-sample")).inRoot(isDialog()).check(matches(withText("• Morning pills\n• Baste the turkey")))
            onView(withText("Some of its check-ins and tasks (7 in all)")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("Not this one")).inRoot(isDialog()).perform(click())
        }
        // Nothing saved: no join waiting, no devices, automatic connections not started.
        assertNull(GistFolder(ctx).waiting)
        assertNull(Devices(ctx).folder)
        assertTrue(!EasyConnect.isOn(ctx))
    }

    @Test
    fun aTypedPersonCodeIsConfirmedToo() {
        links.myName = "Sam"
        val inv = LinkInvite('c', LinkInvites.fingerprint("their-key"), LinkInvites.newToken())
        page().use {
            onView(tagged("new-connection")).perform(click())
            tap("new-person")
            tap("new-connect")
            onView(withText("Type the code")).inRoot(isDialog()).perform(click())
            onView(tagged("invite-field")).inRoot(isDialog()).perform(replaceText(inv.code))
            onView(withText("Next")).inRoot(isDialog()).perform(click())
            onView(tagged("confirm-how")).inRoot(isDialog()).check(matches(withText("Manual connection")))
            onView(tagged("confirm-control")).inRoot(isDialog()).check(matches(withText(containsString("Read-only both ways"))))
            onView(withText("Not this one")).inRoot(isDialog()).perform(click())
        }
        assertNull("nothing saved", GistFolder(ctx).waiting)
        assertTrue(links.all.isEmpty())
    }

    @Test
    fun thePreviewRidesInTheQrCode() {
        val inv = LinkInvite('d', LinkInvites.fingerprint("k"), LinkInvites.newToken())
        val p = ConnectPreview.of(ctx, 'd')
        assertEquals(4, p.count)
        assertEquals(listOf("Morning pills", "Baste the turkey", "Evening walk"), p.sample)
        val (raw, back) = ConnectPreview.split(ConnectPreview.withPreview(inv.qr, p))
        assertEquals(inv.qr, raw)
        assertEquals(p, back)
        assertEquals(inv.code, LinkInvite.parse(raw)!!.code)
        // A person's code shows no check-ins: nothing is shared yet.
        assertTrue(ConnectPreview.of(ctx, 'c').sample.isEmpty())
        // Without one, just the code.
        assertEquals(inv.qr to null, ConnectPreview.split(inv.qr))
    }
}
