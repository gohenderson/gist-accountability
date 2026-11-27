package dev.gist.checkin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A connection with another copy of Gist (see LinkCore.kt for the folder and the encryption; Connections keeps them).
 * [side]: this copy's id in the folder; [peer]: the other side's, once it has joined.
 * [privateKey] stays on your devices (in Gist's own storage, never in the folder or backups).
 *
 * With your devices kept in step, each connection has one **home**: the device that reads
 * and publishes for it (two would clash). The others see it through device sync ([home] is
 * then that device's side, [homeName] its name) and can take it over ([homeAt]: when the
 * current home took it; the later wins).
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
    /**
     * An invite not yet used: its one-time token (base64) and until when it can be used. Only
     * a joiner that proves it has the token is let in. Null on older connections (which let in
     * whoever joined first) and once used.
     */
    val inviteToken: String? = null,
    val inviteExpires: Long = 0,
    /** Made with an invite (so a joiner must prove it has the token). */
    val needsProof: Boolean = false,
    /** The device that's its home, when it isn't this one (null: this one). */
    val home: String? = null,
    val homeName: String = "",
    val homeAt: Long = 0,
) {
    val connected: Boolean get() = peer != null && peerKey != null

    /** This device reads and publishes for it. */
    val isHere: Boolean get() = home == null

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
    private val prefs = Profiles.prefs(context, PREFS)

    /** Your name, as the other side sees it (the same on all your devices). */
    var myName: String
        get() = Store(context).linkName.ifEmpty { prefs.getString(K_NAME, "") ?: "" }
        set(v) {
            prefs.edit().putString(K_NAME, v.trim()).commit()
            Store(context).linkName = v.trim() // for your other devices
        }

    /** Every connection: those kept here, then those kept on your other devices. */
    val all: List<Link>
        get() = here.let { h -> h + remote().filter { r -> h.none { it.id == r.id } } }

    /** The connections this device is home for (it reads and publishes for them). */
    val here: List<Link>
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

    private fun update(id: String, change: (Link) -> Link) = synchronized(LOCK) { save(here.map { if (it.id == id) change(it) else it }) }

    fun setShown(id: String, shown: Boolean) {
        if (here.any { it.id == id }) return update(id) { it.copy(shown = shown) }
        val hidden = hiddenRemote()
        prefs.edit().putStringSet(K_HIDDEN, if (shown) hidden - id else hidden + id).commit()
    }

    private fun hiddenRemote(): Set<String> = prefs.getStringSet(K_HIDDEN, null)?.toSet() ?: emptySet()

    /**
     * Forgets connection [id] (and what it shared), and stops sharing with it; your other
     * devices forget it too. Only on its home. The folder is left as it is.
     */
    fun remove(id: String, now: Long = System.currentTimeMillis()) {
        synchronized(LOCK) {
            if (here.none { it.id == id }) return
            save(here.filter { it.id != id })
            prefs.edit().remove(K_GOT + id).putString(K_GONE, JSONObject((gone() + (id to now)) as Map<*, *>).toString()).commit()
        }
        val store = Store(context)
        store.checkins.filter { id in it.sharedWith }.forEach { c ->
            store.updateCheckin(c.id) { it.copy(sharedWith = it.sharedWith - id) }
        }
    }

    /**
     * Starts a connection in [folder] (empty, or not yet a connection), with an invite (a QR
     * code or text code, see [invitation]) to pass to the other person outside the folder.
     */
    fun invite(folder: String, now: Long = System.currentTimeMillis()): Link {
        val f = openFolder(folder)
        val (pub, priv) = LinkCrypto.newKeyPair()
        val link = Link(id = Store.newId() + Store.newId(), folder = folder, side = Store.newId(), publicKey = pub, privateKey = priv,
            inviteToken = java.util.Base64.getEncoder().encodeToString(LinkInvites.newToken()),
            inviteExpires = now + LinkInvites.VALID_MS, needsProof = true, homeAt = now)
        LinkProtocol.invite(f, link.id, LinkHello(link.side, myName, pub))
        synchronized(LOCK) { save(here + link) }
        return link
    }

    /** [l]'s invite to show (a new one if it ran out), or null once someone's joined. */
    fun invitation(l: Link, now: Long = System.currentTimeMillis()): Pair<LinkInvite, Long>? {
        if (l.connected || !l.isHere) return null
        var cur = get(l.id) ?: l
        if (cur.inviteToken == null || cur.inviteExpires < now) {
            val token = java.util.Base64.getEncoder().encodeToString(LinkInvites.newToken())
            update(l.id) { it.copy(inviteToken = token, inviteExpires = now + LinkInvites.VALID_MS, needsProof = true) }
            cur = get(l.id)!!
        }
        val token = java.util.Base64.getDecoder().decode(cur.inviteToken)
        return LinkInvite('c', LinkInvites.fingerprint(cur.publicKey), token) to cur.inviteExpires
    }

    /**
     * Of the folders at [locations] (say each under People in your Gist folder), the one
     * waiting to be joined with [invite]: a connection you're not in, started by the side
     * the invite names. Null when none is (yet: Syncthing may still be bringing it).
     */
    fun findInvited(locations: Collection<String>, invite: LinkInvite): String? = locations.firstOrNull { loc ->
        try {
            val f = openFolder(loc)
            val id = LinkProtocol.linkId(f)
            id != null && all.none { it.id == id } && LinkProtocol.sides(f, "").mapNotNull { LinkProtocol.hello(f, it) }
                .any { LinkInvites.fingerprint(it.key).contentEquals(invite.fingerprint) }
        } catch (e: Exception) {
            false
        }
    }

    /** Why a join didn't work. */
    enum class JoinProblem { NOT_A_CONNECTION, ALREADY_YOURS, NOT_THEIR_INVITE }

    /**
     * Joins the connection waiting in [folder] with [invite] (scanned or typed): only if the
     * inviting side's key there is the one the invite names. Proves to them it had the invite.
     */
    fun join(folder: String, invite: LinkInvite, now: Long = System.currentTimeMillis()): Pair<Link?, JoinProblem?> {
        val f = openFolder(folder)
        val id = LinkProtocol.linkId(f) ?: return null to JoinProblem.NOT_A_CONNECTION
        if (all.any { it.id == id }) return null to JoinProblem.ALREADY_YOURS
        val inviter = LinkProtocol.sides(f, "").mapNotNull { LinkProtocol.hello(f, it) }
            .firstOrNull { LinkInvites.fingerprint(it.key).contentEquals(invite.fingerprint) }
            ?: return null to JoinProblem.NOT_THEIR_INVITE
        val (pub, priv) = LinkCrypto.newKeyPair()
        val side = Store.newId()
        val link = Link(id = id, folder = folder, side = side, publicKey = pub, privateKey = priv,
            peer = inviter.side, peerName = inviter.name, peerKey = inviter.key, homeAt = now)
        LinkProtocol.writeHello(f, LinkHello(side, myName, pub, LinkInvites.proof(invite.token, id, side, pub, now), now))
        synchronized(LOCK) { save(here + link) }
        return link to null
    }

    /**
     * Brings every connection up to date: notices who joined, publishes what you share (only
     * what changed), and reads what they share. True when what they share changed.
     */
    fun sync(): Boolean {
        val items = Store(context).checkins
        var changed = false
        for (l in here) {
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
        if (!l.connected) {
            // Someone joined: with an invite, only the one that proves it had the token.
            val token = l.inviteToken?.let { java.util.Base64.getDecoder().decode(it) }
            val h = LinkProtocol.sides(f, l.side).mapNotNull { LinkProtocol.hello(f, it) }.firstOrNull { h ->
                if (l.needsProof) token != null && LinkInvites.proves(h, token, l.id, l.inviteExpires) else true
            }
            if (h != null) {
                l = l.copy(peer = h.side, peerName = h.name, peerKey = h.key, inviteToken = null)
                update(l.id) { it.copy(peer = h.side, peerName = h.name, peerKey = h.key, inviteToken = null) }
            }
        } else {
            // Their name changed (their key never does).
            LinkProtocol.hello(f, l.peer!!)?.takeIf { it.key == l.peerKey && it.name != l.peerName }?.let { h ->
                l = l.copy(peerName = h.name)
                update(l.id) { it.copy(peerName = h.name) }
            }
        }
        // Your name changed: say so.
        LinkProtocol.rename(f, l.side, myName, l.publicKey)
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

    // ------------------------------------------------------------ on your other devices

    private fun gone(): Map<String, Long> = prefs.getString(K_GONE, null)?.let { s ->
        try { JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } } catch (e: Exception) { null }
    } ?: emptyMap()

    private fun remoteItems(): Map<String, JSONObject> = prefs.getString(K_REMOTE, null)?.let { s ->
        try { JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getJSONObject(it) } } } catch (e: Exception) { null }
    } ?: emptyMap()

    private fun saveRemote(m: Map<String, JSONObject>) {
        prefs.edit().putString(K_REMOTE, JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }.toString()).commit()
    }

    /** The connections kept on your other devices. */
    private fun remote(): List<Link> {
        val hidden = hiddenRemote()
        return remoteItems().values.mapNotNull { o ->
            try {
                fromJson(o.getJSONObject("link")).copy(folder = "", sent = emptyMap(), home = o.getString("home"),
                    homeName = o.optString("homeName"), homeAt = o.optLong("at"), shown = o.getJSONObject("link").getString("id") !in hidden)
            } catch (e: Exception) { null }
        }
    }

    /**
     * For your other devices (as device [side], named [deviceName]): each connection kept
     * here, "l:<id>" (its keys, who it's with, and what they share), and each one removed.
     */
    fun deviceItems(side: String, deviceName: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (l in here) {
            out["l:${l.id}"] = JSONObject().put("at", l.homeAt).put("home", side).put("homeName", deviceName)
                .put("link", toJson(l.copy(folder = "", sent = emptyMap())))
                .put("shared", JSONObject().apply { received(l.id).forEach { (k, v) -> put(k, JSONObject(v)) } })
                .toString()
        }
        gone().forEach { (id, at) -> out["l:$id"] = JSONObject().put("gone", true).put("at", at).toString() }
        return out
    }

    /**
     * Takes in your other devices' connections ([items]; this device is [side]): ones they
     * keep, one taken over from here (this device stops reading and publishing for it), and
     * ones removed. True when anything changed.
     */
    fun takeDeviceItems(items: Map<String, String>, side: String): Boolean {
        var changed = false
        synchronized(LOCK) {
            val remote = remoteItems().toMutableMap()
            items.filterKeys { it.startsWith("l:") }.forEach { (k, text) ->
                val id = k.removePrefix("l:")
                val o = try { JSONObject(text) } catch (e: Exception) { return@forEach }
                val at = o.optLong("at", 0)
                val mine = here.firstOrNull { it.id == id }
                if (o.optBoolean("gone")) {
                    if (remote[id]?.let { it.optLong("at") <= at } == true) {
                        remote.remove(id)
                        prefs.edit().remove(K_GOT + id).commit()
                        changed = true
                    }
                    return@forEach
                }
                if (o.optString("home") == side || !o.has("link") || (gone()[id] ?: -1) >= at) return@forEach
                if (mine != null) {
                    if (at <= mine.homeAt) return@forEach
                    // Taken over by another of your devices: it reads and publishes now.
                    save(here.filter { it.id != id })
                }
                val cur = remote[id]
                if (cur != null && cur.optLong("at") > at) return@forEach
                if (cur?.toString() == o.toString()) return@forEach
                remote[id] = o
                val shared = o.optJSONObject("shared") ?: JSONObject()
                prefs.edit().putString(K_GOT + id, shared.toString()).commit()
                changed = true
            }
            if (changed) saveRemote(remote)
        }
        return changed
    }

    /** Why taking a connection over didn't work. */
    enum class AdoptProblem { NOT_KEPT_ELSEWHERE, NOT_ITS_FOLDER }

    /**
     * Makes this device the home of connection [id] (kept on another of your devices), using
     * its [folder] as reached from here: carries on from what was last published there.
     */
    fun adopt(id: String, folder: String, now: Long = System.currentTimeMillis()): AdoptProblem? {
        val r = remote().firstOrNull { it.id == id } ?: return AdoptProblem.NOT_KEPT_ELSEWHERE
        val f = openFolder(folder)
        if (LinkProtocol.linkId(f) != id) return AdoptProblem.NOT_ITS_FOLDER
        // Carry on from this side's last change in the folder (and what it held).
        val (seq, sent) = r.key()?.let { LinkProtocol.receive(f, id, r.side, it, 0, emptyMap()) } ?: (0 to emptyMap())
        val link = r.copy(folder = folder, sentSeq = seq, sent = sent, home = null, homeName = "", homeAt = now,
            shown = r.shown)
        synchronized(LOCK) {
            save(here.filter { it.id != id } + link)
            saveRemote(remoteItems() - id)
            prefs.edit().putStringSet(K_HIDDEN, hiddenRemote() - id).commit()
        }
        return null
    }

    private fun toJson(l: Link) = JSONObject()
        .put("id", l.id).put("folder", l.folder).put("side", l.side)
        .put("publicKey", l.publicKey).put("privateKey", l.privateKey)
        .put("peer", l.peer ?: JSONObject.NULL).put("peerName", l.peerName).put("peerKey", l.peerKey ?: JSONObject.NULL)
        .put("shown", l.shown).put("sentSeq", l.sentSeq).put("gotSeq", l.gotSeq)
        .put("sent", JSONObject(l.sent.mapValues { JSONObject(it.value) }))
        .put("inviteToken", l.inviteToken ?: JSONObject.NULL).put("inviteExpires", l.inviteExpires).put("needsProof", l.needsProof)
        .put("homeAt", l.homeAt)

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
            inviteToken = o.optString("inviteToken").takeIf { o.has("inviteToken") && !o.isNull("inviteToken") && it.isNotEmpty() },
            inviteExpires = o.optLong("inviteExpires"), needsProof = o.optBoolean("needsProof", false),
            homeAt = o.optLong("homeAt"),
        )
    }

    companion object {
        const val PREFS = "gist-links"
        private const val K_LINKS = "links"
        private const val K_NAME = "myName"
        private const val K_GOT = "got."
        private const val K_REMOTE = "remote"
        private const val K_HIDDEN = "hiddenRemote"
        private const val K_GONE = "gone"
        /** Among the items a side publishes: the tasks of the other side's it has done (id → when). */
        const val DONE_KEY = "~done"
        private val LOCK = Any()

        /** Runs [sync] now, on this thread (the background service). True when what's shared with you changed. */
        fun syncNow(context: android.content.Context): Boolean {
            val links = Connections(context)
            if (links.here.isEmpty()) return false
            return try { synchronized(SYNC) { links.sync() } } catch (e: Exception) { false }
        }

        /** Runs [sync] off the main thread, then [then] on it when what's shared with you changed. */
        fun syncInBackground(activity: android.app.Activity, then: () -> Unit = {}) {
            val links = Connections(Profiles.scoped(activity, Profiles.of(activity)))
            if (links.here.isEmpty()) return
            Thread {
                // A folder it can't reach (deleted, access gone) mustn't take the app down.
                val changed = try { synchronized(SYNC) { links.sync() } } catch (e: Exception) { false }
                if (changed) activity.runOnUiThread { if (!activity.isFinishing) then() }
            }.start()
        }

        private val SYNC = Any()
    }
}
