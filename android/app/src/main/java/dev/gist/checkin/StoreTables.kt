package dev.gist.checkin

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.time.LocalDate

/**
 * Check-ins, results and the waiting list as database rows: reading and writing them on a
 * given database, for [Store] and for the import from the old preferences ([LegacyImport]).
 */
internal object CheckinRows {
    private const val TIME = "time"

    /** Replaces profile [p]'s check-ins with [list], in its order. */
    fun write(w: SQLiteDatabase, p: String, list: List<Checkin>) {
        w.delete("checkin", "profile_id = ?", arrayOf(p)) // their triggers, steps, questions and shares go too
        list.forEachIndexed { i, c -> insert(w, p, i, c) }
    }

    private fun insert(w: SQLiteDatabase, p: String, position: Int, c: Checkin) {
        val (alert, sound) = when (c.soundUri) {
            Checkin.SILENT -> "vibrate" to null
            Checkin.QUIET -> "quiet" to null
            else -> "sound" to c.soundUri
        }
        w.insertOrThrow("checkin", null, ContentValues().apply {
            put("profile_id", p)
            put("id", c.id)
            put("position", position)
            put("kind", if (c.task) "task" else "checkin")
            put("name", c.name)
            put("description", c.description)
            put("category", c.category)
            putBool("enabled", c.enabled)
            putBool("about_yesterday", c.aboutYesterday)
            put("commitment", c.commitment)
            put("alert", alert)
            put("sound_uri", sound)
            put("sound_title", c.soundTitle)
            put("snooze_minutes", c.snoozeMinutes)
            put("voice_source", c.voiceSource)
            put("voice_text", c.voiceText)
            putBool("voice_headphones_only", c.voiceHeadphonesOnly)
            putRepeat(this, "repeat", c.repeat)
            put("background_kind", c.background.kind)
            put("background_interval_sec", c.background.intervalSec)
            put("background_caption", c.background.caption)
            put("time_commitment", c.timeCommitment)
            put("follows_up_id", c.followsUp)
            put("assignee", c.assignee)
            put("done_at", c.doneAt)
            put("done_by", c.doneBy)
            put("backlog_at", c.backlogAt)
            put("linked_from", c.linkedFrom)
            put("linked_assignee", c.linkedAssignee)
            putBool("linked_gone", c.linkedGone)
            put("linked_done_at", c.linkedDoneAt)
            put("updated_at", c.updatedAt)
        })
        var t = 0
        c.times.forEach { time ->
            w.insertOrThrow("checkin_trigger", null, trigger(p, c.id, t++).apply {
                put("kind", TIME)
                put("hour", time.hour)
                put("minute", time.minute)
                time.days?.let { putRepeat(this, "repeat", it) }
                put("where_mode", time.where)
                putPlace(this, time.place)
                putBool("once_per_day", true)
                put("min_run_minutes", 0)
            })
        }
        c.events.forEach { e ->
            w.insertOrThrow("checkin_trigger", null, trigger(p, c.id, t++).apply {
                put("kind", e.kind)
                putRepeat(this, "repeat", e.days)
                put("where_mode", e.where)
                putPlace(this, e.place)
                put("window_start_min", e.windowStart?.let { it.hour * 60 + it.minute })
                put("window_end_min", e.windowEnd?.let { it.hour * 60 + it.minute })
                putBool("once_per_day", e.oncePerDay)
                put("min_run_minutes", e.minRunMinutes)
            })
        }
        c.steps.forEachIndexed { si, s ->
            w.insertOrThrow("step", null, ContentValues().apply {
                put("profile_id", p)
                put("checkin_id", c.id)
                put("position", si)
                put("id", s.id)
                put("kind", s.kind)
                put("math_count", s.mathCount)
                put("math_level", s.mathLevel)
                put("scan_code", s.scanCode)
                put("scan_label", s.scanLabel)
                put("pick_count", s.pickCount)
            })
            s.questions.forEachIndexed { qi, q ->
                w.insertOrThrow("question", null, ContentValues().apply {
                    put("profile_id", p)
                    put("checkin_id", c.id)
                    put("step_position", si)
                    put("position", qi)
                    put("id", q.id)
                    put("text", q.text)
                    put("type", q.type)
                    putBool("yes_is_good", q.yesIsGood)
                })
            }
        }
        c.sharedWith.forEach { link ->
            w.insertOrThrow("checkin_share", null, ContentValues().apply {
                put("profile_id", p)
                put("checkin_id", c.id)
                put("connection_id", link)
            })
        }
    }

    private fun trigger(p: String, id: String, position: Int) = ContentValues().apply {
        put("profile_id", p)
        put("checkin_id", id)
        put("position", position)
    }

