package dev.gist.checkin

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Before a connection is made, the two sides talk through something that isn't the connection
 * yet, so the side with the code can see what it's connecting to, and say no:
 *
 * - **Automatic** ([EasyConnect]): a small setup folder in Gist's own Syncthing, shared only with a
 *   device that proved it scanned the code. The side that showed the code puts its
 *   [ConnectPreview] there ([OFFER]); the other answers yes or no ([answerFile]).
 * - **Manual**: the connection's folder itself (People/Jess, My devices), once it has arrived. The
 *   side that showed the code puts its preview there, encrypted with the code's secret, so only
 *   whoever has the code can read it ([PREVIEW]); the other, saying no, leaves a mark only the
 *   code could make ([DECLINED]), and the side that showed it withdraws the code when it sees it.
 *
 * Nothing from your lists goes through either but the preview's few names.
 */
object Handshake {
    /** Automatic: the preview, in the setup folder. */
    const val OFFER = "offer.json"
    /** Manual: the preview, encrypted with the code, in the connection's folder. */
    const val PREVIEW = "invite-preview.gist"
    /** Manual: "not this one", proved with the code, in the connection's folder. */
    const val DECLINED = "invite-declined.gist"

    /** Automatic: the scanning device's answer. */
    fun answerFile(deviceId: String) = "answer-${deviceId.filter { it.isLetterOrDigit() }.take(7).lowercase()}.json"

    private fun key(token: ByteArray, what: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token, "HmacSHA256"))
        return mac.doFinal("gist-invite-$what".toByteArray())
    }

    /** [preview], encrypted for whoever has the code with [token]. */
    fun seal(token: ByteArray, preview: ConnectPreview): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(token, "preview"), "AES"), GCMParameterSpec(128, iv))
        return Base64.getEncoder().encode(iv + c.doFinal(preview.encode().toByteArray()))
    }

    /** The preview [sealed] with [token], or null (another code's, changed, or not one). */
    fun open(token: ByteArray, sealed: ByteArray): ConnectPreview? = runCatching {
        val raw = Base64.getDecoder().decode(sealed)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(token, "preview"), "AES"), GCMParameterSpec(128, raw, 0, 12))
        ConnectPreview.decode(String(c.doFinal(raw, 12, raw.size - 12)))
    }.getOrNull()

    /** The "not this one" mark for the code with [token]: only someone who has the code can make it. */
    fun declineMark(token: ByteArray): ByteArray = Base64.getEncoder().encode(key(token, "declined"))

    // ---------------------------------------------------------------- manual: in the connection's folder

    /** Puts [preview] in [folder] for whoever has the code with [token]. */
    fun putPreview(folder: LinkFolder, token: ByteArray, preview: ConnectPreview) =
        folder.write(PREVIEW, seal(token, preview))

    /** The preview in [folder] for the code with [token], if there's one. */
    fun preview(folder: LinkFolder, token: ByteArray): ConnectPreview? =
        runCatching { folder.read(PREVIEW)?.let { open(token, it) } }.getOrNull()

    /** Says no to the code with [token], in [folder]. */
    fun decline(folder: LinkFolder, token: ByteArray) = folder.write(DECLINED, declineMark(token))

    /** Whether someone with the code with [token] said no, in [folder]. */
    fun declined(folder: LinkFolder, token: ByteArray): Boolean = runCatching {
        folder.read(DECLINED)?.let { MessageDigest.isEqual(it, declineMark(token)) } ?: false
    }.getOrDefault(false)

    /**
     * The side that showed manual codes: withdraws any someone turned down (their "no" has
     * arrived in its folder). Who each was for (a person's folder name, or "your device").
     */
    fun withdrawDeclined(context: android.content.Context): List<String> {
        val out = mutableListOf<String>()
        val links = Connections(context)
        for (l in links.all.filter { it.isHere && !it.connected }) {
            val token = l.inviteToken?.let { Base64.getDecoder().decode(it) } ?: continue
            val f = runCatching { LinkFolder.open(context, l.folder) }.getOrNull() ?: continue
            if (!declined(f, token)) continue
            clear(f)
            links.remove(l.id)
            out += l.folder.substringAfterLast('/')
        }
        val devices = Devices(context)
        val inv = devices.openInvitation()
        val loc = devices.folder
        if (inv != null && loc != null) {
            val f = runCatching { LinkFolder.open(context, loc) }.getOrNull()
            if (f != null && declined(f, inv.token)) {
                clear(f)
                devices.cancelInvitation()
                out += "your device"
            }
        }
        return out
    }

    /** Takes the preview and any "no" out of [folder] (the code's used or withdrawn). */
    fun clear(folder: LinkFolder) {
        runCatching { folder.delete(PREVIEW) }
        runCatching { folder.delete(DECLINED) }
    }
}
