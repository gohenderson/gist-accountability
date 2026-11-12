import GistCore
import SwiftUI

/// Export backup: which check-ins go in the file, each with its history or without.
struct ExportChoicesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    /// Called with the chosen check-ins.
    let onExport: ([Backup.ExportChoice]) -> Void

    @State private var included: Set<String> = []
    @State private var withHistory: Set<String> = []
    @State private var ready = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(model.checkins) { c in
                        VStack(alignment: .leading, spacing: 6) {
                            Toggle(c.name, isOn: member(c.id, $included))
                                .accessibilityIdentifier("export-include:\(c.id)")
                            if included.contains(c.id) {
                                Toggle("Include its history (\(days(c)))", isOn: member(c.id, $withHistory))
                                    .font(.footnote)
                                    .padding(.leading, 16)
                                    .accessibilityIdentifier("export-history:\(c.id)")
                            }
                        }
                    }
                } header: {
                    HStack {
                        Text("Check-ins")
                        Spacer()
                        Button(included.count == model.checkins.count ? "None" : "All") {
                            included = included.count == model.checkins.count ? [] : Set(model.checkins.map { $0.id })
                        }
                        .font(.footnote)
                        .accessibilityIdentifier("export-all")
                    }
                } footer: {
                    Text("Settings and history, as one .yaml file you can read and edit. It's the same format as Gist for Android, so it moves between phones either way. With one check-in, the file is named after it: handy for sharing a check-in.")
                }
            }
            .navigationTitle("Export backup")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Export") {
                        let choices = model.checkins.filter { included.contains($0.id) }
                            .map { Backup.ExportChoice($0.id, withHistory: withHistory.contains($0.id)) }
                        dismiss()
                        onExport(choices)
                    }
                    .disabled(included.isEmpty)
                    .accessibilityIdentifier("export-go")
                }
            }
            .onAppear {
                guard !ready else { return }
                ready = true
                included = Set(model.checkins.map { $0.id })
                withHistory = included
            }
        }
    }

    private func days(_ c: Checkin) -> String {
        let n = model.history(c).count
        return n == 1 ? "1 day" : "\(n) days"
    }
}

/// Import backup: what's in the file, and for each check-in whether to bring it in, with
/// its history or not, and what to do if you already have it.
struct ImportChoicesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let contents: Backup.Contents

    @State private var included: Set<String> = []
    @State private var withHistory: Set<String> = []
    @State private var modes: [String: Backup.Mode] = [:]
    @State private var ready = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(contents.checkins) { c in
                        VStack(alignment: .leading, spacing: 6) {
                            Toggle(c.name, isOn: member(c.id, $included))
                                .accessibilityIdentifier("import-include:\(c.id)")
                            if included.contains(c.id) {
                                let n = contents.historyDays(c.id)
                                if n > 0 {
                                    Toggle("Import its history (\(n == 1 ? "1 day" : "\(n) days"))", isOn: member(c.id, $withHistory))
                                        .font(.footnote)
                                        .padding(.leading, 16)
                                }
                                if let mine = model.match(c) {
                                    Picker("You have \(mine.name)", selection: modeBinding(c.id)) {
                                        Text("Update it").tag(Backup.Mode.update)
                                        Text("Replace it").tag(Backup.Mode.replace)
                                        Text("Keep it, add this as a copy").tag(Backup.Mode.copy)
                                    }
                                    .font(.footnote)
                                    .padding(.leading, 16)
                                    .accessibilityIdentifier("import-mode:\(c.id)")
                                    Text(explain(modes[c.id] ?? .update))
                                        .font(.caption)
                                        .foregroundStyle(Theme.muted)
                                        .padding(.leading, 16)
                                } else {
                                    Text("New: it's added.").font(.caption).foregroundStyle(Theme.muted).padding(.leading, 16)
                                }
                            }
                        }
                    }
                } header: {
                    Text("In this file")
                } footer: {
                    Text("Check-ins you don't pick, and yours that aren't in the file, are left alone. Photos, videos and voice clips stay on Android for now.")
                }
            }
            .navigationTitle("Import backup")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Import") {
                        let choices = contents.checkins.filter { included.contains($0.id) }.map { c in
                            Backup.ImportChoice(c.id, mode: model.match(c) == nil ? .new : (modes[c.id] ?? .update),
                                                withHistory: withHistory.contains(c.id))
                        }
                        let n = model.importBackup(contents, choices: choices)
                        model.notice = "Imported \(n) check-in\(n == 1 ? "" : "s")."
                        dismiss()
                    }
                    .disabled(included.isEmpty)
                    .accessibilityIdentifier("import-go")
                }
            }
            .onAppear {
                guard !ready else { return }
                ready = true
                included = Set(contents.checkins.map { $0.id })
                withHistory = included
            }
        }
    }

    private func modeBinding(_ id: String) -> Binding<Backup.Mode> {
        Binding(get: { modes[id] ?? .update }, set: { modes[id] = $0 })
    }

    private func explain(_ mode: Backup.Mode) -> String {
        switch mode {
        case .update: return "Settings from the file; your history is kept (the file's is added, and wins for the same time)."
        case .replace: return "The file's version, history included. Yours is deleted."
        case .copy: return "Added alongside yours, named \"… (imported)\"."
        case .new: return ""
        }
    }
}

/// A toggle's binding for "[id] is in [set]".
func member(_ id: String, _ set: Binding<Set<String>>) -> Binding<Bool> {
    Binding(get: { set.wrappedValue.contains(id) }, set: { on in
        if on { set.wrappedValue.insert(id) } else { set.wrappedValue.remove(id) }
    })
}