    private fun putPlace(v: ContentValues, place: Place?) {
        if (place == null) return
        v.put("place_name", place.name)
        v.put("place_lat", place.lat)
        v.put("place_lon", place.lon)
        v.put("place_radius_m", place.radiusM)
    }

    /** A repeat as its kind, its days as bits (bit 0: the last day of the month) and its date. */
    private fun putRepeat(v: ContentValues, prefix: String, r: Repeat) {
        v.put("${prefix}_kind", r.kind)
        v.put("${prefix}_days", r.days.fold(0L) { bits, d -> bits or (1L shl (if (d == Repeat.LAST_DAY) 0 else d)) })
        v.put("${prefix}_date", r.date?.toString())
    }

    private fun repeat(kind: String?, bits: Long?, date: String?): Repeat? {
        if (kind == null) return null
        val days = (0..31).filter { bits != null && bits and (1L shl it) != 0L }.map { if (it == 0) Repeat.LAST_DAY else it }.toSet()
        return Repeat(kind, days, date?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
    }

    private fun minutes(m: Int?) = m?.let { AlarmTime(it / 60, it % 60) }

    /** Profile [p]'s check-ins, in order. */
    fun read(r: SQLiteDatabase, p: String): List<Checkin> {
        val args = arrayOf(p)
        val shares = HashMap<String, MutableSet<String>>()
        r.query("checkin_share", arrayOf("checkin_id", "connection_id"), "profile_id = ?", args, null, null, null).rows { c ->
            shares.getOrPut(c.str("checkin_id")) { mutableSetOf() }.add(c.str("connection_id"))
        }
        val questions = HashMap<Pair<String, Int>, MutableList<Question>>()
        r.query("question", null, "profile_id = ?", args, null, null, "checkin_id, step_position, position").rows { c ->
            questions.getOrPut(c.str("checkin_id") to c.int("step_position")) { mutableListOf() }
                .add(Question(c.str("id"), c.str("text"), c.bool("yes_is_good"), c.str("type")))
        }
        val steps = HashMap<String, MutableList<StepConfig>>()
        r.query("step", null, "profile_id = ?", args, null, null, "checkin_id, position").rows { c ->
            val id = c.str("checkin_id")
            steps.getOrPut(id) { mutableListOf() }.add(StepConfig(
                id = c.str("id"),
                kind = c.str("kind"),
                mathCount = c.int("math_count"),
                mathLevel = c.int("math_level"),
                scanCode = c.strOrNull("scan_code"),
                scanLabel = c.str("scan_label"),
                questions = questions[id to c.int("position")] ?: emptyList(),
                pickCount = c.int("pick_count"),
            ))
        }
        val times = HashMap<String, MutableList<AlarmTime>>()
        val events = HashMap<String, MutableList<Trigger>>()
        r.query("checkin_trigger", null, "profile_id = ?", args, null, null, "checkin_id, position").rows { c ->
            val id = c.str("checkin_id")
            val place = c.longOrNull("place_radius_m")?.let {
                Place(c.strOrNull("place_name") ?: "Place", c.dbl("place_lat"), c.dbl("place_lon"), it.toInt())
            }
            val days = repeat(c.strOrNull("repeat_kind"), c.longOrNull("repeat_days"), c.strOrNull("repeat_date"))
            val kind = c.str("kind")
            if (kind == TIME) {
                times.getOrPut(id) { mutableListOf() }
                    .add(AlarmTime(c.int("hour"), c.int("minute"), days, c.str("where_mode"), place))
            } else {
                events.getOrPut(id) { mutableListOf() }.add(Trigger(
                    kind = kind,
                    place = place,
                    where = c.str("where_mode"),
                    windowStart = minutes(c.intOrNull("window_start_min")),
                    windowEnd = minutes(c.intOrNull("window_end_min")),
                    oncePerDay = c.bool("once_per_day"),
                    minRunMinutes = c.int("min_run_minutes"),
                    days = days ?: Repeat.EVERY_DAY,
                ))
            }
        }
        return r.query("checkin", null, "profile_id = ?", args, null, null, "position").rows { c ->
            val id = c.str("id")
            Checkin(
                id = id,
                name = c.str("name"),
                times = times[id] ?: emptyList(),
                repeat = repeat(c.str("repeat_kind"), c.long("repeat_days"), c.strOrNull("repeat_date")) ?: Repeat.EVERY_DAY,
                enabled = c.bool("enabled"),
                aboutYesterday = c.bool("about_yesterday"),
                steps = steps[id] ?: emptyList(),
                soundUri = when (c.str("alert")) {
                    "vibrate" -> Checkin.SILENT
                    "quiet" -> Checkin.QUIET
                    else -> c.strOrNull("sound_uri")
                },
                soundTitle = c.str("sound_title"),
                background = Background(c.str("background_kind"), c.int("background_interval_sec"), c.str("background_caption")),
                events = events[id] ?: emptyList(),
                category = c.str("category"),
                commitment = c.str("commitment"),
                snoozeMinutes = c.int("snooze_minutes"),
                voiceSource = c.str("voice_source"),
                voiceText = c.str("voice_text"),
                voiceHeadphonesOnly = c.bool("voice_headphones_only"),
                task = c.str("kind") == "task",
                doneAt = c.longOrNull("done_at"),
                backlogAt = c.longOrNull("backlog_at"),
                description = c.str("description"),
                timeCommitment = c.str("time_commitment"),
                followsUp = c.strOrNull("follows_up_id"),
                sharedWith = shares[id] ?: emptySet(),
                assignee = c.str("assignee"),
                doneBy = c.strOrNull("done_by"),
                linkedFrom = c.strOrNull("linked_from"),
                linkedAssignee = c.str("linked_assignee"),
                linkedGone = c.bool("linked_gone"),
                linkedDoneAt = c.longOrNull("linked_done_at"),
                updatedAt = c.longOrNull("updated_at"),
            )
        }
    }
}

/**
 * Results: one row per check-in, day and time, and one per answer. [Slot] is a result as
 * stored, with when it was last changed (for syncing) and any fields this version doesn't
 * know. Their JSON (for backups and your other devices) is the shape Gist always used:
 * {"s": status, "a": {question: yes/no}, "v": {question: value}, "early", "soft", "w", ...}.
 */
internal object ResultRows {
    data class Slot(val result: SlotResult, val changedAt: Long? = null, val extra: JSONObject? = null)

