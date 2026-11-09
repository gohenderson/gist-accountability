package dev.gist.checkin

import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.containsString
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
        TestEnv.store.collapsedCheckins = emptySet()
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
            onView(tagged("toggle-all")).perform(scrollTo()).check(matches(withText("Collapse all")))
            onView(tagged("toggle-all")).perform(click())
            for (id in listOf("m", "e", "f")) onView(tagged("toggle:$id")).check(doesNotExist())
            onView(tagged("toggle-all")).perform(scrollTo()).check(matches(withText("Expand all")))
            onView(tagged("toggle-all")).perform(click())
            for (id in listOf("m", "e", "f")) onView(tagged("toggle:$id")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun collapseAllAlsoFoldsTheCheckins() {
        threeGroups()
        main().use {
            onView(tagged("toggle-all")).perform(scrollTo(), click())
            // Opening a group shows its check-ins folded to one line.
            onView(tagged("group-header:Morning")).perform(scrollTo(), click())
            onView(tagged("row:m")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("card:m")).check(doesNotExist())
            onView(tagged("toggle-all")).perform(scrollTo()).check(matches(withText("Collapse all"))) // not all folded now
        }
        assertEquals(setOf("m", "e", "f"), TestEnv.store.collapsedCheckins)
    }

    @Test
    fun tappingTheTitleFoldsAndUnfoldsACheckin() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha"), TestEnv.checkin("b", "Beta")))
        main().use {
            onView(tagged("card:a")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("checkin-chevron:a")).check(matches(withText("▾")))
            onView(tagged("checkin-title:a")).perform(scrollTo(), click())
            onView(tagged("row:a")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("card:a")).check(doesNotExist())
            onView(tagged("checkin-chevron:a")).check(matches(withText("▸")))
            onView(tagged("next-line:a")).check(matches(withText(org.hamcrest.Matchers.startsWith("Next: "))))
            onView(tagged("card:b")).perform(scrollTo()).check(matches(isDisplayed())) // the other stays open
            onView(tagged("toggle:a")).check(matches(isDisplayed())) // the switch is still there
            onView(tagged("checkin-title:a")).perform(scrollTo(), click())
            onView(tagged("card:a")).perform(scrollTo()).check(matches(isDisplayed()))
        }
        assertTrue(TestEnv.store.collapsedCheckins.isEmpty())
    }

    @Test
    fun foldingWhileScrolledDownNeverFlashesTheTop() {
        TestEnv.reset((1..10).map { TestEnv.checkin("c$it", "Check-in $it") })
        main().use { scenario ->
            var sv: android.widget.ScrollView? = null
            val frames = java.util.Collections.synchronizedList(mutableListOf<Int>())
            var recording = false
            scenario.onActivity { a ->
                sv = a.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.widget.ScrollView
                sv!!.scrollTo(0, sv!!.getChildAt(0).height) // all the way down
                // The decor view stays across page rebuilds: record where every frame is drawn.
                a.window.decorView.viewTreeObserver.addOnDrawListener {
                    if (recording) {
                        val page = a.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.widget.ScrollView
                        frames += page.scrollY
                    }
                }
            }
            var before = 0
            scenario.onActivity { before = sv!!.scrollY; recording = true }
            assertTrue("scrolled down: $before", before > 0)
            onView(tagged("checkin-title:c10")).perform(click()) // fold the last one
            onView(tagged("row:c10")).check(matches(isDisplayed()))
            scenario.onActivity { recording = false }
            assertTrue("drew something", frames.isNotEmpty())
            assertTrue("never drawn at the top: $frames", frames.none { it == 0 })
        }
    }

    @Test
    fun foldedAndOpenCardsLookAlike() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha"), TestEnv.checkin("b", "Beta")))
        TestEnv.store.collapsedCheckins = setOf("a")
        main().use { scenario ->
            onView(tagged("row:a")).perform(scrollTo())
            onView(tagged("card:b")).perform(scrollTo())
            scenario.onActivity { act ->
                val root = act.window.decorView
                fun bg(tag: String) = root.findViewWithTag<View>(tag).background as android.graphics.drawable.GradientDrawable
                val folded = bg("row:a")
                val open = bg("card:b")
                assertEquals("corners", open.cornerRadius, folded.cornerRadius, 0.01f)
                assertEquals("color", open.color?.defaultColor, folded.color?.defaultColor)
                fun size(tag: String) = root.findViewWithTag<android.widget.TextView>(tag).textSize
                assertEquals("name size", size("checkin-title:b"), size("checkin-title:a"), 0.01f)
            }
        }
    }

    @Test
    fun collapseAllFoldsCardsWhenUngrouped() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha"), TestEnv.checkin("b", "Beta")))
        main().use {
            onView(tagged("toggle-all")).perform(scrollTo()).check(matches(withText("Collapse all")))
            onView(tagged("toggle-all")).perform(click())
            for (id in listOf("a", "b")) onView(tagged("row:$id")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("History")).check(doesNotExist())
            onView(tagged("toggle-all")).perform(scrollTo()).check(matches(withText("Expand all")))
            onView(tagged("toggle-all")).perform(click())
            for (id in listOf("a", "b")) onView(tagged("card:$id")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun theOldCompactLayoutStartsFolded() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha"), TestEnv.checkin("b", "Beta")))
        TestEnv.ctx.getSharedPreferences("gist", android.content.Context.MODE_PRIVATE).edit()
            .putString("style", "compact").commit()
        assertEquals(setOf("a", "b"), TestEnv.store.collapsedCheckins)
        TestEnv.store.collapsedCheckins = setOf("b") // the first change replaces the old setting
        assertEquals(setOf("b"), TestEnv.store.collapsedCheckins)
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
            // Rows and groups show their score only as color (and read it out); no numbers.
            for (id in listOf("a", "b", "c")) onView(tagged("score:$id")).check(doesNotExist())
            onView(withText("100%")).check(doesNotExist())
            onView(tagged("checkin-header:a")).perform(scrollTo()).check(matches(withContentDescription(containsString("100% on track"))))
            onView(tagged("checkin-header:b")).perform(scrollTo()).check(matches(withContentDescription(containsString("50% on track"))))
            onView(tagged("group-header:Morning")).perform(scrollTo()).check(matches(withContentDescription(containsString("75% on track"))))
            onView(tagged("group-score:Morning")).check(doesNotExist())
            assertEquals(Score.color(0.75, bad, good), textColorOf("group:Morning"))
            assertEquals(bad, textColorOf("group:Evening"))
            // The overall number stays at the top.
            onView(tagged("overall-score")).perform(scrollTo()).check(matches(withText("50%")))
            assertEquals(Score.color(0.5, bad, good), textColorOf("overall-score"))
        }
    }

    @Test
    fun theCheckinPageShowsItsScore() {
        val q = Question("soda", "Soda?")
        TestEnv.reset(listOf(TestEnv.checkin("a", "Half", listOf(q))))
        history("a", 2)
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, "a")).use {
            onView(tagged("checkin-score")).check(matches(withText("50%")))
            assertEquals(Score.color(0.5, TestEnv.ctx.getColor(R.color.bad), TestEnv.ctx.getColor(R.color.good)),
                textColorOf("checkin-score"))
        }
    }

    @Test
    fun noHistoryMeansNoScoreOnItsPage() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "New", listOf(Question("soda", "Soda?")))))
        ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, "a")).use {
            onView(tagged("checkin-name")).check(matches(withText("New")))
            onView(tagged("checkin-score")).check(doesNotExist())
        }
    }

    @Test
    fun noHistoryMeansNoScore() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "New", listOf(Question("soda", "Soda?")))))
        main().use {
            onView(tagged("checkin-header:a")).check(matches(withContentDescription(org.hamcrest.Matchers.not(containsString("on track")))))
            onView(tagged("overall-score")).check(doesNotExist())
        }
    }
}
