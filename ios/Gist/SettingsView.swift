import GistCore
import SwiftUI
import UniformTypeIdentifiers

/// A backup file, for the share sheet and the file picker.
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.yaml, .plainText, .json, .data] }
    static var writableContentTypes: [UTType] { [.yaml] }

    var text: String

    init(text: String) {
        self.text = text
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents, let text = String(data: data, encoding: .utf8) else {
            throw BackupError("That file couldn't be read.")
        }
        self.text = text
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

/// Backups (the same files as the Android app's) and About.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var choosingExport = false
    @State private var exporting: BackupDocument?
    @State private var exportName = "gist-backup.yaml"
    @State private var importing = false
    @State private var imported: Backup.Contents?

    private var version: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? ""
        return b.isEmpty ? v : "\(v) (\(b))"
    }

    var body: some View {
        Form {
            Section {
                Button("Export backup…") { choosingExport = true }
                .accessibilityIdentifier("export")
                Button("Import backup…") { importing = true }
                    .accessibilityIdentifier("import")
            } header: {
                Text("Backup")
            } footer: {
                Text("Choose which check-ins go in or come in, with their history or without. It's the same format as Gist for Android, so a backup moves between phones either way.")
            }

            Section("About") {
                LabeledContent("Version", value: version)
                Text("Alarms that make you follow through. No ads, no accounts, no tracking: your data stays on your phone.")
                    .foregroundStyle(Theme.muted)
                Link("Source code", destination: URL(string: "https://github.com/gohenderson/gist-accountability")!)
                Link("Report a problem", destination: URL(string: "https://github.com/gohenderson/gist-accountability/issues")!)
                Link("Donate", destination: URL(string: "https://github.com/gohenderson/gist-accountability#donate")!)
            }

            Section {
                Text("Gist is free software under the GNU General Public License, version 3 or later. It comes with ABSOLUTELY NO WARRANTY.")
                    .font(.footnote)
                    .foregroundStyle(Theme.muted)
                Link("Read the license", destination: URL(string: "https://github.com/gohenderson/gist-accountability/blob/main/LICENSE")!)
            }
        }
        .navigationTitle("Settings")
        .sheet(isPresented: $choosingExport) {
            ExportChoicesView { choices in
                do {
                    exportName = model.backupName(choices)
                    let text = try model.backupText(choices)
                    // After the sheet has gone, or the file panel won't show.
                    Task {
                        try? await Task.sleep(for: .milliseconds(500))
                        exporting = BackupDocument(text: text)
                    }
                } catch {
                    model.notice = "Couldn't make the backup: \(error)"
                }
            }
        }
        .sheet(isPresented: Binding(get: { imported != nil }, set: { if !$0 { imported = nil } })) {
            if let imported { ImportChoicesView(contents: imported) }
        }
        .fileExporter(isPresented: Binding(get: { exporting != nil }, set: { if !$0 { exporting = nil } }),
                      document: exporting, contentType: .yaml, defaultFilename: exportName) { result in
            if case .failure(let error) = result { model.notice = "Couldn't save the backup: \(error.localizedDescription)" }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: BackupDocument.readableContentTypes) { result in
            switch result {
            case .success(let url):
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do {
                    let text = try String(contentsOf: url, encoding: .utf8)
                    let contents = try Backup.parse(text)
                    Task {
                        try? await Task.sleep(for: .milliseconds(500))
                        imported = contents
                    }
                } catch let e as BackupError {
                    model.notice = e.message
                } catch {
                    model.notice = "That file couldn't be read: \(error.localizedDescription)"
                }
            case .failure(let error):
                model.notice = error.localizedDescription
            }
        }
    }
}
