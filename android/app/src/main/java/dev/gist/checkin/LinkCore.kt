package dev.gist.checkin

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Connections: sharing check-ins and tasks with another copy of Gist through a shared folder
 * (later kept in sync between phones by Syncthing; for now two copies on one phone can use
 * the same folder). Each connection has its own folder. Only the owner ever writes what it
 * shares: the other side reads it, read-only.
 *
 * A connection's folder:
 *   gist-link.json            {"format": "gist-link", "version": 1, "link": "<id>"} (plain)
 *   from-<side>/hello.json    that side's name and public key (plain)
 *   from-<side>/000001.gist   numbered changes: items set or changed, and ids removed
 *   from-<side>/snapshot.gist everything that side shares now, with the last change's number
 *   from-<side>/read.json     how far that side has read each other side: {"<side>": n} (plain)
 *
 * Read receipts let each side clean out its own changes every reader has read (the snapshot
 * covers anyone new or far behind). Only a file's writer ever removes it.
 *
 * Each side writes only in its own from-<side>/ folder, so two writers never touch the same
 * file. Changes and snapshots are encrypted (AES-256-GCM: tampering or a wrong key fails to
 * open) with a key both sides work out from their own private key and the other's public key
 * (ECDH on P-256, then HKDF-SHA256): the key itself is never written anywhere.
 */

/** A folder both copies of Gist can read and write: one picked with Android's folder picker (tests use a plain one). */
interface LinkFolder {
    fun read(path: String): ByteArray?
    fun write(path: String, bytes: ByteArray)
    /** The names in the top folder, or in folder [dir]. */
    fun names(dir: String = ""): List<String>
    /** Removes a file (nothing if it isn't there). */
    fun delete(path: String)

    companion object {
        /** A folder picked with the folder picker ("content://…"). */
        fun open(context: Context, location: String): LinkFolder {
            require(location.startsWith("content://")) { "Not a folder picked for a connection" }
            return SafFolder(context.contentResolver, Uri.parse(location))
        }
    }
}

/**
 * Paths inside a connection's folder: plain names joined by "/", nothing that could reach
 * outside it ("..", ".", empty parts, backslashes). Names come partly from the other side
 * (their side id), so they're checked before use.
 */
object LinkPath {
    private val part = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun parts(path: String): List<String> {
        val parts = path.split('/')
        require(parts.isNotEmpty() && parts.all { part.matches(it) && it != "." && it != ".." && !it.contains("..") }) {
            "Not a path in a connection folder: $path"
        }
        return parts
    }
}

/** A folder picked with Android's folder picker (the Storage Access Framework), e.g. Documents/GistLink. */
class SafFolder(private val resolver: ContentResolver, private val tree: Uri) : LinkFolder {
    private val rootId = DocumentsContract.getTreeDocumentId(tree)

    /** The document id of [name] in folder [parentId], or null. */
    private fun child(parentId: String, name: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return c.getString(0)
        }
        return null
    }

    override fun delete(path: String) {
        try {
            find(path)?.let { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it)) }
        } catch (e: Exception) {
        }
    }

    override fun names(dir: String): List<String> {
        val parent = if (dir.isEmpty()) rootId else find(dir) ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val out = mutableListOf<String>()
        try {
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) out.add(c.getString(0))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun find(path: String): String? {
        var id = rootId
        for (part in LinkPath.parts(path)) id = child(id, part) ?: return null
        return id
    }

    override fun read(path: String): ByteArray? = try {
        find(path)?.let { id ->
            resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { it.readBytes() }
        }
    } catch (e: Exception) {
        null
    }

    override fun write(path: String, bytes: ByteArray) {
        val parts = LinkPath.parts(path)
        var parent = rootId
        for (dir in parts.dropLast(1)) {
            parent = child(parent, dir) ?: DocumentsContract.createDocument(resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, parent), DocumentsContract.Document.MIME_TYPE_DIR, dir)
                ?.let { DocumentsContract.getDocumentId(it) } ?: error("Can't make the folder $dir")
        }
        val name = parts.last()
        val uri = child(parent, name)?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
            ?: DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parent),
                "application/octet-stream", name) ?: error("Can't write $path")
        resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
    }
}

