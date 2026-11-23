import Charts
import GistCore
import SwiftUI

/// One check-in's last 30 days: how many were logged and on track, which questions slip
/// most, and a day-by-day grid (✓ on track, ✗ off track or missed, – skipped, · didn't ring).
struct HistoryView: View {
    @Environment(AppModel.self) private var model
    let checkinId: String

    var body: some View {
        if let c = model.checkin(checkinId) {
            let results = model.history(c)
            let days = Days.lastN(30, calendar: model.calendar)
            List {
                Section {
                    Text(Stats.line(c, results, days: days))
                    ForEach(c.questions) { q in
                        let off = days.compactMap { results[$0] }.filter { Stats.dayMark(q, $0) == .offTrack }.count
                        LabeledContent(q.text, value: "\(off)× off track")
                    }
                }
                timing(results, days)
                Section("Day by day") {
                    ForEach(days, id: \.self) { day in
                        HStack {
                            Text(TimeText.day(day)).font(.subheadline)
                            Spacer()
                            Text(c.questions.map { cell($0, results[day] ?? [:]) }.joined(separator: " "))
                                .font(.body.monospaced())
                        }
                    }
                }
            }
            .navigationTitle(c.name)
            .navigationBarTitleDisplayMode(.inline)
        } else {
            Text("This check-in was deleted.").foregroundStyle(Theme.muted)
        }
    }

    /// How long it takes: medians for the last 7 and 30 days, and a bar per day (getting
    /// started, then doing it). Only once something has been timed.
    @ViewBuilder private func timing(_ results: History, _ days: [String]) -> some View {
        let timed = Timing.days(results, days)
        if let month = Timing.line(timed) {
            Section {
                if let week = Timing.line(Timing.days(results, Array(days.prefix(7)))) {
                    LabeledContent("Last 7 days", value: week)
                }
                LabeledContent("Last 30 days", value: month)
                    .accessibilityIdentifier("timing-30")
                Chart {
                    ForEach(timed, id: \.day) { d in
                        if let s = d.start {
                            BarMark(x: .value("Day", Days.date(d.day) ?? Date(), unit: .day), y: .value("Minutes", Double(s) / 60))
                                .foregroundStyle(by: .value("Part", "Getting started"))
                        }
                        BarMark(x: .value("Day", Days.date(d.day) ?? Date(), unit: .day), y: .value("Minutes", Double(d.steps) / 60))
                            .foregroundStyle(by: .value("Part", "Doing it"))
                    }
                }
                .chartForegroundStyleScale(["Getting started": Color.orange, "Doing it": Theme.primaryColor])
                .chartYAxisLabel("minutes")
                .frame(height: 160)
            } header: {
                Text("How long it takes")
            } footer: {
                Text("Getting started: from when it rang to Start check-in. Doing it: from then to done. Middle values (medians), so one slow day doesn't skew them.")
            }
        }
    }

    /// A number or text question on track shows what you entered (the day's last one).
    private func cell(_ q: Question, _ day: DayResults) -> String {
        let m = Stats.dayMark(q, day)
        if q.takesValue, m == .onTrack,
           let v = day.keys.sorted().compactMap({ day[$0]?.values[q.id] }).last(where: { !$0.isEmpty }) {
            return v.count > 12 ? String(v.prefix(11)) + "…" : v
        }
        return symbol(m)
    }

    private func symbol(_ m: Mark) -> String {
        switch m {
        case .onTrack: return "✓"
        case .offTrack: return "✗"
        case .skipped: return "–"
        case .none: return "·"
        }
    }
}
