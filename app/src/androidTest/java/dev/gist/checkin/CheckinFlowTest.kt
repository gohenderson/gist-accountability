package dev.gist.checkin

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The alarm screen: it can't be escaped, and each step behaves. */
@RunWith(AndroidJUnit4::class)
class CheckinFlowTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private val soda = Question("soda", "Did you drink soda today?")
    private val veg = Question("veg", "Did you eat vegetables today?", yesIsGood = true)

    private fun launchAlarmScreen(): ActivityScenario<CheckinActivity> =
        ActivityScenario.launch(Intent(TestEnv.ctx, CheckinActivity::class.java))

    private fun pendingToday(id: String) = Pending(id, TestEnv.today())

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    @Test
    fun backButtonDoesNotEscapeTheAlarm() {
        TestEnv.reset(listOf(TestEnv.checkin("eve", "Evening", listOf(soda))), listOf(pendingToday("eve")))
        launchAlarmScreen().use { scenario ->
            Espresso.pressBackUnconditionally()
            onView(withText(soda.text)).check(matches(isDisplayed()))
            assertNotEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
        assertEquals(1, TestEnv.store.pending.size)
    }

    @Test
    fun submitStaysDisabledUntilEveryQuestionIsAnsweredThenLogs() {
        TestEnv.reset(listOf(TestEnv.checkin("eve", "Evening", listOf(soda, veg))), listOf(pendingToday("eve")))
        launchAlarmScreen().use {
            onView(withText("2 left to answer")).perform(scrollTo()).check(matches(not(isEnabled())))
            onView(tagged("q:soda:no")).perform(scrollTo(), click())
            onView(withText("1 left to answer")).perform(scrollTo()).check(matches(not(isEnabled())))
            onView(tagged("q:veg:yes")).perform(scrollTo(), click())
            onView(withText("Submit")).perform(scrollTo()).check(matches(isEnabled())).perform(click())
        }
        assertEquals(mapOf("soda" to false, "veg" to true), TestEnv.store.entry(TestEnv.today()))
        assertTrue("check-in should no longer be waiting", TestEnv.store.pending.isEmpty())
    }

    @Test
    fun wrongMathAnswerGivesANewProblemAndRightAnswersFinish() {
        TestEnv.reset(listOf(TestEnv.checkin("wake", "Wake up", mathCount = 2, mathLevel = 4)), listOf(pendingToday("wake")))
        launchAlarmScreen().use {
            onView(withText("Problem 1 of 2")).check(matches(isDisplayed()))
            answer(solve(textOf(tagged("math-problem"))) + 1) // wrong
            onView(withText("Problem 1 of 2")).check(matches(isDisplayed()))
            answer(solve(textOf(tagged("math-problem"))))
            onView(withText("Problem 2 of 2")).check(matches(isDisplayed()))
            answer(solve(textOf(tagged("math-problem"))))
        }
        assertTrue("alarm should be done after the last problem", TestEnv.store.pending.isEmpty())
    }

    @Test
    fun stepsRunInOrderMathThenQuestions() {
        TestEnv.reset(
            listOf(TestEnv.checkin("wake", "Wake up", listOf(soda), mathCount = 1)),
            listOf(pendingToday("wake")),
        )
        launchAlarmScreen().use {
            onView(withText("Problem 1 of 1")).check(matches(isDisplayed()))
            answer(solve(textOf(tagged("math-problem"))))
            onView(withText(soda.text)).check(matches(isDisplayed()))
            onView(tagged("q:soda:no")).perform(scrollTo(), click())
            onView(withText("Submit")).perform(scrollTo(), click())
        }
        assertEquals(mapOf("soda" to false), TestEnv.store.entry(TestEnv.today()))
    }

    @Test
    fun customOrderQuestionsThenMathIsFollowed() {
        TestEnv.reset(
            listOf(TestEnv.checkin("wake", "Wake up", listOf(soda), mathCount = 1).let { it.copy(steps = it.steps.reversed()) }),
            listOf(pendingToday("wake")),
        )
        launchAlarmScreen().use {
            onView(withText(soda.text)).check(matches(isDisplayed()))
            onView(tagged("q:soda:no")).perform(scrollTo(), click())
            onView(withText("Submit")).perform(scrollTo(), click())
            onView(withText("Problem 1 of 1")).check(matches(isDisplayed()))
            answer(solve(textOf(tagged("math-problem"))))
        }
        assertEquals(mapOf("soda" to false), TestEnv.store.entry(TestEnv.today()))
        assertTrue("finished", TestEnv.store.pending.isEmpty())
    }

    @Test
    fun theSameKindOfStepCanRunSeveralTimes() {
        TestEnv.reset(
            listOf(TestEnv.checkin("wake", "Wake up").copy(steps = listOf(
                StepConfig("m1", Steps.MATH, mathCount = 1, mathLevel = 1),
                StepConfig("q1", Steps.QUESTIONS, questions = listOf(soda)),
                StepConfig("m2", Steps.MATH, mathCount = 2, mathLevel = 1),
            ))),
            listOf(pendingToday("wake")),
        )
        launchAlarmScreen().use {
            onView(withText("Problem 1 of 1")).check(matches(isDisplayed()))
            answer(solve(textOf(tagged("math-problem"))))
            onView(tagged("q:soda:no")).perform(scrollTo(), click())
            onView(withText("Submit")).perform(scrollTo(), click())
            onView(withText("Problem 1 of 2")).check(matches(isDisplayed()))
            answer(solve(textOf(tagged("math-problem"))))
            answer(solve(textOf(tagged("math-problem"))))
        }
        assertTrue("finished", TestEnv.store.pending.isEmpty())
        assertEquals(mapOf("soda" to false), TestEnv.store.entry(TestEnv.today()))
    }

    @Test
    fun aStepThatIsntSetUpIsSkipped() {
        // Scan added but no code registered: only the math runs.
        TestEnv.reset(
            listOf(TestEnv.checkin("wake", "Wake up").copy(steps = listOf(
                StepConfig("s", Steps.SCAN), // no code registered
                StepConfig("m", Steps.MATH, mathCount = 1, mathLevel = 1),
            ))),
            listOf(pendingToday("wake")),
        )
        launchAlarmScreen().use {
            onView(withText("Problem 1 of 1")).check(matches(isDisplayed()))
            answer(solve(textOf(tagged("math-problem"))))
        }
        assertTrue("finished", TestEnv.store.pending.isEmpty())
    }

    @Test
    fun twoWaitingCheckinsRunBackToBack() {
        TestEnv.reset(
            listOf(TestEnv.checkin("a", "First check-in", listOf(soda)), TestEnv.checkin("b", "Second check-in", listOf(veg))),
            listOf(pendingToday("a"), pendingToday("b")),
        )
        launchAlarmScreen().use {
            onView(withText("First check-in")).check(matches(isDisplayed()))
            onView(tagged("q:soda:no")).perform(scrollTo(), click())
            onView(withText("Submit")).perform(scrollTo(), click())

            onView(withText("Second check-in")).check(matches(isDisplayed()))
            onView(tagged("q:veg:yes")).perform(scrollTo(), click())
            onView(withText("Submit")).perform(scrollTo(), click())
        }
        assertEquals(mapOf("soda" to false, "veg" to true), TestEnv.store.entry(TestEnv.today()))
        assertTrue(TestEnv.store.pending.isEmpty())
    }

    @Test
    fun scanWithoutACameraOffersTheMathFallback() {
        // The CI emulator has no camera, so the scanner reports an error right away.
        TestEnv.reset(listOf(TestEnv.checkin("teeth", "Brush teeth", scanCode = "CODE")), listOf(pendingToday("teeth")))
        launchAlarmScreen().use {
            onView(withText("Go scan: Test spot")).check(matches(isDisplayed()))
            TestEnv.eventually { onView(tagged("scan-fallback")).perform(scrollTo()).check(matches(isDisplayed())) }
            onView(tagged("scan-fallback")).perform(click())
            onView(withText("Problem 1 of 5")).check(matches(isDisplayed()))
        }
        assertEquals("still waiting until the fallback problems are solved", 1, TestEnv.store.pending.size)
    }

    @Test
    fun checkinWithNoStepsJustNeedsDismiss() {
        TestEnv.reset(listOf(TestEnv.checkin("plain", "Plain alarm")), listOf(pendingToday("plain")))
        launchAlarmScreen().use {
            onView(withText("Dismiss")).perform(click())
        }
        assertTrue(TestEnv.store.pending.isEmpty())
    }

    private fun answer(n: Long) {
        onView(tagged("math-answer")).perform(replaceText(n.toString()), closeSoftKeyboard())
        onView(withText("Check")).perform(click())
    }

    /** "347 × 38 + 47 × 26 = ?" → 13408 */
    private fun solve(text: String): Long =
        text.removeSuffix(" = ?").split(" + ").sumOf { term ->
            term.split(" × ").map { it.trim().toLong() }.reduce { a, b -> a * b }
        }
}
