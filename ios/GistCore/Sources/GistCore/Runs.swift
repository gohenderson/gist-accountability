import Foundation

/// Notices runs from the step counter, the same way as the Android app. Running has a high,
/// steady cadence (usually 150–180 steps a minute; brisk walking is about 100–130), so a
/// cadence of at least [runCadence] for [startAfter] starts a run, and [endAfter] below it
/// (stopped, or back to walking) finishes it. Short dips (a traffic light) don't end a run;
/// a short dash for the bus doesn't start one.
///
/// Feed it the counter's running total with [steps], and call [tick] now and then so a run
/// can finish even when no more steps come in.
public struct RunDetector {
    public enum Event: Equatable {
        case started(at: Date)
        case finished(startedAt: Date, endedAt: Date)

        /// A finished run's length in whole minutes.
        public var minutes: Int? {
            if case let .finished(s, e) = self { return Int(e.timeIntervalSince(s) / 60) }
            return nil
        }
    }

    public let runCadence: Double
    let window: TimeInterval
    let startAfter: TimeInterval
    let endAfter: TimeInterval

    private var samples: [(at: Date, total: Int)] = []
    public private(set) var running = false
    private var fastSince: Date?
    private var runStart = Date.distantPast
    private var lastFast = Date.distantPast

    public init(runCadence: Double = 140, window: TimeInterval = 30, startAfter: TimeInterval = 60, endAfter: TimeInterval = 90) {
        self.runCadence = runCadence
        self.window = window
        self.startAfter = startAfter
        self.endAfter = endAfter
    }

    /// When the current run started, or nil when not running.
    public var runStartedAt: Date? { running ? runStart : nil }

    /// How long the current run has lasted at [now], in whole minutes.
    public func minutes(at now: Date) -> Int? {
        runStartedAt.map { Int(now.timeIntervalSince($0) / 60) }
    }

    public mutating func steps(_ total: Int, at time: Date) -> Event? {
        if let last = samples.last, total < last.total || time < last.at { samples.removeAll() } // counter restarted
        samples.append((time, total))
        while samples.count > 2 && samples[1].at < time.addingTimeInterval(-2 * window) { samples.removeFirst() }
        return evaluate(time)
    }

    public mutating func tick(_ now: Date) -> Event? { evaluate(now) }

    /// Steps per minute over the last [window] up to [now]; 0 until there's enough data.
    public func cadence(at now: Date) -> Double {
        guard let latest = samples.last, let first = samples.first else { return 0 }
        let base = samples.last { $0.at <= now.addingTimeInterval(-window) } ?? first
        let span = now.timeIntervalSince(base.at)
        if span < window / 2 { return 0 }
        return Double(latest.total - base.total) * 60 / span
    }

    private mutating func evaluate(_ now: Date) -> Event? {
        let fast = cadence(at: now) >= runCadence
        if !running {
            guard fast else {
                fastSince = nil
                return nil
            }
            let since = fastSince ?? now
            fastSince = since
            if now.timeIntervalSince(since) >= startAfter {
                running = true
                runStart = since.addingTimeInterval(-window / 2) // the cadence window lags a little behind
                lastFast = now
                return .started(at: runStart)
            }
            return nil
        }
        if fast {
            lastFast = now
            return nil
        }
        if now.timeIntervalSince(lastFast) >= endAfter {
            running = false
            fastSince = nil
            return .finished(startedAt: runStart, endedAt: lastFast)
        }
        return nil
    }
}

/// Which check-ins a run sets off. Same rules as Android.
public enum Runs {
    /// Do any check-ins that are on have run triggers?
    public static func anyUse(_ checkins: [Checkin]) -> Bool {
        checkins.contains { c in c.enabled && c.events.contains { $0.isMovement } }
    }

    /// Days, time window and once a day: shared by all event triggers.
    public static func conditionsHold(_ t: Trigger, now: Date, rangToday: Bool, calendar: Calendar = .current) -> Bool {
        t.days.matches(now, calendar: calendar) && Places.inWindow(t, now, calendar: calendar) && !(t.oncePerDay && rangToday)
    }

    /// Does [c] ring because a run started ([finishedMinutes] nil) or finished?
    public static func eventRings(_ c: Checkin, finishedMinutes: Int?, now: Date, rangToday: Bool,
                                  calendar: Calendar = .current) -> Bool {
        guard c.enabled else { return false }
        return c.events.contains { t in
            switch t.kind {
            case Trigger.runStart: guard finishedMinutes == nil else { return false }
            case Trigger.runEnd: guard let m = finishedMinutes, m >= t.minRunMinutes else { return false }
            default: return false
            }
            return conditionsHold(t, now: now, rangToday: rangToday, calendar: calendar)
        }
    }

    /// The current run has lasted [minutes]: [c]'s "N minutes into a run" triggers that are
    /// due and not in [fired] yet (keys from [milestoneKey]), each once per run.
    public static func progressRings(_ c: Checkin, minutes: Int, fired: Set<String>, now: Date, rangToday: Bool,
                                     calendar: Calendar = .current) -> [String] {
        guard c.enabled else { return [] }
        var keys: [String] = []
        for (i, t) in c.events.enumerated() where t.kind == Trigger.runFor && minutes >= max(1, t.minRunMinutes) {
            let key = milestoneKey(c, i, t)
            if fired.contains(key) { continue }
            if conditionsHold(t, now: now, rangToday: rangToday, calendar: calendar) { keys.append(key) }
        }
        return keys
    }

    public static func milestoneKey(_ c: Checkin, _ index: Int, _ t: Trigger) -> String {
        "\(c.id):\(index):\(t.minRunMinutes)"
    }
}

/// Voice check-ins: what to say, and timed ones that are due (iPhone speaks them only
/// while Gist is awake, so it checks every so often).
public enum Voice {
    public static func spokenText(_ c: Checkin) -> String {
        let t = c.voiceText.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? c.name : t
    }

    /// Voice check-ins' times that came due in the last [within] seconds up to [now] and
    /// haven't been spoken (or skipped) yet.
    public static func due(_ data: GistData, now: Date, within: TimeInterval = 180, calendar: Calendar = .current) -> [Pending] {
        var out: [Pending] = []
        for c in data.checkins where c.enabled && c.isVoice {
            let from = now.addingTimeInterval(-within)
            for o in Scheduler.occurrences(c, now: from, calendar: calendar, count: 12) where o.at > from && o.at <= now {
                if !data.isClosed(day: o.day, checkinId: c.id, time: o.timeKey) {
                    out.append(Pending(checkinId: c.id, day: o.day, time: o.timeKey))
                }
            }
        }
        return out
    }
}
