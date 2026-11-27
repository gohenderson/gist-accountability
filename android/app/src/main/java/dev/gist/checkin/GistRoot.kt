package dev.gist.checkin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * The Gist folder: /storage/emulated/0/Gist, at the top of the phone's storage, where the app
 * you share folders with can reach it too. Any case will do (gist, GIST); no other folder is
 * accepted. Each profile has its folder in it ([GistFolder]).
 *
 * Android doesn't let an app make a folder at the top of your storage, or reach one without you
 * saying so: you allow access to it once, in Android's folder picker (where you can also make it
 * the first time). Access is for the whole device, every profile; Gist makes everything inside.
 */
object GistRoot {
    const val NAME = "Gist"
    private const val STORAGE = "/storage/emulated/0"
    const val PATH = "$STORAGE/$NAME"

    private const val AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY = "primary:"
    private const val K_TREE = "gistRoot"

    /** Android's folder picker, opened at the Gist folder (at the top of your storage if it isn't there yet). */
    fun pickIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(AUTHORITY, PRIMARY + NAME))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

    /** Whether the folder picked ([tree]) is the Gist folder: "Gist" (any case), at the top of the phone's storage. */
    fun isIt(tree: Uri): Boolean = nameOf(tree) != null

    /** The picked folder's name as it is ("Gist", "gist"…) if it's the Gist folder; else null. */
    private fun nameOf(tree: Uri): String? = try {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val name = id.removePrefix(PRIMARY)
        name.takeIf { tree.authority == AUTHORITY && id.startsWith(PRIMARY) && it.equals(NAME, ignoreCase = true) }
    } catch (e: Exception) {
        null
    }

    /**
     * Keeps access to [tree] (the Gist folder, picked) for good. False if it's another folder:
     * then nothing is kept.
     */
    fun allow(context: Context, tree: Uri): Boolean {
        if (!isIt(tree)) return false
        context.contentResolver.takePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        Db.get(context).setState(K_TREE, tree.toString())
        return true
    }

    /**
     * The Gist folder as Gist reaches it (a content:// tree), or null until you've allowed it,
     * or if access was taken away or the folder's gone.
     */
    fun tree(context: Context): String? {
        val saved = Db.get(context).state(K_TREE) ?: return null
        val uri = Uri.parse(saved)
        if (!isIt(uri)) return null
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!granted || !exists(context, uri)) return null
        return saved
    }

    /**
     * A folder in the Gist folder ("<tree>#<profile>/People/Jess") by its path on the phone's
     * storage ("/storage/emulated/0/gist/<profile>/People/Jess", with the folder's own case).
     */
    fun pathOf(location: String): String {
        val name = nameOf(Uri.parse(location.substringBefore('#'))) ?: NAME
        val sub = location.substringAfter('#', "")
        return "$STORAGE/$name" + (if (sub.isEmpty()) "" else "/$sub")
    }

    private fun exists(context: Context, tree: Uri): Boolean = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } == true
    } catch (e: Exception) {
        false
    }
}