/** The keys and the encryption for connections. Platform code only (java.security, javax.crypto). */
object LinkCrypto {
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()
    private const val MAGIC = "GIST1"

    /** A new key pair: (public, private), each base64 (X.509 / PKCS#8). */
    fun newKeyPair(): Pair<String, String> {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }
        val kp = g.generateKeyPair()
        return b64.encodeToString(kp.public.encoded) to b64.encodeToString(kp.private.encoded)
    }

    private fun publicKey(s: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64.decode(s)))
    private fun privateKey(s: String): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unb64.decode(s)))

    /** The connection's key: the same on both sides (my private + their public = their private + my public). */
    fun sharedKey(myPrivate: String, theirPublic: String, linkId: String): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey(myPrivate))
        ka.doPhase(publicKey(theirPublic), true)
        return hkdf(ka.generateSecret(), linkId.toByteArray(), "gist-link v1".toByteArray(), 32)
    }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(secret)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    /** "4F7A-91C2": both phones show the same code when nobody swapped a key. Compare them once. */
    fun code(keyA: String, keyB: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(listOf(keyA, keyB).sorted().joinToString("|").toByteArray())
        val hex = d.take(4).joinToString("") { "%02X".format(it) }
        return hex.take(4) + "-" + hex.drop(4)
    }

    /** Encrypts [plain] for a file; [label] (where it's written) must match to open it, so files can't be swapped. */
    fun seal(key: ByteArray, label: String, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(label.toByteArray())
        return MAGIC.toByteArray() + nonce + c.doFinal(plain)
    }

    /** The plain text, or null when it's not ours, was changed, or was moved from elsewhere. */
    fun open(key: ByteArray, label: String, sealed: ByteArray): ByteArray? = try {
        val m = MAGIC.toByteArray()
        require(sealed.size > m.size + 12 + 16 && sealed.copyOfRange(0, m.size).contentEquals(m))
        val nonce = sealed.copyOfRange(m.size, m.size + 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(label.toByteArray())
        c.doFinal(sealed.copyOfRange(m.size + 12, sealed.size))
    } catch (e: Exception) {
        null
    }
}

/**
 * One side's hello: who's there, and their public key. A side that joined with an invite also
 * proves it had the invite's token: [proof] (see LinkInvites.proof) as of [joinedAt].
 */
data class LinkHello(val side: String, val name: String, val key: String, val proof: String? = null, val joinedAt: Long? = null)

/**
 * An invite, passed outside the folder (a QR code to scan, or a text code to type): which kind
 * ('c' a connection, 'd' your devices), a fingerprint of the inviting side's public key (so the
 * joiner knows whose hello is real), and a one-time token the joiner proves it has (so the
 * inviter lets in only that joiner). The token never goes in the folder.
 */
class LinkInvite(val kind: Char, val fingerprint: ByteArray, val token: ByteArray) {
    /** The text code: 36 letters and digits in groups of 4 ("ABCD-EFGH-…"); the QR code holds it too. */
    val code: String get() = LinkInvites.base32(byteArrayOf(1, kind.code.toByte()) + fingerprint + token).chunked(4).joinToString("-")

    /** What the QR code holds. */
    val qr: String get() = "GIST:" + code.replace("-", "")

    companion object {
        /** An invite from a scanned QR code or a typed code (spaces, dashes and case don't matter); null if it isn't one. */
        fun parse(text: String): LinkInvite? {
            val raw = text.trim().removePrefix("GIST:").removePrefix("gist:").uppercase().filter { it.isLetterOrDigit() }
            val bytes = LinkInvites.unbase32(raw) ?: return null
            if (bytes.size != 2 + LinkInvites.FINGERPRINT + LinkInvites.TOKEN || bytes[0] != 1.toByte()) return null
            val kind = bytes[1].toInt().toChar()
            if (kind != 'c' && kind != 'd') return null
            return LinkInvite(kind, bytes.copyOfRange(2, 2 + LinkInvites.FINGERPRINT), bytes.copyOfRange(2 + LinkInvites.FINGERPRINT, bytes.size))
        }
    }
}

