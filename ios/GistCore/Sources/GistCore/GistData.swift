import Foundation

/// Everything the app keeps: check-ins (in your order) and results by day, check-in and time.
/// The rules for logging match the Android app's Store:
///   results["2026-11-07"]["<checkinId>"]["21:30"] = SlotResult(answered, answers: …)
public struct GistData: Equatable {
    public var checkins: [Checkin]
    /// day → check-in id → time → result
    public var results: [String: [String: DayResults]]

    public init(checkins: [Checkin] = Checkin.defaults, results: [String: [String: DayResults]] = [:]) {
        self.checkins = checkins
        self.results = results
    }

    // MARK: Check-ins

    public func checkin(_ id: String) -> Checkin? { checkins.first { $0.id == id } }

    public mutating func update(_ id: String, _ change: (inout Checkin) -> Void) {
        guard let i = checkins.firstIndex(where: { $0.id == id }) else { return }
        change(&checkins[i])
    }

    // MARK: Reading results

    /// One check-in's results on one day, by time.
    public func results(day: String, checkinId: String) -> DayResults {
        results[day]?[checkinId] ?? [:]
    }

    public func result(day: String, checkinId: String, time: String) -> SlotResult? {
        results[day]?[checkinId]?[time]
    }

    /// Is this time finished or skipped (so its alarm shouldn't ring)?
    public func isClosed(day: String, checkinId: String, time: String) -> Bool {
        result(day: day, checkinId: checkinId, time: time)?.closed == true
    }

    /// What the stats read: soft and voice results are left out (unless [includeSoft]), and a
    /// check-in that isn't strict right now has no stats at all.
    public func allResults(_ checkinId: String, includeSoft: Bool = false) -> History {
        if !includeSoft, let c = checkin(checkinId), !c.counted { return [:] }
        var out: History = [:]
        for (day, byCheckin) in results {
            guard let slots = byCheckin[checkinId] else { continue }
            let kept = slots.filter { includeSoft || !$0.value.soft }
            if !kept.isEmpty { out[day] = kept }
        }
        return out
    }

    /// Everything [checkinId] logged, soft results included (for backups).
    public func history(_ checkinId: String) -> History { allResults(checkinId, includeSoft: true) }

    // MARK: Logging

    /// Answers for one time, merged into any already saved for it.
    public mutating func saveAnswers(day: String, checkinId: String, time: String, answers: [String: Bool], early: Bool = false) {
        let prev = result(day: day, checkinId: checkinId, time: time)
        let merged = (prev?.answers ?? [:]).merging(answers) { _, new in new }
        put(day, checkinId, time, SlotResult(SlotResult.answered, answers: merged, early: early || prev?.early == true))
    }

    /// A check-in without questions was completed.
    public mutating func markDone(day: String, checkinId: String, time: String, early: Bool = false) {
        put(day, checkinId, time, SlotResult(SlotResult.done, early: early))
    }

    /// The alarm rang. Doesn't overwrite a time that's already finished or skipped.
    public mutating func markRang(day: String, checkinId: String, time: String) {
        if result(day: day, checkinId: checkinId, time: time) == nil {
            put(day, checkinId, time, SlotResult(SlotResult.rang))
        }
    }

    public mutating func markSkipped(day: String, checkinId: String, time: String) {
        put(day, checkinId, time, SlotResult(SlotResult.skipped))
    }

    /// A soft alarm was dismissed without doing its steps.
    public mutating func markDismissed(day: String, checkinId: String, time: String) {
        put(day, checkinId, time, SlotResult(SlotResult.dismissed))
    }

    /// Forget a time's result (e.g. undo a skip).
    public mutating func clearResult(day: String, checkinId: String, time: String) {
        results[day]?[checkinId]?[time] = nil
        if results[day]?[checkinId]?.isEmpty == true { results[day]?[checkinId] = nil }
        if results[day]?.isEmpty == true { results[day] = nil }
    }

    /// Results logged while a check-in isn't strict are marked soft (see allResults).
    private mutating func put(_ day: String, _ checkinId: String, _ time: String, _ r: SlotResult) {
        var r = r
        if let c = checkin(checkinId), !c.counted { r.soft = true }
        results[day, default: [:]][checkinId, default: [:]][time] = r
    }

    // MARK: History as a whole

    /// Forgets everything [checkinId] logged.
    public mutating func deleteHistory(_ checkinId: String) {
        for day in Array(results.keys) {
            results[day]?[checkinId] = nil
            if results[day]?.isEmpty == true { results[day] = nil }
        }
    }

    /// Writes [history] for [checkinId]. With [merge], times already logged and not in
    /// [history] are kept; without it, the check-in's history is replaced.
    public mutating func putHistory(_ checkinId: String, _ history: History, merge: Bool) {
        if !merge { deleteHistory(checkinId) }
        for (day, slots) in history {
            for (time, r) in slots {
                results[day, default: [:]][checkinId, default: [:]][time] = r
            }
        }
    }

    // MARK: Saving

    /// How the app saves this on the phone (the same shapes as a backup's checkins / entries).
    public var json: JSON {
        .object(JSONObject([
            ("checkins", .array(checkins.map { c -> JSON in c.json })),
            ("entries", .object(GistData.entriesJSON(results))),
        ]))
    }

    public static func from(_ v: JSON) throws -> GistData {
        guard let o = v.objectValue else { throw BackupError("Saved data is damaged.") }
        let checkins = try (o.array("checkins") ?? []).compactMap { $0.objectValue }.map { try Checkin.from($0) }
        return GistData(checkins: checkins, results: entries(from: o.object("entries") ?? JSONObject()))
    }

    /// results → { day: { checkin: { time: {s, a, early} } } }, days in order.
    static func entriesJSON(_ results: [String: [String: DayResults]]) -> JSONObject {
        var out = JSONObject()
        for day in results.keys.sorted() {
            var d = JSONObject()
            for id in results[day]!.keys.sorted() {
                var c = JSONObject()
                for time in results[day]![id]!.keys.sorted() { c[time] = results[day]![id]![time]!.json }
                d[id] = .object(c)
            }
            out[day] = .object(d)
        }
        return out
    }

    static func entries(from o: JSONObject) -> [String: [String: DayResults]] {
        var out: [String: [String: DayResults]] = [:]
        for (day, dv) in o.pairs {
            guard let d = dv.objectValue else { continue }
            for (id, cv) in d.pairs {
                guard let c = cv.objectValue else { continue }
                for (time, rv) in c.pairs {
                    guard let r = rv.objectValue else { continue }
                    out[day, default: [:]][id, default: [:]][time] = SlotResult.from(r)
                }
            }
        }
        return out
    }
}
