package dev.gist.checkin

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Background photos, videos and voice clips kept in step on your devices (see [Devices]).
 *
 * Each check-in's media is described by an item "m:<checkinId>": its files (photo-0.jpg,
 * video.mp4, voice.m4a…) by SHA-256, and when that set last changed; the later change wins,
 * and an empty set means the media was removed. The files themselves go in the folder once
 * each, encrypted with your devices' key and named by their hash
 * (from-<side>/m-<first 32 hex>.gist), so they never change and never conflict; a device
 * that needs one takes it from whichever device has it, and checks its hash. A set whose
 * files haven't arrived yet (Syncthing may bring the item first) waits and is tried again.
 * A device removes a file of its own once nothing uses it and every device has read past
 * the change that dropped it.
 *
 * Sizes are the app's own limits on what can be picked (see [Backgrounds]): photos are
 * downscaled, videos and clips capped.
 */
class DeviceMedia(
    private val context: Context,
    private val prefs: SharedPreferences,
    /** The devices' key generation: files are named for it, so a new key means new copies. */
    private val epoch: Int = 0,
) {
    /** A check-in's media: file name → SHA-256 (hex), and when it last changed. */
    data class MediaSet(val files: Map<String, String>, val at: Long)

    private fun load(key: String): Map<String, MediaSet> = prefs.getString(key, null)?.let { s ->
        try {
            val o = JSONObject(s)
            o.keys().asSequence().mapNotNull { id -> parse(o.optJSONObject(id))?.let { id to it } }.toMap()
        } catch (e: Exception) { null }
    } ?: emptyMap()

    private fun save(key: String, m: Map<String, MediaSet>) {
        prefs.edit().putString(key, JSONObject().apply { m.forEach { (id, s) -> put(id, json(s)) } }.toString()).commit()
    }

    /** What this device has (as last seen or taken in), by check-in. */
    fun known(): Map<String, MediaSet> = load(K_MEDIA)

    /** Sets from other devices still waiting for their files. */
    fun waiting(): Map<String, MediaSet> = load(K_WANT)

    /** The items to publish: "m:<id>" for each check-in's media. */
    fun items(): Map<String, String> = known().entries.associate { (id, s) -> "m:$id" to json(s).toString() }

    /** Notes the media sets in another device's [items] that are newer than what's here. */
    fun want(items: Map<String, String>) {
        val known = known()
        val want = waiting().toMutableMap()
        items.filterKeys { it.startsWith("m:") }.forEach { (k, text) ->
            val id = k.removePrefix("m:")
            if (!validId(id)) return@forEach
            val s = try { parse(JSONObject(text)) } catch (e: Exception) { null } ?: return@forEach
            if (s.at > maxOf(known[id]?.at ?: 0, want[id]?.at ?: 0)) want[id] = s
        }
        save(K_WANT, want)
    }

    /**
     * Writes the waiting sets whose files are all in the folder (or here already). True when
     * any media here changed.
     */
    fun takeWaiting(folder: LinkFolder, group: String, key: ByteArray): Boolean {
        val want = waiting().toMutableMap()
        if (want.isEmpty()) return false
        val known = known().toMutableMap()
        var changed = false
        val blobs = blobsBySide(folder)
        for ((id, s) in want.entries.toList()) {
            if (s.at <= (known[id]?.at ?: 0)) { want.remove(id); continue }
            val have = current(id)
            // Get every file that isn't already here, before changing anything.
            val got = HashMap<String, ByteArray>()
            var missing = false
            for ((name, hash) in s.files) {
                if (have[name] == hash) continue
                val bytes = fetch(folder, blobs, group, key, hash)
                if (bytes == null) { missing = true; break }
                got[name] = bytes
            }
            if (missing) continue
            val dir = Backgrounds.dir(context, id)
            got.forEach { (name, bytes) ->
                val tmp = File(dir, "$name.sync")
                tmp.writeBytes(bytes)
                tmp.renameTo(File(dir, name))
            }
            have.keys.filter { it !in s.files }.forEach { File(dir, it).delete() }
            known[id] = s
            want.remove(id)
            changed = true
        }
        save(K_MEDIA, known)
        save(K_WANT, want)
        return changed
    }

    /**
     * Looks at the media of [ids] (the check-ins here) and stamps any that changed (or that
     * hasn't been seen before) with [now]. Forgets check-ins no longer here.
     */
    fun refresh(ids: List<String>, now: Long = System.currentTimeMillis()) {
        val known = known()
        val next = LinkedHashMap<String, MediaSet>()
        for (id in ids) {
            if (!validId(id)) continue
            val cur = current(id)
            val k = known[id]
            next[id] = when {
                k == null && cur.isEmpty() -> continue
                k == null || k.files != cur -> MediaSet(cur, now)
                else -> k
            }
        }
        save(K_MEDIA, next)
        // Hashes of files no longer here aren't needed.
        val paths = ids.flatMap { id -> current(id).keys.map { File(File(root(), id), it).path } }.toSet()
        prefs.edit().putString(K_HASHES, JSONObject(hashes().filterKeys { it in paths } as Map<*, *>).toString()).commit()
    }

    /** Puts into the folder (in [side]'s folder) any file a set here uses that no device has put there yet. */
    fun upload(folder: LinkFolder, side: String, group: String, key: ByteArray) {
        val blobs = blobsBySide(folder)
        val there = blobs.values.flatten().toHashSet()
        for ((id, s) in known()) {
            for ((name, hash) in s.files) {
                val blob = blobName(hash, epoch)
                if (blob in there) continue
                val f = File(File(root(), id), name)
                if (!f.exists()) continue
                val bytes = f.readBytes()
                if (sha256(bytes) != hash) continue // changed since: the next sync stamps the new one
                folder.write("${LinkProtocol.dir(side)}/$blob", LinkCrypto.seal(key, "$group/$blob", bytes))
                there.add(blob)
            }
        }
    }

    /**
     * Removes this device's files in the folder that nothing uses any more, once every
     * other device ([readers]) has read up to [seq] (so it's heard about the change).
     */
    fun cleanUp(folder: LinkFolder, side: String, readers: List<String>, seq: Int) {
        if (readers.any { LinkProtocol.readUpTo(folder, it, side) < seq }) return
        val used = (known().values + waiting().values).flatMap { it.files.values }.map { blobName(it, epoch) }.toSet()
        folder.names(LinkProtocol.dir(side)).filter { BLOB.matches(it) && it !in used }
            .forEach { folder.delete("${LinkProtocol.dir(side)}/$it") }
    }

    private fun blobsBySide(folder: LinkFolder): Map<String, List<String>> =
        folder.names().filter { it.startsWith("from-") && LinkProtocol.validSide(it.removePrefix("from-")) }
            .associateWith { d -> folder.names(d).filter { BLOB.matches(it) } }

    private fun fetch(folder: LinkFolder, blobs: Map<String, List<String>>, group: String, key: ByteArray, hash: String): ByteArray? {
        val blob = blobName(hash, epoch)
        for ((dir, names) in blobs) {
            if (blob !in names) continue
            val sealed = folder.read("$dir/$blob") ?: continue
            val bytes = LinkCrypto.open(key, "$group/$blob", sealed) ?: continue
            if (sha256(bytes) == hash) return bytes
        }
        return null
    }

    private fun root() = File(Profiles.filesDir(context), "backgrounds")

    /** The media files check-in [id] has here now: name → hash. */
    private fun current(id: String): Map<String, String> {
        val dir = File(root(), id)
        val files = dir.listFiles()?.filter { it.isFile && NAME.matches(it.name) && it.length() > 0 } ?: return emptyMap()
        if (files.isEmpty()) return emptyMap()
        val cache = hashes().toMutableMap()
        val out = sortedMapOf<String, String>()
        for (f in files) {
            val stamp = "${f.length()}:${f.lastModified()}"
            val cached = cache[f.path]?.takeIf { it.startsWith("$stamp:") }?.substringAfterLast(':')
            val hash = cached ?: sha256(f).also { cache[f.path] = "$stamp:$it" }
            out[f.name] = hash
        }
        prefs.edit().putString(K_HASHES, JSONObject(cache as Map<*, *>).toString()).commit()
        return out
    }

    private fun hashes(): Map<String, String> = prefs.getString(K_HASHES, null)?.let { s ->
        try { JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } } catch (e: Exception) { null }
    } ?: emptyMap()

    companion object {
        private const val K_MEDIA = "media"
        private const val K_WANT = "mediaWanted"
        private const val K_HASHES = "mediaHashes"
        /** Its keys in the devices' preferences (cleared when syncing starts afresh). */
        val KEYS = listOf(K_MEDIA, K_WANT, K_HASHES)

        /** Media file names a check-in can have (others, like half-copied files, are ignored). */
        val NAME = Regex("photo-\\d{1,3}\\.jpg|video\\.mp4|voice\\.m4a")
        private val HASH = Regex("[0-9a-f]{64}")
        private val BLOB = Regex("m-[0-9a-f]{32}(-\\d{1,9})?\\.gist")
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun validId(id: String) = ID.matches(id)

        /** A file's name in the folder: by its hash, and the key's generation (none for the first). */
        fun blobName(hash: String, epoch: Int = 0) = "m-${hash.take(32)}${if (epoch > 0) "-$epoch" else ""}.gist"

        fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return hex(md.digest())
        }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        fun json(s: MediaSet): JSONObject = JSONObject().put("files", JSONObject(s.files as Map<*, *>)).put("at", s.at)

        /** A media set from JSON, keeping only valid names and hashes; null if it isn't one. */
        fun parse(o: JSONObject?): MediaSet? {
            if (o == null || !o.has("at")) return null
            val f = o.optJSONObject("files") ?: return null
            val files = f.keys().asSequence()
                .mapNotNull { n -> f.optString(n).takeIf { NAME.matches(n) && HASH.matches(it) }?.let { n to it } }
                .toMap().toSortedMap()
            return MediaSet(files, o.optLong("at", 0))
        }
    }
}
