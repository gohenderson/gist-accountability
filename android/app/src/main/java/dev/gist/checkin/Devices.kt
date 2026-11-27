package dev.gist.checkin

import android.content.Context
import org.json.JSONObject
import java.util.Base64

/**
 * Your own devices (say a phone and a tablet): all your check-ins, tasks and answers kept in
 * step through a folder they share (later kept in step between them by Syncthing). Edit on
 * any; the later edit of a check-in wins, and the later answer of a time wins (an answer
 * always beats "rang"). Answering on one counts on all: once the others hear, they stop
 * waiting (and ringing) for it. Each device can be set not to ring.
 *
 * The folder is like a connection's (LinkCore.kt), but every device reads every other, and
 * they share one key: the first device makes it; each device that joins gets it from any
 * device already in, sealed with a key only the two of them can work out (ECDH). Each device
 * publishes everything it has (check-ins "c:<id>", deletions "d:<id>", each day's answers
 * "r:<day>", the order of your list "o:list", each shared setting "s:<key>") as numbered
 * changes plus a snapshot. The later reorder wins, and the later change of each setting
 * (Store.SYNCED_SETTINGS; Ring on this device and the like stay per device).
 *
 * Background photos, videos and voice clips: see [DeviceMedia] ("m:<id>", files sent once each).
 *
 * Connections with other people: each has one home device that reads and publishes for it
 * ("l:<id>": its keys, who it's with and what they share; see [Connections.deviceItems]).
 * What you share, add from them or finish for them is in your check-ins, so it reaches the
 * home like any change; another device can take a connection over.
 */
