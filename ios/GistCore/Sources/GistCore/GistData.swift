import Foundation

/// Everything the app keeps: check-ins (in your order) and results by day, check-in and time.
/// The rules for logging match the Android app's Store:
///   results["2026-11-07"]["<checkinId>"]["21:30"] = SlotResult(answered, answers: …)
public struct GistData: Equatable {
    /// The time now (tests can set it).
    public static var clock: () -> Date = { Date() }
    public static var nowMs: Int { Int(clock().timeIntervalSince1970 * 1000) }
    static let dayMs = 24 * 60 * 60 * 1000

    public var checkins: [Checkin] {
        // A task that's just gone into the backlog (new, or turned off) gets stamped, unless its
        // time was set with it (a restored backup). Loading saved data doesn't count.
        // While your devices are kept in step ([tracking]): a check-in changed here (not one
        // arriving from another device with its own time) gets [Checkin.updatedAt], a deleted
        // one is remembered in [deleted], and a reorder stamps [orderAt]. As on Android.
        didSet {
            let was = Dictionary(oldValue.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            let now = GistData.nowMs
            var stamped = false
            var list = checkins
            for i in list.indices {
                let c = list[i], old = was[c.id]
                if c.inBacklog && old?.inBacklog != true && c.backlogAt == old?.backlogAt {
                    list[i].backlogAt = now
                    stamped = true
                }
                if tracking, list[i].updatedAt == old?.updatedAt {
                    var a = list[i], b = old
                    a.updatedAt = nil
                    b?.updatedAt = nil
                    if b == nil || a != b {
                        list[i].updatedAt = now
                        stamped = true
                    }
                }
            }
            if tracking {
                let ids = Set(list.map { $0.id })
                for id in was.keys where !ids.contains(id) && deleted[id] == nil { deleted[id] = now }
                let before = oldValue.map { $0.id }.filter { ids.contains($0) }
                let after = list.map { $0.id }.filter { was[$0] != nil }
                if before != after { orderAt = now }
            }
            if stamped { checkins = list }
        }
    }
    /// Your devices are kept in step: stamp changes (see [checkins] and results' [SlotResult.w]).
    public var tracking = false
    /// Check-ins deleted while syncing: id → when (so your other devices delete them too).
    public var deleted: [String: Int] = [:]
    /// When your list's order last changed (here or on your other devices); 0 if never since syncing.
    public var orderAt = 0
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

    // MARK: Tasks

    /// Tasks that are on and not done.
    public var activeTasks: [Checkin] { checkins.filter { $0.isActiveTask } }
    /// Tasks that are off and not done.
    public var backlog: [Checkin] { checkins.filter { $0.inBacklog } }
    public var doneTasks: [Checkin] { checkins.filter { $0.taskDone } }

    /// Marks task [id] done: it turns off (no more rings) and moves to Done tasks.
    public mutating func completeTask(_ id: String, at: Int = GistData.nowMs) {
        update(id) { c in
            guard c.isTask else { return }
            c.enabled = false
            c.doneAt = at
        }
    }

    // MARK: Follow-ups

    public static let followUpPrefix = "(Follow-Up) "

    /// "(Follow-Up) Sand the patch": plain that it's the new task. One prefix, however deep.
    public static func followUpName(_ name: String) -> String {
        followUpPrefix + (name.hasPrefix(followUpPrefix) ? String(name.dropFirst(followUpPrefix.count)) : name)
    }

    /// A follow-up to task [sourceId] (the Android app's Store.createFollowUp): a new task with
    /// its name (prefixed), description, category, time commitment, commitment, sound, snooze
    /// and steps (fresh ids), but no triggers. [active]: today's list, or the backlog. Placed
    /// right after the task it follows.
    @discardableResult
    public mutating func createFollowUp(_ sourceId: String, active: Bool, newId: String = Ids.new()) -> Checkin? {
        guard let src = checkin(sourceId) else { return nil }
        var steps = src.steps
        for i in steps.indices { steps[i].id = Ids.new() }
        let c = Checkin(id: newId, name: GistData.followUpName(src.name), times: [], enabled: active, steps: steps,
                        soundUri: src.soundUri, soundTitle: src.soundTitle, category: src.category,
                        commitment: src.commitment, snoozeMinutes: src.snoozeMinutes,
                        voiceSource: src.voiceSource, voiceText: src.voiceText, voiceHeadphonesOnly: src.voiceHeadphonesOnly,
                        task: true, description: src.description, timeCommitment: src.timeCommitment, followsUp: src.id)
        let at = (checkins.firstIndex { $0.id == sourceId } ?? checkins.count - 1) + 1
        checkins.insert(c, at: at)
        return c
    }

    /// Tasks that follow up [id].
    public func followUps(of id: String) -> [Checkin] { checkins.filter { $0.followsUp == id } }

    /// Puts done task [id] back on the list (on again).
    public mutating func reopenTask(_ id: String) {
        update(id) { c in
            guard c.isTask else { return }
            c.enabled = true
            c.doneAt = nil
            c.doneBy = nil
        }
    }

    /// Tasks done in the last [days] days.
    public func tasksDone(inLast days: Int) -> Int {
        let since = GistData.nowMs - days * GistData.dayMs
        return checkins.filter { $0.taskDone && ($0.doneAt ?? 0) >= since }.count
    }

    /// Tasks that went into the backlog in the last [days] days (even if picked since).
    public func tasksBacklogged(inLast days: Int) -> Int {
        let since = GistData.nowMs - days * GistData.dayMs
        return checkins.filter { $0.isTask && ($0.backlogAt ?? 0) >= since }.count
    }

    /// The pick-tasks step: how many to pick from the backlog to have [count] active tasks
    /// (0: already enough, so the step is skipped; never more than the backlog has).
    public func tasksToPick(_ count: Int) -> Int {
        min(backlog.count, max(0, count - activeTasks.count))
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
    /// [values]: typed answers, "" for one left empty (which replaces one saved before).
    /// [took]: how long it took (fixing a day in History keeps the first time's).
    public mutating func saveAnswers(day: String, checkinId: String, time: String, answers: [String: Bool], early: Bool = false,
                                     values: [String: String] = [:], took: Took? = nil) {
        let prev = result(day: day, checkinId: checkinId, time: time)
        let merged = (prev?.answers ?? [:]).merging(answers) { _, new in new }
        let mergedValues = (prev?.values ?? [:]).merging(values) { _, new in new }
        put(day, checkinId, time, SlotResult(SlotResult.answered, answers: merged, early: early || prev?.early == true,
                                             values: mergedValues, took: took ?? prev?.took))
    }

    /// A check-in without questions was completed.
    public mutating func markDone(day: String, checkinId: String, time: String, early: Bool = false, took: Took? = nil) {
        put(day, checkinId, time, SlotResult(SlotResult.done, early: early, took: took))
    }

    /// The alarm rang (at [at], epoch ms). Doesn't overwrite a time that's already rung,
    /// finished or skipped.
    public mutating func markRang(day: String, checkinId: String, time: String, at: Int = GistData.nowMs) {
        if result(day: day, checkinId: checkinId, time: time) == nil {
            put(day, checkinId, time, SlotResult(SlotResult.rang, rangAt: at))
        }
    }

    /// How long a check-in took, from when this time first rang ([rangAt], nil if done before
    /// it rang), [startedAt] (Start check-in) and [doneAt], all epoch ms. Only for check-ins
    /// that count.
    public func took(day: String, checkinId: String, time: String, startedAt: Int, doneAt: Int = GistData.nowMs) -> Took? {
        guard checkin(checkinId)?.counted == true else { return nil }
        let rang = result(day: day, checkinId: checkinId, time: time)?.rangAt
        let start = rang.map { max(0, (startedAt - $0) / 1000) }
        return Took(start: start, steps: max(0, (doneAt - startedAt) / 1000))
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

    /// Results logged while a check-in isn't strict are marked soft (see allResults). Doing a
    /// task once completes it.
    private mutating func put(_ day: String, _ checkinId: String, _ time: String, _ r: SlotResult) {
        var r = r
        if let c = checkin(checkinId), !c.counted { r.soft = true }
        if tracking { r.w = GistData.nowMs } // the later answer wins between your devices
        results[day, default: [:]][checkinId, default: [:]][time] = r
        if GistData.taskDoneStatuses.contains(r.status), let c = checkin(checkinId), c.isTask, c.doneAt == nil {
            completeTask(c.id)
        }
    }

    /// Results that complete a task: done, answered, or (soft) dismissed.
    static let taskDoneStatuses: Set<String> = [SlotResult.done, SlotResult.answered, SlotResult.dismissed]

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
        var o = JSONObject([
            ("checkins", .array(checkins.map { c -> JSON in c.json })),
            ("entries", .object(GistData.entriesJSON(results))),
        ])
        // Kept for your devices (not part of backups).
        if !deleted.isEmpty || orderAt > 0 {
            o["sync"] = .object(JSONObject([
                ("deleted", .object(JSONObject(deleted.keys.sorted().map { ($0, .int(deleted[$0]!)) }))),
                ("orderAt", .int(orderAt)),
            ]))
        }
        return .object(o)
    }

    public static func from(_ v: JSON) throws -> GistData {
        guard let o = v.objectValue else { throw BackupError("Saved data is damaged.") }
        let checkins = try (o.array("checkins") ?? []).compactMap { $0.objectValue }.map { try Checkin.from($0) }
        var d = GistData(checkins: checkins, results: entries(from: o.object("entries") ?? JSONObject()))
        if let s = o.object("sync") {
            for (k, v) in (s.object("deleted") ?? JSONObject()).pairs { if let n = v.intValue { d.deleted[k] = n } }
            d.orderAt = s.int("orderAt", 0)
        }
        return d
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
