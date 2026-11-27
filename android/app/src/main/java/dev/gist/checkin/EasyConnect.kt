package dev.gist.checkin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Easy Connect: your devices in step with nothing to set up but a QR code. Gist runs Syncthing
 * itself ([SyncthingEngine], [SyncthingService]) and has it share My devices between them.
 *
 * - **Show the code** (a device with My devices): Gist shares My devices in its Syncthing and
 *   shows a QR code: a devices invite (the same one Add a device shows), its Syncthing device
 *   ID and the folder's Syncthing id ([Code]).
 * - **Scan it** (the new device): Gist names its Syncthing after the invite ([tag]), adds the
 *   first device and the folder, and waits for My devices to arrive, then joins with the
 *   invite as usual (GistFolder.waiting).
 * - The first device accepts a device asking to connect only while its invite is open, and
 *   only one going by that invite's tag (which only the code reveals). Gist's own join proof
 *   and encryption still apply on top: a device is let in only once it proves it had the
 *   invite, and everything in My devices is encrypted for the group.
 *
 * My devices stays where it was (PublicAppData/Gist/<profile>/My devices), so anything else
 * can still share it as well. Syncthing reaches it by its path, which needs All files access
 * ([canReachFiles]).
 */
object EasyConnect {
    private const val PREFS = "gist-easy"
    private const val K_TAG = "offerTag"
    private const val K_FOLDER = "offerFolder"
    private const val K_UNTIL = "offerUntil"
    private const val K_ON = "easyConnect"

    /** What the QR code holds: "GISTEC1:<invite code>:<Syncthing device ID>:<folder id>". */
    data class Code(val invite: LinkInvite, val deviceId: String, val folderId: String) {
        val qr: String get() = "$PREFIX${invite.code.replace("-", "")}:${deviceId.replace("-", "")}:$folderId"

        companion object {
            const val PREFIX = "GISTEC1:"
            private val FOLDER = Regex("[A-Za-z0-9-]{1,64}")

            /** A scanned Easy Connect code (for your devices), or null if it isn't one. */
            fun parse(text: String): Code? {
                val t = text.trim()
                if (!t.startsWith(PREFIX, ignoreCase = true)) return null
                val parts = t.substring(PREFIX.length).split(':')
                if (parts.size != 3) return null
                val invite = LinkInvite.parse(parts[0])?.takeIf { it.kind == 'd' } ?: return null
                val id = deviceId(parts[1]) ?: return null
                val folder = parts[2].takeIf { FOLDER.matches(it) } ?: return null
                return Code(invite, id, folder)
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

    /** Whether Syncthing can reach your folders by their path (Android's All files access). */
    fun canReachFiles(): Boolean = Environment.isExternalStorageManager()

    /** Android's page for allowing Gist All files access. */
    fun filesAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    /** Whether Easy Connect is on (Syncthing runs in the background). */
    fun isOn(context: Context): Boolean = Db.get(context).state(K_ON) == "1"

    fun setOn(context: Context, on: Boolean) {
        Db.get(context).setState(K_ON, if (on) "1" else null)
        if (on) SyncthingService.start(context) else SyncthingService.stop(context)
    }

    /** The label Syncthing shows for My devices of [context]'s profile. */
    private fun label(context: Context) = "Gist · My devices (${Profiles.name(context, Profiles.of(context))})"

    /**
     * The code for another device to scan (this one has My devices, ready): shares My devices in
     * Syncthing, and remembers the invite's tag to accept it. Blocks (starts Syncthing).
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
        Profiles.prefs(context, PREFS).edit()
            .putString(K_TAG, tag(invite.token)).putString(K_FOLDER, folder).putLong(K_UNTIL, until).commit()
        setOn(context, true)
        return Code(invite, engine.deviceId(), folder) to until
    }

    /** The scanning side: sets up Syncthing for [code], then waits for My devices. Blocks (starts Syncthing). */
    fun join(context: Context, code: Code) {
        val path = GistFolder(context).path(GistFolder.DEVICES) ?: throw IllegalStateException("No Gist folder")
        val engine = SyncthingEngine.shared(context)
        engine.start()
        joinFolder(engine, code.deviceId, code.folderId, label(context), path, tag(code.invite.token))
        GistFolder(context).waiting = "d:${code.invite.code}"
        setOn(context, true)
    }

    /**
     * What the background service does every few seconds, for each profile: accept the device
     * an open code was shown to, and keep your devices in step.
     */
    fun tick(context: Context, engine: SyncthingEngine) {
        for (p in Profiles.contexts(context)) {
            val prefs = Profiles.prefs(p, PREFS)
            val tag = prefs.getString(K_TAG, null)
            val folder = prefs.getString(K_FOLDER, null)
            if (tag != null && folder != null) {
                if (prefs.getLong(K_UNTIL, 0) < System.currentTimeMillis()) {
                    prefs.edit().remove(K_TAG).remove(K_FOLDER).remove(K_UNTIL).commit()
                } else {
                    runCatching { acceptPending(engine, tag, folder) }
                }
            }
            // Scanned a code: join as soon as My devices has arrived.
            val gf = GistFolder(p)
            val waiting = gf.waiting
            if (waiting != null && waiting.startsWith("d:") && Devices(p).folder == null) {
                val invite = LinkInvite.parse(waiting.substring(2))
                val loc = gf.devices()
                if (invite != null && loc != null && runCatching { Devices(p).join(loc, invite) == null }.getOrDefault(false)) {
                    gf.waiting = null
                }
            }
            runCatching { Devices.syncNow(p) }
        }
    }

    /** What the status line says: devices Syncthing is connected to. */
    fun status(context: Context): String? {
        val engine = SyncthingEngine.shared(context)
        if (!engine.running) return null
        return runCatching {
            val n = engine.connected().size
            if (n == 0) "Easy Connect is on: not connected to another device right now"
            else "Easy Connect is on: connected to $n other device${if (n == 1) "" else "s"}"
        }.getOrNull()
    }
}
