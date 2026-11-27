package dev.gist.checkin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.ImageDecoder
import android.net.Uri
import android.view.View
import java.io.File

/**
 * A profile's picture: a small square PNG in the profile's own files (this phone only; it isn't
 * synced or backed up). Shown round, at the top right of every page and in the menu; without
 * one, the first letter of the profile's name.
 */
object ProfilePicture {
    /** Pictures are kept at this size (square). */
    const val SIDE = 256

    fun file(context: Context, id: String): File =
        File(Profiles.filesDir(Profiles.scoped(context, id)), "picture.png")

    fun has(context: Context, id: String): Boolean = file(context, id).isFile

    /** The picture, or null (none, or unreadable). */
    fun bitmap(context: Context, id: String): Bitmap? {
        val f = file(context, id)
        if (!f.isFile) return null
        val key = "${f.path}@${f.lastModified()}"
        cache[key]?.let { return it }
        return BitmapFactory.decodeFile(f.path)?.also {
            cache.keys.removeAll { k -> k.startsWith("${f.path}@") }
            cache[key] = it
        }
    }

    /** Sets [id]'s picture from an image: the middle square of it, [SIDE] pixels. */
    fun set(context: Context, id: String, uri: Uri) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val image = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val short = minOf(info.size.width, info.size.height).coerceAtLeast(1)
            val scale = maxOf(1.0, short.toDouble() / SIDE)
            decoder.setTargetSize((info.size.width / scale).toInt().coerceAtLeast(1), (info.size.height / scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        save(context, id, square(image))
    }

    /** Saves [bitmap] (square) as [id]'s picture. */
    fun save(context: Context, id: String, bitmap: Bitmap) {
        val f = file(context, id)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IllegalStateException("Couldn't save the picture")
        }
    }

    fun remove(context: Context, id: String) {
        file(context, id).delete()
    }

    /** The middle square of [b], [SIDE] pixels a side. */
    private fun square(b: Bitmap): Bitmap {
        val side = minOf(b.width, b.height)
        val cropped = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        return if (side == SIDE) cropped else Bitmap.createScaledBitmap(cropped, SIDE, SIDE, true)
    }

    /** The letter shown without a picture: the name's first letter (or digit), capitalised. */
    fun initial(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "?"
        val i = trimmed.indexOfFirst { it.isLetterOrDigit() }.coerceAtLeast(0)
        return String(Character.toChars(trimmed.codePointAt(i))).uppercase()
    }

    /** A steady color per profile for its letter's circle. */
    fun colorFor(id: String): Int = PALETTE[Math.floorMod(id.ifEmpty { "default" }.hashCode(), PALETTE.size)]

    private val PALETTE = intArrayOf(
        Color.parseColor("#1E7D5B"), // Gist green
        Color.parseColor("#3D6FB6"),
        Color.parseColor("#8A4FB0"),
        Color.parseColor("#C0573A"),
        Color.parseColor("#B07A12"),
        Color.parseColor("#2F8A99"),
        Color.parseColor("#B04A7A"),
    )

    private val cache = HashMap<String, Bitmap>()
}

/**
 * A round profile picture, or the profile's first letter on a colored circle. [ring] draws a
 * ring around it (the profile you're on, in the menu).
 */
class AvatarView(context: Context, val profileId: String, name: String, sizeDp: Int) : View(context) {
    /** What it shows without a picture (for tests and talkback). */
    val initial = ProfilePicture.initial(name)
    /** Whether it shows a picture. */
    val showsPicture: Boolean
    var ring: Boolean = false
        set(v) { field = v; invalidate() }

    private val size = context.dp(sizeDp)
    private val picture = ProfilePicture.bitmap(context, profileId)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ProfilePicture.colorFor(profileId) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        color = context.colorPrimary
    }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = size * 0.45f
    }
    private val shader = picture?.let {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }

    init {
        showsPicture = picture != null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(size, size)

    override fun onDraw(canvas: Canvas) {
        val gap = if (ring) context.dp(4).toFloat() else 0f
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - gap
        val bmp = picture
        if (bmp != null && shader != null) {
            val m = Matrix()
            val s = (2 * r) / bmp.width
            m.setScale(s, s)
            m.postTranslate(cx - r, cy - r)
            shader.shader.setLocalMatrix(m)
            canvas.drawCircle(cx, cy, r, shader)
        } else {
            canvas.drawCircle(cx, cy, r, fill)
            letter.textSize = 2 * r * 0.45f
            val y = cy - (letter.descent() + letter.ascent()) / 2
            canvas.drawText(initial, cx, y, letter)
        }
        if (ring) canvas.drawCircle(cx, cy, minOf(width, height) / 2f - ringPaint.strokeWidth / 2, ringPaint)
    }
}
