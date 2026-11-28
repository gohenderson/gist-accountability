package dev.gist.checkin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/**
 * The Gist folder: /storage/emulated/0/Gist, at the top of the phone's storage, where the app
 * you share folders with can reach it too. Any case will do (gist, GIST): an existing one is
 * used, or Gist makes "Gist". Each profile has its folder in it ([GistFolder]).
 *
 * Gist reaches it by path, as Syncthing (Easy Connect) and other apps do, which takes Android's
 * All files access (Android 10, which doesn't have it: the storage permission, with legacy
 * storage). Asked once, for the whole device and every profile. On GrapheneOS, Storage Scopes
 * can narrow it to the Gist folder.
 */
object GistRoot {
    const val NAME = "Gist"
    private const val STORAGE = "/storage/emulated/0"
    const val PATH = "$STORAGE/$NAME"

    private const val AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY = "primary:"

    /** Where the phone's storage is (/storage/emulated/0); tests can point it elsewhere. */
    internal var storage: File = File(STORAGE)

    /**
     * Tests say whether there's access (changing All files access restarts the app, so a test
     * can't turn it on and off).
     */
    internal var accessForTests: Boolean? = null

    /** Whether Gist may reach the Gist folder (All files access, or Android 10's storage permission). */
    fun canReach(context: Context): Boolean =
        accessForTests ?: if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** The storage permissions Android 10 asks for in place of All files access. */
    val LEGACY_STORAGE = arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)

    /** Android's page for allowing Gist All files access (Android 11+). */
    fun accessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }

    /** The Gist folder there already, in whatever case ("gist"), or null. */
    private fun existing(): File? =
        storage.listFiles()?.filter { it.isDirectory && it.name.equals(NAME, ignoreCase = true) }
            ?.minByOrNull { if (it.name == NAME) 0 else 1 }
            ?: File(storage, NAME).takeIf { it.isDirectory }

    /**
     * The Gist folder, made if it isn't there; null without access, or if it couldn't be made
     * (then [problem] says why).
     */
    fun dir(context: Context): File? {
        if (!canReach(context)) return null
        existing()?.let { return it }
        val made = File(storage, NAME)
        return made.takeIf { it.mkdir() || it.isDirectory }
    }

    /** Why there's no Gist folder, for the Connections page; null if there is one. */
    fun problem(context: Context): String? = when {
        !canReach(context) -> null
        dir(context) == null -> "Gist couldn't make the $NAME folder at the top of your storage. Make it yourself " +
            "(any file manager, any case), or if you limit Gist's files (Storage Scopes on GrapheneOS), add it there."
        else -> null
    }

    /**
     * A folder in the Gist folder as it was reached before (a picked folder's "<tree>#<sub>",
     * from when Gist used the folder picker) by its path, or null if it's another kind of
     * location.
     */
    fun pathOfTree(location: String): String? = try {
        val tree = Uri.parse(location.substringBefore('#'))
        val id = DocumentsContract.getTreeDocumentId(tree)
        val name = id.removePrefix(PRIMARY)
        if (tree.authority == AUTHORITY && id.startsWith(PRIMARY) && name.equals(NAME, ignoreCase = true)) {
            val sub = location.substringAfter('#', "")
            "${storage.path}/$name" + (if (sub.isEmpty()) "" else "/$sub")
        } else null
    } catch (e: Exception) {
        null
    }
}