object LinkInvites {
    const val FINGERPRINT = 10
    const val TOKEN = 10
    /** How long an invite can be used. */
    const val VALID_MS = 15 * 60 * 1000L
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun fingerprint(publicKey: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(publicKey.toByteArray()).copyOf(FINGERPRINT)

    fun newToken(): ByteArray = ByteArray(TOKEN).also { SecureRandom().nextBytes(it) }

    /** Proof that the side [side] with [publicKey] joining [scope] (a connection or devices id) at [joinedAt] had [token]. */
    fun proof(token: ByteArray, scope: String, side: String, publicKey: String, joinedAt: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token, "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal("gist-join|$scope|$side|$publicKey|$joinedAt".toByteArray()))
    }

    /** [h] joined [scope] with [token], before [expires]. */
    fun proves(h: LinkHello, token: ByteArray, scope: String, expires: Long): Boolean {
        val proof = h.proof ?: return false
        val at = h.joinedAt ?: return false
        if (at > expires) return false
        return MessageDigest.isEqual(proof.toByteArray(), proof(token, scope, h.side, h.key, at).toByteArray())
    }

    fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    fun unbase32(text: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in text) {
            val v = ALPHABET.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}

/** Reading and writing a connection's folder (no app state: see Connections for that). */
object LinkProtocol {
    const val VERSION = 1
    const val MARKER = "gist-link.json"

    fun dir(side: String) = "from-$side"
    private fun changeName(seq: Int) = "%06d.gist".format(seq)

    /** Starts a connection in an empty folder: the marker and this side's hello. */
    fun invite(folder: LinkFolder, linkId: String, hello: LinkHello) {
        folder.write(MARKER, JSONObject().put("format", "gist-link").put("version", VERSION).put("link", linkId)
            .toString(2).toByteArray())
        writeHello(folder, hello)
    }

    /** The connection id in [folder], or null when it isn't a Gist connection folder. */
    fun linkId(folder: LinkFolder): String? = folder.read(MARKER)?.let {
        try {
            JSONObject(String(it)).takeIf { o -> o.optString("format") == "gist-link" }?.optString("link")?.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    fun writeHello(folder: LinkFolder, hello: LinkHello) {
        folder.write("${dir(hello.side)}/hello.json", JSONObject().put("side", hello.side).put("name", hello.name)
            .put("key", hello.key).apply {
                hello.proof?.let { put("proof", it) }
                hello.joinedAt?.let { put("joinedAt", it) }
            }.toString(2).toByteArray())
    }

    /** Rewrites [side]'s hello with a new [name], keeping its key and proof. */
    fun rename(folder: LinkFolder, side: String, name: String, key: String) {
        val h = hello(folder, side)
        if (h?.name == name) return
        writeHello(folder, (h ?: LinkHello(side, name, key)).copy(name = name, key = key))
    }

    /** A side id: letters and digits only (it's part of file paths). */
    fun validSide(side: String) = side.matches(Regex("[A-Za-z0-9]{1,32}"))

    /** [side]'s hello, if it's there (and says it's [side]). */
    fun hello(folder: LinkFolder, side: String): LinkHello? {
        if (!validSide(side)) return null
        return folder.read("${dir(side)}/hello.json")?.let {
            try {
                val o = JSONObject(String(it))
                LinkHello(o.getString("side"), o.optString("name").take(60), o.getString("key"),
                    o.optString("proof").ifEmpty { null }, if (o.has("joinedAt")) o.optLong("joinedAt") else null)
                    .takeIf { h -> h.side == side }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** The other side's hello: from any from-<side>/ folder that isn't [mySide]'s. */
    fun otherHello(folder: LinkFolder, mySide: String): LinkHello? =
        folder.names().filter { it.startsWith("from-") && it != dir(mySide) }
            .map { it.removePrefix("from-") }.filter { validSide(it) }
            .firstNotNullOfOrNull { hello(folder, it) }

    /** Says how far [side] has read the others ([read]: other side → change number). */
    fun writeReceipts(folder: LinkFolder, side: String, read: Map<String, Int>) {
        val o = JSONObject()
        read.toSortedMap().forEach { (s, n) -> o.put(s, n) }
        val name = "${dir(side)}/read.json"
        val bytes = o.toString().toByteArray()
        if (!(folder.read(name)?.contentEquals(bytes) ?: false)) folder.write(name, bytes)
    }

    /** How far [reader] has read [side]'s changes (0: not yet). */
    fun readUpTo(folder: LinkFolder, reader: String, side: String): Int {
        if (!validSide(reader)) return 0
        return folder.read("${dir(reader)}/read.json")?.let {
            try { JSONObject(String(it)).optInt(side, 0) } catch (e: Exception) { 0 }
        } ?: 0
    }

    /**
     * Removes [side]'s changes that all of [readers] have read (the snapshot stays). Returns
     * how many went. With no readers yet, nothing goes.
     */
    fun cleanUp(folder: LinkFolder, side: String, readers: List<String>): Int {
        if (readers.isEmpty()) return 0
        val upTo = readers.minOf { readUpTo(folder, it, side) }
        if (upTo <= 0) return 0
        val old = folder.names(dir(side)).filter { Regex("\\d{6}\\.gist").matches(it) && it.take(6).toInt() <= upTo }
        old.forEach { folder.delete("${dir(side)}/$it") }
        return old.size
    }

    /** The other sides in the folder (from their from-<side>/ folders). */
    fun sides(folder: LinkFolder, mySide: String): List<String> =
        folder.names().filter { it.startsWith("from-") }.map { it.removePrefix("from-") }
            .filter { validSide(it) && it != mySide }

    /** What changed from [before] to [now] (id → item JSON as text): set (new or different) and removed. */
    fun diff(before: Map<String, String>, now: Map<String, String>): Pair<Map<String, String>, List<String>> =
        now.filter { (id, json) -> before[id] != json } to before.keys.filter { it !in now }

    /** Writes change [seq] and the snapshot of [all] after it. */
    fun publish(folder: LinkFolder, linkId: String, side: String, key: ByteArray, seq: Int,
                set: Map<String, String>, removed: List<String>, all: Map<String, String>) {
        val change = JSONObject().put("seq", seq).put("set", items(set)).put("removed", JSONArray(removed))
        val name = "${dir(side)}/${changeName(seq)}"
        folder.write(name, LinkCrypto.seal(key, "$linkId/$name", change.toString().toByteArray()))
        val snap = JSONObject().put("seq", seq).put("items", items(all))
        val snapName = "${dir(side)}/snapshot.gist"
        folder.write(snapName, LinkCrypto.seal(key, "$linkId/$snapName", snap.toString().toByteArray()))
    }

    private fun items(m: Map<String, String>) = JSONObject().apply { m.forEach { (id, json) -> put(id, JSONObject(json)) } }

    private fun itemsOf(o: JSONObject?): Map<String, String> =
        o?.keys()?.asSequence()?.associateWith { o.getJSONObject(it).toString() } ?: emptyMap()

    private fun openJson(folder: LinkFolder, linkId: String, key: ByteArray, name: String): JSONObject? =
        folder.read(name)?.let { LinkCrypto.open(key, "$linkId/$name", it) }?.let {
            try { JSONObject(String(it)) } catch (e: Exception) { null }
        }

    /**
     * Catches up on [side]'s changes after [seq], from [have]: the new number and items. Starts
     * from the snapshot when the next change isn't there (a new connection, or fell behind).
     * Files that don't open (a wrong key, or changed) are left alone.
     */
    fun receive(folder: LinkFolder, linkId: String, side: String, key: ByteArray, seq: Int,
                have: Map<String, String>): Pair<Int, Map<String, String>> {
        if (!validSide(side)) return seq to have
        var n = seq
        val items = have.toMutableMap()
        fun next() = openJson(folder, linkId, key, "${dir(side)}/${changeName(n + 1)}")
        if (next() == null) {
            val snap = openJson(folder, linkId, key, "${dir(side)}/snapshot.gist")
            if (snap != null && snap.optInt("seq") > n) {
                n = snap.optInt("seq")
                items.clear()
                items.putAll(itemsOf(snap.optJSONObject("items")))
            }
        }
        while (true) {
            val change = next() ?: break
            itemsOf(change.optJSONObject("set")).forEach { (id, json) -> items[id] = json }
            change.optJSONArray("removed")?.let { a -> (0 until a.length()).forEach { items.remove(a.getString(it)) } }
            n++
        }
        return n to items
    }
}
