package dev.gist.checkin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Easy Connect: your devices, and the people you share with, connected with nothing to set up
 * but a QR code. Gist runs Syncthing itself ([SyncthingEngine], [SyncthingService]) and has it
 * share My devices between your devices, and a connection's folder (People/Jess) between you
 * and that person.
 *
 * - **Show the code**: for your devices (this one has My devices), Gist shares My devices in
 *   its Syncthing; for a person, it makes their folder (People/<name>) and a connection invite,
 *   as Invite does, and shares that. The QR code holds the invite, this device's Syncthing ID,
 *   the folder's Syncthing id, and for a person, your name ([Code]).
 * - **Scan it** (the other device): Gist names its Syncthing after the invite ([tag]), adds the
 *   first device and the folder (for a person: People/<their name>), and waits for the folder
 *   to arrive, then joins with the invite as usual (GistFolder.waiting, [finishJoins]).
 * - The first device accepts a device asking to connect only while its invite is open, and
 *   only one going by that invite's tag (which only the code reveals), and shares only that
 *   folder with it: a person never gets My devices. Gist's own join proof
 *   and encryption still apply on top: a device is let in only once it proves it had the
 *   invite, and everything in My devices is encrypted for the group.
 *
 * My devices stays where it was (Gist/<profile>/My devices), so anything else
 * can still share it as well. Syncthing reaches it by its path, which needs All files access
 * ([GistRoot], which Gist needs too).
 */
object EasyConnect {
    private const val PREFS = "gist-easy"
    /** Codes shown and still open: [{"tag", "folder", "until"}]. */
    private const val K_OFFERS = "offers"
    /** Folder ids Easy Connect set up (a set). */
    private const val K_AUTO = "auto"
    private const val K_ON = "easyConnect"

    /**
     * What the QR code holds: "GISTEC1:<invite code>:<Syncthing device ID>:<folder id>", and for a
     * person ([LinkInvite.kind] 'c') ":<your name>" (base64url), to name their folder for you.
     */
    data class Code(val invite: LinkInvite, val deviceId: String, val folderId: String, val name: String? = null) {
        val qr: String get() = "$PREFIX${invite.code.replace("-", "")}:${deviceId.replace("-", "")}:$folderId" +
            (name?.let { ":" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray()) } ?: "")

