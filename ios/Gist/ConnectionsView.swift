import CoreImage.CIFilterBuiltins
import GistCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Settings → Connections, laid out like Android's: your name, your Gist folder (and a join
/// waiting for its folder), your devices, a new connection (Invite / Join), then each
/// connection. Folders are shared between devices by whatever you use to share folders.
struct ConnectionsView: View {
    @Environment(AppModel.self) private var model
    @State private var name = ""
    @State private var deviceName = ""
    @State private var picking: Pick?
    @State private var showing: ShownInvite?
    @State private var joining: Character?
    @State private var askingWho = false
    @State private var who = ""
    @State private var message: String?
    @State private var confirmRemove: Connection?
    @State private var confirmRemoveDevice: (side: String, name: String)?
    @State private var confirmLeave = false
    @State private var confirmAdopt: Connection?
    /// A scanned or typed invite waiting for its folder to be picked (no Gist folder yet).
    @State private var pendingInvite: LinkInvite?
    @State private var adopting: String?

    enum Pick: Identifiable {
        case root, setUp, join, adopt
        var id: Self { self }
    }

    struct ShownInvite: Identifiable {
        let title: String
        let how: String
        let invite: LinkInvite
        let expires: Int
        var id: String { invite.code }
    }

    private var parts: SyncParts { model.sync }

    init() {}

