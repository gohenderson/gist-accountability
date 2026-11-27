package dev.gist.checkin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A question a check-in asks. [type]:
 *  - [YES_NO]: answered Yes or No; [yesIsGood] says which counts as "on track";
 *  - [NUMBER] / [TEXT]: you type a value (a weight, a note). Always optional: answering is
 *    on track, leaving it empty is off track. (A yes/no question changed to one of these
 *    keeps its old yes/no answers, judged by [yesIsGood], so its history doesn't change.)
 */
data class Question(val id: String, val text: String, val yesIsGood: Boolean = false, val type: String = YES_NO) {
    fun isGood(answer: Boolean): Boolean = answer == yesIsGood

    /** Answered by typing a value rather than Yes / No. */
    val takesValue: Boolean get() = type != YES_NO

    /** Was [r] (one time's result) on track for this question? null: it says nothing about it. */
    fun onTrack(r: SlotResult): Boolean? = when {
        !takesValue -> r.answers[id]?.let { isGood(it) }
        r.values.containsKey(id) -> r.values.getValue(id).isNotBlank() // "": asked and left empty
        else -> r.answers[id]?.let { isGood(it) } // answered back when it was yes/no; else not asked yet
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("yesIsGood", yesIsGood)
        .apply { if (type != YES_NO) put("type", type) }

    companion object {
        const val YES_NO = "yesNo"
        const val NUMBER = "number"
        const val TEXT = "text"
        val TYPES = listOf(YES_NO, NUMBER, TEXT)

        fun fromJson(o: JSONObject, id: String = o.optString("id", "")): Question =
            Question(id, o.optString("text", ""), o.optBoolean("yesIsGood", false),
                o.optString("type", YES_NO).takeIf { it in TYPES } ?: YES_NO)

        /** A typed number as Gist keeps it: "182.4", "-3", "1,5" → "1.5"; null if it isn't one. */
        fun cleanNumber(input: String): String? {
            val t = input.trim().replace(',', '.')
            if (!Regex("""[+-]?(\d+\.?\d*|\.\d+)""").matches(t)) return null
            return java.math.BigDecimal(t).stripTrailingZeros().toPlainString()
        }
    }
}

/** A time of day an alarm rings. */
/**
 * A time of day a check-in rings: a timed trigger. [days]: the days it rings on (null: the
 * check-in's [Checkin.repeat]). [where] / [place]: optionally only when you're at
 * ([Trigger.AT]) or away from ([Trigger.AWAY]) a place at that moment.
 */
data class AlarmTime(
    val hour: Int,
    val minute: Int,
    val days: Repeat? = null,
    val where: String = Trigger.ANYWHERE,
    val place: Place? = null,
) : Comparable<AlarmTime> {
    /** Does ringing depend on where you are? */
    val usesLocation: Boolean get() = where != Trigger.ANYWHERE && place != null

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
    val soundUri: String? = null,  // null = phone's default alarm sound, SILENT = vibrate only, QUIET = no alarm
    val soundTitle: String = "",   // shown in the app (and kept in backups)
    val background: Background = Background(), // shown while ringing; media in Backgrounds
    val events: List<Trigger> = emptyList(), // event triggers: arriving / leaving a place, running
    val category: String = "",               // for grouping on the start page; "" = none
    /**
     * How hard it holds you to it:
     *  - [STRICT] (default): the steps are the only way to stop it; counted in the stats.
     *  - [SOFT]: can be snoozed or dismissed without the steps, doesn't pull itself back to
     *    the front, and is left out of every stat (score, history, averages, CSV).
     *  - [VOICE]: no alarm at all: it speaks [voiceText] (or plays its recorded clip) through
     *    your headphones, e.g. encouragement 30 minutes into a run. Not in the stats either.
     */
    val commitment: String = STRICT,
    val snoozeMinutes: Int = DEFAULT_SNOOZE, // soft alarms only
    val voiceSource: String = VOICE_TEXT,    // voice only: VOICE_TEXT (spoken) or VOICE_CLIP (recorded)
    val voiceText: String = "",
    val voiceHeadphonesOnly: Boolean = true, // voice only: stay quiet when nothing's plugged in / paired
    /**
     * A one-time task ("Call John"): doing it once (finishing it, Do it now, or dismissing a
     * soft one) completes it: it turns off and moves to Done tasks. Its triggers are like any
     * check-in's (usually a time "On a specific date"); with none it's a backlog item that
     * waits on the start page until you do it. Left out of the overall score.
     */
    val task: Boolean = false,
    /** A task that's been done: when (epoch ms). Done tasks are off and listed apart. */
    val doneAt: Long? = null,
    /** When this task last went into the backlog (epoch ms); stamped by [Store.checkins]. */
    val backlogAt: Long? = null,
    /** Free text: what it's about, notes (several lines). */
    val description: String = "",
    /** Roughly how long it takes: one of [TIME_COMMITMENTS], or "" if not set. */
    val timeCommitment: String = "",
    /** A follow-up task: the id of the task it continues ("Sand the patch" → "Replace the liner"). */
    val followsUp: String? = null,
    /** Connections (Links) it's shared with, read-only: they see it, you change it. */
    val sharedWith: Set<String> = emptySet(),
    /**
     * A shared task of yours: who can do it. "" anyone it's shared with (and you); Store.ASSIGN_ME
     * only you; or a connection's id: only them.
     */
    val assignee: String = "",
    /** A task someone else finished for you: their name. */
    val doneBy: String? = null,
    /**
     * Added from a connection: "<link id>/<their id>". Its name and description stay theirs
     * (updated when they change them); everything else (alarms, times, steps) is yours.
     */
    val linkedFrom: String? = null,
    /** Who can do it, as they shared it: Store.LINK_ANYONE, LINK_YOU, LINK_OWNER or LINK_OTHER. */
    val linkedAssignee: String = "",
    /** They removed it (or stopped sharing it): it stays in your list, marked. */
    val linkedGone: Boolean = false,
    /** When it was last seen done on their side (to notice when that changes). */
    val linkedDoneAt: Long? = null,
    /** When it was last changed (epoch ms), stamped by Store.checkins: the later edit wins between your devices. */
    val updatedAt: Long? = null,
) {
    /** Added from a connection. */
    val isLinked: Boolean get() = linkedFrom != null
    val linkId: String? get() = linkedFrom?.substringBefore('/')
    val sourceId: String? get() = linkedFrom?.substringAfter('/')

    /** Something shared with you that you may do (tasks: anyone's, or assigned to you; check-ins: always). */
    val youCanDo: Boolean get() = !isTask || linkedAssignee == Store.LINK_ANYONE || linkedAssignee == Store.LINK_YOU ||
        linkedAssignee.isEmpty()

    val isTask: Boolean get() = task
    val taskDone: Boolean get() = task && doneAt != null
    /** A task that's off (and not done) waits in the backlog; on, it's active. */
    val inBacklog: Boolean get() = task && !enabled && doneAt == null

    val soft: Boolean get() = commitment == SOFT
    /** Reminds with a silent notification instead of an alarm ([QUIET]). */
    val quiet: Boolean get() = soundUri == QUIET
    val voice: Boolean get() = commitment == VOICE

    /** Only strict check-ins count in the stats. */
    val counted: Boolean get() = commitment == STRICT

    /** The days [t] rings on: its own, or the check-in's repeat rule. */
    fun daysFor(t: AlarmTime): Repeat = t.days ?: repeat

    /** Does any time have days of its own? */
    val perTimeDays: Boolean get() = times.any { it.days != null }

    /** Event triggers that watch a place. */
    val placeEvents: List<Trigger> get() = events.filter { it.isPlace }

    /** Event triggers that watch for running. */
    val runEvents: List<Trigger> get() = events.filter { it.isMovement }

    /** Does anything about this check-in need the phone's location? */
    val usesLocation: Boolean get() = times.any { it.usesLocation } || events.any { it.usesLocation }

    /** Has no triggers at all (it only rings when tested)? */
    val hasNoTriggers: Boolean get() = times.isEmpty() && events.isEmpty()

    /** Every question in its question steps, in order: these are what gets logged. */
    val questions: List<Question> get() = steps.filter { it.kind == Steps.QUESTIONS }.flatMap { it.questions }

    companion object {
        const val SILENT = "silent"
        /** No alarm at all: no sound, no vibration, no full screen; just a silent reminder notification. */
        const val QUIET = "quiet"

        const val STRICT = "strict"
        const val SOFT = "soft"
        const val VOICE = "voice"
        val COMMITMENTS = listOf(STRICT, SOFT, VOICE)
        const val VOICE_TEXT = "text"
        const val VOICE_CLIP = "clip"

        /** Snooze lengths a soft alarm can use, in minutes. */
        val SNOOZE_CHOICES = listOf(5, 10, 15, 20, 30)
        const val DEFAULT_SNOOZE = 10

        /** Time commitments, shortest first: key to name. */
        val TIME_COMMITMENTS = listOf(
            "minutes" to "A few minutes", "hour" to "An hour", "hours" to "A few hours",
            "day" to "A day", "days" to "A few days", "week" to "A week", "weeks" to "A few weeks",
            "month" to "A month", "months" to "A few months",
        )

        fun timeCommitmentName(key: String): String? = TIME_COMMITMENTS.firstOrNull { it.first == key }?.second
    }
}

/**
 * A check-in that has rung and is waiting for answers: which day the answers count for,
 * and which of the check-in's times ("HH:mm") rang. An empty [time] means its first time.
 * [practice]: a test ring from the edit page. It runs the steps like a real one but logs
 * nothing, so it never counts as (or closes) a real ring.
 */
data class Pending(val checkinId: String, val day: String, val time: String = "", val practice: Boolean = false)

/**
 * How long a check-in took, in seconds: [start] from the first ring to tapping Start check-in
 * ("getting started"; null when done early, before it rang), and [steps] from then to done
 * ("doing it").
 */
data class Took(val start: Int?, val steps: Int) {
    val total: Int get() = (start ?: 0) + steps

    companion object {
        /** "42s", "3m 05s", "1h 02m" */
        fun text(seconds: Int): String = when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "%dm %02ds".format(seconds / 60, seconds % 60)
            else -> "%dh %02dm".format(seconds / 3600, seconds / 60 % 60)
        }
    }
}

/** One time's answers: question id → answer (yes/no questions). */
typealias Answers = Map<String, Boolean>

/** One time's typed answers: question id → value (number and text questions); "" when left empty. */
typealias Values = Map<String, String>

/**
 * What happened with one check-in at one time on one day.
 * [status]: RANG (rang, not finished: counts as missed), ANSWERED (questions answered),
 * DONE (finished a check-in without questions), SKIPPED (skipped before it rang).
 * [early]: finished before the alarm time.
 */
data class SlotResult(
    val status: String,
    val answers: Answers = emptyMap(),
    val early: Boolean = false,
    val soft: Boolean = false, // logged by a soft or voice check-in: kept for scheduling, left out of stats
    val values: Values = emptyMap(),
    /** When it first rang (epoch ms), while it waits to be done; for [Took]. */
    val rangAt: Long? = null,
    /** How long it took, for check-ins that count (not soft ones, not tests). */
    val took: Took? = null,
) {
    /** Finished or skipped: the alarm for this time no longer needs to ring. */
    val closed: Boolean get() = status != RANG

    companion object {
        const val RANG = "rang"
        const val ANSWERED = "answered"
        const val DONE = "done"
        const val SKIPPED = "skipped"
        const val DISMISSED = "dismissed" // a soft alarm dismissed without its steps
    }
}

/**
 * One profile's check-ins and tasks, results, waiting list and settings, kept in the database
 * ([Db]): check-ins, results, the waiting list and deletions in their own tables, settings as
 * key-value rows ([DbPrefs], under [PREFS]).
 *
 * Results go to backups and your other devices as JSON, per day, check-in and time:
 *   { "2026-11-07": { "<checkinId>": { "21:30": { "s": "answered", "a": { "<questionId>": true },
 *                                                "v": { "<questionId>": 182.4 }, "early": false } } } }
 * ("v": typed answers, numbers as numbers and text as text, "" when left empty; only when there are some.
 *  "rangAt": when a waiting time first rang, epoch ms. "took": {"start": s, "steps": s}, how long a
 *  check-in that counts took: from ringing to Start check-in (none when done early), and doing it.
 *  "w": when it last changed, while syncing your devices.) See [ResultRows].
 */
class Store(context: Context) {
    private val db = Db.get(context)
    private val profile = Profiles.of(context)
    private val prefs = Profiles.prefs(context, PREFS)

    // ------------------------------------------------------------ check-ins

    /** Before any are saved: the examples (a fresh install's Morning and Evening). */
    var checkins: List<Checkin>
        get() {
            synchronized(db) {
                db.checkinCache[profile]?.let { return it }
                if (!prefs.getBoolean(K_CHECKINS_SAVED, false)) return DEFAULT_CHECKINS
                return CheckinRows.read(db.read, profile).also { db.checkinCache[profile] = it }
            }
        }
        set(v) = synchronized(db) { writeCheckins(v) }

    private fun writeCheckins(v: List<Checkin>) {
            // A task that's just gone into the backlog (new, or turned off) gets stamped,
            // unless the caller set the time itself (a restored backup).
            val saved = prefs.getBoolean(K_CHECKINS_SAVED, false)
            val was = if (saved) checkins.associateBy { it.id } else emptyMap()
            val now = System.currentTimeMillis()
            val list = v.map { c0 ->
                val old = was[c0.id]
                val entered = c0.inBacklog && old?.inBacklog != true && c0.backlogAt == old?.backlogAt
                var c = if (entered) c0.copy(backlogAt = now) else c0
                // Changed here (not arriving from another device with its own time): stamp it.
                if (tracking && c.updatedAt == old?.updatedAt && (old == null || c.copy(updatedAt = null) != old.copy(updatedAt = null))) {
                    c = c.copy(updatedAt = now)
                }
                c
            }.distinctBy { it.id }
            // Deleted: remembered, so your other devices delete it too.
            val gone = was.keys - v.map { it.id }.toSet()
            if (tracking && gone.isNotEmpty()) {
                val d = deleted.toMutableMap()
                gone.forEach { d.putIfAbsent(it, now) }
                deleted = d
            }
            // Reordered (not just added to or deleted from): your other devices take the new order.
            val reordered = tracking && run {
                val newIds = list.map { it.id }
                val kept = newIds.toSet()
                was.keys.filter { it in kept } != newIds.filter { it in was }
            }
            db.checkinCache.remove(profile)
            db.tx { w ->
                CheckinRows.write(w, profile, list)
                if (!saved) prefs.edit().putBoolean(K_CHECKINS_SAVED, true).commit()
                if (reordered) prefs.edit().putLong(K_ORDER_AT, now).commit()
            }
            db.checkinCache[profile] = list
    }

    /** When the order of your list last changed here (or on your other devices); 0 if never since syncing. */
    var orderAt: Long
        get() = prefs.getLong(K_ORDER_AT, 0)
        set(v) { prefs.edit().putLong(K_ORDER_AT, v).commit() }

    /**
     * Keeping track of when things change and what's deleted, for syncing your own devices
     * (on once they're set up: see Devices).
     */
    var tracking: Boolean
        get() = prefs.getBoolean(K_TRACKING, false)
        set(v) { prefs.edit().putBoolean(K_TRACKING, v).commit() }

    /** Check-ins deleted here or on your other devices: id → when (so they stay deleted everywhere). */
    var deleted: Map<String, Long>
        get() = DeletedRows.read(db.read, profile)
        set(v) { db.tx { DeletedRows.write(it, profile, v) } }

    /**
     * This device rings for check-ins (on by default). Off: they remind quietly here, like No
     * alarm, and wait on the start page, where you can still check in or dismiss them. Not
     * shared with your other devices.
     */
    var ringHere: Boolean
        get() = prefs.getBoolean(K_RING_HERE, true)
        set(v) { prefs.edit().putBoolean(K_RING_HERE, v).commit() }

    /** Tasks done in the last [days] days. */
    fun tasksDoneSince(days: Int): Int {
        val since = System.currentTimeMillis() - days * DAY_MS
        return checkins.count { it.taskDone && it.doneAt!! >= since }
    }

    /** Tasks that went into the backlog in the last [days] days (even if picked since). */
    fun tasksBackloggedSince(days: Int): Int {
        val since = System.currentTimeMillis() - days * DAY_MS
        return checkins.count { it.isTask && (it.backlogAt ?: 0) >= since }
    }

    fun checkin(id: String): Checkin? = checkins.firstOrNull { it.id == id }

    fun updateCheckin(id: String, change: (Checkin) -> Checkin) {
        checkins = checkins.map { if (it.id == id) change(it) else it }
    }

    // ------------------------------------------------------------ pending

    /**
     * What's waiting that the alarm deals with: everything except "no alarm" check-ins
     * (Checkin.QUIET), which wait quietly on the start page and in their own notification.
     */
    val alarmPending: List<Pending>
        get() = pending.filter { it.practice || (ringHere && checkin(it.checkinId)?.quiet != true) }

    var pending: List<Pending>
        get() = PendingRows.read(db.read, profile)
        set(v) { db.tx { PendingRows.write(it, profile, v) } }

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

    fun result(day: String, checkinId: String, time: String): SlotResult? = slot(day, checkinId, time)?.result

    private fun slot(day: String, checkinId: String, time: String): ResultRows.Slot? =
        ResultRows.query(db.read, "profile_id = ? AND checkin_id = ? AND day = ? AND time = ?", arrayOf(profile, checkinId, day, time))[checkinId]?.get(day)?.get(time)

    /** One check-in's results on one day, by time. */
    fun results(day: String, checkinId: String): Map<String, SlotResult> =
        ResultRows.query(db.read, "profile_id = ? AND checkin_id = ? AND day = ?", arrayOf(profile, checkinId, day))[checkinId]
            ?.get(day)?.mapValues { it.value.result } ?: emptyMap()

    /**
     * One check-in's results for every day, oldest first: day → time → result. This is what
     * the stats read, so soft and voice results are left out (unless [includeSoft]); a check-in
     * that isn't strict right now has no stats at all.
     */
    fun allResults(checkinId: String, includeSoft: Boolean = false): Map<String, Map<String, SlotResult>> {
        if (!includeSoft && checkin(checkinId)?.counted == false) return emptyMap()
        val all = ResultRows.query(db.read, "profile_id = ? AND checkin_id = ?", arrayOf(profile, checkinId))[checkinId] ?: return emptyMap()
        val out = sortedMapOf<String, Map<String, SlotResult>>()
        all.forEach { (day, slots) ->
            val r = slots.mapValues { it.value.result }.filterValues { includeSoft || !it.soft }
            if (r.isNotEmpty()) out[day] = r
        }
        return out
    }

    /** Is this time finished or skipped (so its alarm shouldn't ring)? */
    fun isClosed(day: String, checkinId: String, time: String): Boolean =
        result(day, checkinId, time)?.closed == true

    /** Answers for one time (merged into any already saved for it). */
    fun saveAnswers(day: String, checkinId: String, time: String, answers: Answers, early: Boolean = false,
                    values: Values = emptyMap(), took: Took? = null) {
        val prev = result(day, checkinId, time)
        val merged = (prev?.answers ?: emptyMap()) + answers
        // An empty value ("" : asked, left empty) replaces one saved before (fixing a day in History).
        val mergedValues = (prev?.values ?: emptyMap()) + values
        // Fixing a day in History keeps how long it took the first time.
        put(day, checkinId, time, SlotResult(SlotResult.ANSWERED, merged, early || prev?.early == true,
            values = mergedValues, took = took ?: prev?.took))
    }

    /** A check-in without questions was completed. */
    fun markDone(day: String, checkinId: String, time: String, early: Boolean = false, took: Took? = null) =
        put(day, checkinId, time, SlotResult(SlotResult.DONE, early = early, took = took))

    /** The alarm rang (at [at]). Doesn't overwrite a time that's already rung, finished or skipped. */
    fun markRang(day: String, checkinId: String, time: String, at: Long = System.currentTimeMillis()) {
        if (result(day, checkinId, time) == null) put(day, checkinId, time, SlotResult(SlotResult.RANG, rangAt = at))
    }

    fun markSkipped(day: String, checkinId: String, time: String) =
        put(day, checkinId, time, SlotResult(SlotResult.SKIPPED))

    /** A soft alarm was dismissed without doing its steps. */
    fun markDismissed(day: String, checkinId: String, time: String) =
        put(day, checkinId, time, SlotResult(SlotResult.DISMISSED))

    /** Is [p]'s check-in a soft alarm? */
    fun isSoft(p: Pending): Boolean = checkin(p.checkinId)?.soft == true

    /** Forget a time's result (e.g. undo a skip). */
    fun clearResult(day: String, checkinId: String, time: String) {
        db.tx { ResultRows.delete(it, profile, checkinId, day, time) }
    }

    /** Marks task [id] done now: it turns off (no more rings) and moves to Done tasks. */
    fun completeTask(id: String, at: Long = System.currentTimeMillis()) {
        updateCheckin(id) { if (it.isTask) it.copy(enabled = false, doneAt = at) else it }
        pending = pending.filter { it.checkinId != id || it.practice }
    }

    /**
     * A follow-up to task [sourceId]: a new task that continues it, with its name, description,
     * category, time commitment, commitment, sound, snooze and steps (fresh ids), but no
     * triggers (you set when). [active]: on today's list, or in the backlog. Null if there's
     * no such check-in.
     */
    fun createFollowUp(sourceId: String, active: Boolean, newId: String = Store.newId()): Checkin? {
        val src = checkin(sourceId) ?: return null
        val c = Checkin(
            id = newId,
            name = followUpName(src.name),
            times = emptyList(),
            enabled = active,
            steps = src.steps.map { it.copy(id = Store.newId()) },
            soundUri = src.soundUri,
            soundTitle = src.soundTitle,
            category = src.category,
            commitment = src.commitment,
            snoozeMinutes = src.snoozeMinutes,
            voiceSource = src.voiceSource,
            voiceText = src.voiceText,
            voiceHeadphonesOnly = src.voiceHeadphonesOnly,
            task = true,
            description = src.description,
            timeCommitment = src.timeCommitment,
            followsUp = src.id,
        )
        // Right after the task it follows, so My order keeps them together.
        val list = checkins.toMutableList()
        list.add(list.indexOfFirst { it.id == sourceId } + 1, c)
        checkins = list
        return c
    }

    /**
     * "(Follow-Up) Sand the patch": the prefix makes it plain you're on the new task, not the
     * old one. A follow-up of a follow-up gets just the one prefix.
     */
    fun followUpName(name: String): String = FOLLOW_UP_PREFIX + name.removePrefix(FOLLOW_UP_PREFIX)

    /** Tasks that follow up [id]. */
    fun followUpsOf(id: String): List<Checkin> = checkins.filter { it.followsUp == id }

    /** Puts done task [id] back on the list (on again; change its date if it has passed). */
    fun reopenTask(id: String) = updateCheckin(id) { if (it.isTask) it.copy(enabled = true, doneAt = null, doneBy = null) else it }

    /** Every answer on [day], all check-ins and times merged. Handy for tests. */
    fun entry(day: String): Answers? {
        val d = ResultRows.query(db.read, "profile_id = ? AND day = ?", arrayOf(profile, day))
        var merged: Answers = emptyMap()
        d.values.forEach { days -> days[day]?.values?.forEach { merged = merged + it.result.answers } }
        return merged.ifEmpty { null }
    }

    /** Results logged while a check-in isn't strict are marked soft (see [allResults]). */
    private fun put(day: String, checkinId: String, time: String, r: SlotResult) {
        val soft = r.soft || checkin(checkinId)?.counted == false
        // When: the later answer wins between your devices.
        val slot = ResultRows.Slot(r.copy(soft = soft), if (tracking) System.currentTimeMillis() else null)
        db.tx { ResultRows.put(it, profile, checkinId, day, time, slot) }
        // Doing a task once completes it.
        if (r.status in TASK_DONE_STATUSES) {
            checkin(checkinId)?.takeIf { it.isTask && it.doneAt == null }?.let { completeTask(it.id) }
        }
    }

    // ------------------------------------------------------------ settings

    /** THEME_SYSTEM, THEME_LIGHT or THEME_DARK. */
    var theme: Int
        get() = prefs.getInt(K_THEME, THEME_SYSTEM)
        set(v) = putSetting(K_THEME) { putInt(K_THEME, v) }

    /** Start page view on the current tab: Grouping.UNGROUPED or Grouping.BY_CATEGORY. */
    var view: String
        get() = viewFor(startTab)
        set(v) { setViewFor(startTab, v) }

    /** Each tab has its own view and sort (the Check-ins tab keeps the original keys). */
    private fun tabKey(key: String, tab: String) = if (tab == TAB_CHECKINS) key else "$key.$tab"

    fun viewFor(tab: String): String =
        prefs.getString(tabKey(K_VIEW, tab), Grouping.UNGROUPED) ?: Grouping.UNGROUPED

    fun setViewFor(tab: String, v: String) = tabKey(K_VIEW, tab).let { k -> putSetting(k) { putString(k, v) } }

    /** The tab's sort: one of Grouping.SORTS (My order by default, so tasks keep your priority). */
    fun sortFor(tab: String): String =
        prefs.getString(tabKey(K_SORT, tab), null)?.takeIf { it in Grouping.SORTS } ?: Grouping.SORT_CUSTOM

    fun setSortFor(tab: String, v: String) = tabKey(K_SORT, tab).let { k -> putSetting(k) { putString(k, v) } }

    /**
     * The start page's tab: [TAB_CHECKINS] (everything that isn't a task), [TAB_TASKS] (active
     * tasks, and the Done tasks button) or [TAB_BACKLOG] (tasks that are off).
     */
    var startTab: String
        get() = prefs.getString(K_START_TAB, TAB_CHECKINS)?.takeIf { it in TABS } ?: TAB_CHECKINS
        set(v) { prefs.edit().putString(K_START_TAB, v).commit() }

    /** The backlog: tasks that are off and not done, in your order. */
    val backlog: List<Checkin> get() = checkins.filter { it.inBacklog }

    /** Active tasks: on and not done. */
    val activeTasks: List<Checkin> get() = checkins.filter { it.task && it.enabled && it.doneAt == null }

    /** Start page order on the current tab: one of Grouping.SORTS (My order by default). */
    var sort: String
        get() = sortFor(startTab)
        set(v) { setSortFor(startTab, v) }

    /**
     * The one check-in whose card is open on the start page; the rest are folded to a line.
     * None by default, so new ones start folded.
     */
    var expandedCheckin: String?
        get() = prefs.getString(K_EXPANDED_CHECKIN, null)
        set(v) {
            prefs.edit().apply { if (v == null) remove(K_EXPANDED_CHECKIN) else putString(K_EXPANDED_CHECKIN, v) }
                .remove(K_COLLAPSED_CHECKINS).remove(K_STYLE).commit() // the old per-card settings
        }

    /** The order of category groups (Grouping.key of each), as you've dragged them. */
    var groupOrder: List<String>
        get() = prefs.getString(K_GROUP_ORDER, "")!!.split('\n').filter { it.isNotEmpty() }
        set(v) = putSetting(K_GROUP_ORDER) { putString(K_GROUP_ORDER, v.joinToString("\n")) }

    /** Collapsed category groups (Grouping.key of each). */
    var collapsed: Set<String>
        get() = prefs.getStringSet(K_COLLAPSED, emptySet())!!.toSet()
        set(v) = putSetting(K_COLLAPSED) { putStringSet(K_COLLAPSED, v.toSet()) }

    /** Puts the check-ins in the order of [ids] (any not listed keep their place at the end). */
    fun reorder(ids: List<String>) {
        val byId = checkins.associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] }
        checkins = ordered + checkins.filter { it.id !in ids }
    }

    /** How long the alarm stays quiet after each tap while you work through a check-in. */
    var pauseSeconds: Int
        get() = prefs.getInt(K_PAUSE, DEFAULT_PAUSE).takeIf { it in PAUSE_CHOICES } ?: DEFAULT_PAUSE
        set(v) = putSetting(K_PAUSE) { putInt(K_PAUSE, v) }

    /** Your name as your connections see it (shared by your devices). */
    var linkName: String
        get() = prefs.getString(K_LINK_NAME, "") ?: ""
        set(v) = putSetting(K_LINK_NAME) { putString(K_LINK_NAME, v) }

    // ------------------------------------------------------------ settings on your other devices

    /**
     * Writes setting [key]; when it changes while syncing, stamps it so your other devices
     * take it (the later change of each setting wins, one setting at a time). Settings about
     * this device or where you are on it (Ring on this device, the open tab and card) aren't
     * shared: see [SYNCED_SETTINGS].
     */
    private fun putSetting(key: String, put: android.content.SharedPreferences.Editor.() -> Unit) {
        val before = settingJson(key)
        val e = prefs.edit().apply(put)
        e.commit()
        if (tracking && settingJson(key) != before) setSettingAt(key, System.currentTimeMillis())
    }

    private fun settingsAt(): Map<String, Long> = prefs.getString(K_SETTINGS_AT, null)?.let { s ->
        try { JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } } catch (e: Exception) { null }
    } ?: emptyMap()

    private fun setSettingAt(key: String, at: Long) {
        prefs.edit().putString(K_SETTINGS_AT, JSONObject((settingsAt() + (key to at)) as Map<*, *>).toString()).commit()
    }

    /** Setting [key]'s value as JSON (a number, text, or list for a set), or null when not set. */
    private fun settingJson(key: String): String? {
        if (!prefs.contains(key)) return null
        return when (val v = prefs.all[key]) {
            is Set<*> -> JSONArray(v.map { it.toString() }.sorted()).toString()
            is String -> JSONObject.quote(v)
            null -> null
            else -> v.toString()
        }
    }

    /** The shared settings that have changed since syncing: key → (value as JSON, when). */
    fun syncedSettings(): Map<String, Pair<String, Long>> {
        val at = settingsAt()
        return SYNCED_SETTINGS.mapNotNull { k ->
            val t = at[k] ?: return@mapNotNull null
            val v = settingJson(k) ?: return@mapNotNull null
            k to (v to t)
        }.toMap()
    }

    /** Takes setting [key] from another device, if it changed there after here. True when it did. */
    fun takeSetting(key: String, json: String, at: Long): Boolean {
        if (key !in SYNCED_SETTINGS || at <= (settingsAt()[key] ?: 0)) return false
        val e = prefs.edit()
        try {
            when (key) {
                K_THEME, K_PAUSE -> e.putInt(key, json.toInt())
                K_COLLAPSED -> e.putStringSet(key, JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                else -> e.putString(key, JSONArray("[$json]").getString(0))
            }
        } catch (ex: Exception) { return false }
        e.commit()
        setSettingAt(key, at)
        return true
    }

    /**
     * Starting to sync (as the first device): your list's order and the shared settings
     * you've set count as changed now, so the devices that join take them.
     */
    fun stampForSync(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(K_ORDER_AT, now).commit()
        SYNCED_SETTINGS.filter { prefs.contains(it) }.forEach { setSettingAt(it, now) }
    }

    // ------------------------------------------------------------ backup support

    /** All results as JSON (day → check-in → time → result), as backups and your devices have them. */
    fun entriesJson(): JSONObject {
        val out = JSONObject()
        ResultRows.query(db.read, "profile_id = ?", arrayOf(profile)).forEach { (id, days) ->
            days.forEach { (day, slots) ->
                val d = out.optJSONObject(day) ?: JSONObject().also { out.put(day, it) }
                d.put(id, JSONObject().apply { slots.forEach { (time, slot) -> put(time, ResultRows.toJson(slot)) } })
            }
        }
        return out
    }

    /** One time's result as JSON (with when it changed, for syncing); null if none. */
    fun slotJson(day: String, checkinId: String, time: String): JSONObject? = slot(day, checkinId, time)?.let { ResultRows.toJson(it) }

    /** Writes results from JSON as they are, each at its check-in, day and time ([Pending] as the key). */
    fun putSlotsJson(slots: List<Pair<Pending, JSONObject>>) {
        db.tx { w -> slots.forEach { (k, json) -> ResultRows.put(w, profile, k.checkinId, k.day, k.time, ResultRows.fromJson(json)) } }
    }

    /** One check-in's history as JSON: day → time → result. */
    fun historyJson(checkinId: String): JSONObject {
        val out = JSONObject()
        ResultRows.query(db.read, "profile_id = ? AND checkin_id = ?", arrayOf(profile, checkinId))[checkinId]?.forEach { (day, slots) ->
            out.put(day, JSONObject().apply { slots.forEach { (time, slot) -> put(time, ResultRows.toJson(slot)) } })
        }
        return out
    }

    /** Forgets everything [checkinId] logged. */
    fun deleteHistory(checkinId: String) {
        db.tx { ResultRows.delete(it, profile, checkinId) }
    }

    /**
     * Writes [history] (day → time → result) for [checkinId]. With [merge], times already
     * logged and not in [history] are kept; without it, the check-in's history is replaced.
     */
    fun putHistory(checkinId: String, history: JSONObject, merge: Boolean) {
        db.tx { w ->
            if (!merge) ResultRows.delete(w, profile, checkinId)
            history.keys().forEach { day ->
                val slots = history.optJSONObject(day) ?: return@forEach
                slots.keys().forEach { time ->
                    slots.optJSONObject(time)?.let { ResultRows.put(w, profile, checkinId, day, time, ResultRows.fromJson(it)) }
                }
            }
        }
    }

    /** Deletes this profile's check-ins, results, waiting list, deletions and settings (for tests). */
    internal fun wipe() = synchronized(db) {
        db.tx { w -> listOf("checkin", "slot_result", "pending", "deleted_item").forEach { w.delete(it, "profile_id = ?", arrayOf(profile)) } }
        prefs.edit().clear().commit()
        db.checkinCache.remove(profile)
    }

    fun checkinFromJson(o: JSONObject): Checkin = parseCheckin(o)

    fun checkinToJson(c: Checkin): JSONObject = checkinJson(c)

    companion object {
        private fun questionFromJson(o: JSONObject) = Question.fromJson(o, o.getString("id"))

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

        fun parseCheckin(o: JSONObject): Checkin {
            // Each time is "HH:mm", or {"t": "HH:mm", "days": {…}} when it has its own days.
            val ts = o.optJSONArray("times") ?: JSONArray()
            val c = Checkin(
                id = o.getString("id"),
                name = o.optString("name", "Check-in"),
                times = (0 until ts.length()).mapNotNull { i ->
                    val obj = ts.optJSONObject(i)
                    if (obj != null) {
                        AlarmTime.parse(obj.optString("t"))?.copy(
                            days = obj.optJSONObject("days")?.let { Repeat.fromJson(it) },
                            where = obj.optString("where", Trigger.ANYWHERE).takeIf { it in setOf(Trigger.AT, Trigger.AWAY) } ?: Trigger.ANYWHERE,
                            place = Place.fromJson(obj.optJSONObject("place")),
                        )
                    } else {
                        AlarmTime.parse(ts.optString(i))
                    }
                }.distinct().sorted(),
                repeat = Repeat.fromJson(o.optJSONObject("repeat")),
                enabled = o.optBoolean("enabled", true),
                aboutYesterday = o.optBoolean("aboutYesterday", false),
                steps = stepsFromJson(o),
                soundUri = o.optString("soundUri", "").ifEmpty { null },
                soundTitle = o.optString("soundTitle", ""),
                background = Background.fromJson(o.optJSONObject("background")),
                events = (o.optJSONArray("events") ?: JSONArray()).let { a ->
                    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Trigger.fromJson(it) }.filter { it.isEvent }
                },
                category = o.optString("category", "").trim(),
                commitment = o.optString("commitment", "").takeIf { it in Checkin.COMMITMENTS }
                    ?: if (o.optBoolean("soft", false)) Checkin.SOFT else Checkin.STRICT,
                voiceSource = if (o.optString("voiceSource") == Checkin.VOICE_CLIP) Checkin.VOICE_CLIP else Checkin.VOICE_TEXT,
                voiceText = o.optString("voiceText", ""),
                voiceHeadphonesOnly = o.optBoolean("voiceHeadphonesOnly", true),
                snoozeMinutes = o.optInt("snoozeMinutes", Checkin.DEFAULT_SNOOZE).takeIf { it in Checkin.SNOOZE_CHOICES } ?: Checkin.DEFAULT_SNOOZE,
                description = o.optString("description", ""),
                timeCommitment = o.optString("timeCommitment", "").takeIf { Checkin.timeCommitmentName(it) != null } ?: "",
                followsUp = o.optString("followsUp", "").ifEmpty { null },
                sharedWith = o.optJSONArray("sharedWith")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
                assignee = o.optString("assignee", ""),
                doneBy = o.optString("doneBy", "").ifEmpty { null },
                linkedFrom = o.optString("linkedFrom", "").ifEmpty { null },
                linkedAssignee = o.optString("linkedAssignee", ""),
                linkedGone = o.optBoolean("linkedGone", false),
                linkedDoneAt = if (o.has("linkedDoneAt")) o.optLong("linkedDoneAt") else null,
                updatedAt = if (o.has("updatedAt")) o.optLong("updatedAt") else null,
                task = o.optBoolean("task", false),
                doneAt = if (o.has("doneAt")) o.optLong("doneAt") else null,
                backlogAt = if (o.has("backlogAt")) o.optLong("backlogAt") else null,
            ).let { c ->
                // The first tasks were a check-in repeating "once" on a date: now a flag, with the
                // date on each of its times ("On a specific date").
                if (c.repeat.kind != Repeat.ONCE) c
                else c.copy(task = true, repeat = Repeat.EVERY_DAY, times = c.times.map { it.copy(days = c.repeat) })
            }
            // Saved before a check-in could have several kinds of trigger: one "trigger" applied
            // to the whole check-in. An event replaced the times; a place condition applied to all.
            val legacy = o.optJSONObject("trigger")
            if (legacy == null || o.has("events")) return c
            val t = Trigger.fromJson(legacy)
            return when {
                t.isEvent -> c.copy(times = emptyList(), events = listOf(t.copy(days = c.repeat)))
                t.where != Trigger.ANYWHERE && t.place != null -> c.copy(times = c.times.map { it.copy(where = t.where, place = t.place) })
                else -> c
            }
        }

        fun checkinJson(c: Checkin): JSONObject {
            return JSONObject()
                .put("id", c.id)
                .put("name", c.name)
                .put("times", JSONArray().apply {
                    c.times.forEach { t ->
                        if (t.days == null && !t.usesLocation) {
                            put(t.toString())
                        } else {
                            put(JSONObject().put("t", t.toString()).apply {
                                t.days?.let { put("days", it.toJson()) }
                                if (t.usesLocation) put("where", t.where).put("place", t.place!!.toJson())
                            })
                        }
                    }
                })
                .put("repeat", c.repeat.toJson())
                .put("enabled", c.enabled)
                .put("aboutYesterday", c.aboutYesterday)
                .put("soundUri", c.soundUri ?: "")
                .put("soundTitle", c.soundTitle)
                .put("background", c.background.toJson())
                .put("steps", JSONArray().apply { c.steps.forEach { put(it.toJson()) } })
                .put("events", JSONArray().apply { c.events.forEach { put(it.toJson()) } })
                .put("category", c.category)
                .put("commitment", c.commitment)
                .put("voiceSource", c.voiceSource)
                .put("voiceText", c.voiceText)
                .put("voiceHeadphonesOnly", c.voiceHeadphonesOnly)
                .put("snoozeMinutes", c.snoozeMinutes)
                .apply {
                    // Only when set; apps that don't know them just skip them.
                    if (c.description.isNotEmpty()) put("description", c.description)
                    if (c.timeCommitment.isNotEmpty()) put("timeCommitment", c.timeCommitment)
                    c.followsUp?.let { put("followsUp", it) }
                    if (c.sharedWith.isNotEmpty()) put("sharedWith", JSONArray(c.sharedWith.sorted()))
                    if (c.assignee.isNotEmpty()) put("assignee", c.assignee)
                    c.doneBy?.let { put("doneBy", it) }
                    c.linkedFrom?.let { put("linkedFrom", it) }
                    if (c.linkedAssignee.isNotEmpty()) put("linkedAssignee", c.linkedAssignee)
                    if (c.linkedGone) put("linkedGone", true)
                    c.linkedDoneAt?.let { put("linkedDoneAt", it) }
                    c.updatedAt?.let { put("updatedAt", it) }
                }
                .apply {
                    if (c.task) put("task", true)
                    c.doneAt?.let { put("doneAt", it) }
                    if (c.task) c.backlogAt?.let { put("backlogAt", it) }
                }
        }

        private const val DAY_MS = 24L * 60 * 60 * 1000
        const val FOLLOW_UP_PREFIX = "(Follow-Up) "
        /** Checkin.assignee: only you. */
        const val ASSIGN_ME = "me"
        /** Checkin.linkedAssignee, as the other side sees a shared task. */
        const val LINK_ANYONE = "anyone"
        const val LINK_YOU = "you"
        const val LINK_OWNER = "owner"
        const val LINK_OTHER = "other"
        /** Results that complete a task: done, answered, or (soft) dismissed. */
        private val TASK_DONE_STATUSES = setOf(SlotResult.DONE, SlotResult.ANSWERED, SlotResult.DISMISSED)
        /** The settings' name in [DbPrefs] (and the old preferences file's). */
        const val PREFS = "gist"
        /** Where the old preferences file kept these (see LegacyImport). */
        internal const val K_CHECKINS = "checkins"
        internal const val K_PENDING = "pendingList"
        internal const val K_ENTRIES = "results"
        /** Set once check-ins have been saved (until then: the examples). */
        internal const val K_CHECKINS_SAVED = "checkinsSaved"
        private const val K_THEME = "theme"
        private const val K_PAUSE = "pauseSeconds"
        private const val K_VIEW = "view"
        private const val K_SORT = "sort"
        private const val K_START_TAB = "startTab"
        const val TAB_CHECKINS = "checkins"
        const val TAB_TASKS = "tasks"
        const val TAB_BACKLOG = "backlog"
        val TABS = listOf(TAB_CHECKINS, TAB_TASKS, TAB_BACKLOG)
        /** The old Cards / Compact layout setting, read once to carry it over. */
        private const val K_STYLE = "style"
        internal const val K_DELETED = "deleted"
        private const val K_RING_HERE = "ringHere"
        private const val K_TRACKING = "trackChanges"
        private const val K_EXPANDED_CHECKIN = "expandedCheckin"
        private const val K_COLLAPSED_CHECKINS = "collapsedCheckins"
        private const val K_GROUP_ORDER = "groupOrder"
        private const val K_COLLAPSED = "collapsedGroups"
        private const val K_ORDER_AT = "orderAt"
        private const val K_LINK_NAME = "linkName"
        private const val K_SETTINGS_AT = "settingsAt"
        /**
         * Settings kept in step on your devices. Not these: Ring on this device, the open tab
         * and card, and syncing itself.
         */
        val SYNCED_SETTINGS = listOf(K_THEME, K_PAUSE, K_GROUP_ORDER, K_COLLAPSED, K_LINK_NAME) +
            TABS.flatMap { tab -> listOf(K_VIEW, K_SORT).map { if (tab == TAB_CHECKINS) it else "$it.$tab" } }

        const val DEFAULT_PAUSE = 45

        val PAUSE_CHOICES = listOf(15, 30, 45, 60)

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
