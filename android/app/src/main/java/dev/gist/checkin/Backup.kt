package dev.gist.checkin

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * Backup files (YAML). You choose which check-ins go in, and for each whether its history
 * goes too:
 *
 *   format: gist-backup
 *   version: 3
 *   exportedAt: '2026-11-08T…'
 *   checkins: [ …same fields as Store… ]
 *   entries:                      # history, only for check-ins exported with it
 *     '2026-11-07':
 *       <checkinId>:
 *         '21:30': {s: answered, a: {soda: false}}
 *   media:
 *     <checkinId>:
 *       photos: [<base64 JPEG>, …]
 *       video: <base64 MP4>
 *       voice: <base64 audio>     # a voice check-in's recorded clip
 *
 * Importing is selective too (see [ImportChoice]): pick check-ins, and for one you already
 * have, update it, replace it, or add it as a copy. Alarm sounds are references to sounds
 * on the phone; if a sound doesn't exist on the new phone, the default alarm sound plays.
 */
object Backup {
    const val FORMAT = "gist-backup"
    const val VERSION = 3

    class Media(val photos: List<ByteArray>, val video: ByteArray?, val voice: ByteArray? = null)

    class Contents(
        val checkins: List<Checkin>,
        val entries: JSONObject,
        val media: Map<String, Media>,
    ) {
        val days: Int get() = entries.length()

        /** [checkinId]'s history in the file: day → time → result. */
        fun history(checkinId: String): JSONObject {
            val out = JSONObject()
            entries.keys().forEach { day -> entries.optJSONObject(day)?.optJSONObject(checkinId)?.let { out.put(day, it) } }
            return out
        }

        fun historyDays(checkinId: String): Int = history(checkinId).length()
    }

    /** Export [checkinId], with its history or without. */
    data class ExportChoice(val checkinId: String, val withHistory: Boolean)

    /** What to do with a check-in from a backup. */
    enum class Mode {
        /** Not on this phone yet: add it. */
        NEW,
        /** Overwrite your check-in's settings and media; keep its history (and add the file's). */
        UPDATE,
        /** Swap your check-in for the file's, history included (yours is deleted). */
        REPLACE,
        /** Keep yours and add the file's as a separate check-in. */
        COPY,
    }

    /** Import the file's [checkinId] this way, with its history or without. */
    data class ImportChoice(val checkinId: String, val mode: Mode, val withHistory: Boolean)

    /** Everything, with history. */
    fun allChoices(context: Context): List<ExportChoice> = Store(context).checkins.map { ExportChoice(it.id, true) }

    fun export(context: Context, choices: List<ExportChoice> = allChoices(context)): String {
        val store = Store(context)
        val chosen = choices.associateBy { it.checkinId }
        val checkins = store.checkins.filter { it.id in chosen }
        val entries = JSONObject()
        for (c in checkins) {
            if (chosen[c.id]?.withHistory != true) continue
            val h = store.historyJson(c.id)
            h.keys().forEach { day ->
                val d = entries.optJSONObject(day) ?: JSONObject().also { entries.put(day, it) }
                d.put(c.id, h.getJSONObject(day))
            }
        }
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
            if (c.voice && Backgrounds.hasVoice(context, c.id)) m.put("voice", b64(Backgrounds.voiceFile(context, c.id).readBytes()))
            if (m.length() > 0) media.put(c.id, m)
        }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", Instant.now().toString())
            .put("checkins", arr)
            .put("entries", sortedByDay(entries))
            .put("media", media)
        return Yaml.dump(
            root,
            header = "Gist backup. Import it with Settings → Import backup… (you choose what to bring in).\n" +
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
        if (version < VERSION) {
            throw IllegalArgumentException("This backup is from an older version of Gist that this one can't read.")
        }
        val store = Store(context)
        try {
            val arr = root.getJSONArray("checkins")
            val checkins = (0 until arr.length()).map { store.checkinFromJson(arr.getJSONObject(it)) }
            if (checkins.map { it.id }.toSet().size != checkins.size) {
                throw IllegalArgumentException("The backup has duplicate check-ins.")
            }
            val entries = root.optJSONObject("entries") ?: JSONObject()
            entries.keys().forEach { day ->
                entries.getJSONObject(day) // each day must be an object of answers
            }
            val media = mutableMapOf<String, Media>()
            val mj = root.optJSONObject("media") ?: JSONObject()
            mj.keys().forEach { id ->
                val m = mj.getJSONObject(id)
                val photos = m.optJSONArray("photos")?.let { a -> (0 until a.length()).map { unb64(a.getString(it)) } }
                val video = m.optString("video", "").ifEmpty { null }?.let { unb64(it) }
                val voice = m.optString("voice", "").ifEmpty { null }?.let { unb64(it) }
                media[id] = Media(photos.orEmpty(), video, voice)
            }
            return Contents(checkins, entries, media)
        } catch (e: JSONException) {
            throw IllegalArgumentException("The backup file is damaged: ${e.message}")
        }
    }

