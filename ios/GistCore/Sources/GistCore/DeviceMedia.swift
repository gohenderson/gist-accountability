import CryptoKit
import Foundation

/**
 Background photos, videos and voice clips kept in step on your devices, as on Android
 (DeviceMedia.kt). Each check-in's media is an item "m:<checkinId>": its files by SHA-256 and
 when that set last changed (the later change wins; empty means removed). The files go in the
 folder once each, encrypted with the devices' key and named by hash and key generation
 (from-<side>/m-<first 32 hex>[-<epoch>].gist). A set whose files haven't arrived yet waits.
 Media lives in [root]/<checkinId>/ (photo-0.jpg…, video.mp4, voice.m4a), as on Android.
 */
public final class DeviceMedia {
    public struct MediaSet: Equatable {
        public var files: [String: String]
        public var at: Int
    }

    let root: URL
    let prefs: SyncPrefs
    let epoch: Int

    public init(root: URL, prefs: SyncPrefs, epoch: Int = 0) {
        self.root = root
        self.prefs = prefs
        self.epoch = epoch
    }

    static let kMedia = "media", kWant = "mediaWanted", kHashes = "mediaHashes"
    /// Its keys in the devices' state (cleared when syncing starts afresh).
    public static let keys = [kMedia, kWant, kHashes]

    static let name = try! NSRegularExpression(pattern: "^(photo-\\d{1,3}\\.jpg|video\\.mp4|voice\\.m4a)$")
    static let hash = try! NSRegularExpression(pattern: "^[0-9a-f]{64}$")
    static let blob = try! NSRegularExpression(pattern: "^m-[0-9a-f]{32}(-\\d{1,9})?\\.gist$")
    static let id = try! NSRegularExpression(pattern: "^[A-Za-z0-9_-]{1,64}$")

