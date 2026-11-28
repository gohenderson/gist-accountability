package dev.gist.checkin

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket

/**
 * Easy Connect between two Syncthings on one phone (each its own process, folder and port):
 * the one that "shows the code" shares a folder; the one that "scans" it joins by the first's
 * device ID with the invite's tag; the first accepts only a device going by that tag; then a
 * file written on one side arrives on the other. (Skipped in builds without Syncthing.)
 */
@RunWith(AndroidJUnit4::class)
class EasyConnectTest {
    private val ctx get() = TestEnv.ctx
    private val root = File(TestEnv.ctx.cacheDir, "easy-connect-test")
    private lateinit var a: SyncthingEngine
    private lateinit var b: SyncthingEngine

    @Before
    fun setUp() {
        root.deleteRecursively()
        a = SyncthingEngine(ctx, File(root, "a-home"))
        b = SyncthingEngine(ctx, File(root, "b-home"))
    }

    @After
    fun tearDown() {
        runCatching { a.stop() }
        runCatching { b.stop() }
        root.deleteRecursively()
    }

    /** Only here, on this phone: a fixed listen address, and no discovery, relays or reporting. */
    private fun local(engine: SyncthingEngine): Int {
        val port = ServerSocket(0).use { it.localPort }
        engine.setOptions(JSONObject()
            .put("listenAddresses", JSONArray(listOf("tcp://127.0.0.1:$port")))
            .put("globalAnnounceEnabled", false)
            .put("localAnnounceEnabled", false)
            .put("relaysEnabled", false)
            .put("natEnabled", false)
            .put("urAccepted", -1)
            .put("crashReportingEnabled", false)
            .put("reconnectionIntervalS", 5))
        return port
    }

