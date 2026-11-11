import Foundation

// The data model, ported from the Android app (Store.kt, Repeat.kt, Trigger.kt, Steps.kt,
// Background.kt). JSON keys, values and defaults match, so backups move between the apps.

public enum Ids {
    /// A short random id, like the Android app's (8 hex characters).
    public static func new() -> String {
        String(UUID().uuidString.lowercased().prefix(8))
    }
}

// MARK: - Question

/// A yes/no question. [yesIsGood] says which answer counts as "on track".
public struct Question: Equatable, Identifiable {
    public var id: String
    public var text: String
    public var yesIsGood: Bool

    public init(id: String, text: String, yesIsGood: Bool = false) {
        self.id = id
        self.text = text
        self.yesIsGood = yesIsGood
    }

    public func isGood(_ answer: Bool) -> Bool { answer == yesIsGood }
}

// MARK: - Repeat

/// Which dates something rings on.
///  - daily: every day;
///  - weekly: on [days] of the week (1 = Monday … 7 = Sunday);
///  - monthly: on [days] of the month (1…31, or lastDay). A date a month doesn't have is
///    skipped that month; lastDay means "end of month".
public struct Repeat: Equatable {
    public static let daily = "daily"
    public static let weekly = "weekly"
    public static let monthly = "monthly"
    public static let lastDay = -1
    public static let weekdays: Set<Int> = [1, 2, 3, 4, 5]
    public static let weekends: Set<Int> = [6, 7]
    public static let everyDay = Repeat()

    public var kind: String
    public var days: Set<Int>

    public init(_ kind: String = Repeat.daily, _ days: Set<Int> = []) {
        self.kind = kind
        self.days = days
    }

    public static func weeklyOn(_ days: Set<Int>) -> Repeat {
        days.isEmpty || days.count == 7 ? .everyDay : Repeat(weekly, days)
    }

    public static func monthlyOn(_ days: Set<Int>) -> Repeat {
        days.isEmpty ? .everyDay : Repeat(monthly, days)
    }

    /// Does it ring on [date] (in [calendar]'s time zone)?
    public func matches(_ date: Date, calendar: Calendar) -> Bool {
        let c = calendar.dateComponents([.year, .month, .day, .weekday], from: date)
        let length = calendar.range(of: .day, in: .month, for: date)?.count ?? 31
        return matches(day: c.day ?? 1, isoWeekday: Days.isoWeekday(c.weekday ?? 1), daysInMonth: length)
    }

    func matches(day: Int, isoWeekday: Int, daysInMonth: Int) -> Bool {
        switch kind {
        case Repeat.weekly: return days.contains(isoWeekday)
        case Repeat.monthly: return days.contains(day) || (days.contains(Repeat.lastDay) && day == daysInMonth)
        default: return true
        }
    }

    /// e.g. "every day", "Mon–Fri", "Mon, Wed, Fri", "monthly on the 1st and 15th".
    public func describe() -> String {
        switch kind {
        case Repeat.weekly:
            if days == Repeat.weekdays { return "Mon–Fri" }
            if days == Repeat.weekends { return "Sat & Sun" }
            if days == Set(1...7) { return "every day" }
            if days.count == 6, let missing = Set(1...7).subtracting(days).first {
                return "every day but \(Repeat.dayName(missing))"
            }
            return days.sorted().map { Repeat.dayName($0) }.joined(separator: ", ")
        case Repeat.monthly:
            var parts = days.filter { $0 != Repeat.lastDay }.sorted().map { Repeat.ordinal($0) }
            if days.contains(Repeat.lastDay) { parts.append("last day") }
            return "monthly on the " + Repeat.joinWithAnd(parts)
        default:
            return "every day"
        }
    }

    public var json: JSON {
        .object(JSONObject([("kind", .string(kind)), ("days", .array(days.sorted().map { d -> JSON in .int(d) }))]))
    }

    /// Missing or unreadable: every day.
    public static func from(_ o: JSONObject?) -> Repeat {
        guard let o else { return .everyDay }
        let days = Set((o.array("days") ?? []).compactMap { $0.intValue })
        switch o.string("kind") {
        case weekly: return weeklyOn(days.filter { (1...7).contains($0) })
        case monthly: return monthlyOn(days.filter { (1...31).contains($0) || $0 == lastDay })
        default: return .everyDay
        }
    }

    public static func dayName(_ d: Int) -> String {
        ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"][max(1, min(7, d)) - 1]
    }