    static func matches(_ r: NSRegularExpression, _ s: String) -> Bool {
        r.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    public static func validId(_ s: String) -> Bool { matches(id, s) }

    public static func blobName(_ hash: String, epoch: Int = 0) -> String {
        "m-\(hash.prefix(32))\(epoch > 0 ? "-\(epoch)" : "").gist"
    }

    public static func sha256(_ d: Data) -> String { SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined() }

    static func json(_ s: MediaSet) -> JSONObject {
        JSONObject([("files", .object(JSONObject(s.files.keys.sorted().map { ($0, .string(s.files[$0]!)) }))), ("at", .int(s.at))])
    }

    /// A media set from JSON, keeping only valid names and hashes; nil if it isn't one.
    static func parse(_ o: JSONObject?) -> MediaSet? {
        guard let o, o.has("at"), let f = o.object("files") else { return nil }
        var files: [String: String] = [:]
        for (n, v) in f.pairs {
            if let h = v.stringValue, matches(name, n), matches(hash, h) { files[n] = h }
        }
        return MediaSet(files: files, at: o.int("at", 0))
    }

    func load(_ key: String) -> [String: MediaSet] {
        guard let o = prefs.string(key).flatMap({ (try? JSONText.parse($0))?.objectValue }) else { return [:] }
        var m: [String: MediaSet] = [:]
        for (id, v) in o.pairs { if let s = DeviceMedia.parse(v.objectValue) { m[id] = s } }
        return m
    }

    func save(_ key: String, _ m: [String: MediaSet]) {
        prefs.set(key, JSONText.string(JSONObject(m.keys.sorted().map { ($0, .object(DeviceMedia.json(m[$0]!))) })))
    }

    /// What this device has (as last seen or taken in), by check-in.
    public func known() -> [String: MediaSet] { load(Self.kMedia) }
    /// Sets from other devices still waiting for their files.
    public func waiting() -> [String: MediaSet] { load(Self.kWant) }

    /// The items to publish: "m:<id>" for each check-in's media.
    public func items() -> [String: String] {
        var out: [String: String] = [:]
        for (id, s) in known() { out["m:\(id)"] = JSONText.string(DeviceMedia.json(s)) }
        return out
    }

    /// Notes the media sets in another device's [items] that are newer than what's here.
    public func want(_ items: [String: String]) {
        let known = known()
        var want = waiting()
        for (k, text) in items where k.hasPrefix("m:") {
            let id = String(k.dropFirst(2))
            guard DeviceMedia.validId(id), let s = DeviceMedia.parse((try? JSONText.parse(text))?.objectValue) else { continue }
            if s.at > max(known[id]?.at ?? 0, want[id]?.at ?? 0) { want[id] = s }
        }
        save(Self.kWant, want)
    }

    /// Writes the waiting sets whose files are all in the folder (or here). True when any media changed.
    public func takeWaiting(_ folder: LinkFolder, group: String, key: Data) -> Bool {
        var want = waiting()
        guard !want.isEmpty else { return false }
        var known = known()
        var changed = false
        let blobs = blobsBySide(folder)
        for (id, s) in want {
            if s.at <= (known[id]?.at ?? 0) { want[id] = nil; continue }
            let have = current(id)
            var got: [String: Data] = [:]
            var missing = false
            for (name, hash) in s.files where have[name] != hash {
                guard let bytes = fetch(folder, blobs, group: group, key: key, hash: hash) else { missing = true; break }
                got[name] = bytes
            }
            if missing { continue }
            let dir = root.appendingPathComponent(id, isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            for (name, bytes) in got { try? bytes.write(to: dir.appendingPathComponent(name), options: .atomic) }
            for name in have.keys where s.files[name] == nil { try? FileManager.default.removeItem(at: dir.appendingPathComponent(name)) }
            known[id] = s
            want[id] = nil
            changed = true
        }
        save(Self.kMedia, known)
        save(Self.kWant, want)
        return changed
    }

    /// Looks at the media of [ids] and stamps any that changed (or is new) with [now]; forgets the rest.
    public func refresh(_ ids: [String], now: Int = GistData.nowMs) {
        let known = known()
        var next: [String: MediaSet] = [:]
        for id in ids where DeviceMedia.validId(id) {
            let cur = current(id)
            if let k = known[id] {
                next[id] = k.files == cur ? k : MediaSet(files: cur, at: now)
            } else if !cur.isEmpty {
                next[id] = MediaSet(files: cur, at: now)
            }
        }
        save(Self.kMedia, next)
    }

    /// Puts into [side]'s folder any file a set here uses that no device has put there yet.
    public func upload(_ folder: LinkFolder, side: String, group: String, key: Data) {
        var there = Set(blobsBySide(folder).values.flatMap { $0 })
        for (id, s) in known() {
            for (name, hash) in s.files {
                let b = DeviceMedia.blobName(hash, epoch: epoch)
                if there.contains(b) { continue }
                guard let bytes = try? Data(contentsOf: root.appendingPathComponent(id).appendingPathComponent(name)),
                      DeviceMedia.sha256(bytes) == hash else { continue }
                try? folder.write("\(LinkProtocol.dir(side))/\(b)", LinkCrypto.seal(key: key, label: "\(group)/\(b)", plain: bytes))
                there.insert(b)
            }
        }
    }

    /// Removes this device's files nothing uses, once every reader has read up to [seq].
    public func cleanUp(_ folder: LinkFolder, side: String, readers: [String], seq: Int) {
        if readers.contains(where: { LinkProtocol.readUpTo(folder, reader: $0, side: side) < seq }) { return }
        let used = Set((Array(known().values) + Array(waiting().values)).flatMap { $0.files.values }
            .map { DeviceMedia.blobName($0, epoch: epoch) })
        for n in folder.names(LinkProtocol.dir(side)) where DeviceMedia.matches(DeviceMedia.blob, n) && !used.contains(n) {
            folder.delete("\(LinkProtocol.dir(side))/\(n)")
        }
    }

    func blobsBySide(_ folder: LinkFolder) -> [String: [String]] {
        var out: [String: [String]] = [:]
        for d in folder.names() where d.hasPrefix("from-") && LinkProtocol.validSide(String(d.dropFirst(5))) {
            out[d] = folder.names(d).filter { DeviceMedia.matches(DeviceMedia.blob, $0) }
        }
        return out
    }

    func fetch(_ folder: LinkFolder, _ blobs: [String: [String]], group: String, key: Data, hash: String) -> Data? {
        let b = DeviceMedia.blobName(hash, epoch: epoch)
        for (dir, names) in blobs where names.contains(b) {
            if let sealed = folder.read("\(dir)/\(b)"), let bytes = LinkCrypto.open(key: key, label: "\(group)/\(b)", sealed: sealed),
               DeviceMedia.sha256(bytes) == hash { return bytes }
        }
        return nil
    }

    /// The media files check-in [id] has here now: name → hash (cached by size and time).
    func current(_ id: String) -> [String: String] {
        let dir = root.appendingPathComponent(id, isDirectory: true)
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else { return [:] }
        var cache = hashes()
        var out: [String: String] = [:]
        for n in names where DeviceMedia.matches(DeviceMedia.name, n) {
            let u = dir.appendingPathComponent(n)
            guard let attrs = try? FileManager.default.attributesOfItem(atPath: u.path),
                  let size = attrs[.size] as? Int, size > 0 else { continue }
            let mtime = Int(((attrs[.modificationDate] as? Date) ?? Date.distantPast).timeIntervalSince1970 * 1000)
            let stamp = "\(size):\(mtime):"
            if let c = cache[u.path], c.hasPrefix(stamp) {
                out[n] = String(c.dropFirst(stamp.count))
            } else if let bytes = try? Data(contentsOf: u) {
                let h = DeviceMedia.sha256(bytes)
                cache[u.path] = stamp + h
                out[n] = h
            }
        }
        prefs.set(Self.kHashes, JSONText.string(JSONObject(cache.keys.sorted().map { ($0, .string(cache[$0]!)) })))
        return out
    }

    func hashes() -> [String: String] {
        guard let o = prefs.string(Self.kHashes).flatMap({ (try? JSONText.parse($0))?.objectValue }) else { return [:] }
        var m: [String: String] = [:]
        for (k, v) in o.pairs { if let s = v.stringValue { m[k] = s } }
        return m
    }
}
