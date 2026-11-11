package dev.gist.checkin

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A place on the map: a circle of [radiusM] metres around [lat], [lon]. */
data class Place(val name: String, val lat: Double, val lon: Double, val radiusM: Int = DEFAULT_RADIUS) {
    fun contains(lat: Double, lon: Double): Boolean = Trigger.distanceM(this.lat, this.lon, lat, lon) <= radiusM

    fun toJson(): JSONObject = JSONObject().put("name", name).put("lat", lat).put("lon", lon).put("radiusM", radiusM)

    companion object {
        const val DEFAULT_RADIUS = 200
        val RADII = listOf(100, 200, 500, 1000)

        fun fromJson(o: JSONObject?): Place? {
            if (o == null || !o.has("lat") || !o.has("lon")) return null
            val lat = o.optDouble("lat")
            val lon = o.optDouble("lon")
            if (!valid(lat, lon)) return null
            return Place(o.optString("name", "Place"), lat, lon, o.optInt("radiusM", DEFAULT_RADIUS).coerceIn(50, 5000))
        }

        fun valid(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0 && !lat.isNaN() && !lon.isNaN()

        /** "40.7128, -74.0060" (as copied from a maps app) → (lat, lon), or null. */
        fun parseCoordinates(text: String): Pair<Double, Double>? {
            val parts = text.trim().removePrefix("(").removeSuffix(")").split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }
            if (parts.size != 2) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lon = parts[1].toDoubleOrNull() ?: return null
            return if (valid(lat, lon)) lat to lon else null
        }
    }
}

/**
 * What makes a check-in ring, and the conditions that must all hold at that moment:
 *  - [TIME]: at its times on the days its repeat rule allows (the classic alarm), and,
 *    optionally, only when you're [AT] or [AWAY] from [place] at that time;
 *  - [ARRIVE] / [LEAVE]: when you enter or leave [place];
 *  - [RUN_START] / [RUN_END]: when you start running, or finish a run of at least
 *    [minRunMinutes] (from the phone's step sensor, see [RunDetector]);
 *  - [RUN_FOR]: once [minRunMinutes] into a run (e.g. a voice cheering you on at 30 minutes);
 * and, for those events, only on the days the repeat rule allows, only between
 * [windowStart] and [windowEnd] (if set), and at most once a day if [oncePerDay].
 * Location and steps are read on the phone; nothing is sent anywhere.
 */
