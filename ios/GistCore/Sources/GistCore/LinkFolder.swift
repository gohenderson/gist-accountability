import Foundation

/// A folder Gist syncs through, by path inside it ("from-ab12/hello.json").
public protocol LinkFolder {
    func read(_ path: String) -> Data?
    func write(_ path: String, _ bytes: Data) throws
    /// The names in folder [dir] ("" for the top), or none.
    func names(_ dir: String) -> [String]
    func delete(_ path: String)
}

public extension LinkFolder {
    func names() -> [String] { names("") }
}

/// Paths inside a sync folder: names joined by "/", nothing that could reach outside it
/// ("..", ".", empty parts, backslashes) or hidden (a leading dot). As on Android.
public enum LinkPath {
    public struct BadPath: Error {}

    public static func parts(_ path: String) throws -> [String] {
        let parts = path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard !parts.isEmpty, parts.allSatisfy({ valid($0) }) else { throw BadPath() }
        return parts
    }

    static func valid(_ part: String) -> Bool {
        guard !part.isEmpty, part.count <= 64, !part.contains(".."), let first = part.unicodeScalars.first,
              first != "." else { return false }
        return part.unicodeScalars.allSatisfy { u in u != "/" && u != "\\" && u != ":" && u.value >= 0x20 }
    }
}

/// A folder on this device (on iOS, one picked in Files: the caller keeps its
/// security-scoped access open while using it).
public struct FileFolder: LinkFolder {
    public let root: URL

    public init(_ root: URL) { self.root = root }

    private func url(_ path: String) -> URL? {
        guard let parts = try? LinkPath.parts(path) else { return nil }
        return parts.reduce(root) { $0.appendingPathComponent($1) }
    }

    public func read(_ path: String) -> Data? {
        guard let u = url(path) else { return nil }
        return try? Data(contentsOf: u)
    }

    public func write(_ path: String, _ bytes: Data) throws {
        guard let u = url(path) else { throw LinkPath.BadPath() }
        try FileManager.default.createDirectory(at: u.deletingLastPathComponent(), withIntermediateDirectories: true)
        try bytes.write(to: u, options: .atomic)
    }

    public func names(_ dir: String) -> [String] {
        let u: URL
        if dir.isEmpty { u = root } else { guard let d = url(dir) else { return [] }; u = d }
        return ((try? FileManager.default.contentsOfDirectory(atPath: u.path)) ?? []).sorted()
    }

    public func delete(_ path: String) {
        guard let u = url(path) else { return }
        try? FileManager.default.removeItem(at: u)
    }
}

/// Folder [sub] (say "People/Jess") inside [base], as a folder of its own.
public struct SubFolder: LinkFolder {
    let base: LinkFolder
    let prefix: String

    public init(_ base: LinkFolder, _ sub: String) throws {
        self.base = base
        self.prefix = try LinkPath.parts(sub).joined(separator: "/")
    }

    public func read(_ path: String) -> Data? { base.read("\(prefix)/\(path)") }
    public func write(_ path: String, _ bytes: Data) throws { try base.write("\(prefix)/\(path)", bytes) }
    public func names(_ dir: String) -> [String] { base.names(dir.isEmpty ? prefix : "\(prefix)/\(dir)") }
    public func delete(_ path: String) { base.delete("\(prefix)/\(path)") }
}
