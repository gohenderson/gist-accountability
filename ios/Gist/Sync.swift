import Foundation
import GistCore
import UIKit

/// Sync state in UserDefaults, one suite per part (like Android's preference files).
final class DefaultsPrefs: SyncPrefs {
    private let d: UserDefaults

    init(_ suite: String) { d = UserDefaults(suiteName: suite) ?? .standard }

    func string(_ key: String) -> String? { d.string(forKey: key) }
    func set(_ key: String, _ value: String?) {
        if let value { d.set(value, forKey: key) } else { d.removeObject(forKey: key) }
    }
}

/// Folders picked in Files, kept as bookmarks so Gist can reach them again after a restart.
/// A location is "bm:<id>" (a picked folder), or a plain path (UI tests), with "#People/Jess"
/// for a folder inside it.
enum FolderAccess {
    struct Unreachable: Error {}
    private static let prefs = DefaultsPrefs("gist-bookmarks")
    private static var open: [String: URL] = [:]

    /// Remembers a folder picked in Files (a security-scoped URL): its location.
    static func remember(_ url: URL) throws -> String {
        let ok = url.startAccessingSecurityScopedResource()
        defer { if ok { url.stopAccessingSecurityScopedResource() } }
        let data = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        let id = UUID().uuidString.lowercased()
        prefs.set(id, data.base64EncodedString())
        return "bm:\(id)"
    }

    /// The folder at [location] (kept open while the app runs).
    static func folder(_ location: String) throws -> LinkFolder {
        let parts = location.split(separator: "#", maxSplits: 1).map(String.init)
        let base = try url(parts[0])
        let f = FileFolder(base)
        return parts.count == 2 ? try SubFolder(f, parts[1]) : f
    }

    static func url(_ base: String) throws -> URL {
        if base.hasPrefix("/") { return URL(fileURLWithPath: base) }
        guard base.hasPrefix("bm:") else { throw Unreachable() }
        let id = String(base.dropFirst(3))
        if let u = open[id] { return u }
        guard let text = prefs.string(id), let data = Data(base64Encoded: text) else { throw Unreachable() }
        var stale = false
        let u = try URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
        _ = u.startAccessingSecurityScopedResource()
        if stale, let fresh = try? u.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) {
            prefs.set(id, fresh.base64EncodedString())
        }
        open[id] = u
        return u
    }

    /// A folder's name to show ("Gist").
    static func name(_ location: String) -> String {
        (try? url(String(location.split(separator: "#").first ?? "")))?.lastPathComponent ?? "Your folder"
    }
}

/// The settings your devices keep in step, by Android's keys and formats (Store.SYNCED_SETTINGS):
/// each tab's view and sort, group order, folded groups, and your name for connections. The
/// theme and pause after a tap aren't on the iPhone, so they're left to Android devices.
final class AppSettings: SyncedSettings {
    private let d = UserDefaults.standard
    static let keys = ["view", "view.tasks", "view.backlog", "sort", "sort.tasks", "sort.backlog",
                       "groupOrder", "collapsedGroups", "linkName"]
    /// Called when a setting arrives from another device.
    var onTake: (() -> Void)?

    private var stamps: [String: Int] {
        get { (d.dictionary(forKey: "settingsAt") as? [String: Int]) ?? [:] }
        set { d.set(newValue, forKey: "settingsAt") }
    }

    /// The UserDefaults key a setting lives under here.
    private static func local(_ key: String) -> String {
        switch key {
        case "collapsedGroups": return "foldedGroups"
        default: return key
        }
    }

    private func value(_ key: String) -> JSON? {
        switch key {
        case "groupOrder":
            guard let a = d.stringArray(forKey: "groupOrder") else { return nil }
            return .string(a.joined(separator: "\n"))
        case "collapsedGroups":
            guard let a = d.stringArray(forKey: "foldedGroups") else { return nil }
            return .array(a.sorted().map { .string($0) })
        default:
            return d.string(forKey: Self.local(key)).map { .string($0) }
        }
    }

    /// [key] changed here (while syncing): stamp it so your other devices take it.
    func changed(_ key: String, tracking: Bool) {
        guard tracking, Self.keys.contains(key) else { return }
        var s = stamps
        s[key] = GistData.nowMs
        stamps = s
    }

    func synced() -> [String: (json: String, at: Int)] {
        var out: [String: (json: String, at: Int)] = [:]
        for (k, at) in stamps where Self.keys.contains(k) {
            if let v = value(k) { out[k] = (JSONText.string(v), at) }
        }
        return out
    }

    func take(_ key: String, json: String, at: Int) -> Bool {
        guard Self.keys.contains(key), at > (stamps[key] ?? 0), let v = try? JSONText.parse(json) else { return false }
        switch key {
        case "groupOrder":
            guard let s = v.stringValue else { return false }
            d.set(s.isEmpty ? [] : s.components(separatedBy: "\n"), forKey: "groupOrder")
        case "collapsedGroups":
            guard let a = v.arrayValue else { return false }
            d.set(a.compactMap { $0.stringValue }, forKey: "foldedGroups")
        default:
            guard let s = v.stringValue else { return false }
            d.set(s, forKey: Self.local(key))
        }
        var st = stamps
        st[key] = at
        stamps = st
        onTake?()
        return true
    }

    func stampAll(now: Int) {
        var s = stamps
        for k in Self.keys where value(k) != nil { s[k] = now }
        stamps = s
    }
}

/// The parts of syncing: your Gist folder, connections and devices.
final class SyncParts {
    let folders = DefaultsPrefs("gist-folder")
    let settings = AppSettings()
    let gistFolder: GistFolder
    let connections: Connections
    let devices: Devices

    init() {
        let open: (String) throws -> LinkFolder = { try FolderAccess.folder($0) }
        gistFolder = GistFolder(prefs: folders, openFolder: open)
        connections = Connections(prefs: DefaultsPrefs("gist-links"), openFolder: open)
        let media = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("backgrounds", isDirectory: true)
        devices = Devices(prefs: DefaultsPrefs("gist-devices"), openFolder: open, connections: connections,
                          mediaRoot: media, defaultName: UIDevice.current.name)
        devices.settings = settings
        connections.sharedName = { UserDefaults.standard.string(forKey: "linkName") ?? "" }
    }
}
