package dev.gist.checkin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A yes/no question. [yesIsGood] says which answer counts as "on track". */
data class Question(val id: String, val text: String, val yesIsGood: Boolean = false) {
    fun isGood(answer: Boolean): Boolean = answer == yesIsGood
}

/**
 * One alarm with its own time and steps. When it rings, the steps run in this order:
 * [mathCount] math problems → scan [scanCode] → answer [questions]. Each step is optional.
 * [aboutYesterday]: answers are logged for the previous day (e.g. a morning check-in
 * asking about last night).
 */
data class Checkin(
    val id: String,
    val name: String,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val aboutYesterday: Boolean = false,
    val questions: List<Question> = emptyList(),
    val mathCount: Int = 0,
    val mathLevel: Int = 3,        // 1..4, see MathGen.LEVELS
    val scanCode: String? = null,  // exact text of the registered QR code / barcode
    val scanLabel: String = "",    // where it is, e.g. "Bathroom sink"
    val soundUri: String? = null,  // null = phone's default alarm sound, SILENT = vibrate only
    val soundTitle: String = "",   // shown in the app (and kept in backups)
    val hasBackground: Boolean = false, // photo shown while ringing; see Backgrounds
) {
    companion object {
        const val SILENT = "silent"
    }
}

/** A check-in that has rung and is waiting for answers. */
data class Pending(val checkinId: String, val day: String)

