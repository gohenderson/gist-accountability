import GistCore
import SwiftUI

enum Route: Hashable {
    case edit(String)
    case history(String)
    case settings
    case library
    case doneTasks
    case connections
}

/// The start page (laid out like the Android app's): a summary for the tab, setup and
/// what's waiting, the Check-ins · Tasks · Backlog tabs with + beside them, the tab's View,
/// and its cards, which open one at a time.
struct StartView: View {
    @Environment(AppModel.self) private var model
    @State private var path = NavigationPath()
    @State private var reordering = false

    private var items: [Checkin] { model.tab.items(model.checkins) }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    summary
                    if !model.alarmsAllowed { setupCard }
                    if model.usesPlaces && !PlaceMonitor.shared.always { locationCard }
                    if model.usesRuns && !RunWatcher.shared.problem.isEmpty { runsCard }
                    waitingCards
                    tabs
                    controls
                    list
                    sharedWithYou
                    if model.tab == .tasks {
                        NavigationLink(value: Route.doneTasks) {
                            Text("Done tasks (\(model.doneTasks.count))").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .padding(.top, 12)
                        .accessibilityIdentifier("done-tasks")
                    }
                }
                .padding(16)
                .frame(maxWidth: 720) // readable on an iPad
                .frame(maxWidth: .infinity)
            }
            .background(Theme.surfaceVariant.opacity(0.6).ignoresSafeArea())
            .navigationTitle("Gist")
            .navigationSubtitle(Date().formatted(.dateTime.weekday(.wide).month(.wide).day()))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink(value: Route.settings) {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("Settings")
                    .accessibilityIdentifier("settings")
                }
            }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .edit(let id): CheckinEditView(checkinId: id)
                case .history(let id): HistoryView(checkinId: id)
                case .settings: SettingsView()
                case .doneTasks: DoneTasksView(path: $path)
                case .connections: ConnectionsView()
                case .library:
                    LibraryView { c in
                        path.removeLast()
                        path.append(Route.edit(c.id))
                    }
                }
            }
            .sheet(isPresented: $reordering) { ReorderView() }
        }
        .onChange(of: model.openEdit) { _, id in
            guard let id else { return }
            model.openEdit = nil
            path.append(Route.edit(id))
        }
        .onChange(of: model.debugRoute) { _, route in
            guard let route else { return }
            model.debugRoute = nil
            let parts = route.split(separator: ":", maxSplits: 1).map(String.init)
            switch parts.first {
            case "edit": if parts.count == 2 { path.append(Route.edit(parts[1])) }
            case "history": if parts.count == 2 { path.append(Route.history(parts[1])) }
            case "settings": path.append(Route.settings)
            case "library": path.append(Route.library)
            case "done": path.append(Route.doneTasks)
            case "connections": path.append(Route.connections)
            case "tab": if parts.count == 2, let t = StartTab(rawValue: parts[1]) { model.setTab(t) }
            case "run":
                if parts.count == 2 {
                    model.running = RunRequest(pending: Pending(checkinId: parts[1], day: Days.today(), time: "07:00", practice: true), early: false)
                }
            default: break
            }
        }
    }

    // MARK: Top

    /// The tab's number, always there so the tabs stay put when you switch.
    @ViewBuilder private var summary: some View {
        let (big, color, text, id) = summaryParts
        HStack(alignment: .center, spacing: 12) {
            Text(big)
                .font(.system(size: 40, weight: .bold, design: .rounded))
                .foregroundStyle(color)
                .accessibilityIdentifier(id)
            Text(text)
                .font(.subheadline)
                .foregroundStyle(Theme.muted)
                .accessibilityIdentifier("summary-text")
            Spacer(minLength: 0)
        }
        .frame(minHeight: 56)
    }

    private var summaryParts: (String, Color, String, String) {
        let days = Score.days
        switch model.tab {
        case .tasks:
            let n = model.data.tasksDone(inLast: days)
            return ("\(n)", Theme.good, "\(n == 1 ? "task" : "tasks") done in the last \(days) days", "tasks-done-count")
        case .backlog:
            let n = model.data.tasksBacklogged(inLast: days)
            return ("\(n)", Theme.primaryColor, "\(n == 1 ? "task" : "tasks") added to the backlog in the last \(days) days",
                    "backlog-added-count")
        case .checkins:
            if let score = Score.average(items.map { model.score($0) }) {
                return (Score.percent(score), Theme.scoreColor(score), "on track over the last \(days) days", "overall-score")
            }
            return ("–", Theme.muted, "on track over the last \(days) days: shows once you've answered some check-ins",
                    "overall-score-none")
        }
    }

    private var setupCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Finish setup").font(.headline)
            Text(model.alarmsAsked
                 ? "Gist isn't allowed to set alarms. Turn on Alarms for Gist in Settings so check-ins can ring."
                 : "Allow Gist to set alarms so check-ins ring, even in Silent mode or a Focus.")
                .foregroundStyle(Theme.muted)
            if model.alarmsAsked {
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
                .buttonStyle(.borderedProminent)
            } else {
                Button("Allow alarms") { Task { await model.allowAlarms() } }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("allow-alarms")
            }
        }
        .card()
    }

    private var locationCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Places need location").font(.headline)
            Text(PlaceMonitor.shared.denied
                 ? "Location is off for Gist. In Settings → Gist → Location, choose Always so places ring while Gist is closed."
                 : "Allow location Always so arriving and leaving places ring while Gist is closed. Your location never leaves the phone.")
                .foregroundStyle(Theme.muted)
            Button(PlaceMonitor.shared.denied ? "Open Settings" : "Allow location") { PlaceMonitor.shared.requestAlways() }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("allow-location-card")
        }
        .card()
    }

    private var runsCard: some View {
        let w = RunWatcher.shared
        return VStack(alignment: .leading, spacing: 8) {
            Text("Runs need a couple of permissions").font(.headline)
            Text(w.problem).foregroundStyle(Theme.muted)
            if w.supported {
                Button(w.motionDenied || PlaceMonitor.shared.denied ? "Open Settings"
                       : w.motion == .notDetermined ? "Allow Motion & Fitness" : "Allow location") {
                    if w.motionDenied || PlaceMonitor.shared.denied {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    } else if w.motion == .notDetermined {
                        w.requestMotion()
                    } else {
                        PlaceMonitor.shared.requestAlways()
                    }
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("allow-runs-card")
            }
        }
        .card()
    }

    /// Soft check-ins that rang and are waiting (strict ones open full screen by themselves;
    /// No alarm ones wait on their own card).
    @ViewBuilder private var waitingCards: some View {
        ForEach(model.waiting) { p in
            if let c = model.checkin(p.checkinId), !c.isQuiet {
                HStack {
                    VStack(alignment: .leading) {
                        Text(c.name).font(.headline)
                        Text("Waiting since \(TimeText.of(p.time))").foregroundStyle(Theme.muted)
                    }
                    Spacer()
                    if c.isSoft && !p.practice {
                        Button("Dismiss") { model.dismiss(RunRequest(pending: p, early: false)) }
                            .buttonStyle(.bordered)
                            .accessibilityIdentifier("dismiss:\(c.id)")
                    }
                    Button("Check in") { model.start(p) }
                        .buttonStyle(.borderedProminent)
                }
                .card()
            }
        }
    }

    // MARK: Tabs

    /// Check-ins · Tasks · Backlog, one at a time, and + to add to the tab you're on.
    private var tabs: some View {
        HStack(spacing: 10) {
            Picker("Show", selection: Binding(get: { model.tab }, set: { t in withAnimation(.snappy) { model.setTab(t) } })) {
                ForEach(StartTab.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            .controlSize(.large)
            .accessibilityIdentifier("tabs")
            Button {
                add()
            } label: {
                Image(systemName: "plus").font(.title3.weight(.semibold)).frame(width: 30, height: 30)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.circle)
            .accessibilityLabel(model.tab == .checkins ? "Add check-in" : model.tab == .tasks ? "New task" : "New backlog task")
            .accessibilityIdentifier("add")
        }
        .padding(.top, 4)
    }

    /// + : a check-in from the library, a new active task, or a new backlog task.
    private func add() {
        switch model.tab {
        case .checkins: path.append(Route.library)
        case .tasks: path.append(Route.edit(model.addTask(active: true).id))
        case .backlog: path.append(Route.edit(model.addTask(active: false).id))
        }
    }

    // MARK: View menu

    /// The tab's View (grouping and sort), always there; Expand / Collapse all for its groups
    /// when grouped (cards open one at a time).
    private var controls: some View {
        let groups = model.groupByCategory ? Grouping.byCategory(items, order: model.groupOrder) : []
        let keys = Set(groups.map { model.groupKey($0.0) })
        let allFolded = Grouping.allFolded(groups: keys, foldedGroups: model.foldedGroups)
        return HStack {
            Menu {
                Toggle("By category", isOn: Binding(get: { model.groupByCategory }, set: { model.setGroupByCategory($0) }))
                Picker("Sort", selection: Binding(get: { model.sort }, set: { model.setSort($0) })) {
                    ForEach(Grouping.sorts, id: \.self) { Text(Grouping.sortTitle($0)).tag($0) }
                }
                if model.sort == Grouping.sortCustom {
                    Button("Change my order…") { reordering = true }
                }
            } label: {
                Text("View: \(Grouping.sortTitle(model.sort))")
                Image(systemName: "chevron.down")
            }
            .accessibilityIdentifier("view-mode")
            Spacer()
            if !keys.isEmpty {
                Button(allFolded ? "Expand all" : "Collapse all") {
                    withAnimation { model.setAllFolded(!allFolded, groups: keys) }
                }
                .accessibilityIdentifier("toggle-all")
            }
        }
        .frame(minHeight: 32)
    }

    // MARK: List

    @ViewBuilder private var list: some View {
        if items.isEmpty {
            Text(model.tab.empty(anyCheckins: !model.checkins.isEmpty)).foregroundStyle(Theme.muted).card()
        } else if model.groupByCategory {
            ForEach(Grouping.byCategory(items, order: model.groupOrder), id: \.0) { group in
                let key = model.groupKey(group.0)
                let folded = model.foldedGroups.contains(key)
                let score = Score.average(group.1.filter { $0.enabled }.map { model.score($0) })
                Button {
                    withAnimation { model.toggleGroup(key) }
                } label: {
                    HStack {
                        Text(group.0).font(.title3.bold())
                            .foregroundStyle(score.map { Theme.scoreColor($0) } ?? .primary)
                        Image(systemName: folded ? "chevron.right" : "chevron.down").foregroundStyle(Theme.muted)
                        Spacer()
                    }
                }
                .buttonStyle(.plain)
                .padding(.top, 10)
                .accessibilityIdentifier("group-header:\(group.0)")
                .accessibilityLabel("\(group.0), \(folded ? "collapsed" : "expanded")" + (score.map { ", \(Score.percent($0)) on track" } ?? ""))
                if !folded {
                    ForEach(sorted(group.1)) { CheckinCard(checkin: $0, path: $path) }
                }
            }
        } else {
            ForEach(sorted(items)) { CheckinCard(checkin: $0, path: $path) }
        }
    }

    /// What your connections share with you (read-only), on the same tab.
    @ViewBuilder private var sharedWithYou: some View {
        let _ = model.syncRevision
        let links = model.sync.connections.all.filter { $0.connected && $0.shown }
        ForEach(links, id: \.id) { l in
            let added = model.sync.connections.added(l, model.data)
            let theirs = model.sync.connections.shared(l).filter { added[$0.id] == nil }.filter { c in
                switch model.tab {
                case .tasks: return c.isTask && !c.inBacklog && !c.taskDone
                case .backlog: return c.inBacklog
                case .checkins: return !c.isTask
                }
            }
            if !theirs.isEmpty {
                let who = l.peerName.isEmpty ? "someone" : l.peerName
                VStack(alignment: .leading, spacing: 2) {
                    Text("From \(who)").font(.title3.bold()).accessibilityIdentifier("from:\(l.id)")
                    Text("Shared with you · read-only").font(.footnote).foregroundStyle(Theme.muted)
                }
                .padding(.top, 16)
                ForEach(theirs) { c in SharedCard(link: l, checkin: c) }
            }
        }
    }

    private func sorted(_ list: [Checkin]) -> [Checkin] {
        Grouping.sort(list, model.sort, next: { model.next($0)?.at }, score: { model.score($0) })
    }
}

/// One check-in or task. Cards open one at a time: tap a card (its title, or anywhere that
/// isn't a button or switch) to open it; whichever was open folds. Its dot, chevron and
/// switch sit on the title's first line, so nothing moves when a long title unfolds.
struct CheckinCard: View {
    @Environment(AppModel.self) private var model
    let checkin: Checkin
    @Binding var path: NavigationPath

    /// The title's first line (and the switch beside it).
    private let line: CGFloat = 31

    var body: some View {
        let c = checkin
        let folded = model.expandedCheckin != c.id
        let score = model.score(c)
        // Waiting on its card: No alarm ones, and any when this device doesn't ring.
        let waiting = c.isQuiet || !model.ringHere ? model.waiting.first { $0.checkinId == c.id && !$0.practice } : nil
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                if !c.isTask {
                    // A check-in's dot, filled with its score's color (an empty ring until it has one).
                    Group {
                        if let score {
                            Circle().fill(Theme.scoreColor(score))
                        } else {
                            Circle().strokeBorder(Theme.muted, lineWidth: 2)
                        }
                    }
                    .frame(width: 11, height: 11)
                    .frame(height: line)
                    .accessibilityHidden(true)
                }
                Button {
                    toggle(c)
                } label: {
                    Text(c.name).font(.headline).foregroundStyle(.primary).lineLimit(folded ? 1 : 3)
                        .multilineTextAlignment(.leading)
                        .padding(.top, 5)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("checkin-title:\(c.id)")
                .accessibilityLabel("\(c.name), \(folded ? "collapsed" : "expanded")" + (score.map { ", \(Score.percent($0)) on track" } ?? ""))
                Image(systemName: folded ? "chevron.right" : "chevron.down")
                    .font(.caption).foregroundStyle(Theme.muted)
                    .frame(height: line)
                    .accessibilityIdentifier("checkin-chevron:\(c.id)")
                Toggle("On", isOn: Binding(get: { c.enabled }, set: { model.setEnabled(c.id, $0) }))
                    .labelsHidden()
                    .frame(height: line)
                    .accessibilityIdentifier("toggle:\(c.id)")
            }
            if folded {
                Text(nextText(c, waiting)).font(.subheadline).foregroundStyle(Theme.muted).lineLimit(1)
                    .accessibilityIdentifier("next-line:\(c.id)")
            } else {
                Text(CheckinSummary.oneLine(c)).font(.subheadline).foregroundStyle(Theme.muted)
                    .accessibilityIdentifier("summary:\(c.id)")
                // Added from a connection: whose it is.
                if let id = c.linkId {
                    let who = model.sync.connections.get(id).map { $0.peerName.isEmpty ? "a connection" : $0.peerName } ?? "a connection"
                    Text(c.linkedGone ? "From \(who) · they removed it" : c.isTask ? "From \(who) · \(CheckinSummary.assigned(c, who))" : "From \(who)")
                        .font(.footnote).foregroundStyle(Theme.muted)
                        .accessibilityIdentifier("linked:\(c.id)")
                }
                // Its description: the first few lines (all of it is on its page).
                if !c.description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text(c.description).font(.subheadline).lineLimit(4)
                        .accessibilityIdentifier("description:\(c.id)")
                }
                if let waiting {
                    waitingBox(c, waiting)
                } else {
                    nextBox(c)
                }
                if c.isSoft {
                    Text("Soft alarm · not counted in stats").font(.footnote).foregroundStyle(Theme.muted)
                } else if c.isVoice {
                    Text("Voice · speaks through your headphones · not counted in stats").font(.footnote).foregroundStyle(Theme.muted)
                } else if !c.questions.isEmpty {
                    Text(model.statsLine(c)).font(.footnote).foregroundStyle(Theme.muted)
                }
                HStack {
                    if !c.questions.isEmpty && c.counted {
                        Button("History") { path.append(Route.history(c.id)) }
                            .accessibilityIdentifier("history:\(c.id)")
                    }
                    Spacer()
                    Button("Edit") { path.append(Route.edit(c.id)) }
                        .accessibilityIdentifier("edit:\(c.id)")
                }
            }
        }
        .card()
        .contentShape(Rectangle())
        .onTapGesture { toggle(c) } // the card's body folds or opens it, like its title
        .accessibilityElement(children: .contain) // keeps the title's and buttons' own ids
        .accessibilityIdentifier(folded ? "row:\(c.id)" : "card:\(c.id)")
    }

    private func toggle(_ c: Checkin) {
        withAnimation(.snappy) { model.toggleFolded(c.id) }
    }

    private func nextText(_ c: Checkin, _ waiting: Pending?) -> String {
        if let waiting { return "Waiting · \(TimeText.of(waiting.time)) · tap to open" }
        if !c.enabled { return c.inBacklog ? "In the backlog" : "Off" }
        if let o = model.next(c) { return "Next: \(TimeText.at(o.at))" }
        if c.isTask { return c.times.isEmpty && c.events.isEmpty ? "No set time" : "Its time has passed" }
        return c.times.isEmpty ? "No set times" : "Not scheduled"
    }

    /// A No alarm check-in that came due: it waits here.
    private func waitingBox(_ c: Checkin, _ p: Pending) -> some View {
        HStack {
            Text("Waiting · \(TimeText.of(p.time))").font(.subheadline.bold())
                .accessibilityIdentifier("waiting:\(c.id)")
            Spacer()
            if c.isSoft {
                Button("Dismiss") { model.dismiss(RunRequest(pending: p, early: false)) }
                    .accessibilityIdentifier("waiting-dismiss:\(c.id)")
            }
            Button("Check in") { model.start(p) }
                .buttonStyle(.bordered)
        }
        .padding(12)
        .background(Theme.surfaceVariant, in: RoundedRectangle(cornerRadius: 14))
    }

    @ViewBuilder private func nextBox(_ c: Checkin) -> some View {
        if let skipped = model.skippedNext(c) {
            HStack {
                Text("Skipped \(TimeText.at(skipped.at))").font(.subheadline)
                Spacer()
                Button("Undo") { model.undoSkip(c, skipped) }
            }
        }
        if c.enabled, let o = model.next(c) {
            HStack {
                Text("Next: \(TimeText.at(o.at))").font(.subheadline.bold())
                    .accessibilityIdentifier("next:\(c.id)")
                Spacer()
                Button("Skip") { model.skipNext(c) }
                Button("Do it now") { model.doNow(c) }
                    .buttonStyle(.bordered)
            }
        } else if c.isTask && !c.isVoice {
            // A task with no ring coming (in the backlog, off, or its time has passed): do it from here.
            let passed = c.enabled && !c.times.isEmpty
            HStack {
                Text(passed ? "Its time has passed" : c.inBacklog ? "In the backlog"
                     : c.times.isEmpty && c.events.isEmpty ? "Task · no set time" : "Task · alarm off")
                    .font(.subheadline)
                    .foregroundStyle(passed ? .orange : .primary)
                    .accessibilityIdentifier("next:\(c.id)")
                Spacer()
                Button("Do it now") { model.doNow(c) }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("task-do:\(c.id)")
            }
            .padding(12)
            .background(Theme.surfaceVariant, in: RoundedRectangle(cornerRadius: 14))
        }
    }
}

