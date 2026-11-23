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
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        TestEnv.store.expandedCheckin = null
        TestEnv.store.sort = Grouping.SORT_CUSTOM
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
            // One menu: By category, then the four sorts.
            for (how in Grouping.SORTS) onView(tagged("sort:$how")).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("view:category")).inRoot(isDialog()).check(matches(isNotChecked())).perform(click())
            onView(tagged("group:Health")).perform(scrollTo()).check(matches(isDisplayed()))
            assertEquals(Grouping.BY_CATEGORY, TestEnv.store.view)
            // Sorting keeps the grouping; unticking By category ungroups.
            onView(tagged("view-mode")).perform(scrollTo(), click())
            onView(tagged("sort:attention")).inRoot(isDialog()).perform(scrollTo(), click())
            onView(tagged("group:Health")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("view-mode")).perform(scrollTo(), click())
            onView(tagged("view:category")).inRoot(isDialog()).check(matches(isChecked())).perform(click())
            onView(tagged("group:Health")).check(doesNotExist())
        }
        assertEquals(Grouping.UNGROUPED, TestEnv.store.view)
        assertEquals(Grouping.SORT_ATTENTION, TestEnv.store.sort)
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
    fun collapseAllLeavesTheCardsAlone() {
        threeGroups()
        TestEnv.store.expandedCheckin = "m"
        main().use {
            onView(tagged("toggle-all")).perform(scrollTo(), click())
            onView(tagged("group-header:Morning")).perform(scrollTo(), click())
            onView(tagged("card:m")).perform(scrollTo()).check(matches(isDisplayed())) // still open
            onView(tagged("toggle-all")).perform(scrollTo()).check(matches(withText("Collapse all"))) // a group is open
        }
        assertEquals("m", TestEnv.store.expandedCheckin)
    }

    @Test
    fun onlyOneCardIsOpenAtATime() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha"), TestEnv.checkin("b", "Beta")))
        main().use {
            // All folded to start (new ones too).
            onView(tagged("row:a")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("row:b")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("checkin-chevron:a")).check(matches(withText("▸")))
            onView(tagged("next-line:a")).check(matches(withText(org.hamcrest.Matchers.startsWith("Next: "))))
            onView(tagged("toggle:a")).check(matches(isDisplayed())) // the switch is there folded too
            // Opening one...
            onView(tagged("checkin-title:a")).perform(scrollTo(), click())
            onView(tagged("card:a")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("checkin-chevron:a")).check(matches(withText("▾")))
            // ...and then another folds the first.
            onView(tagged("checkin-title:b")).perform(scrollTo(), click())
            onView(tagged("card:b")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("row:a")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("card:a")).check(doesNotExist())
            // Tapping the open one folds it: everything can be folded.
            onView(tagged("checkin-title:b")).perform(scrollTo(), click())
            onView(tagged("row:b")).perform(scrollTo()).check(matches(isDisplayed()))
        }
        assertNull(TestEnv.store.expandedCheckin)
    }

    @Test
    fun foldingWhileScrolledDownNeverFlashesTheTop() {
        TestEnv.reset((1..10).map { TestEnv.checkin("c$it", "Check-in $it") })
        TestEnv.store.expandedCheckin = "c10"
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
        TestEnv.store.expandedCheckin = "b"
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
                // The name sits in the same spot, so folding doesn't nudge it up or down.
                fun pad(tag: String) = root.findViewWithTag<View>(tag).let { listOf(it.paddingLeft, it.paddingTop, it.paddingRight) }
                assertEquals("padding", pad("card:b"), pad("row:a"))
                fun titleOffset(card: String, title: String): Pair<Int, Int> {
                    val c = IntArray(2).also { root.findViewWithTag<View>(card).getLocationOnScreen(it) }
                    val t = IntArray(2).also { root.findViewWithTag<View>(title).getLocationOnScreen(it) }
                    return (t[0] - c[0]) to (t[1] - c[1])
                }
                assertEquals("name position", titleOffset("card:b", "checkin-title:b"), titleOffset("row:a", "checkin-title:a"))
            }
        }
    }

    /** Ids of the check-in cards on screen, top to bottom. */
    private fun cardOrder(scenario: ActivityScenario<MainActivity>, ids: List<String>): List<String> {
        var order = emptyList<String>()
        scenario.onActivity { a ->
            val root = a.window.decorView
            order = ids.mapNotNull { id ->
                val v = root.findViewWithTag<View>("card:$id") ?: root.findViewWithTag<View>("row:$id") ?: return@mapNotNull null
                val xy = IntArray(2).also { v.getLocationInWindow(it) }
                id to xy[1]
            }.sortedBy { it.second }.map { it.first }
        }
        return order
    }

    @Test
    fun sortingReordersTheListAndHidesTheHandles() {
        // My order: Zed, apple, Mid. Next rings: Mid soonest, then Zed; apple is off (no next).
        val now = java.time.LocalTime.now()
        fun at(min: Long) = now.plusMinutes(min).let { AlarmTime(it.hour, it.minute) }
        TestEnv.reset(listOf(
            TestEnv.checkin("z", "Zed").copy(times = listOf(at(120))),
            TestEnv.checkin("a", "apple").copy(enabled = false),
            TestEnv.checkin("m", "Mid").copy(times = listOf(at(30))),
        ))
        val ids = listOf("z", "a", "m")
        main().use { scenario ->
            assertEquals(ids, cardOrder(scenario, ids))
            onView(tagged("view-mode")).check(matches(withText("View: My order ▾")))
            onView(tagged("drag-checkin:z")).check(matches(isDisplayed()))

            onView(tagged("view-mode")).perform(scrollTo(), click())
            onView(tagged("sort:next")).inRoot(isDialog()).perform(scrollTo(), click())
            onView(tagged("view-mode")).check(matches(withText("View: Next alarm ▾")))
            assertEquals(listOf("m", "z", "a"), cardOrder(scenario, ids))
            onView(tagged("drag-checkin:z")).check(doesNotExist()) // no dragging while sorted

            onView(tagged("view-mode")).perform(scrollTo(), click())
            onView(tagged("sort:name")).inRoot(isDialog()).perform(scrollTo(), click())
            assertEquals(listOf("a", "m", "z"), cardOrder(scenario, ids))
        }
        assertEquals("remembered", Grouping.SORT_NAME, TestEnv.store.sort)
        assertEquals("your order is kept", ids, TestEnv.store.checkins.map { it.id })
    }

    @Test
    fun needsAttentionSortsWithinEachGroup() {
        val q = Question("soda", "Soda?")
        TestEnv.reset(listOf(
            TestEnv.checkin("g", "Good", listOf(q)).copy(category = "Health"),
            TestEnv.checkin("b", "Bad", listOf(q)).copy(category = "Health"),
            TestEnv.checkin("o", "Other one", listOf(q)).copy(category = "Fitness"),
        ))
        history("g", 4) // all on track
        history("b", 0) // all off track
        TestEnv.store.view = Grouping.BY_CATEGORY
        TestEnv.store.sort = Grouping.SORT_ATTENTION
        val ids = listOf("g", "b", "o")
        main().use { scenario ->
            // Groups stay A–Z (Fitness, Health); within Health the worst comes first.
            assertEquals(listOf("o", "b", "g"), cardOrder(scenario, ids))
        }
        TestEnv.store.view = Grouping.UNGROUPED
    }

    @Test
    fun collapseAllIsOnlyForGroups() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha"), TestEnv.checkin("b", "Beta")))
        main().use {
            onView(tagged("view-mode")).check(matches(isDisplayed()))
            onView(tagged("toggle-all")).check(doesNotExist())
        }
    }

    @Test
    fun theOldPerCardFoldingIsDropped() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha"), TestEnv.checkin("b", "Beta")))
        val prefs = TestEnv.ctx.getSharedPreferences("gist", android.content.Context.MODE_PRIVATE)
        prefs.edit().putStringSet("collapsedCheckins", setOf("a")).putString("style", "compact").commit()
        assertNull("all folded to start", TestEnv.store.expandedCheckin)
        TestEnv.store.expandedCheckin = "b"
        assertEquals("b", TestEnv.store.expandedCheckin)
        assertFalse(prefs.contains("collapsedCheckins") || prefs.contains("style"))
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

    @Test
    fun checkinsTasksAndBacklogTabs() {
        val task = TestEnv.checkin("t", "Call John").copy(task = true, times = emptyList()) // on: active
        val backlog = TestEnv.checkin("b", "Fix the fence").copy(task = true, times = emptyList(), enabled = false)
        val done = TestEnv.checkin("d", "Done thing").copy(task = true, times = emptyList(), enabled = false,
            doneAt = System.currentTimeMillis())
        TestEnv.reset(listOf(TestEnv.checkin("c", "Daily"), task, backlog, done))
        main().use {
            // By default: the Check-ins tab, and no header above the list.
            onView(tagged("tab:checkins")).check(matches(withText("Check-ins")))
            onView(tagged("tab:tasks")).check(matches(withText("Tasks")))
            onView(tagged("tab:backlog")).check(matches(withText("Backlog")))
            onView(tagged("add")).check(matches(withText("+")))
            onView(withText("CHECK-INS")).check(doesNotExist())
            onView(tagged("checkin-title:c")).check(matches(isDisplayed()))
            onView(tagged("checkin-title:t")).check(doesNotExist())
            onView(tagged("checkin-title:b")).check(doesNotExist())
            onView(tagged("done-tasks")).check(doesNotExist())

            // Tasks: just the active tasks, and the Done tasks button.
            onView(tagged("tab:tasks")).perform(click())
            onView(tagged("checkin-title:t")).check(matches(isDisplayed()))
            onView(tagged("checkin-title:c")).check(doesNotExist())
            onView(tagged("checkin-title:b")).check(doesNotExist())
            onView(tagged("done-tasks")).perform(scrollTo()).check(matches(isDisplayed()))
            assertEquals(Store.TAB_TASKS, TestEnv.store.startTab)

            // Backlog: just the backlog.
            onView(tagged("tab:backlog")).perform(click())
            onView(tagged("checkin-title:b")).check(matches(isDisplayed()))
            onView(tagged("checkin-title:t")).check(doesNotExist())
            onView(tagged("done-tasks")).check(doesNotExist())

            // + on the Backlog tab makes a new backlog task.
            onView(tagged("add")).perform(click())
            TestEnv.eventually { TestEnv.openSection(CheckinEditActivity.SECTION_SCHEDULE) }
            TestEnv.eventually { onView(tagged("task-backlog")).check(matches(isDisplayed())) }
        }
        assertEquals(Store.TAB_BACKLOG, TestEnv.store.startTab)
        assertEquals(1, TestEnv.store.checkins.count { it.id !in setOf("c", "t", "b", "d") && it.inBacklog })
    }

    @Test
    fun eachTabHasItsOwnViewAndFolding() {
        fun task(id: String, name: String) = TestEnv.checkin(id, name).copy(task = true, times = emptyList())
        TestEnv.reset(listOf(TestEnv.checkin("c1", "Daily"), TestEnv.checkin("c2", "Evening"),
            task("t1", "Call John"), task("t2", "Buy milk")))
        TestEnv.store.setSortFor(Store.TAB_CHECKINS, Grouping.SORT_NEXT)
        main().use {
            onView(tagged("view-mode")).check(matches(withText("View: Next alarm ▾")))
            // Tasks start in My order (drag them into priority order).
            onView(tagged("tab:tasks")).perform(click())
            onView(tagged("view-mode")).check(matches(withText("View: My order ▾")))
            onView(tagged("drag-checkin:t1")).check(matches(isDisplayed()))
            onView(tagged("view-mode")).perform(click())
            onView(tagged("sort:${Grouping.SORT_NAME}")).perform(click())
            onView(tagged("view-mode")).check(matches(withText("View: Name ▾")))
            onView(tagged("tab:checkins")).perform(click())
            onView(tagged("view-mode")).check(matches(withText("View: Next alarm ▾")))
        }
        assertEquals(Grouping.SORT_NEXT, TestEnv.store.sortFor(Store.TAB_CHECKINS))
        assertEquals(Grouping.SORT_NAME, TestEnv.store.sortFor(Store.TAB_TASKS))
        assertEquals(Grouping.SORT_CUSTOM, TestEnv.store.sortFor(Store.TAB_BACKLOG))
    }

    @Test
    fun settingsIsAtTheTop() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Daily")))
        main().use { scenario ->
            onView(tagged("settings")).check(matches(withContentDescription("Settings")))
            onView(tagged("about")).check(doesNotExist()) // in Settings
            onView(tagged("donate")).check(doesNotExist())
            onView(withText("Settings · backup · appearance")).check(doesNotExist())
            // 💲 ❓ ⚙️, with the gear at the far right.
            onView(tagged("settings")).perform(click())
            TestEnv.eventually { onView(withText("Settings")).check(matches(isDisplayed())) }
        }
    }

    @Test
    fun plusOnTheTasksTabMakesAnActiveTask() {
        TestEnv.reset(emptyList())
        TestEnv.store.startTab = Store.TAB_TASKS
        main().use {
            onView(tagged("add")).perform(click())
            TestEnv.eventually { onView(tagged("checkin-name")).check(matches(isDisplayed())) }
        }
        val c = TestEnv.store.checkins.single()
        assertTrue(c.isTask)
        assertTrue("active, not in the backlog", c.enabled)
    }

    @Test
    fun checkinsHaveADotAndTasksNone() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Daily"), TestEnv.checkin("t", "Call John").copy(task = true, times = emptyList())))
        TestEnv.store.expandedCheckin = "c"
        main().use { scenario ->
            onView(tagged("card:c")).perform(scrollTo())
            scenario.onActivity { act ->
                val dot = act.window.decorView.findViewWithTag<View>("marker:c")
                assertEquals(android.graphics.drawable.GradientDrawable.OVAL,
                    (dot.background as android.graphics.drawable.GradientDrawable).shape)
            }
            onView(tagged("tab:tasks")).perform(click())
            onView(tagged("row:t")).perform(scrollTo())
            onView(tagged("marker:t")).check(doesNotExist())
        }
    }

    @Test
    fun theDragHandleDoesNotMakeCardsTaller() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Daily"), TestEnv.checkin("b", "Evening")))
        TestEnv.store.expandedCheckin = "a"
        fun heights(sort: String): List<Int> {
            TestEnv.store.sort = sort
            var h = emptyList<Int>()
            main().use { scenario ->
                onView(tagged("card:a")).perform(scrollTo())
                scenario.onActivity { act ->
                    val root = act.window.decorView
                    h = listOf("checkin-header:a", "card:a").map { root.findViewWithTag<View>(it).height }
                }
            }
            return h
        }
        val custom = heights(Grouping.SORT_CUSTOM) // with ≡ handles
        val byName = heights(Grouping.SORT_NAME)   // without
        assertEquals("title line and card are the same height either way", byName, custom)
    }

    @Test
    fun theTitleLineStaysPutWhenALongTitleUnfolds() {
        val long = "Paint the latch screw heads white, maybe with nail polish, before the weekend"
        TestEnv.reset(listOf(TestEnv.checkin("a", long), TestEnv.checkin("b", "Short")))
        // Where each thing on the title line sits, from the top of its card.
        fun tops(): List<Int> {
            var t = emptyList<Int>()
            main().use { scenario ->
                onView(tagged("checkin-title:a")).perform(scrollTo())
                scenario.onActivity { act ->
                    val root = act.window.decorView
                    fun y(tag: String) = IntArray(2).also { root.findViewWithTag<View>(tag).getLocationOnScreen(it) }[1]
                    val card = y(if (TestEnv.store.expandedCheckin == "a") "card:a" else "row:a")
                    t = listOf("checkin-title:a", "drag-checkin:a", "marker:a", "checkin-chevron:a", "toggle:a").map { y(it) - card }
                }
            }
            return t
        }
        TestEnv.store.sort = Grouping.SORT_CUSTOM // with drag handles
        val folded = tops()
        TestEnv.store.expandedCheckin = "a"
        val open = tops()
        assertEquals("nothing on the title line moves", folded, open)
    }

    @Test
    fun plusIsAsTallAsTheTabs() {
        TestEnv.reset(emptyList())
        main().use { scenario ->
            scenario.onActivity { act ->
                val root = act.window.decorView
                val tab = root.findViewWithTag<View>("tab:checkins")
                val plus = root.findViewWithTag<View>("add")
                assertEquals(tab.height, plus.height)
                assertEquals("a circle", plus.height, plus.width)
            }
        }
    }

    @Test
    fun tappingACardsBodyFoldsItButItsButtonsDontFold() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha"), TestEnv.checkin("b", "Beta")))
        TestEnv.store.expandedCheckin = "a"
        main().use {
            // Its buttons do their own thing (History opens; back leaves the card open).
            onView(allOf(withText("History"), isDescendantOfA(tagged("card:a")))).perform(scrollTo(), click())
            TestEnv.eventually { onView(withText("History · last 30 days")).check(matches(isDisplayed())) }
            androidx.test.espresso.Espresso.pressBack()
            onView(tagged("card:a")).perform(scrollTo()).check(matches(isDisplayed()))
            // A plain line of text on the card folds it.
            onView(tagged("summary:a")).perform(scrollTo(), click())
            onView(tagged("row:a")).perform(scrollTo()).check(matches(isDisplayed()))
        }
        assertNull(TestEnv.store.expandedCheckin)
        // And the body of a folded row opens it.
        main().use {
            onView(tagged("next-line:b")).perform(scrollTo(), click())
            onView(tagged("card:b")).perform(scrollTo()).check(matches(isDisplayed()))
        }
        assertEquals("b", TestEnv.store.expandedCheckin)
    }
}
