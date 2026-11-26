package dev.gist.checkin

import android.content.Context

/**
 * Your Gist folder: picked once on each device, it holds every folder Gist syncs through.
 *
 *     Gist/
 *       My devices/     shared (in Syncthing) with your own devices
 *       People/
 *         Jess/         shared with Jess only, one folder per connection
 *
 * Gist folder itself isn't shared; each folder in it is shared on its own. Gist makes the
 * folders when you set up or invite, and finds them when you join (by the invite's code), so
 * there's nothing else to pick. A folder in it is reached as "<Gist folder>#People/Jess"
 * (plain paths, in tests: "<Gist folder>/People/Jess").
 */
class GistFolder(
    private val context: Context,
    /** Opens a folder (tests use plain folders). */
    private val openFolder: (String) -> LinkFolder = { LinkFolder.open(context, it) },
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The Gist folder on this device, or null until picked. */
    var root: String?
        get() = prefs.getString(K_ROOT, null)
        set(v) { prefs.edit().putString(K_ROOT, v).commit() }

    /**
     * An invite scanned or typed whose folder hasn't arrived yet ("c:" or "d:" + its code):
     * joined as soon as Syncthing brings the folder.
     */
    var waiting: String?
        get() = prefs.getString(K_WAITING, null)
        set(v) { prefs.edit().putString(K_WAITING, v).commit() }

    /** Where folder [sub] of the Gist folder is reached. */
    fun location(sub: String): String? = root?.let { at(it, sub) }

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
        private const val PREFS = "gist-folder"
        private const val K_ROOT = "root"
        private const val K_WAITING = "waiting"
        const val DEVICES = "My devices"
        const val PEOPLE = "People"

        fun at(root: String, sub: String) = if (root.startsWith("content://")) "$root#$sub" else "$root/$sub"

        private fun valid(name: String) = try { LinkPath.parts(name).size == 1 } catch (e: Exception) { false }

        /** A person's name as a folder name: letters, digits, spaces, dots, dashes, underscores. */
        fun safeName(name: String): String =
            name.map { if (it.isLetterOrDigit() && it.code < 128 || it in " ._-") it else ' ' }.joinToString("")
                .replace(Regex(" +"), " ").trim().trimStart('.', '_', '-', ' ').take(40).trim()
    }
}
