package dev.gist.checkin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Where Gist keeps what it shares: /storage/emulated/0/PublicAppData, a folder other apps
 * (whatever you share folders with) can reach too. Gist's own is PublicAppData/Gist, with a
 * folder in it for each profile ([GistFolder]).
 *
 * Android doesn't let an app make a folder at the top of your storage, or reach it without
 * you saying so: you allow access to PublicAppData once, in Android's folder picker (where you
 * can also make it, the first time). Only that folder is accepted. Access is for the whole
 * device, every profile; Gist makes everything inside it.
 */
object PublicAppData {
    const val NAME = "PublicAppData"
    const val PATH = "/storage/emulated/0/$NAME"
    /** Gist's folder in it. */
    const val GIST = "Gist"

    private const val AUTHORITY = "com.android.externalstorage.documents"
    private const val DOC_ID = "primary:$NAME"
    private const val K_TREE = "publicAppData"

    /** Android's folder picker, opened at PublicAppData (at the top of your storage if it isn't there yet). */
    fun pickIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(AUTHORITY, DOC_ID))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

    /** Whether the folder picked ([tree]) is PublicAppData itself (not one in it, or another). */
    fun isIt(tree: Uri): Boolean = try {
        tree.authority == AUTHORITY && DocumentsContract.getTreeDocumentId(tree) == DOC_ID
    } catch (e: Exception) {
        false
    }

    /**
     * Keeps access to [tree] (PublicAppData, picked) for good. False if it's another folder:
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
     * PublicAppData, as Gist reaches it (a content:// tree), or null until you've allowed it,
     * or if access was taken away or the folder's gone.
     */
    fun tree(context: Context): String? {
        val saved = Db.get(context).state(K_TREE) ?: return null
        val uri = Uri.parse(saved)
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!granted || !exists(context, uri)) return null
        return saved
    }

    /** Gist's folder: PublicAppData/Gist, or null without access. */
    fun gist(context: Context): String? = tree(context)?.let { "$it#$GIST" }

    private fun exists(context: Context, tree: Uri): Boolean = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } == true
    } catch (e: Exception) {
        false
    }
}
