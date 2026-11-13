import GistCore
import SwiftUI

/// Add check-in: start from a ready-made check-in (the same library as the Android app's) or
/// from a blank one. Either way it's yours to change afterwards.
struct LibraryView: View {
    @Environment(AppModel.self) private var model
    /// Called with the new check-in, to open it for editing.
    let onAdd: (Checkin) -> Void

    var body: some View {
        List {
            Section {
                Button {
                    onAdd(model.addCheckin())
                } label: {
                    row("Create your own", "A blank check-in ringing at noon. Add times and steps.", icon: "plus.circle.fill")
                }
                .accessibilityIdentifier("library:blank")
            }
            ForEach(Library.categories, id: \.self) { category in
                Section(category) {
                    ForEach(Library.templates.filter { $0.category == category }) { t in
                        Button {
                            onAdd(model.addCheckin(from: t))
                        } label: {
                            row(t.checkin.name, t.blurb, detail: summary(t.checkin))
                        }
                        .accessibilityIdentifier("library:\(t.key)")
                    }
                }
            }
        }
        .navigationTitle("Add check-in")
    }

    private func row(_ title: String, _ blurb: String, detail: String? = nil, icon: String? = nil) -> some View {
        HStack(alignment: .top, spacing: 12) {
            if let icon {
                Image(systemName: icon).font(.title2).foregroundStyle(Theme.primaryColor)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline).foregroundStyle(.primary)
                Text(blurb).font(.subheadline).foregroundStyle(Theme.muted)
                if let detail {
                    Text(detail).font(.caption).foregroundStyle(Theme.muted)
                }
            }
        }
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    private func summary(_ c: Checkin) -> String {
        var parts = c.times.map { TimeText.of($0.description) }
        parts += c.events.map { t in
            t.kind == Trigger.runFor ? "\(Trigger.runForText(t.minRunMinutes)) into a run" : PlaceText.describe(t).lowercasedFirst
        }
        if c.isSoft { parts.append("soft") }
        if c.isVoice { parts.append("voice") }
        return parts.joined(separator: " · ")
    }
}