    public static func ordinal(_ n: Int) -> String {
        let suffix: String
        if (11...13).contains(n % 100) {
            suffix = "th"
        } else {
            switch n % 10 {
            case 1: suffix = "st"
            case 2: suffix = "nd"
            case 3: suffix = "rd"
            default: suffix = "th"
            }
        }
        return "\(n)\(suffix)"
    }

    static func joinWithAnd(_ parts: [String]) -> String {
        switch parts.count {
        case 0: return ""
        case 1: return parts[0]
        default: return parts.dropLast().joined(separator: ", ") + " and " + parts.last!
        }
    }
}

// MARK: - Place

/// A place on the map: a circle of [radiusM] metres around [lat], [lon].
public struct Place: Equatable {
    public static let defaultRadius = 200
    public static let radii = [100, 200, 500, 1000]

    public var name: String
    public var lat: Double
    public var lon: Double
    public var radiusM: Int

    public init(name: String, lat: Double, lon: Double, radiusM: Int = Place.defaultRadius) {
        self.name = name
        self.lat = lat
        self.lon = lon
        self.radiusM = radiusM
    }

    public func contains(lat: Double, lon: Double) -> Bool {
        Trigger.distanceM(self.lat, self.lon, lat, lon) <= Double(radiusM)
    }

    public var json: JSON {
        .object(JSONObject([
            ("name", .string(name)), ("lat", .double(lat)), ("lon", .double(lon)), ("radiusM", .int(radiusM)),
        ]))
    }

    public static func from(_ o: JSONObject?) -> Place? {
        guard let o, let lat = o.double("lat"), let lon = o.double("lon"), valid(lat, lon) else { return nil }
        return Place(name: o.string("name", "Place"), lat: lat, lon: lon,
                     radiusM: min(5000, max(50, o.int("radiusM", defaultRadius))))
    }

    public static func valid(_ lat: Double, _ lon: Double) -> Bool {
        !lat.isNaN && !lon.isNaN && (-90...90).contains(lat) && (-180...180).contains(lon)
    }

    /// "40.7128, -74.0060" (as copied from a maps app) → (lat, lon), or nil.
    public static func parseCoordinates(_ text: String) -> (Double, Double)? {
        var t = text.trimmingCharacters(in: .whitespaces)
        if t.hasPrefix("(") { t.removeFirst() }
        if t.hasSuffix(")") { t.removeLast() }
        let parts = t.split(whereSeparator: { $0 == "," || $0 == ";" || $0.isWhitespace }).map(String.init)
        guard parts.count == 2, let lat = Double(parts[0]), let lon = Double(parts[1]), valid(lat, lon) else { return nil }
        return (lat, lon)
    }
}

// MARK: - AlarmTime

/// A time of day a check-in rings: a timed trigger. [days]: the days it rings on (nil: the
/// check-in's repeat rule). [where] / [place]: optionally only when you're at or away from a
/// place at that moment.
public struct AlarmTime: Equatable, Comparable, CustomStringConvertible {
    public var hour: Int
    public var minute: Int
    public var days: Repeat?
    public var `where`: String
    public var place: Place?

    public init(_ hour: Int, _ minute: Int, days: Repeat? = nil, where: String = Trigger.anywhere, place: Place? = nil) {
        self.hour = hour
        self.minute = minute
        self.days = days
        self.where = `where`
        self.place = place
    }

    /// Does ringing depend on where you are?
    public var usesLocation: Bool { `where` != Trigger.anywhere && place != nil }

    public var minutesOfDay: Int { hour * 60 + minute }

    public static func < (a: AlarmTime, b: AlarmTime) -> Bool { a.minutesOfDay < b.minutesOfDay }

    /// "HH:mm", e.g. "07:00".
    public var description: String { String(format: "%02d:%02d", hour, minute) }

    public static func parse(_ s: String) -> AlarmTime? {
        let parts = s.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count >= 2, let h = Int(parts[0]), let m = Int(parts[1]),
              (0...23).contains(h), (0...59).contains(m) else { return nil }
        return AlarmTime(h, m)
    }

    /// "HH:mm", or an object when it has days or a place condition of its own.
    public var json: JSON {
        if days == nil && !usesLocation { return .string(description) }
        var o = JSONObject().with("t", .string(description))
        if let days { o["days"] = days.json }
        if usesLocation, let place {
            o["where"] = .string(`where`)
            o["place"] = place.json
        }
        return .object(o)
    }

