import Foundation

/**
 Your Gist folder, as on Android (GistFolder.kt): picked once on each device, it holds every
 folder Gist syncs through.

     Gist/
       My devices/     shared with your own devices
       People/
         Jess/         shared with Jess only, one folder per connection

 A folder in it is reached as "<Gist folder>#People/Jess".
 */
public final class GistFolder {
    public static let devicesName = "My devices"
    public static let peopleName = "People"

    let prefs: SyncPrefs
    let openFolder: (String) throws -> LinkFolder

    public init(prefs: SyncPrefs, openFolder: @escaping (String) throws -> LinkFolder) {
        self.prefs = prefs
        self.openFolder = openFolder
    }

    /// The Gist folder on this device, or nil until picked.
    public var root: String? {
        get { prefs.string("root") }
        set { prefs.set("root", newValue) }
    }

    /// An invite whose folder hasn't arrived yet ("c:" or "d:" + its code).
    public var waiting: String? {
        get { prefs.string("waiting") }
        set { prefs.set("waiting", newValue) }
    }

    public static func at(_ root: String, _ sub: String) -> String { "\(root)#\(sub)" }

    public func location(_ sub: String) -> String? { root.map { Self.at($0, sub) } }

    /// Where your devices sync.
    public func devices() -> String? { location(Self.devicesName) }

    /// The folders under People: name → location.
    public func people() -> [String: String] {
        guard let r = root, let f = try? openFolder(r) else { return [:] }
        var out: [String: String] = [:]
        for n in f.names(Self.peopleName) where (try? LinkPath.parts(n))?.count == 1 {
            out[n] = Self.at(r, "\(Self.peopleName)/\(n)")
        }
        return out
    }

    /// A new folder under People for a connection with [person] (Jess, Jess 2…): its location.
    public func newPerson(_ person: String) -> String? {
        guard let r = root else { return nil }
        let base = Self.safeName(person).isEmpty ? "Someone" : Self.safeName(person)
        let taken = Set(((try? openFolder(r))?.names(Self.peopleName) ?? []).map { $0.lowercased() })
        var n = 1
        while taken.contains((n == 1 ? base : "\(base) \(n)").lowercased()) { n += 1 }
        return Self.at(r, "\(Self.peopleName)/\(n == 1 ? base : "\(base) \(n)")")
    }

    /// A person's name as a folder name: letters, digits, spaces, dots, dashes, underscores.
    public static func safeName(_ name: String) -> String {
        let mapped = String(name.map { ch -> Character in
            (ch.isASCII && (ch.isLetter || ch.isNumber)) || " ._-".contains(ch) ? ch : " "
        })
        let collapsed = mapped.split(separator: " ", omittingEmptySubsequences: true).joined(separator: " ")
        let trimmed = collapsed.drop { " ._-".contains($0) }
        return String(String(trimmed).prefix(40)).trimmingCharacters(in: .whitespaces)
    }
}