    /** Your check-in that the file's [c] would update or replace: same id, else same name. */
    fun match(local: List<Checkin>, c: Checkin): Checkin? =
        local.firstOrNull { it.id == c.id } ?: local.firstOrNull { it.name.equals(c.name, ignoreCase = true) }

    /** Applies [choices] to this phone and re-arms the affected alarms. Returns the check-ins it wrote. */
    fun import(context: Context, contents: Contents, choices: List<ImportChoice>): List<Checkin> {
        val store = Store(context)
        val written = mutableListOf<Checkin>()
        for (choice in choices) {
            val src = contents.checkins.firstOrNull { it.id == choice.checkinId } ?: continue
            val list = store.checkins
            val local = match(list, src)
            val mode = if (local == null && choice.mode != Mode.COPY) Mode.NEW else choice.mode
            val history = contents.history(src.id)
            val target: Checkin = when (mode) {
                Mode.NEW, Mode.COPY -> {
                    val idFree = list.none { it.id == src.id }
                    val id = if (mode == Mode.NEW && idFree) src.id else Store.newId()
                    val names = list.map { it.name.lowercase() }.toSet()
                    val name = if (src.name.lowercase() !in names) src.name else
                        (listOf("${src.name} (imported)") + (2..99).map { "${src.name} (imported $it)" })
                            .first { it.lowercase() !in names }
                    src.copy(id = id, name = name).also { store.checkins = list + it }
                }
                Mode.UPDATE, Mode.REPLACE -> {
                    val keep = local!!
                    AlarmScheduler.cancel(context, keep.id)
                    src.copy(id = keep.id).also { n -> store.checkins = list.map { if (it.id == keep.id) n else it } }
                }
            }
            // Media follows the file's settings.
            Backgrounds.delete(context, target.id)
            contents.media[src.id]?.let { m ->
                m.photos.take(Backgrounds.MAX_PHOTOS).forEachIndexed { i, bytes ->
                    Backgrounds.photoFile(context, target.id, i).writeBytes(bytes)
                }
                m.video?.let { Backgrounds.videoFile(context, target.id).writeBytes(it) }
                m.voice?.let { Backgrounds.voiceFile(context, target.id).writeBytes(it) }
            }
            var final = target
            if (target.background.hasMedia && !Backgrounds.ready(context, target.id, target.background)) {
                final = target.copy(background = target.background.copy(kind = Background.NONE))
                store.updateCheckin(target.id) { final }
            }
            when (mode) {
                Mode.REPLACE -> store.putHistory(target.id, if (choice.withHistory) history else JSONObject(), merge = false)
                Mode.UPDATE -> if (choice.withHistory) store.putHistory(target.id, history, merge = true)
                else -> if (choice.withHistory) store.putHistory(target.id, history, merge = false)
            }
            AlarmScheduler.schedule(context, final)
            written += final
        }
        store.prunePending()
        return written
    }

    private fun sortedByDay(entries: JSONObject): JSONObject {
        val out = JSONObject()
        entries.keys().asSequence().sorted().forEach { out.put(it, entries.get(it)) }
        return out
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unb64(s: String): ByteArray = try {
        Base64.decode(s, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("The backup file is damaged (bad photo or video data).")
    }
}
