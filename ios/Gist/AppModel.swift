import Foundation
import GistCore
import Observation
import SwiftUI

/// A check-in to run full screen: one that's waiting (rang), a test ring, or "Do it now".
struct RunRequest: Identifiable, Equatable {
    let pending: Pending
    let early: Bool
    var id: String { pending.id + (early ? "|early" : "") }
}

/// Everything the app shows and changes. Check-ins and results are saved as YAML (the same
/// shapes as a backup) in the app's private storage; view settings in UserDefaults.
@MainActor
@Observable
final class AppModel {
    private(set) var data: GistData
    /// Rings that went off and aren't done yet, oldest first.
    private(set) var waiting: [Pending] = []
    private(set) var practice: [PracticeRing] = []
    /// What's showing full screen.
    var running: RunRequest?
    var alarmsAllowed = false
    var alarmsAsked = false
    /// A short message to show (e.g. after an import).
    var notice: String?
    /// A screen to open at launch (debug builds, for screenshots): "edit:<id>", "history:<id>",
    /// "settings" or "run:<id>".
    var debugRoute: String?

    private(set) var groupByCategory: Bool
    private(set) var sort: String
    private(set) var foldedCheckins: Set<String>
    private(set) var foldedGroups: Set<String>
    private(set) var groupOrder: [String]

    private let defaults = UserDefaults.standard
    private let fileURL: URL
    let calendar = Calendar.current

