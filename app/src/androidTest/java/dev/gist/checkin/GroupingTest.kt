package dev.gist.checkin

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Start page: grouping by category, and dragging check-ins into your own order. */
@RunWith(AndroidJUnit4::class)
class GroupingTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    @Before
    fun setUp() {
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.POST_NOTIFICATIONS")
        TestEnv.allowOverlay(true) // keep the "Finish setup" card short
    }

    @After
    fun tearDown() {
        TestEnv.allowOverlay(false)
        TestEnv.store.view = Grouping.UNGROUPED
        TestEnv.reset(emptyList())
    }

    private fun main() = ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java))

    @Test
    fun groupedViewShowsASectionPerCategory() {
        TestEnv.reset(listOf(
            TestEnv.checkin("a", "Meds").copy(category = "Health"),
            TestEnv.checkin("b", "Loose one"),
            TestEnv.checkin("c", "Run").copy(category = "Fitness"),
        ))
        TestEnv.store.view = Grouping.BY_CATEGORY
        main().use {
            onView(tagged("group:Fitness")).perform(scrollTo()).check(matches(withText("Fitness · 1")))
            onView(tagged("group:Health")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("group:Other")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun theViewIsChosenFromTheViewMenu() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "A").copy(category = "Health"), TestEnv.checkin("b", "B")))
        main().use {
            onView(tagged("view-mode")).perform(scrollTo(), click())
            onView(tagged("view:category")).inRoot(isDialog()).perform(click())
            onView(tagged("group:Health")).perform(scrollTo()).check(matches(isDisplayed()))
        }
        assertEquals(Grouping.BY_CATEGORY, TestEnv.store.view)
    }

    @Test
    fun draggingACheckinMovesIt() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "First"), TestEnv.checkin("b", "Second"), TestEnv.checkin("c", "Third")))
        main().use {
            onView(tagged("drag-checkin:c")).perform(scrollTo())
            val handle = TestEnv.boundsOf("drag-checkin:c")
            val target = TestEnv.boundsOf("drag-checkin:a")
            TestEnv.dragVertically(handle.centerX(), handle.centerY(), target.top - 60)
            TestEnv.eventually { assertEquals(listOf("c", "a", "b"), TestEnv.store.checkins.map { it.id }) }
        }
    }

    @Test
    fun aCategoryIsSetOnTheEditPage() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "A")))
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, "a")).use {
            onView(tagged("category")).perform(scrollTo(), click())
            onView(tagged("category:new")).inRoot(isDialog()).perform(click())
            onView(tagged("category-name")).inRoot(isDialog()).perform(replaceText("Health"))
            onView(withText("Save")).inRoot(isDialog()).perform(click())
            onView(withText("Category: Health")).perform(scrollTo()).check(matches(isDisplayed()))
        }
        assertEquals("Health", TestEnv.store.checkin("a")!!.category)
    }
}
