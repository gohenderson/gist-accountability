package dev.gist.checkin

import android.graphics.Rect
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "When it rings": choosing steps, their own pages, and drag-to-reorder. */
@RunWith(AndroidJUnit4::class)
class StepsTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private val q = Question("q1", "Did you stretch?", true)

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    private fun edit(id: String) = ActivityScenario.launch<CheckinEditActivity>(CheckinEditActivity.intent(TestEnv.ctx, id))

    /** A view's bounds on screen, found by its tag. */
    private fun boundsOf(tag: String): Rect {
        val r = Rect()
        onView(tagged(tag)).check { v, e ->
            if (e != null) throw e
            val xy = IntArray(2)
            v.getLocationOnScreen(xy)
            r.set(xy[0], xy[1], xy[0] + v.width, xy[1] + v.height)
        }
        return r
    }

    private fun kinds() = TestEnv.store.checkin("c")!!.steps.map { it.kind }

    @Test
    fun stepsAreListedInTheirOrder() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Wake", listOf(q), mathCount = 2).let { it.copy(steps = it.steps.reversed()) }))
        edit("c").use {
            onView(withText("1. Questions")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("2. Math problems")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("answer 1 question → solve 2 math")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun draggingAStepAboveAnotherReordersThem() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Wake", listOf(q), mathCount = 2, scanCode = "CODE")))
        edit("c").use {
            onView(tagged("step:2")).perform(scrollTo())
            onView(tagged("step:0")).perform(scrollTo())
            val handle = boundsOf("drag:2")
            val top = boundsOf("step:0")
            // Drag Questions (3rd) up past Math (1st), like a finger would.
            dragVertically(handle.centerX(), handle.centerY(), top.top - 10)
            TestEnv.eventually { assertEquals(listOf(Steps.QUESTIONS, Steps.MATH, Steps.SCAN), kinds()) }
            TestEnv.eventually { onView(withText("1. Questions")).check(matches(isDisplayed())) }
        }
    }

    /** Touch down, move in small steps (letting the list relayout), lift. */
    private fun dragVertically(x: Int, fromY: Int, toY: Int) {
        val inst = TestEnv.instrumentation
        val start = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, y: Int) {
            val ev = android.view.MotionEvent.obtain(start, android.os.SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            inst.sendPointerSync(ev)
            ev.recycle()
        }
        send(android.view.MotionEvent.ACTION_DOWN, fromY)
        val steps = 30
        for (i in 1..steps) {
            send(android.view.MotionEvent.ACTION_MOVE, fromY + (toY - fromY) * i / steps)
            android.os.SystemClock.sleep(30)
        }
        android.os.SystemClock.sleep(200)
        send(android.view.MotionEvent.ACTION_MOVE, toY)
        send(android.view.MotionEvent.ACTION_UP, toY)
        inst.waitForIdleSync()
    }

    @Test
    fun addingAStepOpensItsPage() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Plain")))
        edit("c").use {
            onView(tagged("add-step")).perform(scrollTo(), click())
            onView(tagged("add:math")).inRoot(isDialog()).perform(click())
            onView(tagged("math-count")).check(matches(isDisplayed()))
            onView(tagged("math-level:4")).perform(scrollTo(), click())
        }
        val st = TestEnv.store.checkin("c")!!.steps.single()
        assertEquals(Steps.MATH, st.kind)
        assertEquals(3, st.mathCount)
        assertEquals(4, st.mathLevel)
    }

    @Test
    fun anyKindCanBeAddedAgainAndAgain() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Lots", listOf(q), mathCount = 2, scanCode = "CODE")))
        edit("c").use {
            repeat(3) {
                onView(tagged("add-step")).perform(scrollTo(), click())
                onView(tagged("add:math")).inRoot(isDialog()).perform(click())
                Espresso.pressBack() // back from the new step's page
            }
            onView(tagged("add-step")).perform(scrollTo()).check(matches(isDisplayed())) // never disappears
            onView(withText("6. Math problems")).perform(scrollTo()).check(matches(isDisplayed()))
        }
        assertEquals(listOf(Steps.MATH, Steps.SCAN, Steps.QUESTIONS, Steps.MATH, Steps.MATH, Steps.MATH), kinds())
    }

    @Test
    fun duplicatingAStepAddsACopyRightAfterIt() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Hard", mathCount = 10, mathLevel = 4)))
        ActivityScenario.launch<StepEditActivity>(StepEditActivity.intent(TestEnv.ctx, "c", "m")).use {
            onView(tagged("duplicate-step")).perform(scrollTo(), click())
        }
        val steps = TestEnv.store.checkin("c")!!.steps
        assertEquals(2, steps.size)
        assertEquals(listOf(10, 10), steps.map { it.mathCount })
        assertEquals(listOf(4, 4), steps.map { it.mathLevel })
        assertTrue("its own id", steps[0].id != steps[1].id)
    }

    @Test
    fun upToAHundredProblemsPerStep() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Hard", mathCount = 10, mathLevel = 4)))
        ActivityScenario.launch<StepEditActivity>(StepEditActivity.intent(TestEnv.ctx, "c", "m")).use { s ->
            s.onActivity { a ->
                val picker = a.window.decorView.findViewWithTag<android.widget.NumberPicker>("math-count")
                assertEquals(Steps.MAX_PROBLEMS, picker.maxValue)
            }
        }
    }

    @Test
    fun aScanStepWithoutACodeSaysSo() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Plain").copy(steps = listOf(StepConfig("s", Steps.SCAN)))))
        edit("c").use {
            onView(withText("Not set up: register a code")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("step:0")).perform(scrollTo(), click())
            onView(tagged("register-code")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun scanPageSavesTheSpotName() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Teeth", scanCode = "CODE")))
        ActivityScenario.launch<StepEditActivity>(StepEditActivity.intent(TestEnv.ctx, "c", "s")).use {
            onView(tagged("scan-label")).perform(
                androidx.test.espresso.action.ViewActions.replaceText("Toothbrush holder"),
                androidx.test.espresso.action.ViewActions.closeSoftKeyboard(),
            )
        }
        assertEquals("Toothbrush holder", TestEnv.store.checkin("c")!!.steps.single().scanLabel)
    }

    @Test
    fun removingAStepKeepsTheOthersInOrder() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Wake", listOf(q), mathCount = 2, scanCode = "CODE")))
        ActivityScenario.launch<StepEditActivity>(StepEditActivity.intent(TestEnv.ctx, "c", "s")).use {
            onView(withText("Remove this step")).perform(scrollTo(), click())
            onView(withText("Remove")).inRoot(isDialog()).perform(click())
        }
        assertEquals(listOf(Steps.MATH, Steps.QUESTIONS), kinds())
    }

    @Test
    fun noStepsMeansDismissOnly() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Plain")))
        edit("c").use {
            onView(withText("No steps: the alarm just shows a Dismiss button.")).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }
}