/// Tasks you've done, most recent first, so you can check whether you did something. Each
/// can be opened, put back on the list, or deleted.
struct DoneTasksView: View {
    @Environment(AppModel.self) private var model
    @Binding var path: NavigationPath
    @State private var deleting: Checkin?

    var body: some View {
        let done = model.doneTasks
        List {
            if done.isEmpty {
                Text("Nothing here yet. Tasks you finish show up here.").foregroundStyle(Theme.muted)
            }
            ForEach(done) { c in
                VStack(alignment: .leading, spacing: 6) {
                    Text(c.name).font(.headline)
                    Text("✓ Done \(TimeText.done(c.doneAt ?? 0))" + (c.doneBy.map { " by \($0)" } ?? "")).font(.subheadline).foregroundStyle(Theme.good)
                    if let date = c.times.compactMap({ $0.days?.date }).first {
                        Text("Was set for \(Repeat.dateText(date))").font(.footnote).foregroundStyle(Theme.muted)
                    }
                    if let prev = c.followsUp.flatMap({ model.checkin($0) }) {
                        Text("Follows: \(prev.name)").font(.footnote).foregroundStyle(Theme.muted)
                    }
                    ForEach(model.data.followUps(of: c.id)) { next in
                        Text("Followed by: \(next.name)").font(.footnote).foregroundStyle(Theme.muted)
                            .accessibilityIdentifier("followed-by:\(c.id)")
                    }
                    FollowUpButton(task: c)
                        .buttonStyle(.bordered)
                        .padding(.top, 4)
                    HStack {
                        Button("Put back") { model.reopenTask(c.id) }
                            .accessibilityIdentifier("reopen:\(c.id)")
                        Spacer()
                        Button("Open") { path.append(Route.edit(c.id)) }
                        Spacer()
                        Button("Delete", role: .destructive) { deleting = c }
                            .accessibilityIdentifier("delete:\(c.id)")
                    }
                    .buttonStyle(.borderless)
                    .padding(.top, 4)
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("done-task:\(c.id)")
            }
        }
        .navigationTitle("Done tasks")
        .navigationSubtitle(done.count == 1 ? "1 task" : "\(done.count) tasks")
        .confirmationDialog("Delete \"\(deleting?.name ?? "")\"?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                if let c = deleting { model.delete(c.id) }
                deleting = nil
            }
        } message: {
            Text("It's removed from Done tasks for good.")
        }
    }
}