    var body: some View {
        let _ = model.syncRevision
        Form {
            Section {
                TextField("Your name", text: $name)
                    .textInputAutocapitalization(.words)
                    .onSubmit { model.linkName = name }
                    .accessibilityIdentifier("my-name")
            } header: {
                Text("Your name")
            } footer: {
                Text("What the people you connect with see.")
            }
            gistFolderSection
            devicesSection
            newConnectionSection
            ForEach(parts.connections.all, id: \.id) { linkSection($0) }
        }
        .navigationTitle("Connections")
        .onAppear {
            name = model.linkName
            deviceName = parts.devices.name
            tryWaiting(quiet: true)
        }
        .onDisappear { if name != model.linkName { model.linkName = name } }
        .refreshable { model.syncNow() }
        .sheet(item: $picking) { p in
            FolderPicker { url in
                picking = nil
                guard let url else { return }
                picked(p, url)
            }
        }
        .sheet(item: $showing) { InviteSheet(shown: $0) }
        .sheet(isPresented: Binding(get: { joining != nil }, set: { if !$0 { joining = nil } })) {
            JoinSheet(kind: joining ?? "c") { code in
                let kind = joining ?? "c"
                joining = nil
                gotInvite(kind, code)
            }
        }
        .alert("Who's it for?", isPresented: $askingWho) {
            TextField("e.g. Jess", text: $who).accessibilityIdentifier("invite-who")
            Button("Invite") { invite(for: who) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Gist makes a folder for it in People in your Gist folder, named for them.")
        }
        .alert("Connections", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(message ?? "")
        }
        .confirmationDialog("Remove this connection?", isPresented: Binding(get: { confirmRemove != nil },
                                                                            set: { if !$0 { confirmRemove = nil } }),
                            titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                if let l = confirmRemove { model.removeConnection(l.id) }
            }
        } message: {
            Text("You stop sharing with them and forget what they shared. Your own check-ins stay.")
        }
        .confirmationDialog("Remove \(confirmRemoveDevice?.name ?? "it")?",
                            isPresented: Binding(get: { confirmRemoveDevice != nil }, set: { if !$0 { confirmRemoveDevice = nil } }),
                            titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                if let d = confirmRemoveDevice {
                    try? parts.devices.remove(d.side)
                    model.syncNow()
                }
            }
        } message: {
            Text("It stops getting your changes, and your devices switch to a new key it doesn't have, so it can't read anything synced from now on. Use this for a device that's lost; on one you have, tap Stop syncing there instead.")
        }
        .confirmationDialog("Stop syncing here?", isPresented: $confirmLeave, titleVisibility: .visible) {
            Button("Stop syncing", role: .destructive) { model.leaveDevices() }
        } message: {
            Text("Everything stays on this device; it stops keeping in step with your others, and they stop listing it.")
        }
        .confirmationDialog("Keep it on this device?", isPresented: Binding(get: { confirmAdopt != nil },
                                                                             set: { if !$0 { confirmAdopt = nil } }),
                            titleVisibility: .visible) {
            Button("Go ahead") { if let l = confirmAdopt { adopt(l) } }
        } message: {
            Text("This device takes over reading and sharing for it, and \(confirmAdopt?.homeName ?? "the other device") stops once it hears. Do this when that device is gone: if both share at once, changes can clash.")
        }
    }

    // MARK: Gist folder

    @ViewBuilder private var gistFolderSection: some View {
        Section {
            if let root = parts.gistFolder.root {
                Text(FolderAccess.name(root)).accessibilityIdentifier("gist-folder")
                Text("My devices: share it with your other devices. People: one folder per connection, each shared with that person only.")
                    .font(.footnote).foregroundStyle(Theme.muted)
                Button("Change…") { picking = .root }.accessibilityIdentifier("gist-folder-change")
            } else {
                Text("Pick one folder for Gist to sync through (say Gist in Files). Gist keeps a folder in it for your devices (My devices) and one for each person you connect with (People/Jess). Share each of those with whatever you use to share folders, not the Gist folder itself.")
                    .foregroundStyle(Theme.muted)
                Button("Pick the Gist folder…") { picking = .root }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("gist-folder-pick")
            }
            if let w = parts.gistFolder.waiting {
                let devices = w.hasPrefix("d:")
                Text("Waiting for \(devices ? "My devices" : "their folder") to arrive. Put the shared folder in \(devices ? "your Gist folder as My devices" : "People in your Gist folder"); Gist joins as soon as it's there.")
                    .foregroundStyle(Color.orange)
                    .accessibilityIdentifier("join-waiting")
                Button("Look again") { tryWaiting(quiet: false) }.accessibilityIdentifier("join-look")
                Button("Pick it myself…") {
                    pendingInvite = LinkInvite.parse(String(w.dropFirst(2)))
                    parts.gistFolder.waiting = nil
                    joiningDevicesByPick = devices
                    picking = .join
                }
                Button("Cancel", role: .destructive) { parts.gistFolder.waiting = nil; model.syncNow() }
            }
        } header: {
            Text("Your Gist folder")
        }
    }

    @State private var joiningDevicesByPick = false

    // MARK: Devices

    @ViewBuilder private var devicesSection: some View {
        let d = parts.devices
        Section {
            if d.folder == nil {
                Text("Keep all your check-ins, tasks and answers in step on your iPhone and iPad (say). Set up on one: Gist makes My devices in your Gist folder; share it with your other devices. Then, for each, tap Add a device on one that's in and Join on the new one (scan or type its code).")
                    .foregroundStyle(Theme.muted)
                Button("Set up…") { setUpDevices() }.accessibilityIdentifier("devices-setup")
                Button("Join…") { joining = "d" }.accessibilityIdentifier("devices-join")
            } else {
                TextField("This device's name", text: $deviceName)
                    .onSubmit { d.name = deviceName; model.syncSoon() }
                    .accessibilityIdentifier("device-name")
                Text("This device's files are in from-\(d.side) in the folder.")
                    .font(.footnote).foregroundStyle(Theme.muted)
                if !d.ready {
                    Text("Waiting to be let in: open Gist on the device whose code you used.")
                        .foregroundStyle(Color.orange).accessibilityIdentifier("devices-waiting")
                }
                let others = d.others()
                if others.isEmpty {
                    Text("No other devices yet: tap Add a device, then Join on the other device.").foregroundStyle(Theme.muted)
                }
                ForEach(others.indices, id: \.self) { i in
                    let o = others[i]
                    HStack {
                        VStack(alignment: .leading) {
                            Text("\(o.name) · code \(o.code)").accessibilityIdentifier("device:\(o.side)")
                            Text("from-\(o.side)").font(.footnote).foregroundStyle(Theme.muted)
                        }
                        Spacer()
                        if d.ready {
                            Button("Remove", role: .destructive) { confirmRemoveDevice = (o.side, o.name) }
                                .buttonStyle(.borderless)
                        }
                    }
                }
                Text("Each pair shows the same code on both devices when nobody swapped a key.")
                    .font(.footnote).foregroundStyle(Theme.muted)
                if d.ready {
                    Button("Add a device…") {
                        if let i = d.invitation() {
                            showing = ShownInvite(title: "Add a device", how: "On the other device, open Connections, tap Join under Your devices, and scan this (or type the code).", invite: i.invite, expires: i.expires)
                        }
                    }
                    .accessibilityIdentifier("devices-add")
                }
                Toggle("Ring on this device", isOn: Binding(get: { model.ringHere }, set: { model.setRingHere($0) }))
                    .accessibilityIdentifier("ring-here")
                if !model.ringHere {
                    Text("Check-ins don't ring here; what's waiting still shows, and answering it here counts on all your devices.")
                        .font(.footnote).foregroundStyle(Theme.muted)
                }
                Button("Stop syncing on this device", role: .destructive) { confirmLeave = true }
                    .accessibilityIdentifier("devices-leave")
            }
        } header: {
            Text("Your devices")
        }
    }

    // MARK: Connections

    @ViewBuilder private var newConnectionSection: some View {
        Section {
            Text("One side invites: Gist makes a folder for the connection in your Gist folder (People/Jess, say) and shows an invite code; share that folder with them. The other joins: scans or types the code, and Gist finds the folder once it's arrived. What you share is encrypted, and only you can change it.")
                .foregroundStyle(Theme.muted)
            Button("Invite…") {
                guard needName() else { return }
                if parts.gistFolder.root != nil { who = ""; askingWho = true } else { picking = .setUp; invitingByPick = true }
            }
            .accessibilityIdentifier("invite")
            Button("Join…") { if needName() { joining = "c" } }.accessibilityIdentifier("join")
        } header: {
            Text("New connection")
        }
    }

    @State private var invitingByPick = false

    @ViewBuilder private func linkSection(_ l: Connection) -> some View {
        Section {
            Text(l.connected ? (l.peerName.isEmpty ? "Someone" : l.peerName) : "Waiting for someone to join…")
                .font(.headline)
                .accessibilityIdentifier("link-name:\(l.id)")
            if l.connected {
                Text("Code \(l.code ?? ""): it's the same on their phone when nobody swapped a key. Compare it once.")
                    .font(.footnote).foregroundStyle(Theme.muted)
                let mine = model.checkins.filter { $0.sharedWith.contains(l.id) }.count
                let theirs = parts.connections.shared(l).count
                Text("You share \(mine) \(mine == 1 ? "item" : "items") · they share \(theirs) \(theirs == 1 ? "item" : "items")")
                    .font(.footnote).foregroundStyle(Theme.muted)
                    .accessibilityIdentifier("link-counts:\(l.id)")
                Toggle("Show on the start page", isOn: Binding(get: { l.shown }, set: { on in
                    parts.connections.setShown(l.id, on)
                    model.syncNow()
                }))
            } else if l.isHere {
                Text("Show them the invite: they tap Join and scan it (or type the code).")
                    .font(.footnote).foregroundStyle(Theme.muted)
                Button("Show the invite") { showLinkInvite(l) }.accessibilityIdentifier("link-invite:\(l.id)")
            } else {
                Text("The invite is on \(l.homeName.isEmpty ? "your other device" : l.homeName).")
                    .font(.footnote).foregroundStyle(Theme.muted)
            }
            if !l.isHere {
                Text("Kept on \(l.homeName.isEmpty ? "your other device" : l.homeName): it reads and shares for this connection, and what you change here reaches it through your devices.")
                    .font(.footnote).foregroundStyle(Theme.muted)
                    .accessibilityIdentifier("link-home:\(l.id)")
                Button("Keep it on this device instead…") { confirmAdopt = l }
            } else {
                if parts.devices.folder != nil {
                    Text("Kept on this device: it reads and shares for this connection for all your devices.")
                        .font(.footnote).foregroundStyle(Theme.muted)
                }
                Button("Remove", role: .destructive) { confirmRemove = l }
                    .accessibilityIdentifier("link-remove:\(l.id)")
            }
        }
    }

    // MARK: Actions

    private func needName() -> Bool {
        if name.trimmingCharacters(in: .whitespaces).isEmpty && model.linkName.isEmpty {
            message = "Add your name first, so they know it's you."
            return false
        }
        if name != model.linkName { model.linkName = name }
        return true
    }

    private func picked(_ p: Pick, _ url: URL) {
        guard let loc = try? FolderAccess.remember(url) else {
            message = "Gist couldn't keep that folder. Try another."
            return
        }
        do {
            switch p {
            case .root:
                parts.gistFolder.root = loc
                tryWaiting(quiet: true)
            case .setUp:
                if invitingByPick {
                    invitingByPick = false
                    let l = try parts.connections.invite(folder: loc)
                    showLinkInvite(l)
                } else {
                    try model.setUpDevices(loc)
                }
            case .join:
                guard let inv = pendingInvite else { return }
                pendingInvite = nil
                if joiningDevicesByPick || inv.kind == "d" {
                    joiningDevicesByPick = false
                    try model.joinDevices(loc, inv)
                    message = "Joined. Open Gist on the device that showed the invite to let this one in."
                } else {
                    let l = try parts.connections.join(folder: loc, invite: inv)
                    message = "Connected with \(l.peerName.isEmpty ? "them" : l.peerName)."
                    model.syncNow()
                }
            case .adopt:
                guard let id = adopting else { return }
                adopting = nil
                try parts.connections.adopt(id, folder: loc)
                model.syncNow()
                message = "Kept on this device now."
            }
        } catch {
            message = Self.problem(error)
        }
        model.syncNow()
    }

    static func problem(_ e: Error) -> String {
        if let p = e as? Connections.JoinProblem {
            switch p {
            case .notAConnection: return "There's no invite to join in that folder."
            case .alreadyYours: return "You're already in that connection."
            case .notTheirInvite: return "That invite isn't for the connection in that folder."
            }
        }
        if let p = e as? Devices.JoinProblem {
            switch p {
            case .notDevices: return "That folder isn't set up for your devices: tap Set up on one of them first."
            case .notTheirInvite: return "That invite isn't from a device in that folder."
            }
        }
        if let p = e as? Connections.AdoptProblem, p == .notItsFolder {
            return "That isn't this connection's folder: pick the one you share with the other person."
        }
        return "Gist couldn't use that folder: \(e.localizedDescription)"
    }

    private func setUpDevices() {
        guard let loc = parts.gistFolder.devices() else { picking = .setUp; return }
        if let f = try? FolderAccess.folder(loc), Devices.groupOf(f) != nil {
            message = "My devices is already set up in your Gist folder: tap Join instead."
            return
        }
        do {
            try model.setUpDevices(loc)
            message = "Set up. Now share My devices (in your Gist folder) with your other devices."
        } catch {
            message = Self.problem(error)
        }
    }

    private func invite(for person: String) {
        guard let loc = parts.gistFolder.newPerson(person) else { return }
        do {
            let l = try parts.connections.invite(folder: loc)
            showLinkInvite(l, person: String(loc.split(separator: "/").last ?? ""))
            model.syncNow()
        } catch {
            message = "Couldn't make the folder: pick your Gist folder again."
        }
    }

    private func showLinkInvite(_ l: Connection, person: String? = nil) {
        let folder = person ?? (l.folder.contains("#\(GistFolder.peopleName)/") ? String(l.folder.split(separator: "/").last ?? "") : nil)
        guard let i = parts.connections.invitation(l) else { return }
        showing = ShownInvite(title: "Invite",
                              how: (folder.map { "Share People/\($0) (in your Gist folder) with them. " } ?? "") +
                                  "On their phone, they open Connections, tap Join and scan this (or type the code).",
                              invite: i.invite, expires: i.expires)
    }

    private func gotInvite(_ kind: Character, _ text: String) {
        guard let inv = LinkInvite.parse(text) else { message = "That isn't a Gist invite code: check it and try again."; return }
        guard inv.kind == kind else {
            message = kind == "c" ? "That's an invite for your devices: use Join under Your devices."
                : "That's an invite for a connection: use Join under New connection."
            return
        }
        if parts.gistFolder.root != nil {
            if !joinInGistFolder(inv, quiet: true) { parts.gistFolder.waiting = "\(kind):\(inv.code)" }
            model.syncNow()
        } else {
            pendingInvite = inv
            joiningDevicesByPick = kind == "d"
            message = "Now pick the same folder they picked."
            picking = .join
        }
    }

    /// Joins with [inv] in the Gist folder. False when its folder isn't there yet.
    private func joinInGistFolder(_ inv: LinkInvite, quiet: Bool) -> Bool {
        let gf = parts.gistFolder
        if inv.kind == "d" {
            guard let loc = gf.devices() else { return false }
            if parts.devices.folder != nil { message = "This device already syncs with your devices."; return true }
            do {
                try model.joinDevices(loc, inv)
                message = "Joined. Open Gist on the device that showed the invite to let this one in."
                return true
            } catch {
                if !quiet { message = "My devices isn't here yet, or the invite's device isn't in it yet." }
                return false
            }
        }
        guard let loc = parts.connections.findInvited(Array(gf.people().values), inv) else {
            if !quiet { message = "Their folder isn't in People yet." }
            return false
        }
        do {
            let l = try parts.connections.join(folder: loc, invite: inv)
            message = "Connected with \(l.peerName.isEmpty ? "them" : l.peerName)."
            return true
        } catch {
            return false
        }
    }

    private func tryWaiting(quiet: Bool) {
        guard let w = parts.gistFolder.waiting else { return }
        guard let inv = LinkInvite.parse(String(w.dropFirst(2))) else { parts.gistFolder.waiting = nil; return }
        if joinInGistFolder(inv, quiet: quiet) {
            parts.gistFolder.waiting = nil
            model.syncNow()
        }
    }

    private func adopt(_ l: Connection) {
        let found = parts.gistFolder.people().values.first { loc in
            (try? FolderAccess.folder(loc)).flatMap { LinkProtocol.linkId($0) } == l.id
        }
        if let found, (try? parts.connections.adopt(l.id, folder: found)) != nil {
            message = "Kept on this device now."
            model.syncNow()
        } else {
            adopting = l.id
            picking = .adopt
        }
    }
}

