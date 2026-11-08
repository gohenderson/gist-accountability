package dev.gist.checkin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File

/**
 * Background photos shown while a check-in's alarm rings. A picked photo is copied
 * (downscaled, upright) into the app's private storage, so it keeps working even if the
 * original is deleted, and it can go into backups.
 */
object Backgrounds {
    private const val MAX_SIDE = 1600

    fun file(context: Context, checkinId: String): File =
        File(File(context.filesDir, "backgrounds").apply { mkdirs() }, "$checkinId.jpg")

    /** Copies the photo at [uri] for [checkinId]. Slow for big photos: call off the main thread. */
    fun saveFromUri(context: Context, checkinId: String, uri: Uri) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = maxOf(1.0, maxOf(w, h).toDouble() / MAX_SIDE)
            decoder.setTargetSize((w / scale).toInt().coerceAtLeast(1), (h / scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        save(context, checkinId, bitmap)
    }

    fun save(context: Context, checkinId: String, bitmap: Bitmap) {
        file(context, checkinId).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    fun load(context: Context, checkinId: String): Bitmap? {
        val f = file(context, checkinId)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    fun delete(context: Context, checkinId: String) {
        file(context, checkinId).delete()
    }

    fun deleteAll(context: Context) {
        File(context.filesDir, "backgrounds").listFiles()?.forEach { it.delete() }
    }
}