/// "My order": drag the tab's check-ins (or tasks: their priority) up and down.
struct ReorderView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(model.tab.items(model.checkins)) { c in Text(c.name) }
                    .onMove { model.moveInTab(from: $0, to: $1) }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("My order")
            .toolbar { Button("Done") { dismiss() } }
        }
    }
}

/// Short descriptions of a check-in.
enum CheckinSummary {
    /// A shared task: who may do it, as its owner set it.
    static func assigned(_ c: Checkin, _ who: String) -> String {
        switch c.linkedAssignee {
        case Checkin.linkYou: return "assigned to you"
        case Checkin.linkOwner: return "only \(who) can do it"
        case Checkin.linkOther: return "assigned to someone else"
        default: return "anyone can do it"
        }
    }

    /// e.g. "7:00 AM, 9:30 PM · Mon–Fri · 3 × Medium, 2 questions"
    static func oneLine(_ c: Checkin) -> String {
        var parts: [String] = []
        if c.times.isEmpty && c.events.isEmpty {
            parts.append("No triggers")
        }
        if !c.times.isEmpty {
            parts.append(c.times.map { t in
                TimeText.of(t) + (t.days.map { " \($0.describe())" } ?? "")
                    + (t.usesLocation ? " \(Places.whereText(t.where, t.place))" : "")
            }.joined(separator: ", "))
            if c.repeat != .everyDay { parts.append(c.repeat.describe()) }
        }
        for t in c.events {
            parts.append(t.kind == Trigger.runFor ? "\(Trigger.runForText(t.minRunMinutes)) into a run" : PlaceText.describe(t).lowercasedFirst)
        }
        parts.append(steps(c))
        return parts.joined(separator: " · ")
    }