/// An invite: its QR code, and the code to type under it, until it runs out.
struct InviteSheet: View {
    let shown: ConnectionsView.ShownInvite
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    Text(shown.how).foregroundStyle(Theme.muted)
                    if let image = Self.qr(shown.invite.qr) {
                        Image(uiImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: 260)
                            .accessibilityLabel("Invite QR code")
                            .accessibilityIdentifier("invite-qr")
                    }
                    Text(shown.invite.code)
                        .font(.system(.title3, design: .monospaced).bold())
                        .textSelection(.enabled)
                        .multilineTextAlignment(.center)
                        .accessibilityIdentifier("invite-code")
                    Text("Works once, until \(Date(timeIntervalSince1970: Double(shown.expires) / 1000).formatted(date: .omitted, time: .shortened)). Don't send it through the shared folder.")
                        .font(.footnote).foregroundStyle(Theme.muted)
                    Button("Copy code") { UIPasteboard.general.string = shown.invite.code }
                }
                .padding()
            }
            .navigationTitle(shown.title)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }

    static func qr(_ text: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// Joining: scan the invite's QR code, or type the code under it.
struct JoinSheet: View {
    let kind: Character
    let onCode: (String) -> Void
    @State private var typed = ""
    @State private var scanning = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Scan the invite's QR code on the other \(kind == "c" ? "phone" : "device"), or type the code shown under it.")
                        .foregroundStyle(Theme.muted)
                    if CodeScanner.supported {
                        Button(scanning ? "Scanning…" : "Scan") {
                            Task { scanning = await CodeScanner.requestCamera() }
                        }
                        .accessibilityIdentifier("join-scan")
                    }
                }
                if scanning {
                    Section {
                        CodeScanner { code in
                            if LinkInvite.parse(code) != nil {
                                scanning = false
                                onCode(code)
                            }
                        }
                        .frame(height: 280)
                    }
                }
                Section("Type the code") {
                    TextField("ABCD-EFGH-…", text: $typed)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("invite-field")
                    Button("Next") { onCode(typed) }
                        .disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
                        .accessibilityIdentifier("invite-next")
                }
            }
            .navigationTitle(kind == "c" ? "Join a connection" : "Join your devices")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
}

/// Files' folder picker (the folder is reached later through a bookmark).
struct FolderPicker: UIViewControllerRepresentable {
    let onPick: (URL?) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let p = UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
        p.delegate = context.coordinator
        return p
    }

    func updateUIViewController(_ vc: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL?) -> Void
        init(onPick: @escaping (URL?) -> Void) { self.onPick = onPick }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { onPick(urls.first) }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { onPick(nil) }
    }
}