    public static func from(_ v: JSON) -> AlarmTime? {
        if let o = v.objectValue {
            guard var t = parse(o.string("t")) else { return nil }
            t.days = o.object("days").map { Repeat.from($0) }
            let w = o.string("where", Trigger.anywhere)
            t.where = [Trigger.at, Trigger.away].contains(w) ? w : Trigger.anywhere
            t.place = Place.from(o.object("place"))
            return t
        }
        return v.stringValue.flatMap { parse($0) }
    }
}

// MARK: - Trigger

/// What makes a check-in ring (besides its times), and the conditions that must hold:
///  - arrive / leave: entering or leaving [place];
///  - runStart / runEnd: starting a run, or finishing one of at least [minRunMinutes];
///  - runFor: [minRunMinutes] into a run;
/// and for those, only on [days], only between [windowStart] and [windowEnd] (if set), and
/// at most once a day if [oncePerDay]. (time is the legacy whole-check-in trigger.)
public struct Trigger: Equatable {
    public static let time = "time"
    public static let arrive = "arrive"
    public static let leave = "leave"
    public static let runStart = "run-start"
    public static let runEnd = "run-end"
    public static let runFor = "run-for"
    public static let kinds: Set<String> = [time, arrive, leave, runStart, runEnd, runFor]
    public static let defaultRunFor = 30
    public static let anywhere = "anywhere"
    public static let at = "at"
    public static let away = "away"

    public var kind: String
    public var place: Place?
    public var `where`: String
    public var windowStart: AlarmTime?
    public var windowEnd: AlarmTime?
    public var oncePerDay: Bool
    public var minRunMinutes: Int
    public var days: Repeat

    public init(kind: String = Trigger.time, place: Place? = nil, where: String = Trigger.anywhere,
                windowStart: AlarmTime? = nil, windowEnd: AlarmTime? = nil, oncePerDay: Bool = true,
                minRunMinutes: Int = 0, days: Repeat = .everyDay) {
        self.kind = kind
        self.place = place
        self.where = `where`
        self.windowStart = windowStart
        self.windowEnd = windowEnd
        self.oncePerDay = oncePerDay
        self.minRunMinutes = minRunMinutes
        self.days = days
    }

    public var isPlace: Bool { kind == Trigger.arrive || kind == Trigger.leave }
    public var isMovement: Bool { kind == Trigger.runStart || kind == Trigger.runEnd || kind == Trigger.runFor }
    public var isEvent: Bool { isPlace || isMovement }
    public var usesLocation: Bool { (isPlace || (kind == Trigger.time && `where` != Trigger.anywhere)) && place != nil }

    public var json: JSON {
        var o = JSONObject([
            ("kind", .string(kind)), ("where", .string(`where`)), ("oncePerDay", .bool(oncePerDay)),
            ("minRunMinutes", .int(minRunMinutes)), ("days", days.json),
        ])
        if let place { o["place"] = place.json }
        if let windowStart { o["windowStart"] = .string(windowStart.description) }
        if let windowEnd { o["windowEnd"] = .string(windowEnd.description) }
        return .object(o)
    }

    public static func from(_ o: JSONObject?) -> Trigger {
        guard let o else { return Trigger() }
        let kind = o.string("kind", time)
        let w = o.string("where", anywhere)
        return Trigger(
            kind: kinds.contains(kind) ? kind : time,
            place: Place.from(o.object("place")),
            where: [anywhere, at, away].contains(w) ? w : anywhere,
            windowStart: AlarmTime.parse(o.string("windowStart")),
            windowEnd: AlarmTime.parse(o.string("windowEnd")),
            oncePerDay: o.bool("oncePerDay", true),
            minRunMinutes: min(600, max(0, o.int("minRunMinutes", 0))),
            days: Repeat.from(o.object("days"))
        )
    }

    /// Great-circle distance in metres.
    public static func distanceM(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let r = 6_371_000.0
        let rad = Double.pi / 180
        let dLat = (lat2 - lat1) * rad
        let dLon = (lon2 - lon1) * rad
        let a = pow(sin(dLat / 2), 2) + cos(lat1 * rad) * cos(lat2 * rad) * pow(sin(dLon / 2), 2)
        return 2 * r * asin(sqrt(a))
    }

