package dev.gist.checkin

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Gist folder deleted after it was set up (or access to it taken away): Android refuses
 * to open it at all. Reading it finds nothing, and Gist keeps going; it asks for the folder
 * again rather than crashing.
 */
@RunWith(AndroidJUnit4::class)
class LostFolderTest {
    private val ctx get() = TestEnv.ctx
    private val gone = "content://com.android.externalstorage.documents/tree/primary%3AGist"
    private val dir = File(TestEnv.ctx.cacheDir, "lost-folder")

    @Before
    fun setUp() {
        TestEnv.reset(emptyList())
        dir.deleteRecursively()
    }

    @After
    fun tearDown() {
        Profiles.prefs(ctx, Devices.PREFS).edit().clear().commit()
        Profiles.prefs(ctx, Connections.PREFS).edit().clear().commit()
        dir.deleteRecursively()
        TestEnv.reset(emptyList())
    }

    @Test
    fun aFolderGistCantReachReadsAsEmpty() {
        val f = LinkFolder.open(ctx, "$gone#Personal - 7xq2/My devices")
        assertTrue(f.names().isEmpty())
        assertTrue(f.names("from-abc").isEmpty())
        assertNull(f.read("from-abc/hello.gist"))
    }

    @Test
    fun yourDevicesInADeletedGistFolderDontCrashConnections() {
        // Set up My devices (in a plain folder here), then have it point where Gist can't reach,
        // as it does once the Gist folder is deleted.
        File(dir, "My devices").mkdirs()
        Devices(ctx) { FileFolder(File(it)) }.setUp(File(dir, "My devices").path)
        Profiles.prefs(ctx, Devices.PREFS).edit().putString("folder", "$gone#Personal - 7xq2/My devices").commit()
        assertTrue(Devices(ctx).others().isEmpty())
        assertTrue("syncing just finds nothing", runCatching { Devices.syncNow(ctx) }.getOrNull() == false)

        ActivityScenario.launch<ConnectionsActivity>(ConnectionsActivity.intent(ctx)).use {
            // No access to the Gist folder: it asks for it again.
            onView(tagged("gist-folder-allow")).check(matches(isDisplayed()))
        }
    }
}
