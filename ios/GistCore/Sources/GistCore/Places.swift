import Foundation

/// Places on iPhone. iOS watches each place's circle and says when you go in or out (even
/// with Gist closed); Gist keeps a short log of those crossings ([PlaceLog]) so it knows
/// whether you were at a place at any recent moment. From that:
///  - arrive / leave triggers ring when you cross (see [eventRings]);
///  - a time that rings only "at" or "away from" a place is checked against where you were
///    at that time; if it doesn't hold, the time is skipped. Unknown: it rings anyway (as on
///    Android: better a ring too many than a missed one).
public enum Places {
    /// iOS can watch up to 20 circles per app; Gist leaves room to spare.
    public static let maxWatched = 18
    /// iOS often reports "inside" right after a circle is added; crossings this soon after
    /// don't ring (they're still logged).
    public static let settleSeconds: TimeInterval = 90

    /// Every distinct place [checkins] need watched (on ones that are on), by key.
    public static func watched(_ checkins: [Checkin]) -> [String: Place] {
        var out: [String: Place] = [:]
        for c in checkins where c.enabled && !c.isVoice {
            for t in c.events where t.isPlace { if let p = t.place { out[p.key] = p } }
            for t in c.times where t.usesLocation { if let p = t.place { out[p.key] = p } }
        }
        return out
    }

    /// Does [time]'s place condition allow it to ring at [at]? No condition, or not known
    /// where you were: yes.
    public static func allows(_ time: AlarmTime, at: Date, log: PlaceLog) -> Bool {
        guard time.usesLocation, let p = time.place, let inside = log.inside(p.key, at: at) else { return true }
        return time.where == Trigger.at ? inside : !inside
    }

    /// Is [now] inside [t]'s time window? No window: any time. 22:00–02:00 crosses midnight.
    public static func inWindow(_ t: Trigger, _ now: Date, calendar: Calendar = .current) -> Bool {
        guard let s = t.windowStart, let e = t.windowEnd else { return true }
        let n = calendar.component(.hour, from: now) * 60 + calendar.component(.minute, from: now)
        let sm = s.minutesOfDay, em = e.minutesOfDay
        return sm <= em ? (sm...em).contains(n) : (n >= sm || n <= em)
    }

    /// Should [c] ring because you just went into ([entering]) or out of the place [key]?
    /// Returns the trigger that fires, or nil. [rangToday]: it already rang (or was done)
    /// today, for "at most once a day".
    public static func eventRings(_ c: Checkin, key: String, entering: Bool, now: Date, rangToday: Bool,
                                  calendar: Calendar = .current) -> Trigger? {
        guard c.enabled, !c.isVoice else { return nil }
        return c.events.first { t in
            guard t.isPlace, t.place?.key == key, (t.kind == Trigger.arrive) == entering else { return false }
            guard t.days.matches(now, calendar: calendar), inWindow(t, now, calendar: calendar) else { return false }
            return !(t.oncePerDay && rangToday)
        }
    }

    /// Has [checkinId] rung, or been done, on [day] (skips don't count)?
    public static func rang(_ data: GistData, checkinId: String, day: String, waiting: [Pending]) -> Bool {
        (data.results[day]?[checkinId]?.values.contains { $0.status != SlotResult.skipped } ?? false)
            || waiting.contains { $0.checkinId == checkinId && !$0.practice && $0.day == day }
    }

    /// "at Home", "away from Home", "anywhere"
    public static func whereText(_ where: String, _ place: Place?) -> String {
        guard let place, `where` != Trigger.anywhere else { return "anywhere" }
        return `where` == Trigger.at ? "at \(place.name)" : "away from \(place.name)"
    }
}

extension Place {
    /// Identifies the circle (name aside), so check-ins sharing a place share one watch.
    public var key: String { String(format: "%.6f,%.6f,%d", lat, lon, radiusM) }
}

/// Going into or out of a place, as iOS reported it.
public struct PlaceCrossing: Equatable, Codable {
    public let key: String
    public let inside: Bool
    public let at: Date

    public init(key: String, inside: Bool, at: Date) {
        self.key = key
        self.inside = inside
        self.at = at
    }
}

/// Recent crossings, to tell where you were at a given moment.
public struct PlaceLog: Equatable, Codable {
    /// Oldest first.
    public private(set) var crossings: [PlaceCrossing] = []
    /// Kept this long (each place's latest is always kept).
    public static let keepHours = 48

    public init(_ crossings: [PlaceCrossing] = []) {
        self.crossings = crossings.sorted { $0.at < $1.at }
    }

    /// Inside [key] at [at]? nil: not known (nothing logged for it by then).
    public func inside(_ key: String, at: Date) -> Bool? {
        crossings.last { $0.key == key && $0.at <= at }?.inside
    }

    /// Inside [key] now (by the latest crossing)?
    public func inside(_ key: String) -> Bool? {
        crossings.last { $0.key == key }?.inside
    }

    /// Logs a crossing. Returns false if it changes nothing (iOS repeating the same state).
    @discardableResult
    public mutating func record(_ c: PlaceCrossing) -> Bool {
        if inside(c.key) == c.inside { return false }
        crossings.append(c)
        crossings.sort { $0.at < $1.at }
        return true
    }

    /// Drops places no longer watched, and crossings older than [keepHours] except each
    /// place's latest.
    public mutating func prune(keeping keys: Set<String>, now: Date) {
        let cutoff = now.addingTimeInterval(-Double(Self.keepHours * 3600))
        var latest: [String: Date] = [:]
        for c in crossings { latest[c.key] = c.at }
        crossings = crossings.filter { c in keys.contains(c.key) && (c.at >= cutoff || latest[c.key] == c.at) }
    }
}
