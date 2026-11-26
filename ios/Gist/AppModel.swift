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
    /// The one model: the app's views, and place crossings that wake Gist in the background.
    static let shared = AppModel()

    private(set) var data: GistData
    /// Rings that went off and aren't done yet, oldest first.
    private(set) var waiting: [Pending] = []
    private(set) var practice: [PracticeRing] = []
    /// Recent place crossings: where you were, for places' rings.
    private(set) var places = PlaceLog()
    /// What's showing full screen.
    var running: RunRequest?
    /// A task that was just done (its steps, Do it now, or dismissing a soft one): its "Task
    /// done" sheet offers a follow-up, every time.
    var justDone: Checkin?
    /// A check-in's page to open on the start page's stack (a new follow-up).
    var openEdit: String?
    var alarmsAllowed = false
    var alarmsAsked = false
    /// A short message to show (e.g. after an import).
    var notice: String?
    /// A screen to open at launch (debug builds, for screenshots): "edit:<id>", "history:<id>",
    /// "settings" or "run:<id>".
    var debugRoute: String?

    /// The start page's tab.
    private(set) var tab: StartTab
    /// Each tab's view: grouped by category, and its sort (the Check-ins tab keeps the
    /// original keys).
    private(set) var groupedTabs: Set<StartTab>
    private(set) var sorts: [StartTab: String]
    /// The one card open on the start page (the rest are folded to a line); none to start.
    private(set) var expandedCheckin: String?
    /// Folded groups, by key (tab-prefixed for Tasks and Backlog: see groupKey).
    private(set) var foldedGroups: Set<String>
    private(set) var groupOrder: [String]

    private let defaults = UserDefaults.standard
    private let fileURL: URL
    let calendar = Calendar.current

    /// Syncing: your Gist folder, connections with other people, and your devices.
    let sync = SyncParts()
    /// Bumped after each sync that changed something (views showing what's shared re-read it).
    private(set) var syncRevision = 0
    /// Ring on this device: off, check-ins don't ring here (another of your devices rings);
    /// what's waiting still shows, and answering it here counts everywhere.
    private(set) var ringHere: Bool

    func setRingHere(_ on: Bool) {
        ringHere = on
        defaults.set(on, forKey: "ringHere")
        rescheduleSoon()
    }
    private var syncing = false
    private var syncTask: Task<Void, Never>?

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
        tab = defaults.string(forKey: "startTab").flatMap { StartTab(rawValue: $0) } ?? .checkins
        groupedTabs = Set(StartTab.allCases.filter {
            UserDefaults.standard.string(forKey: AppModel.tabKey("view", $0)) == Grouping.byCategoryView
        })
        sorts = Dictionary(uniqueKeysWithValues: StartTab.allCases.map { t in
            (t, UserDefaults.standard.string(forKey: AppModel.tabKey("sort", t)).flatMap { Grouping.sorts.contains($0) ? $0 : nil }
                ?? Grouping.sortCustom)
        })
        expandedCheckin = defaults.string(forKey: "expandedCheckin")
        defaults.removeObject(forKey: "foldedCheckins") // the old per-card setting
        foldedGroups = Set(defaults.stringArray(forKey: "foldedGroups") ?? [])
        groupOrder = defaults.stringArray(forKey: "groupOrder") ?? []
        waiting = Self.decode([Pending].self, defaults.data(forKey: "waiting")) ?? []
        practice = Self.decode([PracticeRing].self, defaults.data(forKey: "practice")) ?? []
        places = Self.decode(PlaceLog.self, defaults.data(forKey: "places")) ?? PlaceLog()
        alarmsAllowed = AlarmService.authorized
        alarmsAsked = !AlarmService.notDetermined
        ringHere = defaults.object(forKey: "ringHere") as? Bool ?? true
        data.tracking = sync.devices.folder != nil
        sync.settings.onTake = { [weak self] in self?.reloadViewSettings() }
    }

    /// Each tab's view and sort, group order and folded groups, as saved (one may have come
    /// from another of your devices).
    private func reloadViewSettings() {
        groupedTabs = Set(StartTab.allCases.filter { defaults.string(forKey: Self.tabKey("view", $0)) == Grouping.byCategoryView })
        sorts = Dictionary(uniqueKeysWithValues: StartTab.allCases.map { t in
            (t, defaults.string(forKey: Self.tabKey("sort", t)).flatMap { Grouping.sorts.contains($0) ? $0 : nil } ?? Grouping.sortCustom)
        })
        foldedGroups = Set(defaults.stringArray(forKey: "foldedGroups") ?? [])
        groupOrder = defaults.stringArray(forKey: "groupOrder") ?? []
    }

    // MARK: - Syncing

    /// Syncs soon (after a change: a few changes in a row go together).
    func syncSoon(after seconds: Double = 2) {
        guard sync.devices.folder != nil || !sync.connections.here.isEmpty else { return }
        syncTask?.cancel()
        syncTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.syncNow()
        }
    }

    /// Reads and publishes your connections and devices now; takes in what changed.
    func syncNow() {
        guard !syncing else { return }
        syncing = true
        defer { syncing = false }
        var d = data
        let before = d
        let shared = sync.connections.sync(&d)
        var result = DevicesSyncResult()
        if sync.devices.folder != nil, let r = try? sync.devices.sync(&d) { result = r }
        if d != before {
            data = d
            save()
            rescheduleSoon()
        }
        // Answered on another device: stop waiting (and ringing) for it here.
        if !result.closed.isEmpty {
            let closed = Set(result.closed.map { "\($0.checkinId)|\($0.day)|\($0.time)" })
            func isClosed(_ p: Pending) -> Bool { !p.practice && closed.contains("\(p.checkinId)|\(p.day)|\(p.time)") }
            waiting.removeAll(where: isClosed)
            if let r = running, isClosed(r.pending) { running = nil }
            save()
            Task { await QuietNotifier.clear(keeping: waiting) }
        }
        if shared || result.changed || d != before { syncRevision += 1 }
    }

    /// Starts keeping your devices in step in [folder] (this device makes the key).
    func setUpDevices(_ folder: String) throws {
        try sync.devices.setUp(folder: folder, data: &data)
        save()
        syncNow()
    }

    /// Joins your devices in [folder] with [invite].
    func joinDevices(_ folder: String, _ invite: LinkInvite) throws {
        try sync.devices.join(folder: folder, invite: invite, data: &data)
        save()
        syncNow()
    }

    func leaveDevices() {
        sync.devices.leave(data: &data)
        save()
        syncRevision += 1
    }

    /// Your name, as your connections see it (the same on your devices).
    var linkName: String {
        get { sync.connections.myName }
        set {
            sync.connections.myName = newValue
            defaults.set(newValue.trimmingCharacters(in: .whitespaces), forKey: "linkName")
            sync.settings.changed("linkName", tracking: data.tracking)
            syncSoon()
        }
    }

    /// Forgets connection [id] (on its home) and stops sharing with it.
    func removeConnection(_ id: String) {
        sync.connections.remove(id, data: &data)
        save()
        syncNow()
    }

    /// Adds [item] that [l] shares to your list.
    @discardableResult
    func addShared(_ l: Link, _ item: Checkin) -> Checkin? {
        let c = sync.connections.addToMyList(l, item, data: &data)
        save()
        rescheduleSoon()
        return c
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
        syncNow() // answers and changes from your other devices first
        alarmsAllowed = AlarmService.authorized
        alarmsAsked = !AlarmService.notDetermined
        let now = Date()
        // Times that only ring at (or away from) a place, when you weren't: skipped.
        for p in AlarmPlan.placeSkips(data, now: now, places: places, calendar: calendar) {
            data.markSkipped(day: p.day, checkinId: p.checkinId, time: p.time)
        }
        // Logged as rung when they rang, for how long getting started took.
        let rings = AlarmPlan.dueRings(data, now: now, places: places, calendar: calendar)
        for (p, at) in rings {
            data.markRang(day: p.day, checkinId: p.checkinId, time: p.time, at: Int(at.timeIntervalSince1970 * 1000))
        }
        let due = rings.map { $0.0 }
        let practiceDue = AlarmPlan.duePractice(practice, now: now, calendar: calendar)
        practice.removeAll { $0.at <= now }
        var all: [Pending] = []
        for p in waiting + due + practiceDue where stillWaiting(p) && !all.contains(p) { all.append(p) }
        waiting = all
        speakDueVoices()
        save()
        presentNext()
        await reschedule()
        await QuietNotifier.clear(keeping: waiting)
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

    /// Strict check-ins and test rings open full screen by themselves; soft and No alarm ones
    /// wait on the start page.
    private func presentNext() {
        guard running == nil else { return }
        if let p = waiting.first(where: { p in
            p.practice || (ringHere && (data.checkin(p.checkinId).map { $0.counted && !$0.isQuiet } ?? false))
        }) {
            running = RunRequest(pending: p, early: false)
        }
    }

    func reschedule() async {
        var plan = AlarmPlan.plan(data, waiting: waiting, practice: practice, oneOff: OneOffStore.all(),
                                  places: places, now: Date(), calendar: calendar)
        // Ring on this device off: only test rings ring here.
        if !ringHere { plan = plan.filter { $0.practice } }
        let checkins = Dictionary(data.checkins.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        await AlarmService.replaceAll(with: plan, checkins: checkins)
        await QuietNotifier.sync(AlarmPlan.quietRings(data, places: places, now: Date(), calendar: calendar),
                                 names: checkins.mapValues { $0.name })
    }

    private func rescheduleSoon() {
        Task {
            await reschedule()
            await PlaceMonitor.shared.sync(Places.watched(data.checkins))
            RunWatcher.shared.sync(data.checkins)
        }
    }

    // MARK: - Places

    /// iOS says you went into or out of a place (maybe with Gist in the background). Logs
    /// it, rings check-ins that arrive / leave there, and resets alarms that depend on
    /// where you are. [settling]: the place was only just added, so it doesn't ring.
    func placeCrossed(_ crossing: PlaceCrossing, settling: Bool) async {
        guard places.record(crossing) else { return }
        places.prune(keeping: Set(Places.watched(data.checkins).keys), now: Date())
        if !settling { ringForPlace(crossing) }
        save()
        await reschedule()
    }

    private func ringForPlace(_ crossing: PlaceCrossing) {
        let now = Date()
        for c in data.checkins {
            let day = Days.forAlarm(aboutYesterday: c.aboutYesterday, at: now, calendar: calendar)
            let rang = Places.rang(data, checkinId: c.id, day: day, waiting: waiting)
            guard Places.eventRings(c, key: crossing.key, entering: crossing.inside, now: now,
                                    rangToday: rang, calendar: calendar) != nil else { continue }
            ringNow(c, day: day, at: now)
        }
    }

    /// Rings [c] now for a place or a run: through AlarmKit a moment from now, like any other
    /// alarm; a voice check-in just speaks. (A voice one can speak again within the same
    /// minute: places and runs have their own once-a-day / once-per-run rules.)
    private func ringNow(_ c: Checkin, day: String, at now: Date) {
        let time = AlarmTime(calendar.component(.hour, from: now), calendar.component(.minute, from: now)).description
        if c.isVoice {
            data.markDone(day: day, checkinId: c.id, time: time) // not counted; for "once a day"
            VoicePlayer.shared.play(c)
            return
        }
        guard !data.isClosed(day: day, checkinId: c.id, time: time) else { return }
        data.markRang(day: day, checkinId: c.id, time: time)
        let p = Pending(checkinId: c.id, day: day, time: time)
        if !waiting.contains(p) { waiting.append(p) }
        if c.isQuiet {
            Task { await QuietNotifier.showNow(p, name: c.name) } // no alarm: a quiet reminder
        } else {
            OneOffStore.add(OneOffRing(checkinId: c.id, day: day, time: time, at: now.addingTimeInterval(3)))
        }
    }

    // MARK: - Runs and voice

    /// A run started ([finishedMinutes] nil) or finished: ring every check-in that wants it.
    func runEvent(finishedMinutes: Int?) {
        let now = Date()
        var rang = false
        for c in data.checkins {
            let day = Days.forAlarm(aboutYesterday: c.aboutYesterday, at: now, calendar: calendar)
            let today = Places.rang(data, checkinId: c.id, day: day, waiting: waiting)
            guard Runs.eventRings(c, finishedMinutes: finishedMinutes, now: now, rangToday: today, calendar: calendar) else { continue }
            ringNow(c, day: day, at: now)
            rang = true
        }
        if rang { afterEventRings() }
    }

    /// The current run has lasted [minutes]: rings "N minutes into a run" check-ins not rung
    /// this run yet ([fired]). Returns the milestones it rang.
    func runProgress(minutes: Int, fired: Set<String>) -> [String] {
        let now = Date()
        var keys: [String] = []
        for c in data.checkins {
            let day = Days.forAlarm(aboutYesterday: c.aboutYesterday, at: now, calendar: calendar)
            let today = Places.rang(data, checkinId: c.id, day: day, waiting: waiting)
            let due = Runs.progressRings(c, minutes: minutes, fired: fired, now: now, rangToday: today, calendar: calendar)
            guard !due.isEmpty else { continue }
            ringNow(c, day: day, at: now)
            keys += due
        }
        if !keys.isEmpty { afterEventRings() }
        return keys
    }

    /// Voice check-ins whose time just came: speak them (called while Gist is awake).
    func speakDueVoices() {
        let due = Voice.due(data, now: Date(), calendar: calendar)
        guard !due.isEmpty else { return }
        for p in due {
            data.markDone(day: p.day, checkinId: p.checkinId, time: p.time)
            if let c = data.checkin(p.checkinId) { VoicePlayer.shared.play(c) }
        }
        save()
    }

    /// Plays a voice check-in now (the check-in page's "Play it now"). Nothing is logged.
    func playVoice(_ c: Checkin) {
        if !VoicePlayer.shared.play(c) {
            notice = "Connect headphones first, or turn off \"Only through headphones\"."
        }
    }

    private func afterEventRings() {
        save()
        Task { await reschedule() }
    }

    /// Location permission changed, or check-ins' places did.
    func placesChanged() async {
        await PlaceMonitor.shared.sync(Places.watched(data.checkins))
        RunWatcher.shared.sync(data.checkins)
        await reschedule()
    }

    /// Do any check-ins that are on use places?
    var usesPlaces: Bool { !Places.watched(data.checkins).isEmpty }

    /// Do any check-ins that are on have run triggers?
    var usesRuns: Bool { Runs.anyUse(data.checkins) }

    /// A test ring in 10 seconds: it runs the steps, logs nothing, and leaves real rings alone.
    func testRing(_ c: Checkin) {
        if c.isVoice {
            playVoice(c)
            return
        }
        practice.append(PracticeRing(checkinId: c.id, at: Date().addingTimeInterval(10)))
        save()
        rescheduleSoon()
        notice = alarmsAllowed
            ? "Ringing in 10 seconds. Lock your phone to see it as an alarm. Nothing is logged."
            : "Allow alarms first (on the start page), then test again."
    }

    // MARK: - Running a check-in

    func start(_ p: Pending) { running = RunRequest(pending: p, early: false) }

    /// "Do it now": run the next ring early; that time's alarm then stays quiet. A task with
    /// no ring coming (in the backlog, off, or its time passed) is done today, under its
    /// nearest time.
    func doNow(_ c: Checkin) {
        if let o = next(c) {
            running = RunRequest(pending: Pending(checkinId: c.id, day: o.day, time: o.timeKey), early: true)
        } else if c.isTask {
            let time = Scheduler.nearestTime(c, calendar: calendar).description
            running = RunRequest(pending: Pending(checkinId: c.id, day: Days.today(calendar: calendar), time: time), early: true)
        }
    }

    /// The pick-tasks step: turns [ids] on (out of the backlog, onto today's list). A test
    /// changes nothing.
    func pickTasks(_ ids: [String], practice: Bool) {
        guard !practice, !ids.isEmpty else { return }
        for id in ids { data.update(id) { if $0.inBacklog { $0.enabled = true } } }
        save()
        rescheduleSoon()
    }

    /// The steps are done: log the answers (nothing for a test), and how long it took, from
    /// [startedAt] (Start check-in, or the steps opening).
    /// [values]: typed answers as typed; every typed question is saved, "" when left empty.
    func finish(_ r: RunRequest, answers: [String: Bool], values: [String: String] = [:], startedAt: Date? = nil) {
        let p = r.pending
        if !p.practice, let c = data.checkin(p.checkinId) {
            let now = Date()
            let took = data.took(day: p.day, checkinId: p.checkinId, time: p.time,
                                 startedAt: Int((startedAt ?? now).timeIntervalSince1970 * 1000),
                                 doneAt: Int(now.timeIntervalSince1970 * 1000))
            if c.questions.isEmpty {
                data.markDone(day: p.day, checkinId: p.checkinId, time: p.time, early: r.early, took: took)
            } else {
                var typed: [String: String] = [:]
                for q in c.questions where q.takesValue {
                    let v = (values[q.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                    typed[q.id] = q.type == Question.number ? (Question.cleanNumber(v) ?? "") : v
                }
                data.saveAnswers(day: p.day, checkinId: p.checkinId, time: p.time, answers: answers, early: r.early,
                                 values: typed, took: took)
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
        let still = waiting
        Task { await QuietNotifier.clear(keeping: still) }
        // A task just done: offer a follow-up (the next alarm waits until that's closed).
        let done = r.pending.practice ? nil : data.checkin(r.pending.checkinId).flatMap { $0.taskDone ? $0 : nil }
        Task {
            try? await Task.sleep(for: .milliseconds(600))
            if let done { justDone = done } else { presentNext() }
        }
    }

    /// The Task done sheet closed: carry on to anything else that's waiting.
    func doneWithTaskDone() {
        justDone = nil
        Task {
            try? await Task.sleep(for: .milliseconds(400))
            presentNext()
        }
    }

    /// Creates a follow-up to [sourceId] (today's list, or the backlog) and opens its page.
    @discardableResult
    func createFollowUp(_ sourceId: String, active: Bool) -> Checkin? {
        guard let c = data.createFollowUp(sourceId, active: active) else { return nil }
        save()
        rescheduleSoon()
        openEdit = c.id
        return c
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
        // Turning a done task back on puts it back on the list.
        data.update(id) { c in
            c.enabled = on
            if on { c.doneAt = nil }
        }
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
        MediaStore.clearAll(id)
        waiting.removeAll { $0.checkinId == id }
        practice.removeAll { $0.checkinId == id }
        save()
        rescheduleSoon()
    }

    func move(from: IndexSet, to: Int) {
        data.checkins.move(fromOffsets: from, toOffset: to)
        save()
    }

    /// Moves within the current tab's list; everything else keeps its place.
    func moveInTab(from: IndexSet, to: Int) {
        var shown = tab.items(data.checkins)
        let slots = data.checkins.indices.filter { i in shown.contains { $0.id == data.checkins[i].id } }
        shown.move(fromOffsets: from, toOffset: to)
        var all = data.checkins
        for (slot, c) in zip(slots, shown) { all[slot] = c }
        data.checkins = all
        save()
    }

    // MARK: - View settings

    /// The Check-ins tab keeps the original keys ("view", "sort"); the others add the tab.
    static func tabKey(_ key: String, _ tab: StartTab) -> String { tab == .checkins ? key : "\(key).\(tab.rawValue)" }

    /// Each tab opens with its cards folded.
    func setTab(_ t: StartTab) {
        if t != tab { expandedCheckin = nil; defaults.removeObject(forKey: "expandedCheckin") }
        tab = t
        defaults.set(t.rawValue, forKey: "startTab")
    }

    var groupByCategory: Bool { groupedTabs.contains(tab) }
    var sort: String { sorts[tab] ?? Grouping.sortCustom }

    func setGroupByCategory(_ on: Bool) {
        if on { groupedTabs.insert(tab) } else { groupedTabs.remove(tab) }
        defaults.set(on ? Grouping.byCategoryView : Grouping.ungrouped, forKey: Self.tabKey("view", tab))
        sync.settings.changed(Self.tabKey("view", tab), tracking: data.tracking)
        syncSoon()
    }

    func setSort(_ how: String) {
        sorts[tab] = how
        defaults.set(how, forKey: Self.tabKey("sort", tab))
        sync.settings.changed(Self.tabKey("sort", tab), tracking: data.tracking)
        syncSoon()
    }

    /// A group's fold state is kept per tab.
    func groupKey(_ category: String) -> String {
        tab == .checkins ? Grouping.key(category) : "\(tab.rawValue)/\(Grouping.key(category))"
    }

    /// Opens [id]'s card (folding whichever was open), or folds it if it's the open one.
    func toggleFolded(_ id: String) {
        expandedCheckin = expandedCheckin == id ? nil : id
        defaults.set(expandedCheckin, forKey: "expandedCheckin")
    }

    /// A new task (from + on the Tasks or Backlog tab): active, or in the backlog.
    func addTask(active: Bool) -> Checkin {
        var t = Library.task()
        t.enabled = active
        save(t)
        return t
    }

    /// A done task back on the list.
    func reopenTask(_ id: String) {
        data.reopenTask(id)
        save()
        rescheduleSoon()
    }

    /// Tasks done, most recent first.
    var doneTasks: [Checkin] { data.doneTasks.sorted { ($0.doneAt ?? 0) > ($1.doneAt ?? 0) } }

    func toggleGroup(_ key: String) {
        if foldedGroups.contains(key) { foldedGroups.remove(key) } else { foldedGroups.insert(key) }
        defaults.set(Array(foldedGroups), forKey: "foldedGroups")
        sync.settings.changed("collapsedGroups", tracking: data.tracking)
        syncSoon()
    }

    /// Expand all / Collapse all: the groups only.
    func setAllFolded(_ folded: Bool, groups: Set<String>) {
        if folded { foldedGroups.formUnion(groups) } else { foldedGroups.subtract(groups) }
        defaults.set(Array(foldedGroups), forKey: "foldedGroups")
        sync.settings.changed("collapsedGroups", tracking: data.tracking)
        syncSoon()
    }

    // MARK: - Backups

    /// The chosen check-ins (everything, with history, by default) as a backup file, in the
    /// same format as the Android app's.
    func backupText(_ choices: [Backup.ExportChoice]? = nil) throws -> String {
        try Backup.export(data, choices: choices, media: MediaStore.media)
    }

    /// The file name for a backup: named after the check-in when there's only one.
    func backupName(_ choices: [Backup.ExportChoice]) -> String {
        var slug = "backup"
        if choices.count == 1, let c = data.checkin(choices[0].checkinId) {
            let s = c.name.lowercased().replacingOccurrences(of: "[^a-z0-9]+", with: "-", options: .regularExpression)
                .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
            if !s.isEmpty { slug = s }
        }
        return "gist-\(slug)-\(Days.today()).yaml"
    }

    /// The check-in of yours that the file's [c] would update or replace, if any.
    func match(_ c: Checkin) -> Checkin? { Backup.match(data.checkins, c) }

    /// Imports a backup's chosen check-ins. Returns how many.
    func importBackup(_ contents: Backup.Contents, choices: [Backup.ImportChoice]) -> Int {
        let written = Backup.importing(contents, choices: choices, into: &data)
        for w in written {
            if let m = contents.media[w.sourceId] { MediaStore.store(m, for: w.checkin.id) }
            MediaStore.keepOnly(w.checkin)
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
        // Default view settings too, so screenshots and UI tests start the same every time.
        for t in StartTab.allCases {
            setTab(t)
            setGroupByCategory(false)
            setSort(Grouping.sortCustom)
        }
        setTab(.checkins)
        setAllFolded(false, groups: foldedGroups)
        expandedCheckin = nil
        defaults.removeObject(forKey: "expandedCheckin")
        groupOrder = []
        defaults.removeObject(forKey: "groupOrder")
        places = PlaceLog()
        data.checkins[0].category = "Food"
        data.checkins[1].category = "Food"
        data.checkins.append(Checkin(id: "wake", name: "Wake up", times: [
            AlarmTime(4, 0, days: Repeat(Repeat.weekly, [2])),
            AlarmTime(4, 45, days: Repeat(Repeat.weekly, [1, 3, 4, 5, 6, 7])),
        ], steps: [StepConfig(id: "wake-m", kind: StepConfig.math, mathCount: 10, mathLevel: 3)], category: "Morning"))
        var stretch = Checkin(id: "stretch", name: "Stand and stretch", times: [AlarmTime(15, 0)],
                              repeat: Repeat(Repeat.weekly, Repeat.weekdays), commitment: Checkin.soft)
        // A drawn sunrise as its background photo, with a caption.
        stretch.background = Background(kind: Background.photo, caption: "Future you says thanks")
        let sunrise = UIGraphicsImageRenderer(size: CGSize(width: 600, height: 1200)).image { ctx in
            let colors = [UIColor(red: 0.13, green: 0.16, blue: 0.35, alpha: 1).cgColor,
                          UIColor(red: 0.93, green: 0.45, blue: 0.33, alpha: 1).cgColor,
                          UIColor(red: 0.99, green: 0.80, blue: 0.45, alpha: 1).cgColor]
            let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors as CFArray, locations: [0, 0.6, 1])!
            ctx.cgContext.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 0, y: 1200), options: [])
            UIColor(red: 1, green: 0.93, blue: 0.7, alpha: 1).setFill()
            ctx.cgContext.fillEllipse(in: CGRect(x: 200, y: 760, width: 200, height: 200))
        }
        if let jpeg = sunrise.jpegData(compressionQuality: 0.9) { try? MediaStore.setPhotos("stretch", [jpeg]) }
        data.checkins.append(stretch)
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

    /// For UI tests of tasks: a morning planner that picks 2 tasks, and 3 in the backlog.
    /// UI tests: syncing starts afresh, with a Gist folder in temporary storage and a name.
    func debugSyncTest() {
        for suite in ["gist-folder", "gist-links", "gist-devices", "gist-bookmarks"] {
            UserDefaults.standard.removePersistentDomain(forName: suite)
        }
        defaults.removeObject(forKey: "linkName")
        defaults.removeObject(forKey: "settingsAt")
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("gist-ui-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        sync.gistFolder.root = dir.path
        sync.connections.myName = "Alex"
        data.tracking = false
        syncRevision += 1
    }

    func loadDemoTasks() {
        // No set time, so it never rings by itself during a test (tests run it as a test ring).
        data.checkins.append(Checkin(id: "plan", name: "Plan the day", times: [],
                                     steps: [StepConfig(id: "plan-p", kind: StepConfig.pickTasks, pickCount: 2)]))
        for (id, name) in [("shed", "Organize shed"), ("milk", "Buy milk"), ("call", "Call John")] {
            var t = Library.task(newId: id)
            t.name = name
            data.checkins.append(t)
        }
        save()
    }
    #endif

    // MARK: - Saving

    private func save() {
        if !syncing { syncSoon() } // your other devices and connections hear about it
        if let text = try? YAMLCodec.dump(data.json) {
            try? text.write(to: fileURL, atomically: true, encoding: .utf8)
        }
        defaults.set(try? JSONEncoder().encode(waiting), forKey: "waiting")
        defaults.set(try? JSONEncoder().encode(practice), forKey: "practice")
        defaults.set(try? JSONEncoder().encode(places), forKey: "places")
    }

    private static func decode<T: Decodable>(_ type: T.Type, _ data: Data?) -> T? {
        guard let data else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }
}
