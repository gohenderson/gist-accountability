package dev.gist.checkin

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.startsWith
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The About page. */
@RunWith(AndroidJUnit4::class)
class AboutTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    @Test
    fun aboutOpensFromTheStartPageWithVersionDonateAndSource() {
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.POST_NOTIFICATIONS")
        TestEnv.reset(emptyList())
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(tagged("about")).perform(scrollTo(), click())
            onView(withText("About Gist")).check(matches(isDisplayed()))
            onView(withText(startsWith("Version "))).check(matches(isDisplayed()))
            onView(tagged("donate")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("source")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("license-notice")).perform(scrollTo())
                .check(matches(withText(org.hamcrest.Matchers.containsString("GNU General Public License"))))
            onView(tagged("license")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }
}
