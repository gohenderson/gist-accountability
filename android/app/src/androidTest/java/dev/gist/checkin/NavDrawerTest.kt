package dev.gist.checkin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The ☰ menu on the start page, and profile pictures (or letters) at the top right. */
@RunWith(AndroidJUnit4::class)
class NavDrawerTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private val ctx get() = TestEnv.ctx

    @Before
    fun setUp() {
        TestEnv.shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        TestEnv.reset(listOf(TestEnv.checkin("c", "Daily")))
    }

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    private fun main() = ActivityScenario.launch(MainActivity::class.java)

    private fun openMenu() {
        onView(tagged("menu")).perform(click())
        TestEnv.eventually { onView(tagged("drawer")).check(matches(isDisplayed())) }
    }

    private fun closed() = TestEnv.eventually {
        onView(tagged("drawer")).check(doesNotExist())
        onView(tagged("drawer-scrim")).check(doesNotExist())
    }

    /** A tap on the dimmed page, right of the menu. */
    private val tapRightEdge = GeneralClickAction(Tap.SINGLE, GeneralLocation.CENTER_RIGHT, Press.FINGER,
        android.view.InputDevice.SOURCE_UNKNOWN, android.view.MotionEvent.BUTTON_PRIMARY)

    @Test
    fun theMenuOpensAndClosesWithTheXTheDimmedPageAndBack() {
        main().use { scenario ->
            onView(tagged("drawer")).check(doesNotExist())
            openMenu()
            onView(tagged("drawer-scrim")).check(matches(isDisplayed()))
            onView(tagged("drawer-profile-name")).check(matches(withText("Personal")))
            for (key in listOf("home", "connections", "backup", "settings", "about")) {
                onView(tagged("nav:$key")).check(matches(isDisplayed()))
            }
            // It's over the left of the page, not the whole of it.
            scenario.onActivity { a ->
                val d = a.window.decorView.findViewWithTag<android.view.View>("drawer")
                assertTrue("menu narrower than the page", d.width < a.window.decorView.width)
            }

            onView(tagged("drawer-close")).perform(click())
            closed()

            openMenu()
            onView(tagged("drawer-scrim")).perform(tapRightEdge)
            closed()

            openMenu()
            pressBack()
            closed()
            scenario.onActivity { assertFalse("Back closed the menu, not the page", it.isFinishing) }

            openMenu()
            onView(tagged("nav:home")).perform(click())
            closed()
        }
    }

    @Test
    fun theMenuGoesToConnectionsBackupSettingsAndAbout() {
        fun goes(key: String, title: String) {
            main().use {
                openMenu()
                onView(tagged("nav:$key")).perform(click())
                TestEnv.eventually { onView(withText(title)).check(matches(isDisplayed())) }
            }
        }
        goes("connections", "Connections")
        goes("settings", "Settings")
        goes("about", "About Gist")
        main().use {
            openMenu()
            onView(tagged("nav:backup")).perform(click())
            TestEnv.eventually { onView(tagged("export-backup")).check(matches(isDisplayed())) }
            onView(tagged("import-backup")).check(matches(isDisplayed()))
            onView(tagged("export-csv")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun everyPageHasTheMenu() {
        fun has(i: android.content.Intent) {
            ActivityScenario.launch<android.app.Activity>(i).use {
                onView(tagged("menu")).check(matches(isDisplayed()))
                openMenu()
                onView(tagged("drawer-close")).perform(click())
                closed()
            }
        }
        has(android.content.Intent(ctx, SettingsActivity::class.java))
        has(ConnectionsActivity.intent(ctx))
        has(BackupActivity.intent(ctx))
        has(AboutActivity.intent(ctx))
        has(HistoryActivity.intent(ctx, "c"))
        has(CheckinEditActivity.intent(ctx, "c"))
        has(LibraryActivity.intent(ctx))
        has(DoneTasksActivity.intent(ctx))
    }

    @Test
    fun fromOneMenuPageToAnotherBackGoesToTheStartPage() {
        main().use { scenario ->
            openMenu()
            onView(tagged("nav:settings")).perform(click())
            TestEnv.eventually { onView(withText("Dark")).check(matches(isDisplayed())) }
            // The page you're on is highlighted; tapping it just closes the menu.
            openMenu()
            onView(tagged("nav:settings")).check(matches(androidx.test.espresso.matcher.ViewMatchers.isSelected()))
            onView(tagged("nav:settings")).perform(click())
            closed()
            onView(withText("Dark")).check(matches(isDisplayed()))
            // Settings → Connections, then Back: the start page.
            openMenu()
            onView(tagged("nav:connections")).perform(click())
            TestEnv.eventually { onView(tagged("menu")).check(matches(isDisplayed())) }
            TestEnv.eventually { onView(tagged("new-connection")).check(matches(isDisplayed())) }
            pressBack()
            TestEnv.eventually { onView(tagged("tab:checkins")).check(matches(isDisplayed())) }
            // Home from a page over the start page goes back to it.
            onView(tagged("checkin-title:c")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun networkIsAllowedHere() {
        assertTrue("sockets work on the emulator", SyncthingEngine.shared(ctx).networkAllowed())
    }

    @Test
    fun settingsKeepsAppearanceTheAlarmAndProfilesOnly() {
        ActivityScenario.launch<SettingsActivity>(android.content.Intent(ctx, SettingsActivity::class.java)).use {
            onView(withText("Dark")).check(matches(isDisplayed()))
            onView(tagged("profiles")).perform(scrollTo()).check(matches(isDisplayed()))
            androidx.test.espresso.Espresso.onView(tagged("connections"))
                .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            androidx.test.espresso.Espresso.onView(tagged("about"))
                .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            androidx.test.espresso.Espresso.onView(withText("Export backup…"))
                .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        }
    }

    @Test
    fun theRailSwitchesProfileAndPlusMakesOne() {
        val work = Profiles.add(ctx, "Work")
        main().use {
            openMenu()
            onView(tagged("drawer-profile:default")).check(matches(isDisplayed()))
            onView(tagged("drawer-new-profile")).check(matches(isDisplayed()))
            onView(tagged("drawer-profile:${work.id}")).perform(click())
            TestEnv.eventually(10_000) { assertEquals(work.id, Profiles.current(ctx)) }
        }
        main().use { scenario ->
            onView(tagged("profile")).check(matches(androidx.test.espresso.matcher.ViewMatchers
                .withContentDescription("Profile: Work. Switch profile")))
            scenario.onActivity { a ->
                val v = a.window.decorView.findViewWithTag<AvatarView>("profile")
                assertEquals("W", v.initial)
                assertFalse(v.showsPicture)
            }
            openMenu()
            onView(tagged("drawer-new-profile")).perform(click())
            onView(tagged("profile-name")).inRoot(isDialog()).perform(androidx.test.espresso.action.ViewActions.replaceText("Side"))
            onView(withText("Create")).inRoot(isDialog()).perform(click())
            TestEnv.eventually(10_000) { assertEquals("Side", Profiles.name(ctx, Profiles.current(ctx))) }
        }
        Profiles.setCurrent(ctx, Profiles.DEFAULT)
    }

    @Test
    fun theTopRightShowsThePictureOrTheFirstLetter() {
        Profiles.rename(ctx, Profiles.DEFAULT, "  émile")
        main().use { scenario ->
            scenario.onActivity { a ->
                val v = a.window.decorView.findViewWithTag<AvatarView>("profile")
                assertEquals("É", v.initial)
                assertFalse(v.showsPicture)
                val at = IntArray(2).also { v.getLocationOnScreen(it) }
                assertTrue("at the right", at[0] + v.width > a.window.decorView.width * 0.85)
            }
        }
        // A wide photo: its middle square is kept, at 256 pixels.
        val photo = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(Color.BLUE)
            drawRect(200f, 0f, 600f, 400f, android.graphics.Paint().apply { color = Color.RED })
        }
        val f = File(ctx.cacheDir, "avatar-test.png")
        f.outputStream().use { photo.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ProfilePicture.set(ctx, Profiles.DEFAULT, Uri.fromFile(f))
        f.delete()
        val saved = ProfilePicture.bitmap(ctx, Profiles.DEFAULT)!!
        assertEquals(ProfilePicture.SIDE, saved.width)
        assertEquals(ProfilePicture.SIDE, saved.height)
        assertEquals("the middle (red) only", Color.RED, saved.getPixel(10, 128))
        main().use { scenario ->
            scenario.onActivity { a ->
                assertTrue(a.window.decorView.findViewWithTag<AvatarView>("profile").showsPicture)
            }
        }
        // Settings can take it away again.
        ActivityScenario.launch<SettingsActivity>(android.content.Intent(ctx, SettingsActivity::class.java)).use {
            onView(tagged("profile-picture:default")).perform(scrollTo(), click())
            onView(tagged("picture-remove")).inRoot(isDialog()).perform(click())
            TestEnv.eventually { assertFalse(ProfilePicture.has(ctx, Profiles.DEFAULT)) }
        }
    }

    @Test
    fun initials() {
        assertEquals("W", ProfilePicture.initial("work"))
        assertEquals("2", ProfilePicture.initial("  2nd phone"))
        assertEquals("S", ProfilePicture.initial("(side)"))
        assertEquals("?", ProfilePicture.initial(""))
        assertEquals("😀", ProfilePicture.initial("😀"))
    }
}
