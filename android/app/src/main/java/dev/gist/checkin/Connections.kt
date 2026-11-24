package dev.gist.checkin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A connection with another copy of Gist (see LinkCore.kt for the folder and the encryption; Connections keeps them).
 * [side]: this copy's id in the folder; [peer]: the other side's, once it has joined.
 * [privateKey] stays on this phone (in Gist's own storage, never in the folder or backups).
 */
data class Link(
    val id: String,
    val folder: String,
    val side: String,
    val publicKey: String,
    val privateKey: String,
    val peer: String? = null,
    val peerName: String = "",
    val peerKey: String? = null,
    /** Show what they share on the start page. */
    val shown: Boolean = true,
    /** The last change number this side published, and what it published (id → item JSON). */
    val sentSeq: Int = 0,
    val sent: Map<String, String> = emptyMap(),
    /** The last of their changes read. */
    val gotSeq: Int = 0,
) {
    val connected: Boolean get() = peer != null && peerKey != null

    /** The code both phones show (compare once). */
    val code: String? get() = peerKey?.let { LinkCrypto.code(publicKey, it) }

    fun key(): ByteArray? = peerKey?.let { LinkCrypto.sharedKey(privateKey, it, id) }
}

/**
 * Connections: inviting and joining, publishing what you share with each (check-ins and tasks
 * whose "Share with" includes it), and reading what each shares with you (read-only). Kept in
 * their own preferences file, apart from the check-ins.
 */
class Connections(
    private val context: Context,
    /** Opens a connection's folder (tests use plain folders). */
    private val openFolder: (String) -> LinkFolder = { LinkFolder.open(context, it) },
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Your name, as the other side sees it. */
    var myName: String
        get() = prefs.getString(K_NAME, "") ?: ""
        set(v) { prefs.edit().putString(K_NAME, v.trim()).commit() }

    val all: List<Link>
        get() = prefs.getString(K_LINKS, null)?.let { s ->
            try {
                val a = JSONArray(s)
                (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()

    fun get(id: String) = all.firstOrNull { it.id == id }

    private fun save(list: List<Link>) {
        prefs.edit().putString(K_LINKS, JSONArray().apply { list.forEach { put(toJson(it)) } }.toString()).commit()
    }

    private fun update(id: String, change: (Link) -> Link) = synchronized(LOCK) { save(all.map { if (it.id == id) change(it) else it }) }

    fun setShown(id: String, shown: Boolean) = update(id) { it.copy(shown = shown) }

    /** Forgets connection [id] here (and what it shared), and stops sharing with it. The folder is left as it is. */
    fun remove(id: String) {
        synchronized(LOCK) {
            save(all.filter { it.id != id })
            prefs.edit().remove(K_GOT + id).commit()
        }
        val store = Store(context)
        store.checkins.filter { id in it.sharedWith }.forEach { c ->
            store.updateCheckin(c.id) { it.copy(sharedWith = it.sharedWith - id) }
        }
    }

    /** Starts a connection in [folder] (empty, or not yet a connection): others join it from the same folder. */
    fun invite(folder: String): Link {
        val f = openFolder(folder)
        val (pub, priv) = LinkCrypto.newKeyPair()
        val link = Link(id = Store.newId() + Store.newId(), folder = folder, side = Store.newId(), publicKey = pub, privateKey = priv)
        LinkProtocol.invite(f, link.id, LinkHello(link.side, myName, pub))
        synchronized(LOCK) { save(all + link) }
        return link
    }

    /** Joins the connection waiting in [folder]; null when there's none there (or it's already one of yours). */
    fun join(folder: String): Link? {
        val f = openFolder(folder)
        val id = LinkProtocol.linkId(f) ?: return null
        if (all.any { it.id == id }) return null
        val (pub, priv) = LinkCrypto.newKeyPair()
        var link = Link(id = id, folder = folder, side = Store.newId(), publicKey = pub, privateKey = priv)
        LinkProtocol.writeHello(f, LinkHello(link.side, myName, pub))
        LinkProtocol.otherHello(f, link.side)?.let { link = link.copy(peer = it.side, peerName = it.name, peerKey = it.key) }
        synchronized(LOCK) { save(all + link) }
        return link
    }

    /**
     * Brings every connection up to date: notices who joined, publishes what you share (only
     * what changed), and reads what they share. True when what they share changed.
     */
    fun sync(): Boolean {
        val items = Store(context).checkins
        var changed = false
        for (l in all) {
            try {
                changed = syncOne(l, items) || changed
            } catch (e: Exception) {
                // A folder that's gone or unreadable: try again next time.
            }
        }
        return changed
    }

    private fun syncOne(start: Link, items: List<Checkin>): Boolean {
        val f = openFolder(start.folder)
        var l = start
        // The other side joined (or changed its name).
        if (!l.connected || l.peerName.isEmpty()) {
            LinkProtocol.otherHello(f, l.side)?.let { h ->
                l = l.copy(peer = h.side, peerName = h.name, peerKey = h.key)
                update(l.id) { it.copy(peer = h.side, peerName = h.name, peerKey = h.key) }
            }
        }
        // Your name changed: say so.
        if (LinkProtocol.hello(f, l.side)?.name != myName) LinkProtocol.writeHello(f, LinkHello(l.side, myName, l.publicKey))
        val key = l.key() ?: return false
        val peer = l.peer ?: return false
        // Read: their changes since last time; then bring yours up to date with them (tasks
        // they finished for you, and what you added from them).
        val before = received(l.id)
        val (gotSeq, got) = LinkProtocol.receive(f, l.id, peer, key, l.gotSeq, before)
        if (gotSeq != l.gotSeq) {
            synchronized(LOCK) { prefs.edit().putString(K_GOT + l.id, JSONObject(got.mapValues { JSONObject(it.value) }).toString()).commit() }
            update(l.id) { it.copy(gotSeq = gotSeq) }
        }
        val mine = apply(l, got) ?: items
        // Publish: what you share with them now (and the tasks of theirs you've done), compared
        // with what you last sent.
        val now = mine.filter { l.id in it.sharedWith && !it.isLinked }.associate { it.id to shareJson(it, l) }.toMutableMap()
        doneForThem(l, mine).takeIf { it.isNotEmpty() }?.let { done ->
            now[DONE_KEY] = JSONObject().apply { done.forEach { (id, at) -> put(id, at) } }.toString()
        }
        val (set, removed) = LinkProtocol.diff(l.sent, now)
        if (set.isNotEmpty() || removed.isNotEmpty() || l.sentSeq == 0) {
            val seq = l.sentSeq + 1
            LinkProtocol.publish(f, l.id, l.side, key, seq, if (l.sentSeq == 0) now else set, removed, now)
            update(l.id) { it.copy(sentSeq = seq, sent = now) }
        }
        // Say how far you've read; clean out your changes they've read.
        LinkProtocol.writeReceipts(f, l.side, mapOf(peer to gotSeq))
        LinkProtocol.cleanUp(f, l.side, listOf(peer))
        return got != before
    }

    /**
     * Brings your check-ins up to date with what [l] shares ([got]): tasks of yours they
     * finished (if they may: anyone's, or assigned to them), and what you added from them
     * (their name and description, who may do it, done on their side, or gone). Returns your
     * check-ins when anything changed.
     */
    private fun apply(l: Link, got: Map<String, String>): List<Checkin>? {
        val store = Store(context)
        val theirs = got.filterKeys { it != DONE_KEY }.mapValues { (_, json) ->
            try { store.checkinFromJson(JSONObject(json)) } catch (e: Exception) { null }
        }
        val done = got[DONE_KEY]?.let { s ->
            try { JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } } catch (e: Exception) { null }
        } ?: emptyMap()
        val before = store.checkins
        val after = before.map { c ->
            when {
                // Yours, shared with them, done by them.
                !c.isLinked && c.isTask && !c.taskDone && l.id in c.sharedWith && c.id in done &&
                    (c.assignee.isEmpty() || c.assignee == l.id) ->
                    c.copy(enabled = false, doneAt = done[c.id], doneBy = l.peerName.ifEmpty { "them" })
                // Added from them: keep up with their side.
                c.linkId == l.id -> {
                    val src = theirs[c.sourceId]
                    if (src == null) {
                        c.copy(linkedGone = true)
                    } else {
                        var u = c.copy(name = src.name, description = src.description, linkedAssignee = src.linkedAssignee,
                            linkedGone = false, linkedDoneAt = src.doneAt)
                        // Done (or put back) on their side since last time: the same here.
                        if (c.isTask && src.doneAt != c.linkedDoneAt) {
                            u = if (src.doneAt != null) {
                                if (u.taskDone) u else u.copy(enabled = false, doneAt = src.doneAt, doneBy = src.doneBy ?: l.peerName)
                            } else if (u.taskDone) u.copy(enabled = true, doneAt = null, doneBy = null) else u
                        }
                        u
                    }
                }
                else -> c
            }
        }
        if (after == before) return null
        store.checkins = after
        after.zip(before).filter { (a, b) -> a != b }.forEach { (a, _) -> AlarmScheduler.schedule(context, a) }
        return after
    }

    /** Tasks of [l]'s you've done that they let you do: their id → when. */
    private fun doneForThem(l: Link, mine: List<Checkin>): Map<String, Long> =
        mine.filter { it.linkId == l.id && it.isTask && it.youCanDo && it.taskDone && it.doneBy == null }
            .associate { it.sourceId!! to it.doneAt!! }

    /**
     * Adds [item] (shared by [l]) to your own list: yours to set alarms, times and steps for;
     * its name and description stay theirs. Null if it's already there.
     */
    fun addToMyList(l: Link, item: Checkin): Checkin? {
        val store = Store(context)
        val key = "${l.id}/${item.id}"
        if (store.checkins.any { it.linkedFrom == key }) return null
        val c = item.copy(id = Store.newId(), sharedWith = emptySet(), assignee = "", followsUp = null,
            linkedFrom = key, linkedGone = false, linkedDoneAt = item.doneAt, backlogAt = null)
        store.checkins = store.checkins + c
        AlarmScheduler.schedule(context, c)
        return c
    }

    /** What you've added from [l] (their id → yours). */
    fun added(l: Link): Map<String, Checkin> =
        Store(context).checkins.filter { it.linkId == l.id }.associateBy { it.sourceId!! }

    /** What [linkId] shares with you: id → item JSON. */
    private fun received(linkId: String): Map<String, String> = prefs.getString(K_GOT + linkId, null)?.let { s ->
        try {
            val o = JSONObject(s)
            o.keys().asSequence().associateWith { o.getJSONObject(it).toString() }
        } catch (e: Exception) {
            null
        }
    } ?: emptyMap()

    /** What [link] shares with you, read-only, in their order. */
    fun shared(link: Link): List<Checkin> {
        val store = Store(context)
        return received(link.id).filterKeys { it != DONE_KEY }.values.mapNotNull {
            try { store.checkinFromJson(JSONObject(it)) } catch (e: Exception) { null }
        }
    }

    /**
     * An item as [l] sees it: without who else it's shared with, and who may do it as they see
     * it (anyone, them, you only, or someone else).
     */
    private fun shareJson(c: Checkin, l: Link): String {
        val who = when (c.assignee) {
            "" -> Store.LINK_ANYONE
            Store.ASSIGN_ME -> Store.LINK_OWNER
            l.id -> Store.LINK_YOU
            else -> Store.LINK_OTHER
        }
        return Store(context).checkinToJson(c.copy(sharedWith = emptySet(), assignee = "",
            linkedAssignee = if (c.isTask) who else "")).toString()
    }

    private fun toJson(l: Link) = JSONObject()
        .put("id", l.id).put("folder", l.folder).put("side", l.side)
        .put("publicKey", l.publicKey).put("privateKey", l.privateKey)
        .put("peer", l.peer ?: JSONObject.NULL).put("peerName", l.peerName).put("peerKey", l.peerKey ?: JSONObject.NULL)
        .put("shown", l.shown).put("sentSeq", l.sentSeq).put("gotSeq", l.gotSeq)
        .put("sent", JSONObject(l.sent.mapValues { JSONObject(it.value) }))

    private fun fromJson(o: JSONObject): Link {
        val sent = o.optJSONObject("sent")
        return Link(
            id = o.getString("id"), folder = o.getString("folder"), side = o.getString("side"),
            publicKey = o.getString("publicKey"), privateKey = o.getString("privateKey"),
            peer = o.optString("peer").takeIf { o.has("peer") && !o.isNull("peer") && it.isNotEmpty() },
            peerName = o.optString("peerName"),
            peerKey = o.optString("peerKey").takeIf { o.has("peerKey") && !o.isNull("peerKey") && it.isNotEmpty() },
            shown = o.optBoolean("shown", true), sentSeq = o.optInt("sentSeq"), gotSeq = o.optInt("gotSeq"),
            sent = sent?.keys()?.asSequence()?.associateWith { sent.getJSONObject(it).toString() } ?: emptyMap(),
        )
    }

    companion object {
        private const val PREFS = "gist-links"
        private const val K_LINKS = "links"
        private const val K_NAME = "myName"
        private const val K_GOT = "got."
        /** Among the items a side publishes: the tasks of the other side's it has done (id → when). */
        const val DONE_KEY = "~done"
        private val LOCK = Any()

        /** Runs [sync] off the main thread, then [then] on it when what's shared with you changed. */
        fun syncInBackground(activity: android.app.Activity, then: () -> Unit = {}) {
            val links = Connections(activity.applicationContext)
            if (links.all.isEmpty()) return
            Thread {
                val changed = synchronized(SYNC) { links.sync() }
                if (changed) activity.runOnUiThread { if (!activity.isFinishing) then() }
            }.start()
        }

        private val SYNC = Any()
    }
}
