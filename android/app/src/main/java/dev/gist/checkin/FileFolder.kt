package dev.gist.checkin

import java.io.File

/** A folder on the phone's storage, reached by path: the Gist folder's ([GistRoot]), or a plain one in tests. */
class FileFolder(private val root: File) : LinkFolder {
    private val base = root.canonicalFile

    /** [path] inside the folder, or an error if it would be anywhere else. */
    private fun inside(path: String): File {
        val f = File(base, LinkPath.parts(path).joinToString(File.separator)).canonicalFile
        require(f.path.startsWith(base.path + File.separator)) { "Outside the connection folder: $path" }
        return f
    }

    override fun read(path: String): ByteArray? = inside(path).takeIf { it.isFile }?.readBytes()

    override fun names(dir: String): List<String> =
        (if (dir.isEmpty()) base else inside(dir)).list()?.toList() ?: emptyList()

    override fun delete(path: String) {
        inside(path).delete()
    }

    override fun write(path: String, bytes: ByteArray) {
        val f = inside(path)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.writeBytes(bytes); tmp.delete() }
    }
}