    init() {
        let dir = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                 appropriateFor: nil, create: true)) ?? FileManager.default.temporaryDirectory
        fileURL = dir.appendingPathComponent("gist-data.yaml")
        if let text = try? String(contentsOf: fileURL, encoding: .utf8),
           let json = try? YAMLCodec.load(text), let loaded = try? GistData.from(json) {
            data = loaded
        } else {
            data = GistData()
        }
        groupByCategory = defaults.string(forKey: "view") == Grouping.byCategoryView
        sort = defaults.string(forKey: "sort").flatMap { Grouping.sorts.contains($0) ? $0 : nil } ?? Grouping.sortCustom
        foldedCheckins = Set(defaults.stringArray(forKey: "foldedCheckins") ?? [])
        foldedGroups = Set(defaults.stringArray(forKey: "foldedGroups") ?? [])
        groupOrder = defaults.stringArray(forKey: "groupOrder") ?? []
        waiting = Self.decode([Pending].self, defaults.data(forKey: "waiting")) ?? []
        practice = Self.decode([PracticeRing].self, defaults.data(forKey: "practice")) ?? []
        alarmsAllowed = AlarmService.authorized
        alarmsAsked = !AlarmService.notDetermined
    }

    // MARK: - Reading

    var checkins: [Checkin] { data.checkins }

    func checkin(_ id: String) -> Checkin? { data.checkin(id) }

    /// The on-track score of the last 7 days (nil: off, not strict, or nothing logged).
    func score(_ c: Checkin) -> Double? {
        guard c.enabled else { return nil }
        return Score.of(c, data.allResults(c.id), days: Days.lastN(Score.days, calendar: calendar))
    }

    func statsLine(_ c: Checkin) -> String {
        Stats.line(c, data.allResults(c.id), days: Days.lastN(7, calendar: calendar))
    }

    /// The next ring that isn't done or skipped.
    func next(_ c: Checkin) -> Occurrence? {
        guard c.enabled else { return nil }
        return Scheduler.next(c, calendar: calendar) { data.isClosed(day: $0.day, checkinId: c.id, time: $0.timeKey) }
    }

    /// The soonest upcoming ring if it was skipped (so it can be undone).
    func skippedNext(_ c: Checkin) -> Occurrence? {
        guard c.enabled, let first = Scheduler.occurrences(c, calendar: calendar, count: 1).first,
              data.result(day: first.day, checkinId: c.id, time: first.timeKey)?.status == SlotResult.skipped else { return nil }
        return first
    }

    func history(_ c: Checkin) -> History { data.allResults(c.id) }

    // MARK: - Alarms

    /// Brings everything up to date: rings that went off while the app was closed are
    /// logged (as missed until done) and wait to be done; strict ones open full screen.
    func refresh() async {
        alarmsAllowed = AlarmService.authorized
        alarmsAsked = !AlarmService.notDetermined
        let now = Date()
        let due = AlarmPlan.due(data, now: now, calendar: calendar)
        for p in due { data.markRang(day: p.day, checkinId: p.checkinId, time: p.time) }
        let practiceDue = AlarmPlan.duePractice(practice, now: now, calendar: calendar)
        practice.removeAll { $0.at <= now }
        var all: [Pending] = []
        for p in waiting + due + practiceDue where stillWaiting(p) && !all.contains(p) { all.append(p) }
        waiting = all
        save()
        presentNext()
        await reschedule()
    }

    func allowAlarms() async {
        _ = await AlarmService.requestAuthorization()
        await refresh()
    }

    private func stillWaiting(_ p: Pending) -> Bool {
        guard let c = data.checkin(p.checkinId) else { return false }
        if p.practice { return true }
        return c.enabled && !data.isClosed(day: p.day, checkinId: p.checkinId, time: p.time)
    }

    /// Strict check-ins and test rings open full screen by themselves; soft ones wait on the
    /// start page.
    private func presentNext() {
        guard running == nil else { return }
        if let p = waiting.first(where: { p in p.practice || data.checkin(p.checkinId)?.counted == true }) {
            running = RunRequest(pending: p, early: false)
        }
    }

    func reschedule() async {
        let plan = AlarmPlan.plan(data, waiting: waiting, practice: practice, snoozed: SnoozeStore.all(),
                                  now: Date(), calendar: calendar)
        let checkins = Dictionary(data.checkins.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        await AlarmService.replaceAll(with: plan, checkins: checkins)
    }

    private func rescheduleSoon() {
        Task { await reschedule() }
    }

    /// A test ring in 10 seconds: it runs the steps, logs nothing, and leaves real rings alone.
    func testRing(_ c: Checkin) {
        practice.append(PracticeRing(checkinId: c.id, at: Date().addingTimeInterval(10)))
        save()
        rescheduleSoon()
        notice = alarmsAllowed
            ? "Ringing in 10 seconds. Lock your phone to see it as an alarm. Nothing is logged."
            : "Allow alarms first (on the start page), then test again."
    }

    // MARK: - Running a check-in

    func start(_ p: Pending) { running = RunRequest(pending: p, early: false) }

    /// "Do it now": run the next ring early; that time's alarm then stays quiet.
    func doNow(_ c: Checkin) {
        guard let o = next(c) else { return }
        running = RunRequest(pending: Pending(checkinId: c.id, day: o.day, time: o.timeKey), early: true)
    }

    /// The steps are done: log the answers (nothing for a test).
    func finish(_ r: RunRequest, answers: [String: Bool]) {
        let p = r.pending
        if !p.practice, let c = data.checkin(p.checkinId) {
            if c.questions.isEmpty {
                data.markDone(day: p.day, checkinId: p.checkinId, time: p.time, early: r.early)
            } else {
                data.saveAnswers(day: p.day, checkinId: p.checkinId, time: p.time, answers: answers, early: r.early)
            }
        }
        close(r)
    }

    /// A soft check-in dismissed without its steps, or a test ended.
    func dismiss(_ r: RunRequest) {
        let p = r.pending
        if !p.practice, !r.early, data.checkin(p.checkinId)?.isSoft == true {
            data.markDismissed(day: p.day, checkinId: p.checkinId, time: p.time)
        }
        close(r)
    }

    private func close(_ r: RunRequest) {
        waiting.removeAll { $0 == r.pending }
        running = nil
        save()
        rescheduleSoon()
        Task {
            try? await Task.sleep(for: .milliseconds(600))
            presentNext()
        }
    }

    func skipNext(_ c: Checkin) {
        guard let o = next(c) else { return }
        data.markSkipped(day: o.day, checkinId: c.id, time: o.timeKey)
        save()
        rescheduleSoon()
    }

    func undoSkip(_ c: Checkin, _ o: Occurrence) {
        data.clearResult(day: o.day, checkinId: c.id, time: o.timeKey)
        save()
        rescheduleSoon()
    }

    // MARK: - Editing

    func save(_ c: Checkin) {
        if data.checkin(c.id) == nil {
            data.checkins.append(c)
        } else {
            data.update(c.id) { $0 = c }
        }
        save()
        rescheduleSoon()
    }

    func setEnabled(_ id: String, _ on: Bool) {
        data.update(id) { $0.enabled = on }
        save()
        rescheduleSoon()
    }

    /// Adds a copy of a library template, or a blank check-in (ringing at noon, no steps yet).
    func addCheckin(from template: Library.Template? = nil) -> Checkin {
        let c = template.map { Library.instantiate($0, existing: data.checkins) } ?? Library.blank(existing: data.checkins)
        save(c)
        return c
    }

    func delete(_ id: String) {
        data.checkins.removeAll { $0.id == id }
        data.deleteHistory(id)
        waiting.removeAll { $0.checkinId == id }
        practice.removeAll { $0.checkinId == id }
        save()
        rescheduleSoon()
    }

    func move(from: IndexSet, to: Int) {
        data.checkins.move(fromOffsets: from, toOffset: to)
        save()
    }

    // MARK: - View settings

    func setGroupByCategory(_ on: Bool) {
        groupByCategory = on
        defaults.set(on ? Grouping.byCategoryView : Grouping.ungrouped, forKey: "view")
    }

    func setSort(_ how: String) {
        sort = how
        defaults.set(how, forKey: "sort")
    }

    func toggleFolded(_ id: String) {
        if foldedCheckins.contains(id) { foldedCheckins.remove(id) } else { foldedCheckins.insert(id) }
        defaults.set(Array(foldedCheckins), forKey: "foldedCheckins")
    }

    func toggleGroup(_ key: String) {
        if foldedGroups.contains(key) { foldedGroups.remove(key) } else { foldedGroups.insert(key) }
        defaults.set(Array(foldedGroups), forKey: "foldedGroups")
    }

    func setAllFolded(_ folded: Bool, groups: Set<String>, ids: Set<String>) {
        if folded {
            foldedGroups.formUnion(groups)
            foldedCheckins.formUnion(ids)
        } else {
            foldedGroups.subtract(groups)
            foldedCheckins.subtract(ids)
        }
        defaults.set(Array(foldedGroups), forKey: "foldedGroups")
        defaults.set(Array(foldedCheckins), forKey: "foldedCheckins")
    }

    // MARK: - Backups

    /// Everything, with history, as a backup file (the same format as the Android app's).
    func backupText() throws -> String { try Backup.export(data) }

    /// Imports every check-in in [text]: ones you have (same id or name) are updated, with the
    /// file's history added; the rest are added. Returns how many.
    func importBackup(_ text: String) throws -> Int {
        let contents = try Backup.parse(text)
        let choices = contents.checkins.map { c in
            Backup.ImportChoice(c.id, mode: Backup.match(data.checkins, c) == nil ? .new : .update, withHistory: true)
        }
        let written = Backup.importing(contents, choices: choices, into: &data)
        // Photos, video and voice clips aren't on iPhone yet: show none rather than a broken one.
        for w in written where w.checkin.background.hasMedia {
            data.update(w.checkin.id) { $0.background.kind = Background.none }
        }
        save()
        rescheduleSoon()
        return written.count
    }

    // MARK: - Screenshots (debug builds only)

    #if DEBUG
    /// Demo history for screenshots: the last 10 days of the default check-ins, mostly on track.
    func loadDemo() {
        data = GistData()
        data.checkins[0].category = "Food"
        data.checkins[1].category = "Food"
        data.checkins.append(Checkin(id: "wake", name: "Wake up", times: [
            AlarmTime(4, 0, days: Repeat(Repeat.weekly, [2])),
            AlarmTime(4, 45, days: Repeat(Repeat.weekly, [1, 3, 4, 5, 6, 7])),
        ], steps: [StepConfig(id: "wake-m", kind: StepConfig.math, mathCount: 10, mathLevel: 3)], category: "Morning"))
        data.checkins.append(Checkin(id: "stretch", name: "Stand and stretch", times: [AlarmTime(15, 0)],
                                     repeat: Repeat(Repeat.weekly, Repeat.weekdays), commitment: Checkin.soft))
        for (i, day) in Days.lastN(10, calendar: calendar).enumerated() where i > 0 {
            data.saveAnswers(day: day, checkinId: "morning", time: "07:00",
                             answers: ["latefood": i % 4 == 0, "alcohol": false])
            data.saveAnswers(day: day, checkinId: "evening", time: "21:30",
                             answers: ["sweetdrink": i % 3 == 0, "fastfood": false, "sweets": i % 5 == 0, "veg": true, "homecooked": i % 2 == 0])
            data.markDone(day: day, checkinId: "wake", time: "04:45")
        }
        // Whatever has already rung today is done, so screenshots show the start page (not a
        // check-in catching up), whatever time CI runs.
        for p in AlarmPlan.due(data, now: Date(), calendar: calendar) where data.checkin(p.checkinId)?.counted == true {
            data.markDone(day: p.day, checkinId: p.checkinId, time: p.time)
        }
        waiting = []
        practice = []
        save()
    }
    #endif

    // MARK: - Saving

    private func save() {
        if let text = try? YAMLCodec.dump(data.json) {
            try? text.write(to: fileURL, atomically: true, encoding: .utf8)
        }
        defaults.set(try? JSONEncoder().encode(waiting), forKey: "waiting")
        defaults.set(try? JSONEncoder().encode(practice), forKey: "practice")
    }

    private static func decode<T: Decodable>(_ type: T.Type, _ data: Data?) -> T? {
        guard let data else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }
}
