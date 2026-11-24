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
 * "r:<day>") as numbered changes plus a snapshot.
 *
 * Not kept in step (yet): the order of your list, background photos and voice clips, settings,
 * and connections (those work from the device you set them up on).
 */
class Devices(
    private val context: Context,
    /** Opens the folder (tests use plain folders). */
    private val openFolder: (String) -> LinkFolder = { LinkFolder.open(context, it) },
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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

    /** The other devices in the folder: side → (name, code to compare). */
    fun others(): List<Triple<String, String, String?>> {
        val f = folder?.let { openFolder(it) } ?: return emptyList()
        val pub = publicKey ?: return emptyList()
        return LinkProtocol.sides(f, side).mapNotNull { s ->
            LinkProtocol.hello(f, s)?.let { h -> Triple(s, h.name, LinkCrypto.code(pub, h.key)) }
        }
    }

    /** Starts syncing in [folder] (empty): this device makes the key; others join. */
    fun setUp(folder: String) {
        val f = openFolder(folder)
        val id = Store.newId() + Store.newId()
        val (pub, priv) = LinkCrypto.newKeyPair()
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        f.write(MARKER, JSONObject().put("format", FORMAT).put("version", 1).put("group", id).toString(2).toByteArray())
        start(folder, id, pub, priv, key)
        LinkProtocol.writeHello(f, LinkHello(side, name, pub))
    }

    /** Joins your devices in [folder]; false when it isn't a devices folder. Ready once a device that's in lets this one in. */
    fun join(folder: String): Boolean {
        val f = openFolder(folder)
        val id = groupOf(f) ?: return false
        val (pub, priv) = LinkCrypto.newKeyPair()
        start(folder, id, pub, priv, null)
        LinkProtocol.writeHello(f, LinkHello(side, name, pub))
        return true
    }

    private fun start(folder: String, id: String, pub: String, priv: String, key: ByteArray?) {
        prefs.edit().putString(K_FOLDER, folder).putString(K_GROUP, id).putString(K_PUB, pub).putString(K_PRIV, priv)
            .putString(K_KEY, key?.let { b64.encodeToString(it) }).putInt(K_SENT_SEQ, 0).remove(K_SENT).remove(K_GOT).commit()
        Store(context).tracking = true
    }

    /** Stops syncing here (your data stays; the folder is left as it is). */
    fun leave() {
        prefs.edit().clear().commit()
        Store(context).tracking = false
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
        if (LinkProtocol.hello(f, side)?.name != name) LinkProtocol.writeHello(f, LinkHello(side, name, pub))
        val others = LinkProtocol.sides(f, side)
        // Not let in yet: look for the key, sealed for this device by one that's in.
        var key = groupKey
        if (key == null) {
            key = others.firstNotNullOfOrNull { s ->
                val h = LinkProtocol.hello(f, s) ?: return@firstNotNullOfOrNull null
                val name = "${LinkProtocol.dir(s)}/key-$side.gist"
                f.read(name)?.let { LinkCrypto.open(LinkCrypto.sharedKey(priv, h.key, id), "$id/$name", it) }
            } ?: return false
            prefs.edit().putString(K_KEY, b64.encodeToString(key)).commit()
        }
        // Let in any device that's joined but has no key yet.
        for (s in others) {
            val name = "${LinkProtocol.dir(side)}/key-$s.gist"
            if (f.read(name) != null) continue
            val h = LinkProtocol.hello(f, s) ?: continue
            f.write(name, LinkCrypto.seal(LinkCrypto.sharedKey(priv, h.key, id), "$id/$name", key))
        }
        // Take in theirs.
        val got = gotSeqs().toMutableMap()
        var changed = false
        for (s in others) {
            val (seq, items) = LinkProtocol.receive(f, id, s, key, got[s] ?: 0, emptyMap())
            if (seq == (got[s] ?: 0)) continue
            changed = merge(items) || changed
            got[s] = seq
        }
        prefs.edit().putString(K_GOT, JSONObject(got as Map<*, *>).toString()).commit()
        // Publish everything here (only what changed since last time).
        val now = everything()
        val sent = sentItems()
        val (set, removed) = LinkProtocol.diff(sent, now)
        val sentSeq = prefs.getInt(K_SENT_SEQ, 0)
        if (set.isNotEmpty() || removed.isNotEmpty() || sentSeq == 0) {
            val seq = sentSeq + 1
            LinkProtocol.publish(f, id, side, key, seq, if (sentSeq == 0) now else set, removed, now)
            prefs.edit().putInt(K_SENT_SEQ, seq).putString(K_SENT, JSONObject(now.mapValues { JSONObject(it.value) }).toString())
                .commit()
        }
        LinkProtocol.writeReceipts(f, side, got)
        LinkProtocol.cleanUp(f, side, others)
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
        var changed = false
        if (deleted != store.deleted) store.deleted = deleted
        if (list != before) {
            store.checkins = list
            changed = true
            AlarmScheduler.scheduleAll(context)
        }
        // Answers: an answer beats "rang"; between answers, the later wins.
        val entries = store.entriesJson()
        val closed = mutableListOf<Pending>()
        var answersChanged = false
        items.filterKeys { it.startsWith("r:") }.forEach { (k, json) ->
            val day = k.removePrefix("r:")
            val theirs = try { JSONObject(json) } catch (e: Exception) { return@forEach }
            val d = entries.optJSONObject(day) ?: JSONObject().also { entries.put(day, it) }
            theirs.keys().forEach { id ->
                val slots = theirs.optJSONObject(id) ?: return@forEach
                val mine = d.optJSONObject(id) ?: JSONObject().also { d.put(id, it) }
                slots.keys().forEach { time ->
                    val t = slots.optJSONObject(time) ?: return@forEach
                    val m = mine.optJSONObject(time)
                    if (m == null || wins(t, m)) {
                        mine.put(time, t)
                        answersChanged = true
                        if (t.optString("s") != SlotResult.RANG) closed.add(Pending(id, day, time))
                    }
                }
            }
        }
        if (answersChanged) {
            store.replaceEntries(entries)
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
        private const val PREFS = "gist-devices"
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
        private val SYNC = Any()

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
            val devices = Devices(activity.applicationContext)
            if (devices.folder == null) return
            Thread {
                val changed = try { synchronized(SYNC) { devices.sync() } } catch (e: Exception) { false }
                if (changed) activity.runOnUiThread { if (!activity.isFinishing) then() }
            }.start()
        }
    }
}