    private fun <T> eventually(timeoutMs: Long, what: String, block: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            runCatching(block).getOrNull()?.let { return it }
            Thread.sleep(500)
        }
        throw AssertionError("$what: not within ${timeoutMs / 1000} s. A: ${a.tail(8)} | B: ${b.tail(8)}")
    }

    @Test
    fun aScannedCodeConnectsAndSyncsMyDevices() {
        assumeTrue("this build has no Syncthing", a.available)
        val t0 = System.currentTimeMillis()
        a.start()
        b.start()
        val portA = local(a)
        local(b)
        val idA = a.deviceId()
        val idB = b.deviceId()
        val token = LinkInvites.newToken()
        val tag = EasyConnect.tag(token)
        val folder = EasyConnect.folderId("testgroup")
        val pathA = File(root, "a-gist/My devices").path
        val pathB = File(root, "b-gist/My devices").path

        // Shows the code: shares My devices with nobody yet.
        EasyConnect.offerFolder(a, folder, "Gist · My devices", pathA)
        File(pathA, "hello.json").writeText("""{"from": "a"}""")
        // Scans it: goes by the invite's tag, adds A (here at its local address) and the folder.
        EasyConnect.joinFolder(b, idA, folder, "Gist · My devices", pathB, tag, addresses = listOf("tcp://127.0.0.1:$portA"))

        // B asks A to connect; A has it waiting, under the tag.
        eventually(60_000, "B waiting on A") { a.pendingDevices()[idB]?.takeIf { it == tag } }
        // Another tag (another invite, or someone guessing) isn't let in.
        assertTrue(EasyConnect.acceptPending(a, "gist-ec-someoneelse", folder).isEmpty())
        assertTrue(idB !in a.devices())
        // The right one is.
        assertEquals(listOf(idB), EasyConnect.acceptPending(a, tag, folder))
        assertTrue(idB in a.devices())

        // Connected, and the file A had arrives on B; one written on B arrives on A.
        eventually(60_000, "A and B connected") { idB.takeIf { it in a.connected() } }
        eventually(90_000, "hello.json on B") { File(pathB, "hello.json").takeIf { it.isFile }?.readText() }
            .let { assertEquals("""{"from": "a"}""", it) }
        File(pathB, "back.json").writeText("""{"from": "b"}""")
        b.scan(folder)
        eventually(90_000, "back.json on A") { File(pathA, "back.json").takeIf { it.isFile }?.readText() }
            .let { assertEquals("""{"from": "b"}""", it) }
        Log.i("EasyConnectTest", "connected and synced both ways in ${System.currentTimeMillis() - t0} ms")
    }

    /** A shows a code; B scans it: they meet in a setup folder, and nothing else, until B answers. */
    private fun meet(): Triple<EasyConnect.Code, File, File> {
        a.start()
        b.start()
        val portA = local(a)
        local(b)
        val idA = a.deviceId()
        val idB = b.deviceId()
        val token = LinkInvites.newToken()
        val tag = EasyConnect.tag(token)
        val setup = EasyConnect.setupId(tag)
        val folder = EasyConnect.folderId("testgroup")
        val dirA = File(root, "a-setup")
        val dirB = File(root, "b-setup")
        // A: My devices (shared with nobody), the setup folder with what it is, and the open code.
        EasyConnect.offerFolder(a, folder, "Gist · My devices", File(root, "a-gist/My devices").path)
        EasyConnect.makeSetup(a, dirA, setup, ConnectPreview(name = "Alex", device = "Alex's phone",
            sample = listOf("Morning pills", "Baste the turkey"), count = 5))
        Profiles.prefs(ctx, "gist-easy").edit().clear().commit()
        EasyConnect.addOffer(ctx, tag, folder, System.currentTimeMillis() + LinkInvites.VALID_MS, setup, 'd')
        // B reaches A with the setup folder only.
        val code = EasyConnect.Code(LinkInvite('d', LinkInvites.fingerprint("a-key"), token), idA, folder)
        EasyConnect.reach(b, code, dirB, addresses = listOf("tcp://127.0.0.1:$portA"))
        eventually(60_000, "A lets B in") {
            EasyConnect.tickOffers(ctx, a) { dirA }
            idB.takeIf { it in a.devices() }
        }
        assertTrue("B gets the setup folder, not My devices", idB !in a.folderDevices()[folder].orEmpty())
        val p = eventually(90_000, "A's preview on B") { EasyConnect.previewIn(dirB) }
        assertEquals("Alex's phone", p.device)
        assertEquals(listOf("Morning pills", "Baste the turkey"), p.sample)
        assertEquals(5, p.count)
        return Triple(code, dirA, dirB)
    }

    @Test
    fun noWithdrawsTheCodeOnTheOtherDevice() {
        assumeTrue("this build has no Syncthing", a.available)
        val (code, dirA, dirB) = meet()
        val idB = b.deviceId()
        val t = Thread { EasyConnect.answer(b, code, dirB, yes = false, waitMs = 60_000) {} }
        t.start()
        val result = eventually(90_000, "A hears no") {
            EasyConnect.tickOffers(ctx, a) { dirA }
            EasyConnect.takeResult(ctx, code.folderId)
        }
        assertEquals("no", result)
        t.join(90_000)
        assertTrue("A forgot B", idB !in a.devices())
        assertTrue("A's setup folder is gone", !dirA.exists() && a.folder(EasyConnect.setupId(EasyConnect.tag(code.invite.token))) == null)
        assertTrue("B's too", !dirB.exists())
        assertTrue("B forgot A", code.deviceId !in b.devices())
        assertTrue("never shared My devices", idB !in a.folderDevices()[code.folderId].orEmpty())
    }

    @Test
    fun yesSharesTheRealFolder() {
        assumeTrue("this build has no Syncthing", a.available)
        val (code, dirA, dirB) = meet()
        val idB = b.deviceId()
        var joined = false
        val t = Thread { EasyConnect.answer(b, code, dirB, yes = true, waitMs = 60_000) { joined = true } }
        t.start()
        val result = eventually(90_000, "A hears yes") {
            EasyConnect.tickOffers(ctx, a) { dirA }
            EasyConnect.takeResult(ctx, code.folderId)
        }
        assertEquals("yes", result)
        t.join(90_000)
        assertTrue(joined)
        assertTrue("My devices shared with B now", idB in a.folderDevices()[code.folderId].orEmpty())
        assertTrue("setup folder gone", !dirA.exists())
    }
}