    /// "15 minutes", "1 hour", "1 hour 30 minutes"
    public static func runForText(_ minutes: Int) -> String {
        let m = max(1, minutes)
        let h = m / 60
        let r = m % 60
        func unit(_ n: Int, _ w: String) -> String { "\(n) \(w)\(n == 1 ? "" : "s")" }
        if h == 0 { return unit(r, "minute") }
        if r == 0 { return unit(h, "hour") }
        return "\(unit(h, "hour")) \(unit(r, "minute"))"
    }
}

// MARK: - Steps

/// One step that runs when a check-in rings: solve math problems, scan a code, or answer
/// questions. A step that isn't set up yet (a scan without a code, no questions) is skipped.
public struct StepConfig: Equatable, Identifiable {
    public static let math = "math"
    public static let scan = "scan"
    public static let questions = "questions"
    public static let kinds = [math, scan, questions]
    public static let maxSteps = 100
    public static let maxProblems = 100

    public var id: String
    public var kind: String
    public var mathCount: Int
    public var mathLevel: Int
    public var scanCode: String?
    public var scanLabel: String
    public var questions: [Question]

    public init(id: String = Ids.new(), kind: String, mathCount: Int = 3, mathLevel: Int = 2,
                scanCode: String? = nil, scanLabel: String = "", questions: [Question] = []) {
        self.id = id
        self.kind = kind
        self.mathCount = mathCount
        self.mathLevel = mathLevel
        self.scanCode = scanCode
        self.scanLabel = scanLabel
        self.questions = questions
    }

    public var configured: Bool {
        switch kind {
        case StepConfig.math: return mathCount > 0
        case StepConfig.scan: return scanCode != nil
        case StepConfig.questions: return !questions.isEmpty
        default: return false
        }
    }

    public var json: JSON {
        var o = JSONObject([("id", .string(id)), ("kind", .string(kind))])
        switch kind {
        case StepConfig.math:
            o["mathCount"] = .int(mathCount)
            o["mathLevel"] = .int(mathLevel)
        case StepConfig.scan:
            o["scanCode"] = .string(scanCode ?? "")
            o["scanLabel"] = .string(scanLabel)
        case StepConfig.questions:
            o["questions"] = .array(questions.map { q -> JSON in
                .object(JSONObject([("id", .string(q.id)), ("text", .string(q.text)), ("yesIsGood", .bool(q.yesIsGood))]))
            })
        default:
            break
        }
        return .object(o)
    }

    public static func from(_ o: JSONObject) -> StepConfig? {
        let kind = o.string("kind")
        guard kinds.contains(kind) else { return nil }
        let id = o.string("id")
        let code = o.string("scanCode")
        let qs: [Question] = (o.array("questions") ?? []).compactMap { v in
            guard let q = v.objectValue else { return nil }
            let text = q.string("text")
            guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            let qid = q.string("id")
            return Question(id: qid.isEmpty ? Ids.new() : qid, text: text, yesIsGood: q.bool("yesIsGood", false))
        }
        return StepConfig(
            id: id.isEmpty ? Ids.new() : id,
            kind: kind,
            mathCount: min(100, max(0, o.int("mathCount", 3))),
            mathLevel: min(MathGen.levels.count, max(1, o.int("mathLevel", 2))),
            scanCode: code.isEmpty ? nil : code,
            scanLabel: o.string("scanLabel"),
            questions: qs
        )
    }
}

// MARK: - Background

/// What's shown behind the ringing screen, plus an optional caption.
public struct Background: Equatable {
    public static let none = "none"
    public static let photo = "photo"
    public static let slideshow = "slideshow"
    public static let video = "video"
    public static let kinds: Set<String> = [none, photo, slideshow, video]
    public static let defaultInterval = 10

    public var kind: String
    public var intervalSec: Int
    public var caption: String

    public init(kind: String = Background.none, intervalSec: Int = Background.defaultInterval, caption: String = "") {
        self.kind = kind
        self.intervalSec = intervalSec
        self.caption = caption
    }

    public var hasMedia: Bool { kind != Background.none }

    public var json: JSON {
        .object(JSONObject([("kind", .string(kind)), ("intervalSec", .int(intervalSec)), ("caption", .string(caption))]))
    }

    public static func from(_ o: JSONObject?) -> Background {
        guard let o else { return Background() }
        let kind = o.string("kind", none)
        return Background(kind: kinds.contains(kind) ? kind : none,
                          intervalSec: min(3600, max(1, o.int("intervalSec", defaultInterval))),
                          caption: o.string("caption"))
    }
}

