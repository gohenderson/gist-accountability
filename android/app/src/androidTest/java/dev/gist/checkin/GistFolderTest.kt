package dev.gist.checkin

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Your Gist folder: picked once, with My devices and a folder per person under People, made
 * when you set up or invite and found when you join.
 */
@RunWith(AndroidJUnit4::class)
class GistFolderTest {
    @get:Rule
    val failureScreenshot = FailureScreenshot()

    private lateinit var dir: File
    private val ctx get() = TestEnv.ctx
    private val open: (String) -> LinkFolder = { FileFolder(File(it)) }
    private val gist get() = GistFolder(ctx, open)
    private val links get() = Connections(ctx, open)
    private val devices get() = Devices(ctx, open)

    @Before
    fun setUp() {
        clear()
        dir = File(ctx.cacheDir, "gist-${System.nanoTime()}").apply { mkdirs() }
        gist.root = dir.path
        links.myName = "Alex"
    }

    @After
    fun tearDown() {
        clear()
        dir.deleteRecursively()
        TestEnv.reset(emptyList())
    }

    private fun clear() {
        for (p in listOf("gist-folder", "gist-links", "gist-devices")) Profiles.prefs(ctx, p).edit().clear().commit()
        Store(ctx).tracking = false
    }

    @Test
    fun foldersInsideAreReachedByName() {
        assertEquals("content://x/tree/y#People/Jess", GistFolder.at("content://x/tree/y", "People/Jess"))
        assertEquals("/a/b/My devices", GistFolder.at("/a/b", "My devices"))
        assertEquals("${dir.path}/My devices", gist.devices())
        // A folder inside another works as a folder of its own.
        val sub = SubFolder(FileFolder(dir), "People/Jess")
        sub.write("from-a/hello.json", "hi".toByteArray())
        assertEquals("hi", String(File(dir, "People/Jess/from-a/hello.json").readBytes()))
        assertEquals("hi", String(sub.read("from-a/hello.json")!!))
        assertEquals(listOf("from-a"), sub.names())
        sub.delete("from-a/hello.json")
        assertNull(sub.read("from-a/hello.json"))
    }

    @Test
    fun eachPersonGetsTheirOwnFolder() {
        assertEquals("Jess", GistFolder.safeName("  Jess  "))
        assertEquals("Jo Ann", GistFolder.safeName("Jo/Ann"))
        assertEquals("x", GistFolder.safeName("../x"))
        val first = gist.newPerson("Jess")!!
        assertEquals("${dir.path}/People/Jess", first)
        links.invite(first)
        assertEquals("${dir.path}/People/Jess 2", gist.newPerson("Jess"))
        assertEquals("taken whatever the case", "${dir.path}/People/jess 2", gist.newPerson("jess"))
        assertEquals("${dir.path}/People/Someone", gist.newPerson("///"))
        assertEquals(mapOf("Jess" to first), gist.people())
    }

    @Test
    fun joiningFindsTheFolderTheInviteIsFor() {
        // Two people have invited this phone; their shared folders arrived under People.
        val jess = LinkCrypto.newKeyPair()
        val sam = LinkCrypto.newKeyPair()
        LinkProtocol.invite(FileFolder(File(dir, "People/Jess")), "link-j", LinkHello("jess", "Jess", jess.first))
        LinkProtocol.invite(FileFolder(File(dir, "People/Sam's")), "link-s", LinkHello("sam", "Sam", sam.first))
        File(dir, "People/Empty").mkdirs()
        val invite = LinkInvite('c', LinkInvites.fingerprint(sam.first), LinkInvites.newToken())
        val loc = links.findInvited(gist.people().values, invite)
        assertEquals("${dir.path}/People/Sam's", loc)
        val (l, problem) = links.join(loc!!, invite)
        assertNull(problem)
        assertEquals("Sam", l!!.peerName)
        assertNull("already joined", links.findInvited(gist.people().values, invite))
        // Not there yet: nothing found (Gist waits for it).
        assertNull(links.findInvited(gist.people().values,
            LinkInvite('c', LinkInvites.fingerprint(LinkCrypto.newKeyPair().first), LinkInvites.newToken())))
    }

    @Test
    fun yourDevicesSyncInMyDevices() {
        TestEnv.reset(listOf(TestEnv.checkin("a", "Alpha")))
        val loc = gist.devices()!!
        devices.setUp(loc)
        assertTrue(File(dir, "My devices/${Devices.MARKER}").isFile)
        assertNotNull(Devices.groupOf(FileFolder(File(loc))))
        devices.sync()
        assertTrue(File(dir, "My devices/from-${devices.side}/snapshot.gist").isFile)
        assertFalse("not a person", gist.people().containsKey("My devices"))
    }
}
