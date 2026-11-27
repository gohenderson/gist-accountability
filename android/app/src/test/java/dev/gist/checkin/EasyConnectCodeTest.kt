package dev.gist.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyConnectCodeTest {
    private val id = "MFZWI3D-BONSGYC-YLTMRWG-C43ENR5-QXGZDMM-FZWI3DP-BONSGYY-LTMRWAD"
    private val invite = LinkInvite('d', ByteArray(LinkInvites.FINGERPRINT) { it.toByte() }, ByteArray(LinkInvites.TOKEN) { (it * 7).toByte() })

    @Test
    fun theCodeRoundTrips() {
        val code = EasyConnect.Code(invite, id, "gist-devices-abc123")
        assertTrue(code.qr, code.qr.startsWith("GISTEC1:"))
        val back = EasyConnect.Code.parse(code.qr)!!
        assertEquals(invite.code, back.invite.code)
        assertEquals(id, back.deviceId)
        assertEquals("gist-devices-abc123", back.folderId)
        // Whitespace around it, lowercase prefix
        assertEquals(id, EasyConnect.Code.parse("  gistec1:" + code.qr.removePrefix("GISTEC1:") + "\n")!!.deviceId)
    }

    @Test
    fun otherTextIsntACode() {
        val good = EasyConnect.Code(invite, id, "gist-devices-abc").qr
        assertNull(EasyConnect.Code.parse(invite.qr)) // an ordinary invite
        assertNull(EasyConnect.Code.parse("GISTEC1:nope"))
        assertNull(EasyConnect.Code.parse(good.replace("gist-devices-abc", "../x")))
        assertNull(EasyConnect.Code.parse(good.replace(id.replace("-", ""), "TOOSHORT")))
        val connection = LinkInvite('c', invite.fingerprint, invite.token)
        assertNull("a connection invite isn't for your devices", EasyConnect.Code.parse(EasyConnect.Code(connection, id, "f").qr))
    }

    @Test
    fun deviceIdsAreNormalised() {
        assertEquals(id, EasyConnect.Code.deviceId(id.lowercase().replace("-", " ")))
        assertNull(EasyConnect.Code.deviceId(id.replace('M', '1'))) // 1 isn't base32
    }

    @Test
    fun theTagComesFromTheInvite() {
        val tag = EasyConnect.tag(invite.token)
        assertTrue(tag, tag.matches(Regex("gist-ec-[a-z2-7]{12}")))
        assertEquals(tag, EasyConnect.tag(invite.token.copyOf()))
        assertNotEquals(tag, EasyConnect.tag(ByteArray(LinkInvites.TOKEN)))
        assertEquals("gist-devices-ab12cd", EasyConnect.folderId("ab12cd"))
        assertEquals("gist-devices-abcd", EasyConnect.folderId("a/b.c-d"))
    }
}
