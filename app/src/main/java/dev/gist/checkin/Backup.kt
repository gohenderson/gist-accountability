package dev.gist.checkin

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * One YAML file with everything needed to move to a new phone:
 *
 *   format: gist-backup
 *   version: 3
 *   exportedAt: '2026-11-08T…'
 *   checkins: [ …same fields as Store… ]
 *   entries:
 *     '2026-11-07':
 *       <checkinId>:
 *         '21:30': {s: answered, a: {soda: false}}
 *   media:
 *     <checkinId>:
 *       photos: [<base64 JPEG>, …]
 *       video: <base64 MP4>
 *
 * Version 2 backups (.json, one photo per check-in) still import. Alarm sounds are
 * references to sounds on the phone; if a sound doesn't exist on the new phone, that
 * check-in plays the default alarm sound instead.
 */
object Backup {
    const val FORMAT = "gist-backup"
    const val VERSION = 3
    private const val OLDEST_READABLE = 2

    class Media(val photos: List<ByteArray>, val video: ByteArray?)

    class Contents(
        val checkins: List<Checkin>,
        val entries: JSONObject,
        val media: Map<String, Media>,
    ) {
        val days: Int get() = entries.length()
    }

    fun export(context: Context): String {
        val store = Store(context)
        val checkins = store.checkins
        val arr = JSONArray()
        checkins.forEach { arr.put(store.checkinToJson(it)) }
        val media = JSONObject()
        for (c in checkins) {
            val m = JSONObject()
            val bg = c.background
            if (bg.kind == Background.PHOTO || bg.kind == Background.SLIDESHOW) {
                val photos = JSONArray()
                Backgrounds.photos(context, c.id).forEach { photos.put(b64(it.readBytes())) }
                if (photos.length() > 0) m.put("photos", photos)
            }
            if (bg.kind == Background.VIDEO) {
                val f = Backgrounds.videoFile(context, c.id)
                if (f.exists()) m.put("video", b64(f.readBytes()))
            }
            if (m.length() > 0) media.put(c.id, m)
        }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", Instant.now().toString())
            .put("checkins", arr)
            .put("entries", store.entriesJson())
            .put("media", media)
        return Yaml.dump(
            root,
            header = "Gist backup. Import it with Settings → Import backup… (it replaces everything on that phone).\n" +
                "You can edit check-ins here; times are \"HH:mm\", repeat kinds are daily / weekly / monthly.",
        )
    }

    /** Reads and checks a backup without changing anything. Throws with a readable message. */
    fun parse(context: Context, text: String): Contents {
        val root = Yaml.toJson(Yaml.load(text)) as? JSONObject
            ?: throw IllegalArgumentException("This isn't a Gist backup file.")
        if (root.optString("format") != FORMAT) {
            throw IllegalArgumentException("This isn't a Gist backup file.")
        }
        val version = root.optInt("version", 0)
        if (version > VERSION) {
            throw IllegalArgumentException("This backup is from a newer version of Gist. Update the app first.")
        }
        if (version < OLDEST_READABLE) {
            throw IllegalArgumentException("This backup is from an older version of Gist that this one can't read.")
        }
        val store = Store(context)
        try {
            val arr = root.getJSONArray("checkins")
            var checkins = (0 until arr.length()).map { store.checkinFromJson(arr.getJSONObject(it)) }
            if (checkins.map { it.id }.toSet().size != checkins.size) {
                throw IllegalArgumentException("The backup has duplicate check-ins.")
            }
            val entries = root.optJSONObject("entries") ?: JSONObject()
            entries.keys().forEach { day ->
                entries.getJSONObject(day) // each day must be an object of answers
            }
            val media = mutableMapOf<String, Media>()
            if (version == 2) {
                // One photo per check-in under "backgrounds".
                val bgs = root.optJSONObject("backgrounds") ?: JSONObject()
                bgs.keys().forEach { id -> media[id] = Media(listOf(unb64(bgs.getString(id))), null) }
                checkins = checkins.map { c ->
                    if (c.id in media) c.copy(background = c.background.copy(kind = Background.PHOTO)) else c
                }
            } else {
                val mj = root.optJSONObject("media") ?: JSONObject()
                mj.keys().forEach { id ->
                    val m = mj.getJSONObject(id)
                    val photos = m.optJSONArray("photos")?.let { a -> (0 until a.length()).map { unb64(a.getString(it)) } }
                    val video = m.optString("video", "").ifEmpty { null }?.let { unb64(it) }
                    media[id] = Media(photos.orEmpty(), video)
                }
            }
            return Contents(checkins, entries, media)
        } catch (e: JSONException) {
            throw IllegalArgumentException("The backup file is damaged: ${e.message}")
        }
    }

    /** Replaces all check-ins, answers and media with [contents], and re-arms the alarms. */
    fun restore(context: Context, contents: Contents) {
        val store = Store(context)
        store.checkins.forEach { AlarmScheduler.cancel(context, it.id) }
        Backgrounds.deleteAll(context)

        val restored = contents.checkins.map { c ->
            val m = contents.media[c.id]
            m?.photos?.take(Backgrounds.MAX_PHOTOS)?.forEachIndexed { i, bytes ->
                Backgrounds.photoFile(context, c.id, i).writeBytes(bytes)
            }
            m?.video?.let { Backgrounds.videoFile(context, c.id).writeBytes(it) }
            // A background whose media isn't in the file falls back to none (the caption stays).
            if (c.background.hasMedia && !Backgrounds.ready(context, c.id, c.background)) {
                c.copy(background = c.background.copy(kind = Background.NONE))
            } else {
                c
            }
        }
        store.checkins = restored
        store.replaceEntries(contents.entries)
        store.pending = emptyList()
        AlarmScheduler.scheduleAll(context)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unb64(s: String): ByteArray = try {
        Base64.decode(s, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("The backup file is damaged (bad photo or video data).")
    }
}
