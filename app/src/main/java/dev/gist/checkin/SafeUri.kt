package dev.gist.checkin

import android.content.Context
import android.net.Uri
import java.nio.file.FileSystems

/**
 * Checks a file the system picker handed back before the app reads or writes it. It must be
 * a content:// document from another app's provider (not one of Gist's own), and its path,
 * once normalized (so ".." can't sneak past), must not point into private app storage
 * (/data/…). The picker never returns anything else; this makes sure nothing else is used.
 */
object SafeUri {

    /** [uri] if it's safe to open, else null. */
    fun picked(context: Context, uri: Uri?): Uri? {
        if (uri == null || uri.scheme != "content") return null
        if (uri.authority.isNullOrEmpty() || uri.authority!!.startsWith(context.packageName)) return null
        val path = uri.path?.takeIf { it.isNotBlank() } ?: return null
        val normalized = FileSystems.getDefault().getPath(path).normalize()
        if (normalized.startsWith("/data") || normalized.any { it.toString() == ".." }) return null
        return uri
    }
}