    private val KNOWN = setOf("s", "a", "v", "early", "soft", "w", "rangAt", "took")

    fun put(w: SQLiteDatabase, p: String, checkinId: String, day: String, time: String, slot: Slot) {
        val key = arrayOf(p, checkinId, day, time)
        w.delete("slot_result", "profile_id = ? AND checkin_id = ? AND day = ? AND time = ?", key) // and its answers
        val r = slot.result
        w.insertOrThrow("slot_result", null, ContentValues().apply {
            put("profile_id", p)
            put("checkin_id", checkinId)
            put("day", day)
            put("time", time)
            put("status", r.status)
            putBool("early", r.early)
            putBool("soft", r.soft)
            put("rang_at", r.rangAt)
            r.took?.let { put("took_start_s", it.start); put("took_steps_s", it.steps) }
            put("changed_at", slot.changedAt)
            put("extra", slot.extra?.takeIf { it.length() > 0 }?.toString())
        })
        (r.answers.keys + r.values.keys).forEach { q ->
            w.insertOrThrow("answer", null, ContentValues().apply {
                put("profile_id", p)
                put("checkin_id", checkinId)
                put("day", day)
                put("time", time)
                put("question_id", q)
                r.answers[q]?.let { putBool("yes_no", it) }
                put("value", r.values[q])
            })
        }
    }

    fun delete(w: SQLiteDatabase, p: String, checkinId: String, day: String? = null, time: String? = null) {
        val where = StringBuilder("profile_id = ? AND checkin_id = ?")
        val args = mutableListOf(p, checkinId)
        day?.let { where.append(" AND day = ?"); args.add(it) }
        time?.let { where.append(" AND time = ?"); args.add(it) }
        w.delete("slot_result", where.toString(), args.toTypedArray())
    }

    /** Results matching [where] (on profile_id, checkin_id, day, time): checkin id → day → time → slot. */
    fun query(r: SQLiteDatabase, where: String, args: Array<String>): Map<String, Map<String, Map<String, Slot>>> {
        data class Key(val id: String, val day: String, val time: String)
        val answers = HashMap<Key, MutableMap<String, Boolean>>()
        val values = HashMap<Key, MutableMap<String, String>>()
        r.query("answer", null, where, args, null, null, null).rows { c ->
            val k = Key(c.str("checkin_id"), c.str("day"), c.str("time"))
            c.intOrNull("yes_no")?.let { answers.getOrPut(k) { mutableMapOf() }[c.str("question_id")] = it != 0 }
            c.strOrNull("value")?.let { values.getOrPut(k) { mutableMapOf() }[c.str("question_id")] = it }
        }
        val out = sortedMapOf<String, java.util.SortedMap<String, java.util.SortedMap<String, Slot>>>()
        r.query("slot_result", null, where, args, null, null, null).rows { c ->
            val k = Key(c.str("checkin_id"), c.str("day"), c.str("time"))
            val steps = c.intOrNull("took_steps_s")
            val result = SlotResult(
                status = c.str("status"),
                answers = answers[k] ?: emptyMap(),
                early = c.bool("early"),
                soft = c.bool("soft"),
                values = values[k] ?: emptyMap(),
                rangAt = c.longOrNull("rang_at"),
                took = steps?.let { Took(c.intOrNull("took_start_s"), it) },
            )
            val extra = c.strOrNull("extra")?.let { runCatching { JSONObject(it) }.getOrNull() }
            out.getOrPut(k.id) { sortedMapOf() }.getOrPut(k.day) { sortedMapOf() }[k.time] = Slot(result, c.longOrNull("changed_at"), extra)
        }
        return out
    }

