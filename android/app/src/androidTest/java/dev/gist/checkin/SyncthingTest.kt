package dev.gist.checkin

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Easy Connect experiment: Syncthing's own program, built for Android and installed with
 * Gist's debug build, starts on the phone, answers on its local API, keeps who it is across
 * restarts, and stops. (Skipped in builds without it; CI's has it.)
 */
@RunWith(AndroidJUnit4::class)
class SyncthingTest {
    private val engine = SyncthingEngine(TestEnv.ctx)

    @After
    fun tearDown() = engine.stop()

    @Test
    fun startsAnswersAndStops() {
        assumeTrue("this build has no Syncthing", engine.available)
        engine.home.deleteRecursively()
        val t0 = System.currentTimeMillis()
        engine.start()
        val startedIn = System.currentTimeMillis() - t0
        Log.i("SyncthingTest", "first start (new keys) took $startedIn ms; ${engine.binary.length() / 1024} KiB")
        assertTrue(engine.running)
        assertTrue(engine.ping())
        assertTrue(engine.version(), engine.version().startsWith("v2."))
        val id = engine.deviceId()
        assertTrue(id, id.matches(Regex("[A-Z2-7]{7}(-[A-Z2-7]{7}){7}")))
        assertEquals("no folders until Gist adds them", 0, JSONArray(engine.call("GET", "/rest/config/folders")).length())

        engine.stop()
        assertFalse(engine.running)

        // Started again, it's the same device (its keys are kept).
        val t1 = System.currentTimeMillis()
        engine.start()
        Log.i("SyncthingTest", "second start took ${System.currentTimeMillis() - t1} ms")
        assertEquals(id, engine.deviceId())
        engine.stop()
        assertFalse(engine.running)
    }

    @Test
    fun itsApiNeedsTheKey() {
        assumeTrue("this build has no Syncthing", engine.available)
        engine.start()
        val port = engine.port
        val c = java.net.URL("http://127.0.0.1:$port/rest/system/status").openConnection() as java.net.HttpURLConnection
        assertEquals("no key, no answer", 403, c.responseCode)
        c.disconnect()
    }
}
