import GistCore
import SwiftUI

enum Route: Hashable {
    case edit(String)
    case history(String)
    case settings
}

/// The start page: setup, what's waiting, the overall score, and every check-in as a card
/// (folded to one line or open), optionally grouped by category and sorted.
struct StartView: View {
    @Environment(AppModel.self) private var model
    @State private var path = NavigationPath()
    @State private var reordering = false

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    overall
                    if !model.alarmsAllowed { setupCard }
                    waitingCards
                    if model.checkins.count > 1 { controls }
                    list
                    Button {
                        let c = model.addCheckin()
                        path.append(Route.edit(c.id))
                    } label: {
                        Label("Add check-in", systemImage: "plus")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 8)
                    .accessibilityIdentifier("add-checkin")
                    NavigationLink(value: Route.settings) {
                        Text("Settings · backup · about").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .padding(.top, 16)
                }
                .padding(16)
            }
            .background(Theme.surfaceVariant.opacity(0.6).ignoresSafeArea())
            .navigationTitle("Gist")
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .edit(let id): CheckinEditView(checkinId: id)
                case .history(let id): HistoryView(checkinId: id)
                case .settings: SettingsView()
                }
            }
            .sheet(isPresented: $reordering) { ReorderView() }
        }
        .onChange(of: model.debugRoute) { _, route in
            guard let route else { return }
            model.debugRoute = nil
            let parts = route.split(separator: ":", maxSplits: 1).map(String.init)
            switch parts.first {
            case "edit": if parts.count == 2 { path.append(Route.edit(parts[1])) }
            case "history": if parts.count == 2 { path.append(Route.history(parts[1])) }
            case "settings": path.append(Route.settings)
            case "run":
                if parts.count == 2 {
                    model.running = RunRequest(pending: Pending(checkinId: parts[1], day: Days.today(), time: "07:00", practice: true), early: false)
                }
            default: break
            }
        }
    }

    // MARK: Top

    @ViewBuilder private var overall: some View {
        if let score = Score.average(model.checkins.map { model.score($0) }) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(Score.percent(score))
                    .font(.system(size: 40, weight: .bold, design: .rounded))
                    .foregroundStyle(Theme.scoreColor(score))
                    .accessibilityIdentifier("overall-score")
                Text("on track over the last \(Score.days) days")
                    .foregroundStyle(Theme.muted)
            }
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

    /// Soft check-ins that rang and are waiting (strict ones open full screen by themselves).
    @ViewBuilder private var waitingCards: some View {
        ForEach(model.waiting) { p in
            if let c = model.checkin(p.checkinId) {
                HStack {
                    VStack(alignment: .leading) {
                        Text(c.name).font(.headline)
                        Text("Waiting since \(TimeText.of(p.time))").foregroundStyle(Theme.muted)
                    }
                    Spacer()
                    Button("Check in") { model.start(p) }
                        .buttonStyle(.borderedProminent)
                }
                .card()
            }
        }
    }

    // MARK: View menu

    private var controls: some View {
        let groups = model.groupByCategory ? Grouping.byCategory(model.checkins, order: model.groupOrder) : []
        let keys = Set(groups.map { Grouping.key($0.0) })
        let ids = Set(model.checkins.map { $0.id })
        let allFolded = Grouping.allFolded(groups: keys, foldedGroups: model.foldedGroups, ids: ids, foldedIds: model.foldedCheckins)
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
            Button(allFolded ? "Expand all" : "Collapse all") {
                model.setAllFolded(!allFolded, groups: keys, ids: ids)
            }
            .accessibilityIdentifier("toggle-all")
        }
    }

    // MARK: List

    @ViewBuilder private var list: some View {
        if model.checkins.isEmpty {
            Text("No check-ins yet. Add one to get started.").foregroundStyle(Theme.muted).card()
        } else if model.groupByCategory {
            ForEach(Grouping.byCategory(model.checkins, order: model.groupOrder), id: \.0) { group in
                let key = Grouping.key(group.0)
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
                .accessibilityLabel("\(group.0), \(folded ? "collapsed" : "expanded")" + (score.map { ", \(Score.percent($0)) on track" } ?? ""))
                if !folded {
                    ForEach(sorted(group.1)) { CheckinCard(checkin: $0, path: $path) }
                }
            }
        } else {
            ForEach(sorted(model.checkins)) { CheckinCard(checkin: $0, path: $path) }
        }
    }

    private func sorted(_ list: [Checkin]) -> [Checkin] {
        Grouping.sort(list, model.sort, next: { model.next($0)?.at }, score: { model.score($0) })
    }
}

