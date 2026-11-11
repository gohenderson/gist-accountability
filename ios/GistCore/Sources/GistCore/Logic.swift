import Foundation

// Scheduling, dates, math problems, scores, stats and sorting: ported from the Android app
// (Days.kt, AlarmScheduler.kt, MathGen.kt, Score.kt, Stats.kt, Grouping.kt).

// MARK: - Days

public enum Days {
    /// An alarm that rings before 4 AM is about the previous day.
    public static let rolloverHour = 4

    /// Calendar's weekday (1 = Sunday … 7 = Saturday) → ISO (1 = Monday … 7 = Sunday).
    public static func isoWeekday(_ weekday: Int) -> Int { weekday == 1 ? 7 : weekday - 1 }

    /// "yyyy-MM-dd" for [date] in [calendar]'s time zone.
    public static func string(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 1, c.day ?? 1)
    }

    /// The start of the day "yyyy-MM-dd" in [calendar]'s time zone.
    public static func date(_ day: String, calendar: Calendar = .current) -> Date? {
        let p = day.split(separator: "-").compactMap { Int($0) }
        guard p.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: p[0], month: p[1], day: p[2]))
    }

    public static func today(_ now: Date = Date(), calendar: Calendar = .current) -> String {
        string(now, calendar: calendar)
    }

    /// The day a check-in ringing at [date] is about.
    public static func forAlarm(aboutYesterday: Bool, at date: Date, calendar: Calendar = .current) -> String {
        var day = calendar.startOfDay(for: date)
        if calendar.component(.hour, from: date) < rolloverHour {
            day = calendar.date(byAdding: .day, value: -1, to: day) ?? day
        }
        if aboutYesterday {
            day = calendar.date(byAdding: .day, value: -1, to: day) ?? day
        }
        return string(day, calendar: calendar)
    }

    /// The last [n] days, most recent first.
    public static func lastN(_ n: Int, today: Date = Date(), calendar: Calendar = .current) -> [String] {
        let start = calendar.startOfDay(for: today)
        return (0..<max(0, n)).compactMap { i in
            calendar.date(byAdding: .day, value: -i, to: start).map { string($0, calendar: calendar) }
        }
    }
}

// MARK: - Scheduling

/// One upcoming ring: which time, when, and the day its answers count for.
public struct Occurrence: Equatable {
    public let time: AlarmTime
    public let at: Date
    public let day: String
    public var timeKey: String { time.description }
}

public enum Scheduler {
    /// The check-in's next [count] rings, soonest first, all strictly after [now], following
    /// each time's days (looks up to ~13 months ahead, enough for any monthly date).
    public static func occurrences(_ c: Checkin, now: Date = Date(), calendar: Calendar = .current, count: Int = 8) -> [Occurrence] {
        if c.times.isEmpty { return [] } // only event triggers: no set times
        var out: [Occurrence] = []
        let start = calendar.startOfDay(for: now)
        var i = 0
        while out.count < count && i <= 400 {
            guard let date = calendar.date(byAdding: .day, value: i, to: start) else { break }
            let ymd = calendar.dateComponents([.year, .month, .day], from: date)
            for t in c.times where c.daysFor(t).matches(date, calendar: calendar) {
                var dc = ymd
                dc.hour = t.hour
                dc.minute = t.minute
                dc.second = 0
                guard let at = calendar.date(from: dc), at > now else { continue }
                out.append(Occurrence(time: t, at: at,
                                      day: Days.forAlarm(aboutYesterday: c.aboutYesterday, at: at, calendar: calendar)))
            }
            i += 1
        }
        return Array(out.sorted { $0.at < $1.at }.prefix(count))
    }

    /// The next ring that isn't [closed] (done early or skipped).
    public static func next(_ c: Checkin, now: Date = Date(), calendar: Calendar = .current,
                            closed: (Occurrence) -> Bool = { _ in false }) -> Occurrence? {
        occurrences(c, now: now, calendar: calendar).first { !closed($0) }
    }
}

// MARK: - Math

/// Wake-up math problems. Answers are always positive whole numbers.
public enum MathGen {
    public struct Problem: Equatable {
        public let text: String
        public let answer: Int
    }

    public struct Level: Equatable {
        public let name: String
        public let example: String
    }

    public static let levels = [
        Level(name: "Easy", example: "47 + 38"),
        Level(name: "Medium", example: "47 × 6 + 23"),
        Level(name: "Hard", example: "47 × 38 + 512"),
        Level(name: "Very hard", example: "347 × 38 + 47 × 26"),
    ]

    public static func levelName(_ level: Int) -> String {
        levels[min(levels.count - 1, max(0, level - 1))].name
    }