        companion object {
            const val PREFIX = "GISTEC1:"
            private val FOLDER = Regex("[A-Za-z0-9-]{1,64}")

            /** A scanned Easy Connect code (for your devices or a person), or null if it isn't one. */
            fun parse(text: String): Code? {
                val t = text.trim()
                if (!t.startsWith(PREFIX, ignoreCase = true)) return null
                val parts = t.substring(PREFIX.length).split(':')
                if (parts.size !in 3..4) return null
                val invite = LinkInvite.parse(parts[0]) ?: return null
                val id = deviceId(parts[1]) ?: return null
                val folder = parts[2].takeIf { FOLDER.matches(it) } ?: return null
                val name = parts.getOrNull(3)?.let {
                    runCatching { String(java.util.Base64.getUrlDecoder().decode(it)) }.getOrNull()?.trim()?.take(64) ?: return null
                }
                return Code(invite, id, folder, name?.ifEmpty { null })
            }

            /** A Syncthing device ID in its usual form (8 groups of 7), from any spacing or case; null if it isn't one. */
            fun deviceId(raw: String): String? {
                val s = raw.uppercase().filter { it.isLetterOrDigit() }
                if (s.length != 56 || s.any { it !in 'A'..'Z' && it !in '2'..'7' }) return null
                return s.chunked(7).joinToString("-")
            }
        }
    }

    /**
     * The name a device scanning the invite with [token] gives its Syncthing, so the device that
     * showed it can tell it from anyone else asking to connect.
     */
    fun tag(token: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token, "HmacSHA256"))
        return "gist-ec-" + LinkInvites.base32(mac.doFinal("gist-easy-connect".toByteArray())).take(12).lowercase()
    }

    /** The Syncthing folder id for My devices of group [groupId]. */
    fun folderId(groupId: String): String = "gist-devices-${groupId.filter { it.isLetterOrDigit() }.take(40)}"

    /** The Syncthing folder id for connection [linkId]'s folder. */
    fun linkFolderId(linkId: String): String = "gist-link-${linkId.filter { it.isLetterOrDigit() }.take(40)}"

    // ---------------------------------------------------------------- Syncthing side

    /** Shares folder [folderId] at [path] in [engine] (with nobody yet), for a code to be shown. */
    fun offerFolder(engine: SyncthingEngine, folderId: String, label: String, path: String) {
        File(path).mkdirs()
        engine.shareFolder(folderId, label, path)
    }

    /**
     * Accepts devices waiting to connect that go by [tag], and shares [folderId] with them.
     * Anyone else is left waiting. The ids accepted.
     */
    fun acceptPending(engine: SyncthingEngine, tag: String, folderId: String): List<String> {
        if (engine.folder(folderId) == null) return emptyList()
        val ids = engine.pendingDevices().filterValues { it == tag }.keys.toList()
        ids.forEach { engine.addDevice(it, tag) }
        if (ids.isNotEmpty()) engine.shareFolder(folderId, "", "", ids)
        return ids
    }

    /**
     * The scanning side: names this Syncthing [tag], adds the device that showed the code
     * ([peerId], at [addresses]) and folder [folderId] at [path], shared with it.
     */
    fun joinFolder(
        engine: SyncthingEngine, peerId: String, folderId: String, label: String, path: String, tag: String,
        addresses: List<String> = listOf("dynamic"),
    ) {
        File(path).mkdirs()
        engine.setName(tag)
        engine.addDevice(peerId, "Gist", addresses)
        engine.shareFolder(folderId, label, path, listOf(peerId))
    }

    // ---------------------------------------------------------------- Gist side

    /** Whether Easy Connect is on (Syncthing runs in the background). */
    fun isOn(context: Context): Boolean = Db.get(context).state(K_ON) == "1"

    fun setOn(context: Context, on: Boolean) {
        Db.get(context).setState(K_ON, if (on) "1" else null)
        if (on) SyncthingService.start(context) else SyncthingService.stop(context)
    }

    /**
     * The Syncthing folders Easy Connect set up (My devices' and connections'), so the
     * Connections page can say a connection is automatic. In [context]'s profile.
     */
    fun isAuto(context: Context, folderId: String): Boolean =
        Profiles.prefs(context, PREFS).getStringSet(K_AUTO, emptySet())!!.contains(folderId)

    private fun markAuto(context: Context, folderId: String, on: Boolean) {
        val prefs = Profiles.prefs(context, PREFS)
        val set = prefs.getStringSet(K_AUTO, emptySet())!!.toMutableSet()
        if (if (on) set.add(folderId) else set.remove(folderId)) prefs.edit().putStringSet(K_AUTO, set).commit()
    }

    /**
     * Withdraws the code shown for [folderId] at once: nobody can join with it any more. For a
     * person, the connection waiting for them goes too.
     */
    fun cancelOffer(context: Context, folderId: String) {
        val prefs = Profiles.prefs(context, PREFS)
        val open = openOffers(context).filter { it.optString("folder") != folderId }
        prefs.edit().putString(K_OFFERS, JSONArray(open).toString()).commit()
    }

    /** The label Syncthing shows for My devices of [context]'s profile. */
    private fun label(context: Context) = "Gist · My devices (${Profiles.name(context, Profiles.of(context))})"

    /** A code [tag] for [folder] stays open until [until] (several can be: your devices, people). */
    private fun addOffer(context: Context, tag: String, folder: String, until: Long) {
        val prefs = Profiles.prefs(context, PREFS)
        val now = System.currentTimeMillis()
        val list = openOffers(context, now).filter { it.optString("folder") != folder } +
            JSONObject().put("tag", tag).put("folder", folder).put("until", until)
        prefs.edit().putString(K_OFFERS, JSONArray(list).toString()).commit()
    }

    /** The codes shown and still open (the rest are dropped). */
    private fun openOffers(context: Context, now: Long = System.currentTimeMillis()): List<JSONObject> {
        val prefs = Profiles.prefs(context, PREFS)
        val all = runCatching { JSONArray(prefs.getString(K_OFFERS, "[]")) }.getOrDefault(JSONArray())
        val open = (0 until all.length()).map { all.getJSONObject(it) }.filter { it.optLong("until") > now }
        if (open.size != all.length()) prefs.edit().putString(K_OFFERS, JSONArray(open).toString()).commit()
        return open
    }

    /**
     * The code for another of your devices to scan (this one has My devices, ready): shares My
     * devices in Syncthing, and remembers the invite's tag to accept it. Blocks (starts Syncthing).
     */
    fun offer(context: Context): Pair<Code, Long> {
        val devices = Devices(context)
        val (invite, until) = devices.invitation() ?: throw IllegalStateException("Set up your devices first")
        val group = devices.group ?: throw IllegalStateException("Set up your devices first")
        val path = GistFolder(context).path(GistFolder.DEVICES) ?: throw IllegalStateException("No Gist folder")
        val engine = SyncthingEngine.shared(context)
        engine.start()
        val folder = folderId(group)
        offerFolder(engine, folder, label(context), path)
        addOffer(context, tag(invite.token), folder, until)
        markAuto(context, folder, true)
        setOn(context, true)
        return Code(invite, engine.deviceId(), folder) to until
    }

    /**
     * The code for [person] to scan: makes their folder (People/<person>) and a connection
     * invite, as Invite does, shares the folder in Syncthing, and remembers the invite's tag.
     * Blocks (starts Syncthing).
     */
    fun offerPerson(context: Context, person: String): Pair<Code, Long> {
        val gf = GistFolder(context)
        val loc = gf.newPerson(person) ?: throw IllegalStateException("No Gist folder")
        val links = Connections(context)
        val link = links.invite(loc)
        val (invite, until) = links.invitation(link) ?: throw IllegalStateException("No invite")
        val engine = SyncthingEngine.shared(context)
        engine.start()
        val folder = linkFolderId(link.id)
        offerFolder(engine, folder, "Gist · ${loc.substringAfterLast('/')}", gf.pathOf(loc))
        addOffer(context, tag(invite.token), folder, until)
        markAuto(context, folder, true)
        setOn(context, true)
        return Code(invite, engine.deviceId(), folder, links.myName.trim().ifEmpty { null }) to until
    }

    /**
     * The scanning side: sets up Syncthing for [code] (for a person, making People/<their
     * name>), then waits for the folder to arrive ([finishJoins]). Blocks (starts Syncthing).
     */
    fun join(context: Context, code: Code) {
        val (path, label) = prepareJoin(context, code)
        val engine = SyncthingEngine.shared(context)
        engine.start()
        joinFolder(engine, code.deviceId, code.folderId, label, path, tag(code.invite.token))
        markAuto(context, code.folderId, true)
        setOn(context, true)
    }

    /**
     * Gist's part of joining with [code]: where the folder goes (My devices, or People/<their
     * name> for a person), and waiting for it. Its path and Syncthing label.
     */
    fun prepareJoin(context: Context, code: Code, open: (String) -> LinkFolder = { LinkFolder.open(context, it) }): Pair<String, String> {
        val gf = GistFolder(context, open)
        val result = if (code.invite.kind == 'd') {
            (gf.path(GistFolder.DEVICES) ?: throw IllegalStateException("No Gist folder")) to label(context)
        } else {
            val loc = gf.newPerson(code.name ?: "Someone") ?: throw IllegalStateException("No Gist folder")
            gf.pathOf(loc) to "Gist · ${loc.substringAfterLast('/')}"
        }
        gf.waiting = "${code.invite.kind}:${code.invite.code}"
        return result
    }

    /**
     * A code scanned here: join with it as soon as its folder has arrived (My devices, or the
     * person's folder in People). True once joined (or it's no longer waiting).
     */
    fun finishJoins(context: Context, open: (String) -> LinkFolder = { LinkFolder.open(context, it) }): Boolean {
        val gf = GistFolder(context, open)
        val waiting = gf.waiting ?: return true
        val invite = LinkInvite.parse(waiting.substring(2)) ?: run { gf.waiting = null; return true }
        val joined = runCatching {
            if (invite.kind == 'd') {
                if (Devices(context, open).folder != null) return@runCatching true
                val loc = gf.devices() ?: return@runCatching false
                Devices(context, open).join(loc, invite) == null
            } else {
                val links = Connections(context, open)
                val loc = links.findInvited(gf.people().values, invite) ?: return@runCatching false
                links.join(loc, invite).second == null
            }
        }.getOrDefault(false)
        if (joined) gf.waiting = null
        return joined
    }

    /**
     * What the background service does every few seconds, for each profile: accept the devices
     * open codes were shown to, finish a join waiting for its folder, and keep your devices and
     * connections in step.
     */
    fun tick(context: Context, engine: SyncthingEngine) {
        for (p in Profiles.contexts(context)) {
            for (o in openOffers(p)) runCatching { acceptPending(engine, o.getString("tag"), o.getString("folder")) }
            finishJoins(p)
            runCatching { Devices.syncNow(p) }
            runCatching { Connections.syncNow(p) }
        }
    }

    /**
     * Stops sharing [folderId] through Syncthing (a connection removed, this device leaving your
     * devices), and forgets devices it no longer shares anything with. Its files stay.
     */
    fun unshare(context: Context, folderId: String) {
        markAuto(context, folderId, false)
        val engine = SyncthingEngine.shared(context)
        if (!engine.available || !isOn(context)) return
        runCatching {
            engine.start()
            val before = engine.folderDevices()
            val theirs = before[folderId] ?: return
            engine.removeFolder(folderId)
            val still = before.filterKeys { it != folderId }.values.flatten().toSet()
            val me = engine.deviceId()
            theirs.filter { it != me && it !in still }.forEach { engine.removeDevice(it) }
        }
    }

    /** What the status line says: devices Syncthing is connected to. */
    fun status(context: Context): String? {
        val engine = SyncthingEngine.shared(context)
        if (!engine.running) return null
        return runCatching {
            val n = engine.connected().size
            if (n == 0) "Automatic connections are on: not connected to another device right now"
            else "Automatic connections are on: connected to $n other device${if (n == 1) "" else "s"}"
        }.getOrNull()
    }
}
