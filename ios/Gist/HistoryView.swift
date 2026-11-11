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
                Section("Day by day") {
                    ForEach(days, id: \.self) { day in
                        HStack {
                            Text(TimeText.day(day)).font(.subheadline)
                            Spacer()
                            Text(c.questions.map { symbol(Stats.dayMark($0, results[day] ?? [:])) }.joined(separator: " "))
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

    private func symbol(_ m: Mark) -> String {
        switch m {
        case .onTrack: return "✓"
        case .offTrack: return "✗"
        case .skipped: return "–"
        case .none: return "·"
        }
    }
}