    public static func make<G: RandomNumberGenerator>(_ level: Int, using rng: inout G) -> Problem {
        func r(_ range: ClosedRange<Int>) -> Int { Int.random(in: range, using: &rng) }
        switch level {
        case 1:
            let a = r(10...99), b = r(10...99)
            return Problem(text: "\(a) + \(b)", answer: a + b)
        case 2:
            let a = r(10...99), b = r(3...9), c = r(10...99)
            return Problem(text: "\(a) × \(b) + \(c)", answer: a * b + c)
        case 3:
            let a = r(11...99), b = r(11...99), c = r(100...999)
            return Problem(text: "\(a) × \(b) + \(c)", answer: a * b + c)
        default:
            let a = r(101...999), b = r(11...99), c = r(11...99), d = r(11...99)
            return Problem(text: "\(a) × \(b) + \(c) × \(d)", answer: a * b + c * d)
        }
    }

    public static func make(_ level: Int) -> Problem {
        var g = SystemRandomNumberGenerator()
        return make(level, using: &g)
    }
}

// MARK: - Stats

/// How one question went on one day, across all of that day's times.
public enum Mark: Equatable { case onTrack, offTrack, skipped, none }

/// Results are saved per time; reports roll them up per day:
///  - offTrack if any time that day was answered off track, or rang and was never finished;
///  - onTrack if every time that rang was answered on track;
///  - skipped if every time that day was skipped;
///  - none if nothing rang that day.
public enum Stats {
    public static func dayMark(_ q: Question, _ day: DayResults) -> Mark {
        let counted = day.values.filter { $0.status != SlotResult.skipped }
        if counted.contains(where: { $0.status == SlotResult.rang }) { return .offTrack } // missed
        let answers = counted.compactMap { $0.answers[q.id] }
        if answers.contains(where: { !q.isGood($0) }) { return .offTrack }
        if !answers.isEmpty { return .onTrack }
        if day.values.contains(where: { $0.status == SlotResult.skipped }) { return .skipped }
        return .none
    }

    /// Days among [days] when the check-in rang or was answered (skipped-only days don't count).
    public static func logged(_ results: History, days: [String]) -> [DayResults] {
        days.compactMap { results[$0] }.filter { day in day.values.contains { $0.status != SlotResult.skipped } }
    }

    /// A logged day is fully on track if no question was off track (or missed) at any time.
    public static func onTrack(_ c: Checkin, _ day: DayResults) -> Bool {
        !c.questions.contains { dayMark($0, day) == .offTrack } && !day.values.contains { $0.status == SlotResult.rang }
    }

    /// e.g. "Last 7 days: 5 logged, 4 fully on track"
    public static func line(_ c: Checkin, _ results: History, days: [String]) -> String {
        let logged = logged(results, days: days)
        let clean = logged.filter { onTrack(c, $0) }.count
        return "Last \(days.count) days: \(logged.count) logged, \(clean) fully on track"
    }
}

// MARK: - Score

/// How on track a check-in has been: the share of the last [days] logged days that were
/// fully on track. Colors run from the off-track red (0%) to the on-track green (100%).
public enum Score {
    public static let days = 7

    /// 0.0–1.0, or nil when nothing was logged in [days].
    public static func of(_ c: Checkin, _ results: History, days: [String]) -> Double? {
        let logged = Stats.logged(results, days: days)
        if logged.isEmpty { return nil }
        return Double(logged.filter { Stats.onTrack(c, $0) }.count) / Double(logged.count)
    }

    /// The average of the known scores, or nil if there are none.
    public static func average(_ scores: [Double?]) -> Double? {
        let known = scores.compactMap { $0 }
        return known.isEmpty ? nil : known.reduce(0, +) / Double(known.count)
    }

    public static func percent(_ score: Double) -> String { "\(Int((score * 100).rounded()))%" }

    public struct RGB: Equatable {
        public let r: Int, g: Int, b: Int
        public init(_ r: Int, _ g: Int, _ b: Int) {
            self.r = r
            self.g = g
            self.b = b
        }
    }

    /// [bad] at 0, [good] at 1, and in between a blend around the color wheel (red → amber →
    /// green), so middling scores read as "getting there" instead of a muddy brown.
    public static func color(_ score: Double, bad: RGB, good: RGB) -> RGB {
        let t = Float(min(1, max(0, score)))
        let a = hsv(bad)
        let b = hsv(good)
        var dh = b.0 - a.0
        if abs(dh) > 180 { dh -= 360 * (dh > 0 ? 1 : -1) }
        let h = (a.0 + dh * t + 360).truncatingRemainder(dividingBy: 360)
        let s = a.1 + (b.1 - a.1) * t
        let v = a.2 + (b.2 - a.2) * t
        return fromHsv(h, s, v)
    }

    static func hsv(_ c: RGB) -> (Float, Float, Float) {
        let r = Float(c.r) / 255, g = Float(c.g) / 255, b = Float(c.b) / 255
        let mx = max(r, g, b), mn = min(r, g, b), d = mx - mn
        var h: Float
        if d == 0 {
            h = 0
        } else if mx == r {
            h = 60 * ((g - b) / d).truncatingRemainder(dividingBy: 6)
        } else if mx == g {
            h = 60 * ((b - r) / d + 2)
        } else {
            h = 60 * ((r - g) / d + 4)
        }
        if h < 0 { h += 360 }
        return (h, mx == 0 ? 0 : d / mx, mx)
    }