/**
 * All app state, kept in one private SharedPreferences file on the phone.
 * Entries are stored as JSON: { "2026-11-07": { "<questionId>": true/false, ... }, ... }
 * Question ids are unique across check-ins, so several check-ins can log into the same day.
 */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gist", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ check-ins

    var checkins: List<Checkin>
        get() {
            val raw = prefs.getString(K_CHECKINS, null) ?: return legacyOrDefault()
            val arr = JSONArray(raw)
            return (0 until arr.length()).map { checkinFromJson(arr.getJSONObject(it)) }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(checkinToJson(it)) }
            prefs.edit().putString(K_CHECKINS, arr.toString()).commit()
        }

    fun checkin(id: String): Checkin? = checkins.firstOrNull { it.id == id }

    fun updateCheckin(id: String, change: (Checkin) -> Checkin) {
        checkins = checkins.map { if (it.id == id) change(it) else it }
    }

    // ------------------------------------------------------------ pending

    var pending: List<Pending>
        get() {
            val raw = prefs.getString(K_PENDING, null) ?: return emptyList()
            val arr = JSONArray(raw)
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Pending(o.getString("c"), o.getString("d"))
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("c", it.checkinId).put("d", it.day)) }
            prefs.edit().putString(K_PENDING, arr.toString()).commit()
        }

    fun addPending(p: Pending) {
        val list = pending
        if (p !in list) pending = list + p
    }

    fun removePending(p: Pending) {
        pending = pending.filter { it != p }
    }

    /** Drop pending check-ins that were deleted. */
    fun prunePending() {
        val ids = checkins.map { it.id }.toSet()
        val list = pending
        val kept = list.filter { it.checkinId in ids }
        if (kept.size != list.size) pending = kept
    }

    // ------------------------------------------------------------ entries

    fun entry(day: String): Map<String, Boolean>? = entriesJson().optJSONObject(day)?.toBoolMap()

    /** All logged days, oldest first. */
    fun allEntries(): Map<String, Map<String, Boolean>> {
        val all = entriesJson()
        val out = sortedMapOf<String, Map<String, Boolean>>()
        all.keys().forEach { day -> all.optJSONObject(day)?.let { out[day] = it.toBoolMap() } }
        return out
    }

    /** Merges [answers] into the day (other check-ins' answers for that day are kept). */
    fun saveEntry(day: String, answers: Map<String, Boolean>) {
        val all = entriesJson()
        val o = all.optJSONObject(day) ?: JSONObject()
        answers.forEach { (id, value) -> o.put(id, value) }
        all.put(day, o)
        prefs.edit().putString(K_ENTRIES, all.toString()).commit()
    }

    // ------------------------------------------------------------ internals

    // ------------------------------------------------------------ settings

    /** THEME_SYSTEM, THEME_LIGHT or THEME_DARK. */
    var theme: Int
        get() = prefs.getInt(K_THEME, THEME_SYSTEM)
        set(v) { prefs.edit().putInt(K_THEME, v).commit() }

    // ------------------------------------------------------------ backup support

    /** All answers as stored (day → question → answer). */
    fun entriesJson(): JSONObject =
        prefs.getString(K_ENTRIES, null)?.let { JSONObject(it) } ?: JSONObject()

    private fun JSONObject.toBoolMap(): Map<String, Boolean> {
        val m = mutableMapOf<String, Boolean>()
        keys().forEach { k -> m[k] = optBoolean(k) }
        return m
    }

    /** Converts settings from the first (single-alarm) version, if present. */
    /** Replaces all answers with [json] (same shape as [entriesJson]). */
    fun replaceEntries(json: JSONObject) {
        prefs.edit().putString(K_ENTRIES, json.toString()).commit()
    }

    private fun legacyOrDefault(): List<Checkin> {
        val rawQuestions = prefs.getString(K_LEGACY_QUESTIONS, null) ?: return DEFAULT_CHECKINS
        val arr = JSONArray(rawQuestions)
        val migrated = Checkin(
            id = "evening",
            name = "Evening",
            hour = prefs.getInt(K_LEGACY_HOUR, 21),
            minute = prefs.getInt(K_LEGACY_MINUTE, 30),
            enabled = prefs.getBoolean(K_LEGACY_ENABLED, true),
            questions = (0 until arr.length()).map { questionFromJson(arr.getJSONObject(it)) },
        )
        return listOf(migrated)
    }

    private fun questionFromJson(o: JSONObject) =
        Question(o.getString("id"), o.getString("text"), o.optBoolean("yesIsGood", false))

    private fun questionToJson(q: Question): JSONObject =
        JSONObject().put("id", q.id).put("text", q.text).put("yesIsGood", q.yesIsGood)

    fun checkinFromJson(o: JSONObject): Checkin {
        val qs = o.optJSONArray("questions") ?: JSONArray()
        return Checkin(
            id = o.getString("id"),
            name = o.optString("name", "Check-in"),
            hour = o.optInt("hour", 21),
            minute = o.optInt("minute", 30),
            enabled = o.optBoolean("enabled", true),
            aboutYesterday = o.optBoolean("aboutYesterday", false),
            questions = (0 until qs.length()).map { questionFromJson(qs.getJSONObject(it)) },
            mathCount = o.optInt("mathCount", 0),
            mathLevel = o.optInt("mathLevel", 3),
            scanCode = o.optString("scanCode", "").ifEmpty { null },
            scanLabel = o.optString("scanLabel", ""),
            soundUri = o.optString("soundUri", "").ifEmpty { null },
            soundTitle = o.optString("soundTitle", ""),
            hasBackground = o.optBoolean("hasBackground", false),
        )
    }

    fun checkinToJson(c: Checkin): JSONObject {
        val qs = JSONArray()
        c.questions.forEach { qs.put(questionToJson(it)) }
        return JSONObject()
            .put("id", c.id)
            .put("name", c.name)
            .put("hour", c.hour)
            .put("minute", c.minute)
            .put("enabled", c.enabled)
            .put("aboutYesterday", c.aboutYesterday)
            .put("questions", qs)
            .put("mathCount", c.mathCount)
            .put("mathLevel", c.mathLevel)
            .put("scanCode", c.scanCode ?: "")
            .put("scanLabel", c.scanLabel)
            .put("soundUri", c.soundUri ?: "")
            .put("soundTitle", c.soundTitle)
            .put("hasBackground", c.hasBackground)
    }

    companion object {
        private const val K_CHECKINS = "checkins"
        private const val K_PENDING = "pendingList"
        private const val K_ENTRIES = "entries"
        private const val K_THEME = "theme"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        private const val K_LEGACY_QUESTIONS = "questions"
        private const val K_LEGACY_HOUR = "hour"
        private const val K_LEGACY_MINUTE = "minute"
        private const val K_LEGACY_ENABLED = "enabled"

        fun newId(): String = UUID.randomUUID().toString().take(8)

        val DEFAULT_CHECKINS = listOf(
            Checkin(
                id = "morning",
                name = "Morning",
                hour = 7,
                minute = 0,
                aboutYesterday = true,
                questions = listOf(
                    Question("latefood", "Did you eat anything after 9 PM last night?"),
                    Question("alcohol", "Did you drink alcohol last night?"),
                ),
            ),
            Checkin(
                id = "evening",
                name = "Evening",
                hour = 21,
                minute = 30,
                questions = listOf(
                    Question("sweetdrink", "Did you drink any sweetened beverages today?"),
                    Question("fastfood", "Did you eat fast food today?"),
                    Question("sweets", "Did you eat anything sweet today?"),
                    Question("veg", "Did you eat vegetables or fruit today?", yesIsGood = true),
                    Question("homecooked", "Did you eat a home-cooked meal today?", yesIsGood = true),
                ),
            ),
        )
    }
}
