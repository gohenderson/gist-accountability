package dev.gist.checkin

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        TestEnv.store.style = Grouping.CARDS
        TestEnv.store.groupOrder = emptyList()
        TestEnv.store.collapsed = emptySet()
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
            onView(tagged("group:Fitness")).perform(scrollTo()).check(matches(withText("Fitness")))
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

    private fun threeGroups() {
        TestEnv.reset(listOf(
            TestEnv.checkin("m", "Wake").copy(category = "Morning"),
            TestEnv.checkin("e", "Wind down").copy(category = "Evening"),
            TestEnv.checkin("f", "Run").copy(category = "Fitness"),
        ))
        TestEnv.store.view = Grouping.BY_CATEGORY
        TestEnv.store.groupOrder = emptyList()
        TestEnv.store.collapsed = emptySet()
    }

    private fun shownGroups() = Grouping.byCategory(TestEnv.store.checkins, TestEnv.store.groupOrder).map { it.first }

    @Test
    fun draggingAGroupMovesIt() {
        threeGroups()
        assertEquals(listOf("Evening", "Fitness", "Morning"), shownGroups()) // A–Z to start
        main().use {
            onView(tagged("drag-group:Morning")).perform(scrollTo())
            onView(tagged("drag-group:Evening")).perform(scrollTo())
            val handle = TestEnv.boundsOf("drag-group:Morning")
            val target = TestEnv.boundsOf("drag-group:Evening")
            TestEnv.dragVertically(handle.centerX(), handle.centerY(), target.top - 40)
            TestEnv.eventually { assertEquals(listOf("Morning", "Evening", "Fitness"), shownGroups()) }
        }
    }

    @Test
    fun groupsCollapseAndExpand() {
        threeGroups()
        main().use {
            onView(tagged("toggle:m")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("group-header:Morning")).perform(scrollTo(), click())
            onView(tagged("toggle:m")).check(doesNotExist())
            onView(tagged("group-chevron:Morning")).check(matches(withText("▸")))
            onView(tagged("toggle:e")).perform(scrollTo()).check(matches(isDisplayed())) // others stay open
            onView(tagged("group-header:Morning")).perform(scrollTo(), click())
            onView(tagged("toggle:m")).perform(scrollTo()).check(matches(isDisplayed()))
        }
        assertTrue(TestEnv.store.collapsed.isEmpty())
    }

    @Test
    fun collapseAllAndExpandAll() {
        threeGroups()
        main().use {
            onView(tagged("toggle-all-groups")).perform(scrollTo()).check(matches(withText("Collapse all")))
            onView(tagged("toggle-all-groups")).perform(click())
            for (id in listOf("m", "e", "f")) onView(tagged("toggle:$id")).check(doesNotExist())
            onView(tagged("toggle-all-groups")).perform(scrollTo()).check(matches(withText("Expand all")))
            onView(tagged("toggle-all-groups")).perform(click())
            for (id in listOf("m", "e", "f")) onView(tagged("toggle:$id")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun compactLayoutShowsOneLineEach() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha"), TestEnv.checkin("b", "Beta")))
        main().use {
            onView(tagged("style-mode")).perform(scrollTo(), click())
            onView(tagged("style:compact")).inRoot(isDialog()).perform(click())
            onView(tagged("row:a")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("next-line:a")).check(matches(withText(org.hamcrest.Matchers.startsWith("Next: "))))
            onView(withText("History")).check(doesNotExist()) // card-only actions are gone
            onView(tagged("row:b")).perform(scrollTo(), click())
            onView(tagged("checkin-name")).check(matches(withText("Beta"))) // tapping opens its page
        }
        assertEquals(Grouping.COMPACT, TestEnv.store.style)
    }

    /** [id] answered on track on [good] of the last 4 days, off track on the rest. */
    private fun history(id: String, good: Int) {
        Days.lastN(4).forEachIndexed { i, day -> TestEnv.store.saveAnswers(day, id, "12:00", mapOf("soda" to (i >= good))) }
    }

    private fun textColorOf(tag: String): Int {
        var color = 0
        onView(tagged(tag)).check { v, e -> if (e != null) throw e; color = (v as android.widget.TextView).currentTextColor }
        return color
    }

    @Test
    fun scoresAreColoredAndRollUp() {
        val q = Question("soda", "Soda?")
        TestEnv.reset(listOf(
            TestEnv.checkin("a", "All good", listOf(q)).copy(category = "Morning"),
            TestEnv.checkin("b", "Half", listOf(q)).copy(category = "Morning"),
            TestEnv.checkin("c", "None good", listOf(q)).copy(category = "Evening"),
        ))
        history("a", 4); history("b", 2); history("c", 0)
        TestEnv.store.view = Grouping.BY_CATEGORY
        val good = TestEnv.ctx.getColor(R.color.good)
        val bad = TestEnv.ctx.getColor(R.color.bad)
        main().use {
            onView(tagged("score:a")).perform(scrollTo()).check(matches(withText("100%")))
            assertEquals(good, textColorOf("score:a"))
            onView(tagged("score:b")).perform(scrollTo()).check(matches(withText("50%")))
            onView(tagged("score:c")).perform(scrollTo()).check(matches(withText("0%")))
            assertEquals(bad, textColorOf("score:c"))
            onView(tagged("group-score:Morning")).perform(scrollTo()).check(matches(withText("75%")))
            onView(tagged("group-score:Evening")).perform(scrollTo()).check(matches(withText("0%")))
            assertEquals(bad, textColorOf("group:Evening"))
            onView(tagged("overall-score")).perform(scrollTo()).check(matches(withText("50%")))
            assertEquals(Score.color(0.5, bad, good), textColorOf("overall-score"))
        }
    }

    @Test
    fun noHistoryMeansNoScore() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "New", listOf(Question("soda", "Soda?")))))
        main().use {
            onView(tagged("score:a")).check(doesNotExist())
            onView(tagged("overall-score")).check(doesNotExist())
        }
    }
}
