package dev.gist.checkin

import org.json.JSONObject

/**
 * What's shown behind a check-in's ringing screen: nothing, one photo, a slideshow of
 * photos (changing every [intervalSec] seconds), or a muted looping video, plus an
 * optional [caption] (shown even with no picture). The media itself lives in
 * [Backgrounds]; this only says which kind to use.
 */
data class Background(
    val kind: String = NONE,
    val intervalSec: Int = DEFAULT_INTERVAL,
    val caption: String = "",
) {
    val hasMedia: Boolean get() = kind != NONE

    /** e.g. "none", "photo", "slideshow · 4 photos · every 10 s", "video" */
    fun describe(photoCount: Int): String = when (kind) {
        PHOTO -> "photo"
        SLIDESHOW -> "slideshow · $photoCount photo${if (photoCount == 1) "" else "s"} · every $intervalSec s"
        VIDEO -> "video"
        else -> "none"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("intervalSec", intervalSec)
        .put("caption", caption)

    companion object {
        const val NONE = "none"
        const val PHOTO = "photo"
        const val SLIDESHOW = "slideshow"
        const val VIDEO = "video"
        const val DEFAULT_INTERVAL = 10
        val KINDS = setOf(NONE, PHOTO, SLIDESHOW, VIDEO)
        val INTERVALS = listOf(3, 5, 10, 30, 60)

        fun fromJson(o: JSONObject?): Background {
            if (o == null) return Background()
            val kind = o.optString("kind", NONE).takeIf { it in KINDS } ?: NONE
            return Background(
                kind = kind,
                intervalSec = o.optInt("intervalSec", DEFAULT_INTERVAL).coerceIn(1, 3600),
                caption = o.optString("caption", ""),
            )
        }
    }
}