// MARK: - Checkin

/// One alarm with its own triggers and steps.
public struct Checkin: Equatable, Identifiable {
    public static let silent = "silent"
    public static let strict = "strict"
    public static let soft = "soft"
    public static let voice = "voice"
    public static let commitments = [strict, soft, voice]
    public static let voiceText = "text"
    public static let voiceClip = "clip"
    public static let snoozeChoices = [5, 10, 15, 20, 30]
    public static let defaultSnooze = 10

    public var id: String
    public var name: String
    public var times: [AlarmTime]
    public var `repeat`: Repeat
    public var enabled: Bool
    public var aboutYesterday: Bool
    public var steps: [StepConfig]
    public var soundUri: String?
    public var soundTitle: String
    public var background: Background
    public var events: [Trigger]
    public var category: String
    public var commitment: String
    public var snoozeMinutes: Int
    public var voiceSource: String
    public var voiceText: String
    public var voiceHeadphonesOnly: Bool

    public init(id: String = Ids.new(), name: String, times: [AlarmTime] = [AlarmTime(12, 0)],
                repeat: Repeat = .everyDay, enabled: Bool = true, aboutYesterday: Bool = false,
                steps: [StepConfig] = [], soundUri: String? = nil, soundTitle: String = "",
                background: Background = Background(), events: [Trigger] = [], category: String = "",
                commitment: String = Checkin.strict, snoozeMinutes: Int = Checkin.defaultSnooze,
                voiceSource: String = Checkin.voiceText, voiceText: String = "", voiceHeadphonesOnly: Bool = true) {
        self.id = id
        self.name = name
        self.times = times
        self.repeat = `repeat`
        self.enabled = enabled
        self.aboutYesterday = aboutYesterday
        self.steps = steps
        self.soundUri = soundUri
        self.soundTitle = soundTitle
        self.background = background
        self.events = events
        self.category = category
        self.commitment = commitment
        self.snoozeMinutes = snoozeMinutes
        self.voiceSource = voiceSource
        self.voiceText = voiceText
        self.voiceHeadphonesOnly = voiceHeadphonesOnly
    }

    public var isSoft: Bool { commitment == Checkin.soft }
    public var isVoice: Bool { commitment == Checkin.voice }
    /// Only strict check-ins count in the stats.
    public var counted: Bool { commitment == Checkin.strict }

    /// The days [t] rings on: its own, or the check-in's repeat rule.
    public func daysFor(_ t: AlarmTime) -> Repeat { t.days ?? self.repeat }

    public var hasNoTriggers: Bool { times.isEmpty && events.isEmpty }

    /// Every question in its question steps, in order: these are what gets logged.
    public var questions: [Question] {
        steps.filter { $0.kind == StepConfig.questions }.flatMap { $0.questions }
    }

    /// The steps that actually run, in order.
    public var activeSteps: [StepConfig] { steps.filter { $0.configured } }

    public var json: JSON {
        .object(JSONObject([
            ("id", .string(id)),
            ("name", .string(name)),
            ("times", .array(times.map { t -> JSON in t.json })),
            ("repeat", self.repeat.json),
            ("enabled", .bool(enabled)),
            ("aboutYesterday", .bool(aboutYesterday)),
            ("soundUri", .string(soundUri ?? "")),
            ("soundTitle", .string(soundTitle)),
            ("background", background.json),
            ("steps", .array(steps.map { st -> JSON in st.json })),
            ("events", .array(events.map { e -> JSON in e.json })),
            ("category", .string(category)),
            ("commitment", .string(commitment)),
            ("voiceSource", .string(voiceSource)),
            ("voiceText", .string(voiceText)),
            ("voiceHeadphonesOnly", .bool(voiceHeadphonesOnly)),
            ("snoozeMinutes", .int(snoozeMinutes)),
        ]))
    }

