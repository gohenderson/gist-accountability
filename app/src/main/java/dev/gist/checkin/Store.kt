package dev.gist.checkin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A yes/no question. [yesIsGood] says which answer counts as "on track". */
data class Question(val id: String, val text: String, val yesIsGood: Boolean = false) {
    fun isGood(answer: Boolean): Boolean = answer == yesIsGood
}

/** A time of day an alarm rings. */
data class AlarmTime(val hour: Int, val minute: Int) : Comparable<AlarmTime> {
    override fun compareTo(other: AlarmTime): Int = (hour * 60 + minute) - (other.hour * 60 + other.minute)

    /** Stored as "HH:mm", e.g. "07:00". */
    override fun toString(): String = String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute)

    companion object {
        fun parse(s: String): AlarmTime? {
            val parts = s.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return if (h in 0..23 && m in 0..59) AlarmTime(h, m) else null
        }
    }
}

/**
 * One alarm with its own times and steps. It rings at each of [times], every day. When it rings, the steps run in this order:
 * [mathCount] math problems → scan [scanCode] → answer [questions]. Each step is optional.
 * [aboutYesterday]: answers are logged for the previous day (e.g. a morning check-in
 * asking about last night).
 */
data class Checkin(
    val id: String,
    val name: String,
    val times: List<AlarmTime> = listOf(AlarmTime(12, 0)),
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

/**
 * A check-in that has rung and is waiting for answers: which day the answers count for,
 * and which of the check-in's times ("HH:mm") rang. An empty [time] means its first time.
 */
data class Pending(val checkinId: String, val day: String, val time: String = "")

/** One time's answers: question id → answer. */
typealias Answers = Map<String, Boolean>

/**
 * All app state, kept in one private SharedPreferences file on the phone.
 * Answers are stored per day and per time the check-in rang:
 *   { "2026-11-07": { "07:30": { "<questionId>": true/false, … }, "21:30": { … } }, … }
 * Question ids are unique across check-ins, so several check-ins can share a day and time.
 * (Before multiple times, a day held answers directly; that's converted on first read.)
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
                Pending(o.getString("c"), o.getString("d"), o.optString("t", ""))
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("c", it.checkinId).put("d", it.day).put("t", it.time)) }
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

    /** A day's answers by time ("HH:mm" → answers). Empty if nothing was logged. */
    fun slots(day: String): Map<String, Answers> =
        entriesJson().optJSONObject(day)?.toSlots() ?: emptyMap()

    /** The answers logged at one time on one day. */
    fun entry(day: String, time: String): Answers? = slots(day)[time]

    /** Every answer logged on [day], all times merged (later times win). Handy for tests. */
    fun entry(day: String): Answers? {
        val s = slots(day)
        if (s.isEmpty()) return null
        return s.toSortedMap().values.fold(emptyMap()) { acc, a -> acc + a }
    }

    /** All logged days, oldest first: day → time → answers. */
    fun allEntries(): Map<String, Map<String, Answers>> {
        val all = entriesJson()
        val out = sortedMapOf<String, Map<String, Answers>>()
        all.keys().forEach { day -> all.optJSONObject(day)?.let { out[day] = it.toSlots() } }
        return out
    }

    /** Saves one time's [answers] (other times and other check-ins' answers are kept). */
    fun saveEntry(day: String, time: String, answers: Answers) {
        val all = entriesJson()
        val d = all.optJSONObject(day) ?: JSONObject().also { all.put(day, it) }
        val slot = d.optJSONObject(time) ?: JSONObject().also { d.put(time, it) }
        answers.forEach { (id, value) -> slot.put(id, value) }
        prefs.edit().putString(K_ENTRIES, all.toString()).commit()
    }

    private fun JSONObject.toSlots(): Map<String, Answers> {
        val out = mutableMapOf<String, Answers>()
        keys().forEach { time -> optJSONObject(time)?.let { out[time] = it.toBoolMap() } }
        return out
    }

    // ------------------------------------------------------------ internals

    // ------------------------------------------------------------ settings

    /** THEME_SYSTEM, THEME_LIGHT or THEME_DARK. */
    var theme: Int
        get() = prefs.getInt(K_THEME, THEME_SYSTEM)
        set(v) { prefs.edit().putInt(K_THEME, v).commit() }

    // ------------------------------------------------------------ backup support

    /** All answers as stored (day → time → question → answer). */
    fun entriesJson(): JSONObject {
        val all = prefs.getString(K_ENTRIES, null)?.let { JSONObject(it) } ?: return JSONObject()
        if (migratePerDayAnswers(all)) prefs.edit().putString(K_ENTRIES, all.toString()).commit()
        return all
    }

    /**
     * Answers saved before multiple times (day → question → answer) move under the owning
     * check-in's first time. Returns true if anything changed.
     */
    private fun migratePerDayAnswers(all: JSONObject): Boolean {
        val days = all.keys().asSequence().toList()
        val legacy = days.filter { day ->
            val d = all.optJSONObject(day)
            d != null && d.keys().asSequence().any { d.opt(it) is Boolean }
        }
        if (legacy.isEmpty()) return false
        val ownerTime = checkins
            .flatMap { c -> c.questions.map { q -> q.id to c.times.first().toString() } }
            .toMap()
        for (day in legacy) {
            val d = all.getJSONObject(day)
            val qids = d.keys().asSequence().filter { d.opt(it) is Boolean }.toList()
            for (qid in qids) {
                val value = d.getBoolean(qid)
                d.remove(qid)
                val time = ownerTime[qid] ?: "12:00"
                val slot = d.optJSONObject(time) ?: JSONObject().also { d.put(time, it) }
                if (!slot.has(qid)) slot.put(qid, value)
            }
        }
        return true
    }

    private fun JSONObject.toBoolMap(): Map<String, Boolean> {
        val m = mutableMapOf<String, Boolean>()
        keys().forEach { k -> m[k] = optBoolean(k) }
        return m
    }

    /** Replaces all answers with [json] (same shape as [entriesJson]). */
    fun replaceEntries(json: JSONObject) {
        prefs.edit().putString(K_ENTRIES, json.toString()).commit()
    }

    /** Converts settings from the first (single-alarm) version, if present. */
    private fun legacyOrDefault(): List<Checkin> {
        val rawQuestions = prefs.getString(K_LEGACY_QUESTIONS, null) ?: return DEFAULT_CHECKINS
        val arr = JSONArray(rawQuestions)
        val migrated = Checkin(
            id = "evening",
            name = "Evening",
            times = listOf(AlarmTime(prefs.getInt(K_LEGACY_HOUR, 21), prefs.getInt(K_LEGACY_MINUTE, 30))),
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
            times = timesFromJson(o),
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

    /** "times": ["07:00", "21:30"]; data saved before multiple times has "hour"/"minute". */
    private fun timesFromJson(o: JSONObject): List<AlarmTime> {
        val arr = o.optJSONArray("times")
        val times = if (arr != null) {
            (0 until arr.length()).mapNotNull { AlarmTime.parse(arr.optString(it)) }
        } else {
            listOf(AlarmTime(o.optInt("hour", 21), o.optInt("minute", 30)))
        }
        return times.distinct().sorted().ifEmpty { listOf(AlarmTime(12, 0)) }
    }

    fun checkinToJson(c: Checkin): JSONObject {
        val qs = JSONArray()
        c.questions.forEach { qs.put(questionToJson(it)) }
        return JSONObject()
            .put("id", c.id)
            .put("name", c.name)
            .put("times", JSONArray().apply { c.times.forEach { put(it.toString()) } })
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
                times = listOf(AlarmTime(7, 0)),
                aboutYesterday = true,
                questions = listOf(
                    Question("latefood", "Did you eat anything after 9 PM last night?"),
                    Question("alcohol", "Did you drink alcohol last night?"),
                ),
            ),
            Checkin(
                id = "evening",
                name = "Evening",
                times = listOf(AlarmTime(21, 30)),
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