    fun toJson(s: Slot): JSONObject {
        val r = s.result
        val o = JSONObject()
        s.extra?.let { e -> e.keys().forEach { k -> if (k !in KNOWN) o.put(k, e.get(k)) } }
        val a = JSONObject()
        r.answers.forEach { (k, v) -> a.put(k, v) }
        o.put("s", r.status).put("a", a).put("early", r.early)
        s.changedAt?.let { o.put("w", it) }
        if (r.soft) o.put("soft", true)
        if (r.values.isNotEmpty()) o.put("v", valuesToJson(r.values))
        r.rangAt?.let { o.put("rangAt", it) }
        r.took?.let { t -> o.put("took", JSONObject().apply { t.start?.let { put("start", it) }; put("steps", t.steps) }) }
        return o
    }

    fun fromJson(o: JSONObject): Slot {
        val a = o.optJSONObject("a")
        val answers = mutableMapOf<String, Boolean>()
        a?.keys()?.forEach { k -> answers[k] = a.optBoolean(k) }
        val took = o.optJSONObject("took")?.takeIf { it.has("steps") }?.let { t ->
            Took(if (t.has("start")) t.optInt("start") else null, t.optInt("steps"))
        }
        val result = SlotResult(o.optString("s", SlotResult.ANSWERED), answers, o.optBoolean("early", false),
            o.optBoolean("soft", false), valuesFromJson(o.optJSONObject("v")),
            if (o.has("rangAt")) o.optLong("rangAt") else null, took)
        val extra = JSONObject().apply { o.keys().forEach { k -> if (k !in KNOWN) put(k, o.get(k)) } }
        return Slot(result, if (o.has("w")) o.optLong("w") else null, extra.takeIf { it.length() > 0 })
    }

    /** Numbers are kept as numbers (so a backup reads `weight: 182.4`), anything else as text. */
    private fun valuesToJson(values: Values): JSONObject = JSONObject().apply {
        values.forEach { (k, v) ->
            val n = Question.cleanNumber(v)
            if (n != null && n == v) put(k, java.math.BigDecimal(n)) else put(k, v)
        }
    }

    private fun valuesFromJson(o: JSONObject?): Values {
        if (o == null) return emptyMap()
        val out = mutableMapOf<String, String>()
        o.keys().forEach { k ->
            when (val v = o.opt(k)) {
                is Number -> out[k] = java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
                is String -> out[k] = v
                else -> {}
            }
        }
        return out
    }
}

/** What's waiting to be answered, in the order it came. */
internal object PendingRows {
    fun write(w: SQLiteDatabase, p: String, list: List<Pending>) {
        w.delete("pending", "profile_id = ?", arrayOf(p))
        list.distinct().forEachIndexed { i, x ->
            w.insertOrThrow("pending", null, ContentValues().apply {
                put("profile_id", p)
                put("seq", i)
                put("checkin_id", x.checkinId)
                put("day", x.day)
                put("time", x.time)
                putBool("practice", x.practice)
            })
        }
    }

    fun read(r: SQLiteDatabase, p: String): List<Pending> =
        r.query("pending", null, "profile_id = ?", arrayOf(p), null, null, "seq").rows { c ->
            Pending(c.str("checkin_id"), c.str("day"), c.str("time"), c.bool("practice"))
        }
}

/** Check-ins deleted here or on your other devices: id → when. */
internal object DeletedRows {
    fun write(w: SQLiteDatabase, p: String, map: Map<String, Long>) {
        w.delete("deleted_item", "profile_id = ?", arrayOf(p))
        map.forEach { (id, at) ->
            w.insertOrThrow("deleted_item", null, ContentValues().apply {
                put("profile_id", p)
                put("item_id", id)
                put("deleted_at", at)
            })
        }
    }

    fun read(r: SQLiteDatabase, p: String): Map<String, Long> =
        r.query("deleted_item", arrayOf("item_id", "deleted_at"), "profile_id = ?", arrayOf(p), null, null, null)
            .rows { c -> c.str("item_id") to c.long("deleted_at") }.toMap()
}
