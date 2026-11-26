package dev.gist.checkin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/**
 * The shared sync test files (repo root shared/sync, made by make_vectors.py), the same ones
 * the iPhone app's SyncVectorsTests checks: keys, encryption, invite codes and a sample
 * folder, so an iPhone and an Android phone read and write the same sync files.
 */
@RunWith(AndroidJUnit4::class)
class SyncVectorsTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val v = JSONObject(assets.open("vectors.json").bufferedReader().use { it.readText() })

    private fun s(path: String): String {
        val parts = path.split(".")
        var o = v
        parts.dropLast(1).forEach { o = o.getJSONObject(it) }
        return o.getString(parts.last())
    }

    private fun hex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun keysAgreeAndShowTheSameCode() {
        val k1 = LinkCrypto.sharedKey(s("a.private"), s("b.public"), s("link"))
        val k2 = LinkCrypto.sharedKey(s("b.private"), s("a.public"), s("link"))
        assertArrayEquals(hex(s("sharedKeyHex")), k1)
        assertArrayEquals(k1, k2)
        assertEquals(s("code"), LinkCrypto.code(s("a.public"), s("b.public")))
    }

    @Test
    fun sealsTheSameBytes() {
        val key = hex(s("sharedKeyHex"))
        val sealed = LinkCrypto.seal(key, s("seal.label"), s("seal.plain").toByteArray(), hex(s("seal.nonceHex")))
        assertEquals(s("seal.sealedBase64"), Base64.getEncoder().encodeToString(sealed))
        assertEquals(s("seal.plain"), String(LinkCrypto.open(key, s("seal.label"), Base64.getDecoder().decode(s("seal.sealedBase64")))!!))
        assertNull(LinkCrypto.open(key, "elsewhere", sealed))
    }

    @Test
    fun inviteCodesAndProofs() {
        val inv = LinkInvite('c', hex(s("invite.fingerprintHex")), hex(s("invite.tokenHex")))
        assertEquals(s("invite.code"), inv.code)
        assertEquals(s("invite.qr"), inv.qr)
        val back = LinkInvite.parse(s("invite.code").lowercase())!!
        assertArrayEquals(inv.token, back.token)
        assertArrayEquals(inv.fingerprint, LinkInvites.fingerprint(s("a.public")))
        assertEquals(s("proof.proof"), LinkInvites.proof(inv.token, s("proof.scope"), s("proof.side"), s("proof.publicKey"),
            v.getJSONObject("proof").getLong("joinedAt")))
    }

    /** The sample folder, copied out of the test's assets. */
    private fun folder(): File {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "sync-${System.nanoTime()}")
        fun copy(asset: String, to: File) {
            val names = assets.list(asset) ?: emptyArray()
            if (names.isEmpty()) {
                to.parentFile?.mkdirs()
                assets.open(asset).use { input -> to.outputStream().use { input.copyTo(it) } }
            } else {
                names.forEach { copy("$asset/$it", File(to, it)) }
            }
        }
        copy("folder", dir)
        return dir
    }

    @Test
    fun readsAFolderTheIPhoneWouldWrite() {
        val dir = folder()
        try {
            val f = FileFolder(dir)
            assertEquals(s("link"), LinkProtocol.linkId(f))
            val b = LinkProtocol.hello(f, "sideb")!!
            assertEquals("Jess", b.name)
            assertTrue(LinkInvites.proves(b, hex(s("invite.tokenHex")), s("link"), Long.MAX_VALUE))
            assertFalse(LinkInvites.proves(b, hex(s("invite.tokenHex")), s("link"), 0))
            assertEquals(1, LinkProtocol.readUpTo(f, "sideb", "sidea"))
            val (seq, items) = LinkProtocol.receive(f, s("link"), "sidea", hex(s("sharedKeyHex")), 0, emptyMap())
            assertEquals(2, seq)
            val expected = v.getJSONObject("folder").getJSONObject("items")
            assertEquals(expected.keys().asSequence().toSet(), items.keys)
            items.forEach { (id, json) -> assertEquals(expected.getJSONObject(id).toString(), JSONObject(json).toString()) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