/// One check-in. Tap its title to fold it to one line or open it again.
struct CheckinCard: View {
    @Environment(AppModel.self) private var model
    let checkin: Checkin
    @Binding var path: NavigationPath

    var body: some View {
        let c = checkin
        let folded = model.foldedCheckins.contains(c.id)
        let score = model.score(c)
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Button {
                    withAnimation(.snappy) { model.toggleFolded(c.id) }
                } label: {
                    HStack(spacing: 6) {
                        Text(c.name).font(.headline).foregroundStyle(.primary).lineLimit(folded ? 1 : 3)
                        Image(systemName: folded ? "chevron.right" : "chevron.down").font(.caption).foregroundStyle(Theme.muted)
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("checkin-title:\(c.id)")
                .accessibilityLabel("\(c.name), \(folded ? "collapsed" : "expanded")" + (score.map { ", \(Score.percent($0)) on track" } ?? ""))
                Toggle("On", isOn: Binding(get: { c.enabled }, set: { model.setEnabled(c.id, $0) }))
                    .labelsHidden()
                    .accessibilityIdentifier("toggle:\(c.id)")
            }
            if folded {
                Text(nextText(c)).font(.subheadline).foregroundStyle(Theme.muted).lineLimit(1)
            } else {
                Text(CheckinSummary.oneLine(c)).font(.subheadline).foregroundStyle(Theme.muted)
                nextBox(c)
                if c.isSoft {
                    Text("Soft alarm · not counted in stats").font(.footnote).foregroundStyle(Theme.muted)
                } else if c.isVoice {
                    Text("Voice check-ins only work on Android for now.").font(.footnote).foregroundStyle(Theme.muted)
                } else if !c.questions.isEmpty {
                    Text(model.statsLine(c)).font(.footnote).foregroundStyle(Theme.muted)
                }
                HStack {
                    if !c.questions.isEmpty && c.counted {
                        Button("History") { path.append(Route.history(c.id)) }
                    }
                    Spacer()
                    Button("Edit") { path.append(Route.edit(c.id)) }
                        .accessibilityIdentifier("edit:\(c.id)")
                }
            }
        }
        .card(score: score)
        .accessibilityIdentifier(folded ? "row:\(c.id)" : "card:\(c.id)")
    }

    private func nextText(_ c: Checkin) -> String {
        if !c.enabled { return "Off" }
        if let o = model.next(c) { return "Next: \(TimeText.at(o.at))" }
        return c.times.isEmpty ? "No set times" : "Not scheduled"
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
                Spacer()
                Button("Skip") { model.skipNext(c) }
                Button("Do it now") { model.doNow(c) }
                    .buttonStyle(.bordered)
            }
        }
    }
}

/// "My order": drag check-ins up and down.
struct ReorderView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(model.checkins) { c in Text(c.name) }
                    .onMove { model.move(from: $0, to: $1) }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("My order")
            .toolbar { Button("Done") { dismiss() } }
        }
    }
}

/// Short descriptions of a check-in.
enum CheckinSummary {
    /// e.g. "7:00 AM, 9:30 PM · Mon–Fri · 3 × Medium, 2 questions"
    static func oneLine(_ c: Checkin) -> String {
        var parts: [String] = []
        if c.times.isEmpty {
            parts.append(c.events.isEmpty ? "No triggers" : "\(c.events.count) place or run trigger\(c.events.count == 1 ? "" : "s") (Android only)")
        } else {
            parts.append(c.times.map { t in
                TimeText.of(t) + (t.days.map { " \($0.describe())" } ?? "")
            }.joined(separator: ", "))
            if c.repeat != .everyDay { parts.append(c.repeat.describe()) }
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
        default: return "\(s.questions.count) question\(s.questions.count == 1 ? "" : "s")"
        }
    }
}
