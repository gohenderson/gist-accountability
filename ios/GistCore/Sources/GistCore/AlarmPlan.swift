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
    /// A soft alarm ringing again after Snooze.
    public let snoozed: Bool

    public init(checkinId: String, day: String, time: String, at: Date, nag: Int = 0, practice: Bool = false, snoozed: Bool = false) {
        self.checkinId = checkinId
        self.day = day
        self.time = time
        self.at = at
        self.nag = nag
        self.practice = practice
        self.snoozed = snoozed
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

/// A soft alarm snoozed from its alarm: it rings again at [at], for the same day and time.
public struct Snooze: Equatable, Codable {
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
    public static func plan(_ data: GistData, waiting: [Pending], practice: [PracticeRing], snoozed: [Snooze] = [],
                            now: Date, calendar: Calendar = .current) -> [PlannedAlarm] {
        var out: [PlannedAlarm] = []
        for c in data.checkins where c.enabled && !c.isVoice {
            let next = Scheduler.next(c, now: now, calendar: calendar) {
                data.isClosed(day: $0.day, checkinId: c.id, time: $0.timeKey)
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
        // Snoozed soft alarms that aren't done or dismissed yet.
        for z in snoozed where z.at > now {
            guard let c = data.checkin(z.checkinId), c.enabled,
                  !data.isClosed(day: z.day, checkinId: z.checkinId, time: z.time) else { continue }
            out.append(PlannedAlarm(checkinId: z.checkinId, day: z.day, time: z.time, at: z.at, snoozed: true))
        }
        return out.sorted { $0.at < $1.at }
    }

    /// Rings since [since] (default: [lookbackHours] ago) up to [now] that aren't done or
    /// skipped: these are waiting. Each check-in's soonest first; check-ins that are off,
    /// and voice ones, never wait.
    public static func due(_ data: GistData, now: Date, since: Date? = nil, calendar: Calendar = .current) -> [Pending] {
        let from = since ?? now.addingTimeInterval(-Double(lookbackHours * 3600))
        var out: [(Date, Pending)] = []
        for c in data.checkins where c.enabled && !c.isVoice {
            for o in Scheduler.occurrences(c, now: from, calendar: calendar, count: 50) where o.at <= now {
                if !data.isClosed(day: o.day, checkinId: c.id, time: o.timeKey) {
                    out.append((o.at, Pending(checkinId: c.id, day: o.day, time: o.timeKey)))
                }
            }
        }
        return out.sorted { $0.0 < $1.0 }.map { $0.1 }
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
