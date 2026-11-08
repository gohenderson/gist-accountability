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
 * One alarm with its own times and steps. It rings at each of [times] on the days [repeat]
 * allows (or on its place / movement [trigger]). When it rings, its [steps] run in order:
 * any number of math, scan and question steps (see [StepConfig]). With none, it's just Dismiss.
 * [aboutYesterday]: answers are logged for the previous day (e.g. a morning check-in
 * asking about last night).
 */
data class Checkin(
    val id: String,
    val name: String,
    val times: List<AlarmTime> = listOf(AlarmTime(12, 0)),
    val repeat: Repeat = Repeat.EVERY_DAY,
    val enabled: Boolean = true,
    val aboutYesterday: Boolean = false,
    val steps: List<StepConfig> = emptyList(),
    val soundUri: String? = null,  // null = phone's default alarm sound, SILENT = vibrate only
    val soundTitle: String = "",   // shown in the app (and kept in backups)
    val background: Background = Background(), // shown while ringing; media in Backgrounds
    val trigger: Trigger = Trigger.DEFAULT, // what makes it ring (times, or arriving/leaving a place)
) {
    /** Every question in its question steps, in order: these are what gets logged. */
    val questions: List<Question> get() = steps.filter { it.kind == Steps.QUESTIONS }.flatMap { it.questions }

    companion object {
        const val SILENT = "silent"
    }
}

/**
 * A check-in that has rung and is waiting for answers: which day the answers count for,
 * and which of the check-in's times ("HH:mm") rang. An empty [time] means its first time.
 * [practice]: a test ring from the edit page. It runs the steps like a real one but logs
 * nothing, so it never counts as (or closes) a real ring.
 */
data class Pending(val checkinId: String, val day: String, val time: String = "", val practice: Boolean = false)

/** One time's answers: question id → answer. */
typealias Answers = Map<String, Boolean>

/**
 * What happened with one check-in at one time on one day.
 * [status]: RANG (rang, not finished: counts as missed), ANSWERED (questions answered),
 * DONE (finished a check-in without questions), SKIPPED (skipped before it rang).
 * [early]: finished before the alarm time.
 */
data class SlotResult(val status: String, val answers: Answers = emptyMap(), val early: Boolean = false) {
    /** Finished or skipped: the alarm for this time no longer needs to ring. */
    val closed: Boolean get() = status != RANG

    companion object {
        const val RANG = "rang"
        const val ANSWERED = "answered"
        const val DONE = "done"
        const val SKIPPED = "skipped"
    }
}