data class Trigger(
    val kind: String = TIME,
    val place: Place? = null,
    val where: String = ANYWHERE,          // TIME only
    val windowStart: AlarmTime? = null,    // ARRIVE / LEAVE only
    val windowEnd: AlarmTime? = null,
    val oncePerDay: Boolean = true,
    val minRunMinutes: Int = 0,            // RUN_END: the shortest run that counts; RUN_FOR: how far in
    val days: Repeat = Repeat.EVERY_DAY,   // the days this event trigger is active
) {
    val isPlace: Boolean get() = kind == ARRIVE || kind == LEAVE
    val isMovement: Boolean get() = kind == RUN_START || kind == RUN_END || kind == RUN_FOR

    /** Rings on an event (place or movement) rather than at set times. */
    val isEvent: Boolean get() = isPlace || isMovement

    /** Does this trigger need location at all? */
    val usesLocation: Boolean get() = (isPlace || (kind == TIME && where != ANYWHERE)) && place != null

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("where", where)
        .put("oncePerDay", oncePerDay)
        .put("minRunMinutes", minRunMinutes)
        .put("days", days.toJson())
        .apply {
            place?.let { put("place", it.toJson()) }
            windowStart?.let { put("windowStart", it.toString()) }
            windowEnd?.let { put("windowEnd", it.toString()) }
        }

    companion object {
        const val TIME = "time"
        const val ARRIVE = "arrive"
        const val LEAVE = "leave"
        const val RUN_START = "run-start"
        const val RUN_END = "run-end"
        const val RUN_FOR = "run-for"
        val KINDS = setOf(TIME, ARRIVE, LEAVE, RUN_START, RUN_END, RUN_FOR)

        /** A new "after N minutes of running" trigger starts at this. */
        const val DEFAULT_RUN_FOR = 30
        const val ANYWHERE = "anywhere"
        const val AT = "at"
        const val AWAY = "away"

        val DEFAULT = Trigger()

        fun fromJson(o: JSONObject?): Trigger {
            if (o == null) return DEFAULT
            val kind = o.optString("kind", TIME).takeIf { it in KINDS } ?: TIME
            return Trigger(
                kind = kind,
                place = Place.fromJson(o.optJSONObject("place")),
                where = o.optString("where", ANYWHERE).takeIf { it in setOf(ANYWHERE, AT, AWAY) } ?: ANYWHERE,
                windowStart = AlarmTime.parse(o.optString("windowStart", "")),
                windowEnd = AlarmTime.parse(o.optString("windowEnd", "")),
                oncePerDay = o.optBoolean("oncePerDay", true),
                minRunMinutes = o.optInt("minRunMinutes", 0).coerceIn(0, 600),
                days = Repeat.fromJson(o.optJSONObject("days")),
            )
        }

        /** Great-circle distance in metres. */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }

        /** Is [now] inside the window? No window = any time. A window like 22:00–02:00 crosses midnight. */
        fun inWindow(t: Trigger, now: LocalTime): Boolean {
            val s = t.windowStart ?: return true
            val e = t.windowEnd ?: return true
            val n = now.hour * 60 + now.minute
            val sm = s.hour * 60 + s.minute
            val em = e.hour * 60 + e.minute
            return if (sm <= em) n in sm..em else n >= sm || n <= em
        }

        /**
         * Should entering ([entering] = true) or leaving [c]'s place ring it now? [rangToday]:
         * it already rang (or was answered) today.
         */
        fun placeEventRings(c: Checkin, t: Trigger, entering: Boolean, now: LocalDateTime, rangToday: Boolean): Boolean {
            if (!c.enabled || !t.isPlace || t.place == null) return false
            if ((t.kind == ARRIVE) != entering) return false
            return conditionsHold(t, now, rangToday)
        }

        /**
         * Should [c] ring for a run that just started ([finishedMinutes] = null) or just finished
         * after [finishedMinutes] minutes?
         */
        fun runEventRings(c: Checkin, t: Trigger, finishedMinutes: Int?, now: LocalDateTime, rangToday: Boolean): Boolean {
            if (!c.enabled) return false
            when (t.kind) {
                RUN_START -> if (finishedMinutes != null) return false
                RUN_END -> if (finishedMinutes == null || finishedMinutes < t.minRunMinutes) return false
                else -> return false
            }
            return conditionsHold(t, now, rangToday)
        }

        /**
         * Should [c] ring now that the current run has lasted [minutes]? Each milestone fires once
         * per run: MovementWatch remembers which already did.
         */
        fun runProgressRings(c: Checkin, t: Trigger, minutes: Int, now: LocalDateTime, rangToday: Boolean): Boolean {
            if (!c.enabled || t.kind != RUN_FOR || minutes < t.minRunMinutes.coerceAtLeast(1)) return false
            return conditionsHold(t, now, rangToday)
        }

        /** Days, time window and once a day: shared by all event triggers. */
        fun conditionsHold(t: Trigger, now: LocalDateTime, rangToday: Boolean): Boolean {
            if (!t.days.matches(now.toLocalDate())) return false
            if (!inWindow(t, now.toLocalTime())) return false
            if (t.oncePerDay && rangToday) return false
            return true
        }

        /**
         * For a timed check-in with a place condition: ring given you're [distanceM] from the
         * place? Unknown location (null) rings anyway: better a ring too many than a missed one.
         */
        fun locationAllows(t: Trigger, distanceM: Double?): Boolean = locationAllows(t.where, t.place, distanceM)

        /** Same, for a timed trigger's own "where". */
        fun locationAllows(where: String, place: Place?, distanceM: Double?): Boolean {
            val p = place ?: return true
            if (where == ANYWHERE || distanceM == null) return true
            val inside = distanceM <= p.radiusM
            return if (where == AT) inside else !inside
        }

        /** "anywhere", "at Home", "away from Home" */
        fun whereText(where: String, place: Place?): String = when {
            place == null || where == ANYWHERE -> "anywhere"
            where == AT -> "at ${place.name}"
            else -> "away from ${place.name}"
        }

        /** e.g. "when you arrive at Gym", "only when away from Home" */
        fun describe(t: Trigger): String {
            val name = t.place?.name ?: "a place (not set)"
            return when (t.kind) {
                ARRIVE -> "when you arrive at $name"
                LEAVE -> "when you leave $name"
                RUN_START -> "when you start running"
                RUN_END -> if (t.minRunMinutes > 0) "when you finish a run of ${t.minRunMinutes}+ min" else "when you finish running"
                RUN_FOR -> "${runForText(t.minRunMinutes)} into a run"
                else -> when (t.where) {
                    AT -> "only when at $name"
                    AWAY -> "only when away from $name"
                    else -> "anywhere"
                }
            }
        }

        /** e.g. "between 6:00 AM and 9:00 AM" or "" (with [timeText] formatting the times). */
        fun windowText(t: Trigger, timeText: (AlarmTime) -> String): String {
            val s = t.windowStart ?: return ""
            val e = t.windowEnd ?: return ""
            return "between ${timeText(s)} and ${timeText(e)}"
        }

        /** "15 minutes", "1 hour", "1 hour 30 minutes" */
        fun runForText(minutes: Int): String {
            val m = minutes.coerceAtLeast(1)
            val h = m / 60
            val r = m % 60
            fun unit(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"
            return when {
                h == 0 -> unit(r, "minute")
                r == 0 -> unit(h, "hour")
                else -> "${unit(h, "hour")} ${unit(r, "minute")}"
            }
        }

        /** "40.71280, -74.00600" */
        fun coordinates(p: Place): String = String.format(Locale.ROOT, "%.5f, %.5f", p.lat, p.lon)
    }
}