    static func steps(_ c: Checkin) -> String {
        let active = c.activeSteps
        if active.isEmpty { return "Just a Dismiss button" }
        return active.map { step($0) }.joined(separator: ", ")
    }

    static func step(_ s: StepConfig) -> String {
        switch s.kind {
        case StepConfig.math: return "\(s.mathCount) × \(MathGen.levelName(s.mathLevel))"
        case StepConfig.scan: return s.scanLabel.isEmpty ? "Scan a code" : "Scan at \(s.scanLabel)"
        case StepConfig.pickTasks: return "pick \(s.pickCount) task\(s.pickCount == 1 ? "" : "s")"
        default: return "\(s.questions.count) question\(s.questions.count == 1 ? "" : "s")"
        }
    }
}

extension String {
    /// "When you arrive" → "when you arrive"
    var lowercasedFirst: String { prefix(1).lowercased() + dropFirst() }
}

/// Something a connection shares with you: its name, when it's set for and its description.
/// Read-only; Add to my list when you may do it.
struct SharedCard: View {
    @Environment(AppModel.self) private var model
    let link: Connection
    let checkin: Checkin
    @State private var added = false

    var body: some View {
        let c = checkin
        let who = link.peerName.isEmpty ? "someone" : link.peerName
        VStack(alignment: .leading, spacing: 6) {
            Text(c.name).font(.headline)
            Text(CheckinSummary.oneLine(c)).font(.subheadline).foregroundStyle(Theme.muted)
            if !c.description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(c.description).font(.subheadline).lineLimit(4)
            }
            Text("From \(who)" + (c.category.isEmpty ? "" : " · \(c.category)")).font(.footnote).foregroundStyle(Theme.muted)
            if c.isTask {
                Text(CheckinSummary.assigned(c, who)).font(.footnote).foregroundStyle(Theme.muted)
                    .accessibilityIdentifier("assigned:\(link.id):\(c.id)")
            }
            if c.youCanDo {
                Button("Add to my list") {
                    if model.addShared(link, c) != nil { added = true }
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("add-shared:\(link.id):\(c.id)")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("shared:\(link.id):\(c.id)")
        .alert("Added", isPresented: $added) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Its alarms are yours to set; its name and description stay \(who)'s.")
        }
    }
}
