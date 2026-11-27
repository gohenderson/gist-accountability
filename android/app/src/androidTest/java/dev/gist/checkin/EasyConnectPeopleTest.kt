package dev.gist.checkin

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Easy Connect with a person, Gist's side: Greg shows a code for Jess; Jess scans it and gets
 * People/Greg; once Greg's side has arrived she's connected, and once hers reaches Greg, he is.
 * Two profiles stand in for two people's phones, and copying between their folders stands in
 * for Syncthing (EasyConnectTest covers Syncthing connecting by the code).
 */
@RunWith(AndroidJUnit4::class)
class EasyConnectPeopleTest {
    private val ctx get() = TestEnv.ctx
    private val root = File(TestEnv.ctx.cacheDir, "easy-people")
    private lateinit var greg: android.content.Context
    private lateinit var jess: android.content.Context

    @Before
    fun setUp() {
        TestEnv.reset(emptyList())
        root.deleteRecursively()
        greg = ctx
        jess = Profiles.scoped(ctx, Profiles.add(ctx, "Jess's phone").id)
        GistFolder(greg).root = File(root, "greg").apply { mkdirs() }.path
        GistFolder(jess).root = File(root, "jess").apply { mkdirs() }.path
        Connections(greg).myName = "Greg"
        Connections(jess).myName = "Jess"
    }

    @After
    fun tearDown() {
        Profiles.prefs(ctx, Connections.PREFS).edit().clear().commit()
        GistFolder(ctx).root = null
        GistFolder(ctx).waiting = null
        root.deleteRecursively()
        TestEnv.reset(emptyList())
    }

    /** Plain folders here (the app's are reached through Android's folder access). */
    private val open: (String) -> LinkFolder = { FileFolder(File(it)) }
    private fun folder(c: android.content.Context) = GistFolder(c, open)
    private fun links(c: android.content.Context) = Connections(c, open)

    /** What Syncthing would do: everything in one folder is in the other too. */
    private fun carry(from: File, to: File) {
        from.walkTopDown().filter { it.isFile }.forEach { f ->
            val target = File(to, f.relativeTo(from).path)
            target.parentFile?.mkdirs()
            f.copyTo(target, overwrite = true)
        }
    }

    @Test
    fun aScannedCodeMakesTheirFolderAndConnectsOnceItArrives() {
        // Greg shows a code for Jess: her folder, and a connection invite (as Easy Connect does it).
        val gf = folder(greg)
        val loc = gf.newPerson("Jess")!!
        val links = links(greg)
        val link = links.invite(loc)
        val (invite, _) = links.invitation(link)!!
        val code = EasyConnect.Code.parse(EasyConnect.Code(invite, "MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD",
            EasyConnect.linkFolderId(link.id), links.myName).qr)!!

        // Jess scans it: People/Greg (named for him), waiting for his side.
        val (path, label) = EasyConnect.prepareJoin(jess, code, open)
        assertEquals(File(root, "jess/People/Greg").path, path)
        assertEquals("Gist · Greg", label)
        assertFalse("nothing's arrived yet", EasyConnect.finishJoins(jess, open))
        assertTrue(links(jess).all.isEmpty())

        // Greg's side arrives: she's connected, with him.
        carry(File(gf.pathOf(loc)), File(path))
        assertTrue(EasyConnect.finishJoins(jess, open))
        assertNull(folder(jess).waiting)
        val hers = links(jess).all.single()
        assertEquals("Greg", hers.peerName)

        // Her side reaches Greg: he's connected with her.
        carry(File(path), File(gf.pathOf(loc)))
        links(greg).sync()
        val his = links(greg).all.single()
        assertTrue(his.connected)
        assertEquals("Jess", his.peerName)
        assertEquals(hers.id, his.id)
    }

    @Test
    fun aSecondPersonWithTheSameNameGetsTheirOwnFolder() {
        File(root, "jess/People/Greg").mkdirs()
        val links = links(greg)
        val link = links.invite(folder(greg).newPerson("Someone")!!)
        val code = EasyConnect.Code(links.invitation(link)!!.first, "MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD",
            EasyConnect.linkFolderId(link.id), "Greg")
        assertEquals(File(root, "jess/People/Greg 2").path, EasyConnect.prepareJoin(jess, code, open).first)
    }
}