    static func fromHsv(_ h: Float, _ s: Float, _ v: Float) -> RGB {
        let c = v * s
        let x = c * (1 - abs((h / 60).truncatingRemainder(dividingBy: 2) - 1))
        let m = v - c
        let rgb: (Float, Float, Float)
        if h < 60 {
            rgb = (c, x, 0)
        } else if h < 120 {
            rgb = (x, c, 0)
        } else if h < 180 {
            rgb = (0, c, x)
        } else if h < 240 {
            rgb = (0, x, c)
        } else if h < 300 {
            rgb = (x, 0, c)
        } else {
            rgb = (c, 0, x)
        }
        func ch(_ f: Float) -> Int { min(255, max(0, Int(((f + m) * 255).rounded()))) }
        return RGB(ch(rgb.0), ch(rgb.1), ch(rgb.2))
    }
}

// MARK: - Grouping and sorting

/// How the start page lists check-ins: in your order, or under a heading per category
/// (check-ins without one come last, under "Other"); and sorted.
public enum Grouping {
    public static let ungrouped = "ungrouped"
    public static let byCategoryView = "category"
    public static let other = "Other"

    public static let sortCustom = "custom"       // My order: as dragged
    public static let sortNext = "next"           // Next alarm: soonest ring first
    public static let sortAttention = "attention" // Needs attention: lowest score first
    public static let sortName = "name"           // Name, A–Z
    public static let sorts = [sortCustom, sortNext, sortAttention, sortName]

    public static func sortTitle(_ how: String) -> String {
        switch how {
        case sortNext: return "Next alarm"
        case sortAttention: return "Needs attention"
        case sortName: return "Name"
        default: return "My order"
        }
    }

    /// [checkins] (in your order) sorted [how]. Check-ins without a next ring or a score go
    /// last. Ties, and everything at the end, keep your order.
    public static func sort(_ checkins: [Checkin], _ how: String,
                            next: (Checkin) -> Date?, score: (Checkin) -> Double?) -> [Checkin] {
        let indexed = Array(checkins.enumerated())
        func stable<K: Comparable>(_ key: (Checkin) -> K?) -> [Checkin] {
            let keys = indexed.map { key($0.element) }
            return indexed.sorted { a, b in
                switch (keys[a.offset], keys[b.offset]) {
                case let (x?, y?): return x != y ? x < y : a.offset < b.offset
                case (.some, .none): return true
                case (.none, .some): return false
                case (.none, .none): return a.offset < b.offset
                }
            }.map { $0.element }
        }
        switch how {
        case sortNext: return stable(next)
        case sortAttention: return stable(score)
        case sortName:
            return indexed.sorted { a, b in
                let x = a.element.name.trimmingCharacters(in: .whitespaces)
                let y = b.element.name.trimmingCharacters(in: .whitespaces)
                let o = x.caseInsensitiveCompare(y)
                return o == .orderedSame ? a.offset < b.offset : o == .orderedAscending
            }.map { $0.element }
        default:
            return checkins
        }
    }

    /// The key for remembering a group ("Health" = "health ").
    public static func key(_ category: String) -> String {
        category.trimmingCharacters(in: .whitespaces).lowercased()
    }

    /// (category, its check-ins in your order). Groups follow [order] (keys); the rest come
    /// after, A–Z, with uncategorized ("Other") last.
    public static func byCategory(_ checkins: [Checkin], order: [String] = []) -> [(String, [Checkin])] {
        var groups: [String: (String, [Checkin])] = [:]
        var keys: [String] = []
        var rest: [Checkin] = []
        for c in checkins {
            let name = c.category.trimmingCharacters(in: .whitespaces)
            if name.isEmpty {
                rest.append(c)
                continue
            }
            let k = name.lowercased()
            if groups[k] == nil {
                groups[k] = (name, [])
                keys.append(k)
            }
            groups[k]!.1.append(c)
        }
        var all = keys.map { groups[$0]! }.sorted { $0.0.caseInsensitiveCompare($1.0) == .orderedAscending }
        if !rest.isEmpty { all.append((other, rest)) }
        if order.isEmpty { return all }
        var pos: [String: Int] = [:]
        for (i, k) in order.enumerated() where pos[k] == nil { pos[k] = i }
        return all.enumerated().sorted { a, b in
            let x = pos[key(a.element.0)] ?? Int.max
            let y = pos[key(b.element.0)] ?? Int.max
            return x != y ? x < y : a.offset < b.offset
        }.map { $0.element }
    }

    /// Expand all / Collapse all: everything is folded when every group and check-in is.
    public static func allFolded(groups: Set<String>, foldedGroups: Set<String>, ids: Set<String>, foldedIds: Set<String>) -> Bool {
        groups.isSubset(of: foldedGroups) && ids.isSubset(of: foldedIds)
    }

    /// The categories in use, A–Z.
    public static func categories(_ checkins: [Checkin]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for c in checkins {
            let name = c.category.trimmingCharacters(in: .whitespaces)
            if !name.isEmpty && seen.insert(name.lowercased()).inserted { out.append(name) }
        }
        return out.sorted { $0.caseInsensitiveCompare($1) == .orderedAscending }
    }
}
