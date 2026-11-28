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
    fun eachProfileHasItsOwnFolderInTheGistFolder() {
        assertEquals("content://x/tree/y#Gist/Work - abcd/People/Jess", GistFolder.at("content://x/tree/y#Gist/Work - abcd", "People/Jess"))
        // The name made safe for a folder, and a code from the profile's id: two names that
        // come out the same as folder names still get two folders.
        val a = Profiles.add(ctx, "John:")
        val b = Profiles.add(ctx, "John;")
        val c = Profiles.add(ctx, "john")
        val names = listOf(a, b, c).map { GistFolder(Profiles.scoped(ctx, it.id)).folderName() }
        names.take(2).forEach { assertTrue(it, it.matches(Regex("John - [a-km-np-z2-9]{4}"))) }
        assertTrue(names[2], names[2].startsWith("john - "))
        assertEquals("all different, whatever the case", 3, names.map { it.lowercase() }.toSet().size)
        names.forEach { LinkPath.parts(it) } // a folder name Gist accepts
        assertEquals("the same code every time", GistFolder.code(a.id, 4), names[0].takeLast(4))
        // Renaming the profile keeps its folder (whatever shares the folders in it points there).
        Profiles.rename(ctx, a.id, "Johnny")
        assertEquals(names[0], GistFolder(Profiles.scoped(ctx, a.id)).folderName())
        assertTrue(GistFolder(Profiles.scoped(ctx, Profiles.add(ctx, "///").id)).folderName().startsWith("Profile - "))
    }

    @Test
    fun foldersReachedWithTheOldFolderPickerAreReachedByPath() {
        fun tree(id: String, authority: String = "com.android.externalstorage.documents") =
            android.provider.DocumentsContract.buildTreeDocumentUri(authority, id).toString()
        // Its path keeps the folder's own case.
        assertEquals("/storage/emulated/0/gist/Work - k3f9/People/Jess", GistRoot.pathOfTree(tree("primary:gist") + "#Work - k3f9/People/Jess"))
        assertEquals("/storage/emulated/0/Gist", GistRoot.pathOfTree(tree("primary:Gist")))
        assertEquals("/storage/emulated/0/Gist/Work - k3f9/My devices",
            gist.pathOf(GistFolder.at(GistFolder.at(tree("primary:Gist"), "Work - k3f9"), "My devices")))
        for (other in listOf("primary:Documents/Gist", "primary:Gists", "1234-5678:Gist")) assertNull(other, GistRoot.pathOfTree(tree(other)))
        assertNull(GistRoot.pathOfTree(tree("primary:Gist", "com.example.docs")))
        assertNull(GistRoot.pathOfTree("/storage/emulated/0/Gist"))
        // And opened by path.
        assertTrue(LinkFolder.open(ctx, tree("primary:Gist") + "#Work - k3f9/My devices") is FileFolder)
        assertTrue(LinkFolder.open(ctx, "${dir.path}/My devices") is FileFolder)
    }

    private fun allFilesAccess(on: Boolean) =
        TestEnv.shell("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE ${if (on) "allow" else "default"}")

    @Test
    fun withAllFilesAccessGistFindsOrMakesTheGistFolder() {
        val storage = File(ctx.cacheDir, "storage-${System.nanoTime()}").apply { mkdirs() }
        val real = GistRoot.storage
        GistRoot.storage = storage
        try {
            allFilesAccess(false)
            assertFalse(GistRoot.canReach(ctx))
            assertNull("no access: nothing", GistRoot.dir(ctx))
            allFilesAccess(true)
            assertTrue(GistRoot.canReach(ctx))
            // One there already, in any case, is used.
            File(storage, "gIsT").mkdirs()
            assertEquals(File(storage, "gIsT"), GistRoot.dir(ctx))
            File(storage, "gIsT").delete()
            // Otherwise Gist makes it.
            assertEquals(File(storage, "Gist"), GistRoot.dir(ctx))
            assertTrue(File(storage, "Gist").isDirectory)
            assertNull(GistRoot.problem(ctx))
            gist.root = null
            assertEquals("${storage.path}/Gist/${gist.folderName()}", gist.root)
            // Where it can't be made, Gist says so.
            File(storage, "Gist").delete()
            storage.setWritable(false)
            assertNull(GistRoot.dir(ctx))
            assertTrue(GistRoot.problem(ctx)!!.contains("couldn't make"))
            storage.setWritable(true)
        } finally {
            GistRoot.storage = real
            allFilesAccess(false)
            storage.deleteRecursively()
        }
    }

    @Test
    fun withAllFilesAccessTheRealGistFolderIsReached() {
        val existed = File(GistRoot.PATH).exists()
        try {
            allFilesAccess(true)
            val d = GistRoot.dir(ctx)!!
            assertTrue(d.path, d.path.equals(GistRoot.PATH, ignoreCase = true))
            val f = FileFolder(File(d, "test-${System.nanoTime()}"))
            f.write("from-a/hello.json", "hi".toByteArray())
            assertEquals("hi", String(f.read("from-a/hello.json")!!))
        } finally {
            if (!existed) File(GistRoot.PATH).deleteRecursively()
            else File(GistRoot.PATH).listFiles()?.filter { it.name.startsWith("test-") }?.forEach { it.deleteRecursively() }
            allFilesAccess(false)
        }
    }

    @Test
    fun withoutAccessGistAsksForTheGistFolder() {
        gist.root = null
        assertNull(gist.root)
        assertNull(gist.devices())
        assertTrue(gist.people().isEmpty())
        androidx.test.core.app.ActivityScenario.launch<ConnectionsActivity>(ConnectionsActivity.intent(ctx)).use {
            androidx.test.espresso.Espresso.onView(tagged("gist-folder-allow"))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    androidx.test.espresso.matcher.ViewMatchers.withText("Allow access to the Gist folder…")))
            androidx.test.espresso.Espresso.onView(tagged("gist-folder")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        }
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
