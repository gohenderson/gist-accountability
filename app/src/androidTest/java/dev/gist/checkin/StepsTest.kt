package dev.gist.checkin

import android.graphics.Rect
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.startsWith
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun stepsAreListedInTheirOrder() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Wake", listOf(q), mathCount = 2).copy(steps = listOf(Steps.QUESTIONS, Steps.MATH))))
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
            onView(tagged("step:questions")).perform(scrollTo())
            onView(tagged("step:math")).perform(scrollTo())
            val handle = boundsOf("drag:questions")
            val top = boundsOf("step:math")
            // Drag Questions (3rd) up past Math (1st), like a finger would.
            dragVertically(handle.centerX(), handle.centerY(), top.top - 10)
            TestEnv.eventually {
                assertEquals(listOf(Steps.QUESTIONS, Steps.MATH, Steps.SCAN), Steps.order(TestEnv.store.checkin("c")!!))
            }
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
            onView(withText(startsWith("Math problems"))).inRoot(isDialog()).perform(click())
            onView(tagged("math-count")).check(matches(isDisplayed()))
            onView(tagged("math-level:4")).perform(scrollTo(), click())
        }
        val c = TestEnv.store.checkin("c")!!
        assertEquals(listOf(Steps.MATH), Steps.order(c))
        assertEquals(3, c.mathCount)
        assertEquals(4, c.mathLevel)
    }

    @Test
    fun aScanStepWithoutACodeSaysSo() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Plain").copy(steps = listOf(Steps.SCAN))))
        edit("c").use {
            onView(withText("Not set up: register a code")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(tagged("step:scan")).perform(scrollTo(), click())
            onView(tagged("register-code")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun scanPageSavesTheSpotName() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Teeth", scanCode = "CODE")))
        ActivityScenario.launch<StepEditActivity>(StepEditActivity.intent(TestEnv.ctx, "c", Steps.SCAN)).use {
            onView(tagged("scan-label")).perform(
                androidx.test.espresso.action.ViewActions.replaceText("Toothbrush holder"),
                androidx.test.espresso.action.ViewActions.closeSoftKeyboard(),
            )
        }
        assertEquals("Toothbrush holder", TestEnv.store.checkin("c")!!.scanLabel)
    }

    @Test
    fun removingAStepClearsItAndTheRestKeepTheirOrder() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Wake", listOf(q), mathCount = 2, scanCode = "CODE")
            .copy(steps = listOf(Steps.SCAN, Steps.QUESTIONS, Steps.MATH))))
        ActivityScenario.launch<StepEditActivity>(StepEditActivity.intent(TestEnv.ctx, "c", Steps.QUESTIONS)).use {
            onView(withText("Remove this step")).perform(scrollTo(), click())
            onView(withText("Remove")).inRoot(isDialog()).perform(click())
        }
        val c = TestEnv.store.checkin("c")!!
        assertTrue(c.questions.isEmpty())
        assertEquals(listOf(Steps.SCAN, Steps.MATH), Steps.order(c))
        edit("c").use {
            onView(tagged("step:questions")).check(doesNotExist())
        }
    }

    @Test
    fun noStepsMeansDismissOnly() {
        TestEnv.reset(listOf(TestEnv.checkin("c", "Plain")))
        edit("c").use {
            onView(withText("No steps: the alarm just shows a Dismiss button.")).perform(scrollTo()).check(matches(isDisplayed()))
        }
        assertNull(TestEnv.store.checkin("c")!!.scanCode)
    }
}
