package dev.gist.checkin

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
 *
 * Each side writes only in its own from-<side>/ folder, so two writers never touch the same
 * file. Changes and snapshots are encrypted (AES-256-GCM: tampering or a wrong key fails to
 * open) with a key both sides work out from their own private key and the other's public key
 * (ECDH on P-256, then HKDF-SHA256): the key itself is never written anywhere.
 */

/** A folder both copies of Gist can read and write: a picked folder (Android's folder picker), or a plain one in tests. */
interface LinkFolder {
    fun read(path: String): ByteArray?
    fun write(path: String, bytes: ByteArray)
    /** The names in the top folder. */
    fun names(): List<String>

    companion object {
        /** "content://…" is a folder picked with the folder picker; anything else is a file path (tests). */
        fun open(context: Context, location: String): LinkFolder =
            if (location.startsWith("content://")) SafFolder(context.contentResolver, Uri.parse(location))
            else FileFolder(File(location))
    }
}

/** A plain folder on disk (tests). */
class FileFolder(private val root: File) : LinkFolder {
    override fun read(path: String): ByteArray? = File(root, path).takeIf { it.isFile }?.readBytes()

    override fun names(): List<String> = root.list()?.toList() ?: emptyList()

    override fun write(path: String, bytes: ByteArray) {
        val f = File(root, path)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.writeBytes(bytes); tmp.delete() }
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

    override fun names(): List<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
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
        for (part in path.split('/').filter { it.isNotEmpty() }) id = child(id, part) ?: return null
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
        val parts = path.split('/').filter { it.isNotEmpty() }
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

/** One side's hello: who's there, and their public key. */
data class LinkHello(val side: String, val name: String, val key: String)

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
            .put("key", hello.key).toString(2).toByteArray())
    }

    /** [side]'s hello, if it's there. */
    fun hello(folder: LinkFolder, side: String): LinkHello? = folder.read("${dir(side)}/hello.json")?.let {
        try {
            val o = JSONObject(String(it))
            LinkHello(o.getString("side"), o.optString("name"), o.getString("key"))
        } catch (e: Exception) {
            null
        }
    }

    /** The other side's hello: from any from-<side>/ folder that isn't [mySide]'s. */
    fun otherHello(folder: LinkFolder, mySide: String): LinkHello? =
        folder.names().filter { it.startsWith("from-") && it != dir(mySide) }
            .firstNotNullOfOrNull { hello(folder, it.removePrefix("from-")) }

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