/**
 * All app state, kept in one private SharedPreferences file on the phone.
 * Results are stored per day, check-in and time:
 *   { "2026-11-07": { "<checkinId>": { "21:30": { "s": "answered", "a": { "<questionId>": true }, "early": false } } } }
 */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gist", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ check-ins

    var checkins: List<Checkin>
        get() {
            val raw = prefs.getString(K_CHECKINS, null) ?: return DEFAULT_CHECKINS
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
                Pending(o.getString("c"), o.getString("d"), o.optString("t", ""), o.optBoolean("p", false))
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("c", it.checkinId).put("d", it.day).put("t", it.time).put("p", it.practice)) }
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

    // ------------------------------------------------------------ results

    fun result(day: String, checkinId: String, time: String): SlotResult? = results(day, checkinId)[time]

    /** One check-in's results on one day, by time. */
    fun results(day: String, checkinId: String): Map<String, SlotResult> =
        entriesJson().optJSONObject(day)?.optJSONObject(checkinId)?.toResults() ?: emptyMap()

    /** One check-in's results for every day, oldest first: day → time → result. */
    fun allResults(checkinId: String): Map<String, Map<String, SlotResult>> {
        val all = entriesJson()
        val out = sortedMapOf<String, Map<String, SlotResult>>()
        all.keys().forEach { day ->
            all.optJSONObject(day)?.optJSONObject(checkinId)?.let { out[day] = it.toResults() }
        }
        return out
    }

    /** Is this time finished or skipped (so its alarm shouldn't ring)? */
    fun isClosed(day: String, checkinId: String, time: String): Boolean =
        result(day, checkinId, time)?.closed == true

    /** Answers for one time (merged into any already saved for it). */
    fun saveAnswers(day: String, checkinId: String, time: String, answers: Answers, early: Boolean = false) {
        val prev = result(day, checkinId, time)
        val merged = (prev?.answers ?: emptyMap()) + answers
        put(day, checkinId, time, SlotResult(SlotResult.ANSWERED, merged, early || prev?.early == true))
    }

    /** A check-in without questions was completed. */
    fun markDone(day: String, checkinId: String, time: String, early: Boolean = false) =
        put(day, checkinId, time, SlotResult(SlotResult.DONE, early = early))

    /** The alarm rang. Doesn't overwrite a time that's already finished or skipped. */
    fun markRang(day: String, checkinId: String, time: String) {
        if (result(day, checkinId, time) == null) put(day, checkinId, time, SlotResult(SlotResult.RANG))
    }

    fun markSkipped(day: String, checkinId: String, time: String) =
        put(day, checkinId, time, SlotResult(SlotResult.SKIPPED))

    /** Forget a time's result (e.g. undo a skip). */
    fun clearResult(day: String, checkinId: String, time: String) {
        val all = entriesJson()
        val c = all.optJSONObject(day)?.optJSONObject(checkinId) ?: return
        c.remove(time)
        if (c.length() == 0) all.getJSONObject(day).remove(checkinId)
        if (all.getJSONObject(day).length() == 0) all.remove(day)
        prefs.edit().putString(K_ENTRIES, all.toString()).commit()
    }

    /** Every answer on [day], all check-ins and times merged. Handy for tests. */
    fun entry(day: String): Answers? {
        val d = entriesJson().optJSONObject(day) ?: return null
        var merged: Answers = emptyMap()
        d.keys().forEach { id -> results(day, id).toSortedMap().values.forEach { merged = merged + it.answers } }
        return merged.ifEmpty { null }
    }

    private fun put(day: String, checkinId: String, time: String, r: SlotResult) {
        val all = entriesJson()
        val d = all.optJSONObject(day) ?: JSONObject().also { all.put(day, it) }
        val c = d.optJSONObject(checkinId) ?: JSONObject().also { d.put(checkinId, it) }
        val a = JSONObject()
        r.answers.forEach { (k, v) -> a.put(k, v) }
        c.put(time, JSONObject().put("s", r.status).put("a", a).put("early", r.early))
        prefs.edit().putString(K_ENTRIES, all.toString()).commit()
    }

    private fun JSONObject.toResults(): Map<String, SlotResult> {
        val out = mutableMapOf<String, SlotResult>()
        keys().forEach { time ->
            val o = optJSONObject(time) ?: return@forEach
            val a = o.optJSONObject("a")
            val answers = mutableMapOf<String, Boolean>()
            a?.keys()?.forEach { k -> answers[k] = a.optBoolean(k) }
            out[time] = SlotResult(o.optString("s", SlotResult.ANSWERED), answers, o.optBoolean("early", false))
        }
        return out
    }

    // ------------------------------------------------------------ settings

    /** THEME_SYSTEM, THEME_LIGHT or THEME_DARK. */
    var theme: Int
        get() = prefs.getInt(K_THEME, THEME_SYSTEM)
        set(v) { prefs.edit().putInt(K_THEME, v).commit() }

    // ------------------------------------------------------------ backup support

    /** All results as stored (day → check-in → time → result). */
    fun entriesJson(): JSONObject =
        prefs.getString(K_ENTRIES, null)?.let { JSONObject(it) } ?: JSONObject()

    /** One check-in's history as stored: day → time → result. */
    fun historyJson(checkinId: String): JSONObject {
        val all = entriesJson()
        val out = JSONObject()
        all.keys().forEach { day -> all.optJSONObject(day)?.optJSONObject(checkinId)?.let { out.put(day, it) } }
        return out
    }

    /** Forgets everything [checkinId] logged. */
    fun deleteHistory(checkinId: String) {
        val all = entriesJson()
        all.keys().asSequence().toList().forEach { day ->
            val d = all.optJSONObject(day) ?: return@forEach
            d.remove(checkinId)
            if (d.length() == 0) all.remove(day)
        }
        replaceEntries(all)
    }

    /**
     * Writes [history] (day → time → result) for [checkinId]. With [merge], times already
     * logged and not in [history] are kept; without it, the check-in's history is replaced.
     */
    fun putHistory(checkinId: String, history: JSONObject, merge: Boolean) {
        if (!merge) deleteHistory(checkinId)
        val all = entriesJson()
        history.keys().forEach { day ->
            val slots = history.optJSONObject(day) ?: return@forEach
            val d = all.optJSONObject(day) ?: JSONObject().also { all.put(day, it) }
            val c = d.optJSONObject(checkinId) ?: JSONObject().also { d.put(checkinId, it) }
            slots.keys().forEach { time -> slots.optJSONObject(time)?.let { c.put(time, it) } }
        }
        replaceEntries(all)
    }

    /** Replaces all results with [json] (same shape as [entriesJson]). */
    fun replaceEntries(json: JSONObject) {
        prefs.edit().putString(K_ENTRIES, json.toString()).commit()
    }

    private fun questionFromJson(o: JSONObject) =
        Question(o.getString("id"), o.getString("text"), o.optBoolean("yesIsGood", false))

    /**
     * The step list. Check-ins saved before steps could repeat kept one math / scan /
     * questions setup on the check-in itself plus an order of kinds; those become steps.
     */
    private fun stepsFromJson(o: JSONObject): List<StepConfig> {
        val arr = o.optJSONArray("steps") ?: JSONArray()
        val objects = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        if (objects.isNotEmpty() || (arr.length() == 0 && !o.has("mathCount") && !o.has("questions") && !o.has("scanCode"))) {
            return objects.mapNotNull { StepConfig.fromJson(it) }
        }
        val qs = o.optJSONArray("questions") ?: JSONArray()
        val questions = (0 until qs.length()).map { questionFromJson(qs.getJSONObject(it)) }
        val mathCount = o.optInt("mathCount", 0)
        val scanCode = o.optString("scanCode", "").ifEmpty { null }
        val listed = (0 until arr.length()).map { arr.optString(it) }.filter { it in Steps.ALL }
        val configured = buildList {
            if (mathCount > 0) add(Steps.MATH)
            if (scanCode != null) add(Steps.SCAN)
            if (questions.isNotEmpty()) add(Steps.QUESTIONS)
        }
        return (listed + configured).distinct().map { kind ->
            when (kind) {
                Steps.MATH -> StepConfig(Store.newId(), kind, mathCount = mathCount.coerceAtLeast(1), mathLevel = o.optInt("mathLevel", 3))
                Steps.SCAN -> StepConfig(Store.newId(), kind, scanCode = scanCode, scanLabel = o.optString("scanLabel", ""))
                else -> StepConfig(Store.newId(), kind, questions = questions)
            }
        }
    }

    fun checkinFromJson(o: JSONObject): Checkin {
        val ts = o.getJSONArray("times")
        return Checkin(
            id = o.getString("id"),
            name = o.optString("name", "Check-in"),
            times = (0 until ts.length()).mapNotNull { AlarmTime.parse(ts.optString(it)) }
                .distinct().sorted().ifEmpty { listOf(AlarmTime(12, 0)) },
            repeat = Repeat.fromJson(o.optJSONObject("repeat")),
            enabled = o.optBoolean("enabled", true),
            aboutYesterday = o.optBoolean("aboutYesterday", false),
            steps = stepsFromJson(o),
            soundUri = o.optString("soundUri", "").ifEmpty { null },
            soundTitle = o.optString("soundTitle", ""),
            background = Background.fromJson(o.optJSONObject("background")),
            trigger = Trigger.fromJson(o.optJSONObject("trigger")),
        )
    }

    fun checkinToJson(c: Checkin): JSONObject {
        return JSONObject()
            .put("id", c.id)
            .put("name", c.name)
            .put("times", JSONArray().apply { c.times.forEach { put(it.toString()) } })
            .put("repeat", c.repeat.toJson())
            .put("enabled", c.enabled)
            .put("aboutYesterday", c.aboutYesterday)
            .put("soundUri", c.soundUri ?: "")
            .put("soundTitle", c.soundTitle)
            .put("background", c.background.toJson())
            .put("steps", JSONArray().apply { c.steps.forEach { put(it.toJson()) } })
            .put("trigger", c.trigger.toJson())
    }

    companion object {
        private const val K_CHECKINS = "checkins"
        private const val K_PENDING = "pendingList"
        private const val K_ENTRIES = "results"
        private const val K_THEME = "theme"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        fun newId(): String = UUID.randomUUID().toString().take(8)

        val DEFAULT_CHECKINS = listOf(
            Checkin(
                id = "morning",
                name = "Morning",
                times = listOf(AlarmTime(7, 0)),
                aboutYesterday = true,
                steps = listOf(StepConfig("morning-q", Steps.QUESTIONS, questions = listOf(
                    Question("latefood", "Did you eat anything after 9 PM last night?"),
                    Question("alcohol", "Did you drink alcohol last night?"),
                ))),
            ),
            Checkin(
                id = "evening",
                name = "Evening",
                times = listOf(AlarmTime(21, 30)),
                steps = listOf(StepConfig("evening-q", Steps.QUESTIONS, questions = listOf(
                    Question("sweetdrink", "Did you drink any sweetened beverages today?"),
                    Question("fastfood", "Did you eat fast food today?"),
                    Question("sweets", "Did you eat anything sweet today?"),
                    Question("veg", "Did you eat vegetables or fruit today?", yesIsGood = true),
                    Question("homecooked", "Did you eat a home-cooked meal today?", yesIsGood = true),
                ))),
            ),
        )
    }
}