    /// Reads a check-in as the Android app writes it. Throws if it has no id.
    public static func from(_ o: JSONObject) throws -> Checkin {
        let id = o.string("id")
        guard !id.isEmpty else { throw BackupError("The backup file is damaged: a check-in has no id.") }
        var seen: [AlarmTime] = []
        for t in (o.array("times") ?? []).compactMap({ AlarmTime.from($0) }) where !seen.contains(t) {
            seen.append(t)
        }
        let commitment = o.string("commitment")
        let snooze = o.int("snoozeMinutes", defaultSnooze)
        return Checkin(
            id: id,
            name: o.string("name", "Check-in"),
            times: seen.sorted(),
            repeat: Repeat.from(o.object("repeat")),
            enabled: o.bool("enabled", true),
            aboutYesterday: o.bool("aboutYesterday", false),
            steps: (o.array("steps") ?? []).compactMap { (v: JSON) -> StepConfig? in
                guard let so = v.objectValue else { return nil }
                return StepConfig.from(so)
            },
            soundUri: o.string("soundUri").isEmpty ? nil : o.string("soundUri"),
            soundTitle: o.string("soundTitle"),
            background: Background.from(o.object("background")),
            events: (o.array("events") ?? []).compactMap { (v: JSON) -> Trigger? in
                guard let eo = v.objectValue else { return nil }
                let t = Trigger.from(eo)
                return t.isEvent ? t : nil
            },
            category: o.string("category").trimmingCharacters(in: .whitespaces),
            commitment: commitments.contains(commitment) ? commitment : (o.bool("soft", false) ? soft : strict),
            snoozeMinutes: snoozeChoices.contains(snooze) ? snooze : defaultSnooze,
            voiceSource: o.string("voiceSource") == voiceClip ? voiceClip : voiceText,
            voiceText: o.string("voiceText"),
            voiceHeadphonesOnly: o.bool("voiceHeadphonesOnly", true)
        )
    }

    /// What a new install starts with (the Android app's defaults).
    public static let defaults: [Checkin] = [
        Checkin(id: "morning", name: "Morning", times: [AlarmTime(7, 0)], aboutYesterday: true, steps: [
            StepConfig(id: "morning-q", kind: StepConfig.questions, questions: [
                Question(id: "latefood", text: "Did you eat anything after 9 PM last night?"),
                Question(id: "alcohol", text: "Did you drink alcohol last night?"),
            ]),
        ]),
        Checkin(id: "evening", name: "Evening", times: [AlarmTime(21, 30)], steps: [
            StepConfig(id: "evening-q", kind: StepConfig.questions, questions: [
                Question(id: "sweetdrink", text: "Did you drink any sweetened beverages today?"),
                Question(id: "fastfood", text: "Did you eat fast food today?"),
                Question(id: "sweets", text: "Did you eat anything sweet today?"),
                Question(id: "veg", text: "Did you eat vegetables or fruit today?", yesIsGood: true),
                Question(id: "homecooked", text: "Did you eat a home-cooked meal today?", yesIsGood: true),
            ]),
        ]),
    ]
}

// MARK: - Results

/// What happened with one check-in at one time on one day.
///  - rang: rang, not finished (counts as missed); answered: questions answered;
///  - done: finished without questions; skipped: skipped before it rang;
///  - dismissed: a soft alarm dismissed without its steps.
/// [early]: finished before the alarm time. [soft]: logged by a soft or voice check-in, kept
/// for scheduling but left out of the stats.
public struct SlotResult: Equatable {
    public static let rang = "rang"
    public static let answered = "answered"
    public static let done = "done"
    public static let skipped = "skipped"
    public static let dismissed = "dismissed"

    public var status: String
    public var answers: [String: Bool]
    public var early: Bool
    public var soft: Bool

    public init(_ status: String, answers: [String: Bool] = [:], early: Bool = false, soft: Bool = false) {
        self.status = status
        self.answers = answers
        self.early = early
        self.soft = soft
    }

    /// Finished or skipped: the alarm for this time no longer needs to ring.
    public var closed: Bool { status != SlotResult.rang }

    public var json: JSON {
        var o = JSONObject([
            ("s", .string(status)),
            ("a", .object(JSONObject(answers.keys.sorted().map { k -> (String, JSON) in (k, .bool(answers[k]!)) }))),
            ("early", .bool(early)),
        ])
        if soft { o["soft"] = .bool(true) }
        return .object(o)
    }

    public static func from(_ o: JSONObject) -> SlotResult {
        var answers: [String: Bool] = [:]
        if let a = o.object("a") {
            for (k, v) in a.pairs { if let b = v.boolValue { answers[k] = b } }
        }
        return SlotResult(o.string("s", answered), answers: answers, early: o.bool("early", false), soft: o.bool("soft", false))
    }
}

/// One check-in's results on one day, by time ("HH:mm").
public typealias DayResults = [String: SlotResult]
/// One check-in's results, by day ("yyyy-MM-dd").
public typealias History = [String: DayResults]