class Devices(
    private val context: Context,
    /** Opens the folder (tests use plain folders). */
    private val openFolder: (String) -> LinkFolder = { LinkFolder.open(context, it) },
) {
    private val prefs = Profiles.prefs(context, PREFS)
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    /** The folder, or null when this device isn't syncing. */
    val folder: String? get() = prefs.getString(K_FOLDER, null)
    val side: String get() = prefs.getString(K_SIDE, null) ?: Store.newId().also { prefs.edit().putString(K_SIDE, it).commit() }
    private val groupId: String? get() = prefs.getString(K_GROUP, null)
    private val publicKey: String? get() = prefs.getString(K_PUB, null)
    private val privateKey: String? get() = prefs.getString(K_PRIV, null)
    private val groupKey: ByteArray? get() = prefs.getString(K_KEY, null)?.let { unb64.decode(it) }

    /** Set up, and has the shared key (false while waiting for another device to let it in). */
    val ready: Boolean get() = folder != null && groupKey != null

    /** This device's name, as your other devices list it. */
    var name: String
        get() = prefs.getString(K_NAME, null) ?: android.os.Build.MODEL ?: "This device"
        set(v) { prefs.edit().putString(K_NAME, v.trim().ifEmpty { null }).commit() }

    /** The other devices in the folder: side → (name, code to compare). Not ones that left or were removed. */
    fun others(): List<Triple<String, String, String?>> {
        val f = folder?.let { openFolder(it) } ?: return emptyList()
        val pub = publicKey ?: return emptyList()
        return members(f).mapNotNull { s ->
            LinkProtocol.hello(f, s)?.let { h -> Triple(s, h.name, LinkCrypto.code(pub, h.key)) }
        }
    }

    /** The key's generation: a new one each time a device is removed. */
    private val epoch: Int get() = prefs.getInt(K_EPOCH, 0)

    /** Devices removed from the group (never read or let in again). */
    private val removed: kotlin.collections.Set<String> get() = prefs.getStringSet(K_REMOVED, null)?.toSet() ?: emptySet()

    /** The other devices that are in: in the folder (with a hello: one that left has none) and not removed. */
    private fun members(f: LinkFolder): List<String> {
        val gone = removed
        return LinkProtocol.sides(f, side).filter { it !in gone && LinkProtocol.hello(f, it) != null }
    }

    /** Starts syncing in [folder] (empty): this device makes the key; others join. */
    fun setUp(folder: String) {
        val f = openFolder(folder)
        val id = Store.newId() + Store.newId()
        val (pub, priv) = LinkCrypto.newKeyPair()
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        f.write(MARKER, JSONObject().put("format", FORMAT).put("version", 1).put("group", id).toString(2).toByteArray())
        start(folder, id, pub, priv, key)
        Store(context).stampForSync() // the devices that join take this one's order and settings
        LinkProtocol.writeHello(f, LinkHello(side, name, pub))
    }

    /**
     * An invite (QR code or text code) for another device to join with, from this one (it has
     * to be ready): a new one if there's none or it ran out. Used once, within 15 minutes.
     */
    fun invitation(now: Long = System.currentTimeMillis()): Pair<LinkInvite, Long>? {
        val pub = publicKey ?: return null
        if (!ready) return null
        var token = prefs.getString(K_INV_TOKEN, null)
        var expires = prefs.getLong(K_INV_EXPIRES, 0)
        if (token == null || expires < now) {
            token = b64.encodeToString(LinkInvites.newToken())
            expires = now + LinkInvites.VALID_MS
            prefs.edit().putString(K_INV_TOKEN, token).putLong(K_INV_EXPIRES, expires).commit()
        }
        return LinkInvite('d', LinkInvites.fingerprint(pub), unb64.decode(token)) to expires
    }

    /** Why a join didn't work. */
    enum class JoinProblem { NOT_DEVICES, NOT_THEIR_INVITE }

    /**
     * Joins your devices in [folder] with [invite] from one of them; ready once that device
     * lets this one in (only it is trusted to hand over the key).
     */
    fun join(folder: String, invite: LinkInvite, now: Long = System.currentTimeMillis()): JoinProblem? {
        val f = openFolder(folder)
        val id = groupOf(f) ?: return JoinProblem.NOT_DEVICES
        val inviter = LinkProtocol.sides(f, side).mapNotNull { LinkProtocol.hello(f, it) }
            .firstOrNull { LinkInvites.fingerprint(it.key).contentEquals(invite.fingerprint) }
            ?: return JoinProblem.NOT_THEIR_INVITE
        val (pub, priv) = LinkCrypto.newKeyPair()
        start(folder, id, pub, priv, null)
        prefs.edit().putString(K_INVITER, inviter.side).commit()
        LinkProtocol.writeHello(f, LinkHello(side, name, pub, LinkInvites.proof(invite.token, id, side, pub, now), now))
        return null
    }

    private fun start(folder: String, id: String, pub: String, priv: String, key: ByteArray?) {
        prefs.edit().putString(K_FOLDER, folder).putString(K_GROUP, id).putString(K_PUB, pub).putString(K_PRIV, priv)
            .putString(K_KEY, key?.let { b64.encodeToString(it) }).putInt(K_SENT_SEQ, 0).remove(K_SENT).remove(K_GOT)
            .apply { DeviceMedia.KEYS.forEach { remove(it) } }.commit()
        Store(context).tracking = true
    }

    /**
     * Stops syncing here: your data stays on this device, and its files leave the folder, so
     * your other devices stop listing it (and waiting for it to read their changes).
     */
    fun leave() {
        folder?.let { loc ->
            try { removeFiles(openFolder(loc), side) } catch (e: Exception) { }
        }
        prefs.edit().clear().commit()
        Store(context).tracking = false
    }

    private fun removeFiles(f: LinkFolder, s: String) {
        val dir = LinkProtocol.dir(s)
        f.names(dir).forEach { f.delete("$dir/$it") }
        f.delete(dir)
    }

    /**
     * Removes another device ([other]; say it's lost): your devices stop reading it, and switch
     * to a new key it doesn't have, so it can't read what's synced from now on. Its files
     * leave the folder.
     */
    fun remove(other: String) {
        val loc = folder ?: return
        if (!ready || other == side) return
        val f = openFolder(loc)
        rotate(f, removed + other)
    }

    /** A new key (one generation on), sealed for each device still in; [gone] left out for good. */
    private fun rotate(f: LinkFolder, gone: kotlin.collections.Set<String>) {
        val id = groupId ?: return
        val priv = privateKey ?: return
        val next = epoch + 1
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit().putStringSet(K_REMOVED, gone).commit()
        for (m in members(f)) {
            val h = LinkProtocol.hello(f, m) ?: continue
            val name = "${LinkProtocol.dir(side)}/rekey-$m.gist"
            val body = JSONObject().put("epoch", next).put("key", b64.encodeToString(key))
                .put("removed", org.json.JSONArray(gone.toList()))
            f.write(name, LinkCrypto.seal(LinkCrypto.sharedKey(priv, h.key, id), "$id/$name", body.toString().toByteArray()))
        }
        gone.forEach { try { removeFiles(f, it) } catch (e: Exception) { } }
        forgetKeysFor(f, gone)
        useKey(key, next, side, gone)
    }

    /**
     * Removes the keys this device sealed for others ([others]) once they have them: their
     * snapshot opens with the key in use.
     */
    private fun forgetPickedUpKeys(f: LinkFolder, id: String, key: ByteArray, others: List<String>) {
        val dir = LinkProtocol.dir(side)
        val mine = f.names(dir).toSet()
        for (s in others) {
            val sealed = listOf("key-$s.gist", "rekey-$s.gist").filter { it in mine }
            if (sealed.isEmpty()) continue
            val snap = "${LinkProtocol.dir(s)}/snapshot.gist"
            val has = f.read(snap)?.let { LinkCrypto.open(key, "$id/$snap", it) } != null
            if (has) sealed.forEach { f.delete("$dir/$it") }
        }
    }

    /** Removes the keys this device sealed for removed devices (theirs to open no more). */
    private fun forgetKeysFor(f: LinkFolder, gone: kotlin.collections.Set<String>) {
        val dir = LinkProtocol.dir(side)
        for (x in gone) {
            f.delete("$dir/key-$x.gist")
            f.delete("$dir/rekey-$x.gist")
        }
    }

    /**
     * Switches to [key] (generation [gen], from device [from], made without the devices
     * [leavesOut]); everything here is published again with it.
     */
    private fun useKey(key: ByteArray, gen: Int, from: String, leavesOut: kotlin.collections.Set<String>) {
        prefs.edit().putString(K_KEY, b64.encodeToString(key)).putInt(K_EPOCH, gen).putString(K_KEY_FROM, from)
            .putStringSet(K_KEY_REMOVED, leavesOut).putBoolean(K_REPUBLISH, true).commit()
    }

    /**
     * A newer key another device sealed for this one (after it removed a device): taken, with
     * who's removed. Of two made at once, the one from the lower side wins everywhere; if a
     * device one of them removed is still in the winner's, it's removed again.
     */
    private fun takeNewKey(f: LinkFolder, id: String, priv: String) {
        var best: Triple<Int, String, ByteArray>? = null
        var bestRemoved = emptySet<String>()
        val gone = removed.toMutableSet()
        for (s in members(f)) {
            val name = "${LinkProtocol.dir(s)}/rekey-$side.gist"
            val sealed = f.read(name) ?: continue
            val h = LinkProtocol.hello(f, s) ?: continue
            val o = LinkCrypto.open(LinkCrypto.sharedKey(priv, h.key, id), "$id/$name", sealed)
                ?.let { try { JSONObject(String(it)) } catch (e: Exception) { null } } ?: continue
            val gen = o.optInt("epoch")
            val key = try { unb64.decode(o.getString("key")) } catch (e: Exception) { continue }
            val rm = o.optJSONArray("removed")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            if (side in rm) continue
            gone.addAll(rm)
            val b = best
            if (b == null || gen > b.first || (gen == b.first && s < b.second)) { best = Triple(gen, s, key); bestRemoved = rm }
        }
        val b = best
        val cur = epoch to (prefs.getString(K_KEY_FROM, null) ?: "")
        val newer = b != null && (b.first > cur.first || (b.first == cur.first && cur.second.isNotEmpty() && b.second < cur.second))
        prefs.edit().putStringSet(K_REMOVED, gone).commit()
        forgetKeysFor(f, gone)
        if (b != null && newer) useKey(b.third, b.first, b.second, bestRemoved)
        // The key in use was made before one of the removals (two removed at once): make
        // another without them.
        val keyLeavesOut = prefs.getStringSet(K_KEY_REMOVED, null)?.toSet() ?: emptySet()
        if (!keyLeavesOut.containsAll(gone)) rotate(f, gone)
    }

    /**
     * Brings this device and the folder up to date: takes in the others' changes, then
     * publishes everything here. True when anything here changed.
     */
    fun sync(): Boolean {
        val loc = folder ?: return false
        val f = openFolder(loc)
        val id = groupId ?: return false
        val pub = publicKey ?: return false
        val priv = privateKey ?: return false
        LinkProtocol.rename(f, side, name, pub)
        // Not let in yet: look for the key, sealed for this device by the one whose invite it used.
        if (groupKey == null) {
            val inviter = prefs.getString(K_INVITER, null) ?: return false
            val h = LinkProtocol.hello(f, inviter) ?: return false
            val name = "${LinkProtocol.dir(inviter)}/key-$side.gist"
            val bytes = f.read(name)?.let { LinkCrypto.open(LinkCrypto.sharedKey(priv, h.key, id), "$id/$name", it) } ?: return false
            // The key, and its generation (just the key from older devices: the first).
            val o = if (bytes.size == 32) null else try { JSONObject(String(bytes)) } catch (e: Exception) { return false }
            val k = keyOf(bytes) ?: return false
            val rm = o?.optJSONArray("removed")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            prefs.edit().putString(K_KEY, b64.encodeToString(k)).putInt(K_EPOCH, o?.optInt("epoch") ?: 0)
                .putString(K_KEY_FROM, inviter).putStringSet(K_REMOVED, rm).putStringSet(K_KEY_REMOVED, rm).commit()
        }
        // A device removed elsewhere: a newer key.
        takeNewKey(f, id, priv)
        val key = groupKey ?: return false
        val others = members(f)
        // Let in the device that joined with this one's invite (proving it had the token), once.
        val token = prefs.getString(K_INV_TOKEN, null)?.let { unb64.decode(it) }
        if (token != null) for (s in others) {
            val name = "${LinkProtocol.dir(side)}/key-$s.gist"
            if (f.read(name) != null) continue
            val h = LinkProtocol.hello(f, s) ?: continue
            if (!LinkInvites.proves(h, token, id, prefs.getLong(K_INV_EXPIRES, 0))) continue
            val body = JSONObject().put("key", b64.encodeToString(key)).put("epoch", epoch)
                .put("removed", org.json.JSONArray(removed.toList()))
            f.write(name, LinkCrypto.seal(LinkCrypto.sharedKey(priv, h.key, id), "$id/$name", body.toString().toByteArray()))
            prefs.edit().remove(K_INV_TOKEN).remove(K_INV_EXPIRES).commit()
            break
        }
        // Take in theirs.
        val media = DeviceMedia(context, prefs, epoch)
        val got = gotSeqs().toMutableMap()
        var changed = false
        for (s in others) {
            val (seq, items) = LinkProtocol.receive(f, id, s, key, got[s] ?: 0, emptyMap())
            if (seq == (got[s] ?: 0)) continue
            changed = merge(items) || changed
            media.want(items)
            got[s] = seq
        }
        prefs.edit().putString(K_GOT, JSONObject(got as Map<*, *>).toString()).commit()
        // Their photos, videos and clips (once the files are there); then put up any of ours.
        if (media.takeWaiting(f, id, key)) changed = true
        media.refresh(Store(context).checkins.map { it.id })
        media.upload(f, side, id, key)
        // Publish everything here (only what changed since last time).
        val now = everything()
        val sent = sentItems()
        val (set, removed) = LinkProtocol.diff(sent, now)
        val sentSeq = prefs.getInt(K_SENT_SEQ, 0)
        // With a new key, everything again (the others can't read what came before with it).
        val all = sentSeq == 0 || prefs.getBoolean(K_REPUBLISH, false)
        if (set.isNotEmpty() || removed.isNotEmpty() || all) {
            val seq = sentSeq + 1
            LinkProtocol.publish(f, id, side, key, seq, if (all) now else set, if (all) emptyList() else removed, now)
            prefs.edit().putInt(K_SENT_SEQ, seq).putString(K_SENT, JSONObject(now.mapValues { JSONObject(it.value) }).toString())
                .putBoolean(K_REPUBLISH, false).commit()
        }
        LinkProtocol.writeReceipts(f, side, got)
        if (others.isEmpty()) {
            // The only device: no one to wait for (a device that joins starts from the snapshot).
            f.names(LinkProtocol.dir(side)).filter { CHANGE.matches(it) }.forEach { f.delete("${LinkProtocol.dir(side)}/$it") }
        } else {
            LinkProtocol.cleanUp(f, side, others)
        }
        media.cleanUp(f, side, others, prefs.getInt(K_SENT_SEQ, 0))
        forgetPickedUpKeys(f, id, key, others)
        return changed
    }

    private fun gotSeqs(): Map<String, Int> = prefs.getString(K_GOT, null)?.let { s ->
        try { JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getInt(it) } } } catch (e: Exception) { null }
    } ?: emptyMap()

    private fun sentItems(): Map<String, String> = prefs.getString(K_SENT, null)?.let { s ->
        try { JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getJSONObject(it).toString() } } } catch (e: Exception) { null }
    } ?: emptyMap()

    /** Everything this device has, as items. */
    private fun everything(): Map<String, String> {
        val store = Store(context)
        val out = LinkedHashMap<String, String>()
        store.checkins.forEach { out["c:${it.id}"] = store.checkinToJson(it).toString() }
        store.deleted.forEach { (id, at) -> out["d:$id"] = JSONObject().put("at", at).toString() }
        val entries = store.entriesJson()
        entries.keys().forEach { day -> entries.optJSONObject(day)?.let { out["r:$day"] = it.toString() } }
        if (store.orderAt > 0) {
            out[ORDER] = JSONObject().put("ids", org.json.JSONArray(store.checkins.map { it.id })).put("at", store.orderAt).toString()
        }
        store.syncedSettings().forEach { (k, v) -> out["s:$k"] = JSONObject().put("v", v.first).put("at", v.second).toString() }
        out.putAll(DeviceMedia(context, prefs, epoch).items())
        out.putAll(Connections(context).deviceItems(side, name))
        return out
    }

    /** Takes in another device's [items]. True when anything here changed. */
    private fun merge(items: Map<String, String>): Boolean {
        val store = Store(context)
        val before = store.checkins
        var list = before.toMutableList()
        val deleted = store.deleted.toMutableMap()
        // Deletions: unless it's been changed since.
        items.filterKeys { it.startsWith("d:") }.forEach { (k, json) ->
            val id = k.removePrefix("d:")
            val at = try { JSONObject(json).getLong("at") } catch (e: Exception) { return@forEach }
            if ((deleted[id] ?: 0) < at) deleted[id] = at
            list.removeAll { it.id == id && (it.updatedAt ?: 0) <= at }
        }
        // Check-ins: the later edit wins; new ones are added at the end.
        items.filterKeys { it.startsWith("c:") }.forEach { (_, json) ->
            val c = try { store.checkinFromJson(JSONObject(json)) } catch (e: Exception) { return@forEach }
            if ((deleted[c.id] ?: -1) >= (c.updatedAt ?: 0)) return@forEach
            val i = list.indexOfFirst { it.id == c.id }
            if (i < 0) list.add(c) else if ((c.updatedAt ?: 0) > (list[i].updatedAt ?: 0)) list[i] = c
        }
        // Order: the later reorder wins (any not in it keep their order, after).
        val order = items[ORDER]?.let { try { JSONObject(it) } catch (e: Exception) { null } }
        val orderAt = order?.optLong("at", 0) ?: 0
        val takeOrder = orderAt > store.orderAt
        if (takeOrder) {
            val ids = order!!.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            val pos = ids.withIndex().associate { it.value to it.index }
            list = (list.filter { it.id in pos }.sortedBy { pos[it.id] } + list.filter { it.id !in pos }).toMutableList()
        }
        var changed = false
        // Connections kept on your other devices (or taken over from this one).
        if (Connections(context).takeDeviceItems(items, side)) changed = true
        // Settings: the later change of each wins.
        items.filterKeys { it.startsWith("s:") }.forEach { (k, json) ->
            val o = try { JSONObject(json) } catch (e: Exception) { return@forEach }
            if (store.takeSetting(k.removePrefix("s:"), o.optString("v"), o.optLong("at", 0))) changed = true
        }
        if (deleted != store.deleted) store.deleted = deleted
        if (list != before) {
            store.checkins = list
            changed = true
            // Deleted elsewhere: no more alarms (or place watches), photos or clips for them here.
            (before.map { it.id } - list.map { it.id }.toSet()).forEach {
                AlarmScheduler.cancel(context, it)
                Backgrounds.delete(context, it)
            }
            AlarmScheduler.scheduleAll(context)
        }
        if (takeOrder) store.orderAt = orderAt // theirs, not the time it was taken in here
        // Answers: an answer beats "rang"; between answers, the later wins.
        val entries = store.entriesJson()
        val closed = mutableListOf<Pending>()
        val taken = mutableListOf<Pair<Pending, JSONObject>>()
        items.filterKeys { it.startsWith("r:") }.forEach { (k, json) ->
            val day = k.removePrefix("r:")
            val theirs = try { JSONObject(json) } catch (e: Exception) { return@forEach }
            val d = entries.optJSONObject(day)
            theirs.keys().forEach { id ->
                val slots = theirs.optJSONObject(id) ?: return@forEach
                val mine = d?.optJSONObject(id)
                slots.keys().forEach { time ->
                    val t = slots.optJSONObject(time) ?: return@forEach
                    val m = mine?.optJSONObject(time)
                    if (m == null || wins(t, m)) {
                        taken.add(Pending(id, day, time) to t)
                        if (t.optString("s") != SlotResult.RANG) closed.add(Pending(id, day, time))
                    }
                }
            }
        }
        if (taken.isNotEmpty()) {
            store.putSlotsJson(taken)
            changed = true
        }
        // Answered elsewhere: stop waiting (and ringing) for it here.
        val waiting = store.pending.filter { p -> !p.practice && closed.any { it.checkinId == p.checkinId && it.day == p.day && it.time == p.time } }
        SoftAlarm.closedElsewhere(context, waiting)
        return changed
    }

    /** Does [theirs] beat [mine] for the same time? */
    private fun wins(theirs: JSONObject, mine: JSONObject): Boolean {
        val tRang = theirs.optString("s") == SlotResult.RANG
        val mRang = mine.optString("s") == SlotResult.RANG
        if (tRang != mRang) return mRang // an answer beats "rang"
        return theirs.optLong("w", 0) > mine.optLong("w", 0)
    }

    companion object {
        const val PREFS = "gist-devices"
        const val FORMAT = "gist-devices"
        const val MARKER = "gist-devices.json"
        private const val K_FOLDER = "folder"
        private const val K_SIDE = "side"
        private const val K_GROUP = "group"
        private const val K_PUB = "pub"
        private const val K_PRIV = "priv"
        private const val K_KEY = "key"
        private const val K_NAME = "name"
        private const val K_SENT_SEQ = "sentSeq"
        private const val K_SENT = "sent"
        private const val K_GOT = "got"
        private const val K_INV_TOKEN = "inviteToken"
        private const val K_INV_EXPIRES = "inviteExpires"
        private const val K_INVITER = "inviter"
        private const val K_EPOCH = "epoch"
        private const val K_KEY_FROM = "keyFrom"
        private const val K_REMOVED = "removed"
        private const val K_REPUBLISH = "republish"
        private const val K_KEY_REMOVED = "keyRemoved"
        private val SYNC = Any()

        private const val ORDER = "o:list"
        private val CHANGE = Regex("\\d{6}\\.gist")

        /** The key in a let-in file: just the key (older devices), or JSON with its generation. */
        fun keyOf(bytes: ByteArray): ByteArray? = if (bytes.size == 32) bytes else try {
            Base64.getDecoder().decode(JSONObject(String(bytes)).getString("key"))
        } catch (e: Exception) { null }

        /** The group id of a devices folder, or null. */

        fun groupOf(folder: LinkFolder): String? = folder.read(MARKER)?.let {
            try {
                JSONObject(String(it)).takeIf { o -> o.optString("format") == FORMAT }?.optString("group")?.ifEmpty { null }
            } catch (e: Exception) {
                null
            }
        }

        /** Syncs your devices off the main thread; then [then] on it when anything here changed. */
        fun syncInBackground(activity: android.app.Activity, then: () -> Unit = {}) {
            val devices = Devices(Profiles.scoped(activity, Profiles.of(activity)))
            if (devices.folder == null) return
            Thread {
                val changed = try { synchronized(SYNC) { devices.sync() } } catch (e: Exception) { false }
                if (changed) activity.runOnUiThread { if (!activity.isFinishing) then() }
            }.start()
        }
    }
}
