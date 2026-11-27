package dev.gist.checkin

import android.content.Context

/**
 * A profile's folder in the Gist folder (/storage/emulated/0/Gist, [GistRoot]), holding every
 * folder the profile syncs through.
 *
 *     Gist/
 *       Personal - 7xq2/       one per profile ([folderName])
 *         My devices/          shared with your own devices
 *         People/
 *           Jess/              shared with Jess only, one folder per connection
 *       Work - k3f9/
 *
 * Nothing is picked but access to the Gist folder, once per device. The profile's folder
 * itself isn't shared; each folder in it is, on its own. Gist makes the folders when you set up
 * or invite, and finds them when you join (by the invite's code). A folder in it is reached as
 * "<Gist folder>#<profile>/People/Jess" (plain paths, in tests: "<root>/People/Jess").
 */
class GistFolder(
    private val context: Context,
    /** Opens a folder (tests use plain folders). */
    private val openFolder: (String) -> LinkFolder = { LinkFolder.open(context, it) },
) {
    private val prefs = Profiles.prefs(context, PREFS)

    /**
     * This profile's folder, or null until you've allowed access to the Gist folder. (Tests
     * set a plain folder instead.)
     */
    var root: String?
        get() = prefs.getString(K_TEST_ROOT, null) ?: GistRoot.tree(context)?.let { at(it, folderName()) }
        internal set(v) { prefs.edit().putString(K_TEST_ROOT, v).commit() }

    /**
     * This profile's folder name in the Gist folder: its name when the folder was first
     * needed, made safe for a folder name, and a short code from the profile's id ("John - k3f9"
     * for "John:"). The code keeps two profiles from ever sharing a folder (names that differ
     * only in characters a folder can't have, or in case). It isn't renamed with the profile,
     * since whatever shares the folders inside points at it.
     */
    fun folderName(): String {
        prefs.getString(K_FOLDER, null)?.let { return it }
        val id = Profiles.of(context)
        val base = safeName(Profiles.name(context, id)).ifEmpty { "Profile" }
        val others = Profiles.all(context).filter { it.id != id }.mapNotNull {
            Profiles.prefs(Profiles.scoped(context, it.id), PREFS).getString(K_FOLDER, null)?.lowercase()
        }.toSet()
        val name = sequenceOf(4, 6, 8, 12).map { "$base - ${code(id, it)}" }.firstOrNull { it.lowercase() !in others }
            ?: "$base - ${code(id, 16)}"
        prefs.edit().putString(K_FOLDER, name).commit()
        return name
    }

    /**
     * An invite scanned or typed whose folder hasn't arrived yet ("c:" or "d:" + its code):
     * joined as soon as Syncthing brings the folder.
     */
    var waiting: String?
        get() = prefs.getString(K_WAITING, null)
        set(v) { prefs.edit().putString(K_WAITING, v).commit() }

    /** Where folder [sub] of the Gist folder is reached. */
    fun location(sub: String): String? = root?.let { at(it, sub) }

    /**
     * Folder [sub]'s path on the phone's storage (/storage/emulated/0/Gist/<profile>/
     * [sub]), for programs that reach folders by path (Syncthing); null without a Gist folder.
     */
    fun path(sub: String): String? = root?.let { pathOf(at(it, sub)) }

    /** A folder in the Gist folder (as [at] gives it) by its path on the phone's storage. */
    fun pathOf(location: String): String =
        if (location.startsWith("content://")) GistRoot.pathOf(location) else location

    /** Where your devices sync. */
    fun devices(): String? = location(DEVICES)

    /** The folders under People: name → location. */
    fun people(): Map<String, String> {
        val r = root ?: return emptyMap()
        return try {
            openFolder(r).names(PEOPLE).filter { !it.startsWith(".") && valid(it) }.associateWith { at(r, "$PEOPLE/$it") }
        } catch (e: Exception) { emptyMap() }
    }

    /** A new folder under People for a connection with [person] (Jess, Jess 2…): its location. */
    fun newPerson(person: String): String? {
        val r = root ?: return null
        val base = safeName(person).ifEmpty { "Someone" }
        val taken = try { openFolder(r).names(PEOPLE).map { it.lowercase() }.toSet() } catch (e: Exception) { emptySet() }
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it.lowercase() !in taken }
        return at(r, "$PEOPLE/$name")
    }

    companion object {
        const val PREFS = "gist-folder"
        /** A plain folder used instead (tests). (Before, "root" was a picked folder.) */
        private const val K_TEST_ROOT = "testRoot"
        private const val K_FOLDER = "folderName"
        private const val K_WAITING = "waiting"
        const val DEVICES = "My devices"
        const val PEOPLE = "People"

        /** Folder [sub] (one or more names, "/" between) of the folder at [root]. */
        fun at(root: String, sub: String) =
            if (root.startsWith("content://") && '#' !in root) "$root#$sub" else "$root/$sub"

        /** [length] lowercase letters and digits from profile [id] (always the same for it). */
        fun code(id: String, length: Int): String {
            val alphabet = "abcdefghijkmnpqrstuvwxyz23456789" // no l, o, 0 or 1 to mix up
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest("gist-profile:$id".toByteArray())
            return (0 until length).map { alphabet[(hash[it].toInt() and 0xff) % alphabet.length] }.joinToString("")
        }

        private fun valid(name: String) = try { LinkPath.parts(name).size == 1 } catch (e: Exception) { false }

        /** A person's name as a folder name: letters, digits, spaces, dots, dashes, underscores. */
        fun safeName(name: String): String =
            name.map { if (it.isLetterOrDigit() && it.code < 128 || it in " ._-") it else ' ' }.joinToString("")
                .replace(Regex(" +"), " ").trim().trimStart('.', '_', '-', ' ').take(40).trim()
    }
}
