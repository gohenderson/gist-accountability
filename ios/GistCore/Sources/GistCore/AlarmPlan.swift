import Foundation

/// A check-in that has rung and is waiting to be done: which day it counts for and which of
/// its times rang. [practice]: a test ring, which logs nothing.
public struct Pending: Equatable, Hashable, Codable, Identifiable {
    public let checkinId: String
    public let day: String
    public let time: String
    public let practice: Bool

    public var id: String { "\(checkinId)|\(day)|\(time)|\(practice)" }

    public init(checkinId: String, day: String, time: String, practice: Bool = false) {
        self.checkinId = checkinId
        self.day = day
        self.time = time
        self.practice = practice
    }
}

/// One alarm to hand to the system. [nag] 0 is the ring itself; 1, 2, … are the re-rings
/// that follow a strict check-in until it's done.
public struct PlannedAlarm: Equatable {
    public let checkinId: String
    public let day: String
    public let time: String
    public let at: Date
    public let nag: Int
    public let practice: Bool
    /// A one-off ring: a snoozed soft alarm, or arriving at or leaving a place.
    public let oneOff: Bool

    public init(checkinId: String, day: String, time: String, at: Date, nag: Int = 0, practice: Bool = false, oneOff: Bool = false) {
        self.checkinId = checkinId
        self.day = day
        self.time = time
        self.at = at
        self.nag = nag
        self.practice = practice
        self.oneOff = oneOff
    }

    public var pending: Pending { Pending(checkinId: checkinId, day: day, time: time, practice: practice) }
}

/// A test ring set up on a check-in's page.
public struct PracticeRing: Equatable, Codable {
    public let checkinId: String
    public let at: Date

    public init(checkinId: String, at: Date) {
        self.checkinId = checkinId
        self.at = at
    }
}

/// A ring outside the check-in's set times, for [day] and [time]: a soft alarm snoozed
/// from its alarm, or one set off by arriving at or leaving a place.
public struct OneOffRing: Equatable, Codable {
    public let checkinId: String
    public let day: String
    public let time: String
    public let at: Date

    public init(checkinId: String, day: String, time: String, at: Date) {
        self.checkinId = checkinId
        self.day = day
        self.time = time
        self.at = at
    }
}

/// What the iPhone app schedules. iOS shows the alarm itself and anyone can stop it there,
/// so a strict check-in that isn't done rings again every [nagMinutes] (up to [nagCount]
/// times) until it is: the iPhone's version of "you can't get out of it".
public enum AlarmPlan {
    public static let nagMinutes = 3
    public static let nagCount = 5
    /// How far back a ring still counts as waiting (rings older than this are just missed).
    public static let lookbackHours = 12

    /// Every alarm to schedule now: each ringing check-in's next open ring (strict ones with
    /// their re-rings after it), re-rings for strict check-ins still [waiting], and [practice]
    /// rings still ahead. Voice check-ins (Android only for now) and ones that are off don't ring.
    /// A time that rings only at (or away from) a place is left out while you're known not
    /// to be (per [places], as of now); crossing into or out of the place reschedules.
    public static func plan(_ data: GistData, waiting: [Pending], practice: [PracticeRing], oneOff: [OneOffRing] = [],
                            places: PlaceLog = PlaceLog(), now: Date, calendar: Calendar = .current) -> [PlannedAlarm] {
        var out: [PlannedAlarm] = []
        for c in data.checkins where c.enabled && !c.isVoice {
            let next = Scheduler.next(c, now: now, calendar: calendar) {
                data.isClosed(day: $0.day, checkinId: c.id, time: $0.timeKey) || !Places.allows($0.time, at: now, log: places)
            }
            guard let next else { continue }
            out.append(PlannedAlarm(checkinId: c.id, day: next.day, time: next.timeKey, at: next.at))
            if c.counted {
                for n in 1...nagCount {
                    out.append(PlannedAlarm(checkinId: c.id, day: next.day, time: next.timeKey,
                                            at: next.at.addingTimeInterval(Double(n * nagMinutes * 60)), nag: n))
                }
            }
        }
        // Strict check-ins that rang and aren't done: keep ringing from now on.
        for p in waiting where !p.practice {
            guard let c = data.checkin(p.checkinId), c.enabled, c.counted,
                  !data.isClosed(day: p.day, checkinId: p.checkinId, time: p.time) else { continue }
            for n in 1...nagCount {
                out.append(PlannedAlarm(checkinId: c.id, day: p.day, time: p.time,
                                        at: now.addingTimeInterval(Double(n * nagMinutes * 60)), nag: n))
            }
        }
        for r in practice where r.at > now && data.checkin(r.checkinId) != nil {
            out.append(PlannedAlarm(checkinId: r.checkinId, day: Days.today(r.at, calendar: calendar),
                                    time: AlarmTime(calendar.component(.hour, from: r.at), calendar.component(.minute, from: r.at)).description,
                                    at: r.at, practice: true))
        }
        // Snoozed soft alarms and place rings that aren't done or dismissed yet.
        for z in oneOff where z.at > now {
            guard let c = data.checkin(z.checkinId), c.enabled,
                  !data.isClosed(day: z.day, checkinId: z.checkinId, time: z.time) else { continue }
            out.append(PlannedAlarm(checkinId: z.checkinId, day: z.day, time: z.time, at: z.at, oneOff: true))
        }
        return out.sorted { $0.at < $1.at }
    }

    /// Rings since [since] (default: [lookbackHours] ago) up to [now] that aren't done or
    /// skipped: these are waiting. Each check-in's soonest first; check-ins that are off,
    /// and voice ones, never wait. Times whose place condition didn't hold then (per
    /// [places]) aren't included: see [placeSkips].
    public static func due(_ data: GistData, now: Date, since: Date? = nil, places: PlaceLog = PlaceLog(),
                           calendar: Calendar = .current) -> [Pending] {
        openRings(data, now: now, since: since, calendar: calendar).filter { Places.allows($0.0, at: $0.1, log: places) }.map { $0.2 }
    }

    /// Times that came due without their place condition holding (you weren't at, or were
    /// at, the place): these are marked skipped, as on Android.
    public static func placeSkips(_ data: GistData, now: Date, since: Date? = nil, places: PlaceLog,
                                  calendar: Calendar = .current) -> [Pending] {
        openRings(data, now: now, since: since, calendar: calendar).filter { !Places.allows($0.0, at: $0.1, log: places) }.map { $0.2 }
    }

    private static func openRings(_ data: GistData, now: Date, since: Date?, calendar: Calendar) -> [(AlarmTime, Date, Pending)] {
        let from = since ?? now.addingTimeInterval(-Double(lookbackHours * 3600))
        var out: [(AlarmTime, Date, Pending)] = []
        for c in data.checkins where c.enabled && !c.isVoice {
            for o in Scheduler.occurrences(c, now: from, calendar: calendar, count: 50) where o.at <= now {
                if !data.isClosed(day: o.day, checkinId: c.id, time: o.timeKey) {
                    out.append((o.time, o.at, Pending(checkinId: c.id, day: o.day, time: o.timeKey)))
                }
            }
        }
        return out.sorted { $0.1 < $1.1 }
    }

    /// Practice rings that have gone off by [now].
    public static func duePractice(_ practice: [PracticeRing], now: Date, calendar: Calendar = .current) -> [Pending] {
        practice.filter { $0.at <= now }.map { r in
            Pending(checkinId: r.checkinId, day: Days.today(r.at, calendar: calendar),
                    time: AlarmTime(calendar.component(.hour, from: r.at), calendar.component(.minute, from: r.at)).description,
                    practice: true)
        }
    }
}
