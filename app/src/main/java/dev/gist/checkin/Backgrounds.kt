package dev.gist.checkin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

/**
 * The photos and videos shown while a check-in's alarm rings (see [Background]). Picked
 * media is copied into the app's private storage (photos downscaled and upright), so it
 * keeps working even if the original is deleted, and it can go into backups.
 *
 * Layout: files/backgrounds/<checkinId>/photo-0.jpg, photo-1.jpg, … and video.mp4
 */
object Backgrounds {
    private const val MAX_SIDE = 1600
    const val MAX_PHOTOS = 20
    const val MAX_VIDEO_BYTES = 30L * 1024 * 1024

    private fun root(context: Context) = File(context.filesDir, "backgrounds")

    fun dir(context: Context, checkinId: String): File = File(root(context), checkinId).apply { mkdirs() }

    fun photoFile(context: Context, checkinId: String, index: Int): File = File(dir(context, checkinId), "photo-$index.jpg")

    fun videoFile(context: Context, checkinId: String): File = File(dir(context, checkinId), "video.mp4")

    /** The check-in's photos, in order. */
    fun photos(context: Context, checkinId: String): List<File> =
        (dir(context, checkinId).listFiles() ?: emptyArray())
            .mapNotNull { f -> Regex("photo-(\\d+)\\.jpg").matchEntire(f.name)?.let { it.groupValues[1].toInt() to f } }
            .sortedBy { it.first }
            .map { it.second }

    fun hasVideo(context: Context, checkinId: String): Boolean = videoFile(context, checkinId).exists()

    /** Replaces the photos with the ones at [uris]. Slow: call off the main thread. */
    fun setPhotosFromUris(context: Context, checkinId: String, uris: List<Uri>) {
        require(uris.isNotEmpty()) { "No photos picked." }
        val bitmaps = uris.take(MAX_PHOTOS).map { decode(context, it) }
        clearPhotos(context, checkinId)
        bitmaps.forEachIndexed { i, b -> savePhoto(context, checkinId, i, b) }
    }

    /** Copies the video at [uri]. Slow: call off the main thread. */
    fun setVideoFromUri(context: Context, checkinId: String, uri: Uri) {
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size > MAX_VIDEO_BYTES) {
            throw IllegalArgumentException("That video is too big (max ${MAX_VIDEO_BYTES / 1024 / 1024} MB). Try a shorter clip.")
        }
        val tmp = File(dir(context, checkinId), "video.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_VIDEO_BYTES) {
                        tmp.delete()
                        throw IllegalArgumentException("That video is too big (max ${MAX_VIDEO_BYTES / 1024 / 1024} MB).")
                    }
                    out.write(buf, 0, n)
                }
            }
        } ?: throw IllegalArgumentException("Couldn't read that video.")
        if (frameOf(tmp) == null) {
            tmp.delete()
            throw IllegalArgumentException("That file doesn't look like a video this phone can play.")
        }
        tmp.renameTo(videoFile(context, checkinId))
    }

    fun savePhoto(context: Context, checkinId: String, index: Int, bitmap: Bitmap) {
        photoFile(context, checkinId, index).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    fun load(file: File): Bitmap? = if (file.exists()) BitmapFactory.decodeFile(file.path) else null

    /** A still for the edit page: the first photo, or a frame of the video. */
    fun preview(context: Context, checkinId: String, bg: Background): Bitmap? = when (bg.kind) {
        Background.PHOTO, Background.SLIDESHOW -> photos(context, checkinId).firstOrNull()?.let { load(it) }
        Background.VIDEO -> videoFile(context, checkinId).takeIf { it.exists() }?.let { frameOf(it) }
        else -> null
    }

    /** True if the media for [bg] is actually there. */
    fun ready(context: Context, checkinId: String, bg: Background): Boolean = when (bg.kind) {
        Background.PHOTO, Background.SLIDESHOW -> photos(context, checkinId).isNotEmpty()
        Background.VIDEO -> hasVideo(context, checkinId)
        else -> false
    }

    fun clearPhotos(context: Context, checkinId: String) = photos(context, checkinId).forEach { it.delete() }

    fun clearVideo(context: Context, checkinId: String) {
        videoFile(context, checkinId).delete()
    }

    /** Removes media that [bg] doesn't use (e.g. the video after switching to photos). */
    fun keepOnly(context: Context, checkinId: String, bg: Background) {
        if (bg.kind != Background.PHOTO && bg.kind != Background.SLIDESHOW) clearPhotos(context, checkinId)
        if (bg.kind != Background.VIDEO) clearVideo(context, checkinId)
    }

    fun delete(context: Context, checkinId: String) {
        File(root(context), checkinId).deleteRecursively()
    }

    fun deleteAll(context: Context) {
        root(context).deleteRecursively()
    }

    private fun decode(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = maxOf(1.0, maxOf(w, h).toDouble() / MAX_SIDE)
            decoder.setTargetSize((w / scale).toInt().coerceAtLeast(1), (h / scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun frameOf(file: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            r.getFrameAtTime(0)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (e: Exception) {}
        }
    }
}
