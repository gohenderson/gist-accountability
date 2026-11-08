package dev.gist.checkin

import android.app.AlarmManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Adding check-ins from the library, or blank. */
@RunWith(AndroidJUnit4::class)
class LibraryTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    @After
    fun tearDown() = TestEnv.reset(emptyList())

    private fun library() = ActivityScenario.launch<LibraryActivity>(LibraryActivity.intent(TestEnv.ctx))

    @Test
    fun addButtonOnTheStartPageOpensTheLibrary() {
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.POST_NOTIFICATIONS")
        TestEnv.reset(emptyList())
        ActivityScenario.launch<MainActivity>(Intent(TestEnv.ctx, MainActivity::class.java)).use {
            onView(withText("+ Add check-in")).perform(click())
            onView(withText("Add a check-in")).check(matches(isDisplayed()))
            onView(withText("Create your own")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun addingATemplateCopiesItArmsItAndOpensIt() {
        TestEnv.reset(emptyList())
        library().use {
            onView(tagged("library:workout")).perform(scrollTo(), click())
            TestEnv.eventually { onView(tagged("checkin-name")).check(matches(withText("Workout"))) }
        }
        val c = TestEnv.store.checkins.single()
        val template = Library.template("workout")!!.checkin
        assertEquals("Workout", c.name)
        assertTrue("gets its own id", c.id.isNotEmpty())
        assertEquals(Repeat.weekly(setOf(1, 3, 5)), c.repeat)
        assertEquals(template.questions, c.questions)
        val alarm = TestEnv.ctx.getSystemService(AlarmManager::class.java).nextAlarmClock
        assertNotNull("alarm should be set", alarm)
        assertEquals(AlarmScheduler.nextTriggerMillis(c)!! / 1000, alarm!!.triggerTime / 1000)
    }

    @Test
    fun addingTheSameOneAgainNumbersTheName() {
        TestEnv.reset(listOf(TestEnv.checkin("w", "Water")))
        library().use {
            onView(tagged("library:water")).perform(scrollTo(), click())
            TestEnv.eventually { onView(tagged("checkin-name")).check(matches(withText("Water 2"))) }
        }
        assertEquals(listOf("Water", "Water 2"), TestEnv.store.checkins.map { it.name })
    }

    @Test
    fun createYourOwnStartsBlank() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Existing")))
        library().use {
            onView(tagged("library:blank")).perform(click())
            TestEnv.eventually { onView(tagged("checkin-name")).check(matches(withText("Check-in 2"))) }
        }
        val c = TestEnv.store.checkins.last()
        assertTrue(c.questions.isEmpty())
        assertTrue(c.steps.isEmpty())
    }
}

/** A ringing alarm can't be escaped by switching away from it. */
@RunWith(AndroidJUnit4::class)
class BringBackTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    @After
    fun tearDown() {
        TestEnv.allowOverlay(false)
        TestEnv.reset(emptyList())
    }

    @Test
    fun pressingHomeWhileRingingBringsTheCheckinBack() {
        TestEnv.shell("pm grant ${TestEnv.ctx.packageName} android.permission.POST_NOTIFICATIONS")
        TestEnv.allowOverlay(true)
        TestEnv.reset(listOf(TestEnv.checkin("f", "Front", listOf(Question("q", "Q?")))))
        AlarmScheduler.scheduleTest(TestEnv.ctx, "f", 1_000)
        TestEnv.eventually(20_000) { assertTrue("alarm didn't start", AlarmService.running) }
        TestEnv.eventually(10_000) { assertTrue("alarm screen should open", CheckinActivity.visible) }

        val shown = CheckinActivity.timesShown
        UiDevice.getInstance(TestEnv.instrumentation).pressHome()
        TestEnv.eventually(10_000) {
            assertTrue("should come back after Home", CheckinActivity.timesShown > shown && CheckinActivity.visible)
        }

        // Also after starting the check-in (alarm paused): still can't leave.
        onView(withText("Start check-in")).perform(click())
        val shown2 = CheckinActivity.timesShown
        UiDevice.getInstance(TestEnv.instrumentation).pressHome()
        TestEnv.eventually(10_000) {
            assertTrue("should come back while paused", CheckinActivity.timesShown > shown2 && CheckinActivity.visible)
        }
        onView(tagged("q:q:no")).check(matches(isDisplayed()))
    }
}
