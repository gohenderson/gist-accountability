package dev.gist.checkin

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * One JSON file with everything needed to move to a new phone:
 * {
 *   "format": "gist-backup", "version": 1, "exportedAt": "…",
 *   "checkins": [ …same as Store… ],
 *   "entries": { "2026-11-07": { "<questionId>": true, … }, … },
 *   "backgrounds": { "<checkinId>": "<base64 JPEG>", … }
 * }
 * Alarm sounds are references to sounds on the phone; if a sound doesn't exist on the
 * new phone, that check-in plays the default alarm sound instead.
 */
object Backup {
    const val FORMAT = "gist-backup"
    const val VERSION = 1

    class Contents(
        val checkins: List<Checkin>,
        val entries: JSONObject,
        val backgrounds: Map<String, ByteArray>,
    ) {
        val days: Int get() = entries.length()
    }

    fun export(context: Context): String {
        val store = Store(context)
        val checkins = store.checkins
        val arr = JSONArray()
        checkins.forEach { arr.put(store.checkinToJson(it)) }
        val bgs = JSONObject()
        for (c in checkins) {
            val f = Backgrounds.file(context, c.id)
            if (c.hasBackground && f.exists()) {
                bgs.put(c.id, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
            }
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", Instant.now().toString())
            .put("checkins", arr)
            .put("entries", store.entriesJson())
            .put("backgrounds", bgs)
            .toString(2)
    }

    /** Reads and checks a backup without changing anything. Throws with a readable message. */
    fun parse(context: Context, text: String): Contents {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IllegalArgumentException("This isn't a Gist backup file (not JSON).")
        }
        if (root.optString("format") != FORMAT) {
            throw IllegalArgumentException("This isn't a Gist backup file.")
        }
        if (root.optInt("version", 0) > VERSION) {
            throw IllegalArgumentException("This backup is from a newer version of Gist. Update the app first.")
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
            val bgJson = root.optJSONObject("backgrounds") ?: JSONObject()
            val bgs = mutableMapOf<String, ByteArray>()
            bgJson.keys().forEach { id -> bgs[id] = Base64.decode(bgJson.getString(id), Base64.DEFAULT) }
            return Contents(checkins, entries, bgs)
        } catch (e: JSONException) {
            throw IllegalArgumentException("The backup file is damaged: ${e.message}")
        }
    }

    /** Replaces all check-ins, answers and photos with [contents], and re-arms the alarms. */
    fun restore(context: Context, contents: Contents) {
        val store = Store(context)
        store.checkins.forEach { AlarmScheduler.cancel(context, it.id) }
        Backgrounds.deleteAll(context)

        val restored = contents.checkins.map { c ->
            val bytes = contents.backgrounds[c.id]
            if (bytes != null) Backgrounds.file(context, c.id).writeBytes(bytes)
            c.copy(hasBackground = bytes != null)
        }
        store.checkins = restored
        store.replaceEntries(contents.entries)
        store.pending = emptyList()
        AlarmScheduler.scheduleAll(context)
    }
}
