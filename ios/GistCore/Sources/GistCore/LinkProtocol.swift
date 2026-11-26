import Foundation

/*
 * A sync folder (a connection's, or your devices'), as the Android app writes it:
 *   gist-link.json            {"format": "gist-link", "version": 1, "link": "<id>"} (plain)
 *   from-<side>/hello.json    that side's name and public key (plain)
 *   from-<side>/000001.gist   numbered changes: items set or changed, and ids removed
 *   from-<side>/snapshot.gist everything that side shares now, with the last change's number
 *   from-<side>/read.json     how far that side has read each other side: {"<side>": n} (plain)
 *
 * Only a file's writer changes or removes it. Items are JSON objects, kept as text by id.
 */
public enum LinkProtocol {
    public static let version = 1
    public static let marker = "gist-link.json"

    public static func dir(_ side: String) -> String { "from-\(side)" }
    static func changeName(_ seq: Int) -> String { String(format: "%06ld.gist", seq) }
    static let changeFile = try! NSRegularExpression(pattern: "^\\d{6}\\.gist$")

    public static func isChangeFile(_ name: String) -> Bool {
        changeFile.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)) != nil
    }

    /// Starts a connection in an empty folder: the marker and this side's hello.
    public static func invite(_ folder: LinkFolder, linkId: String, hello: LinkHello) throws {
        let m = JSONObject([("format", .string("gist-link")), ("version", .int(version)), ("link", .string(linkId))])
        try folder.write(marker, JSONText.data(m))
        try writeHello(folder, hello)
    }

    /// The connection id in [folder], or nil when it isn't a Gist connection folder.
    public static func linkId(_ folder: LinkFolder) -> String? {
        guard let o = JSONText.object(folder.read(marker)), o.string("format") == "gist-link" else { return nil }
        let id = o.string("link")
        return id.isEmpty ? nil : id
    }

    public static func writeHello(_ folder: LinkFolder, _ h: LinkHello) throws {
        var o = JSONObject([("side", .string(h.side)), ("name", .string(h.name)), ("key", .string(h.key))])
        if let p = h.proof { o["proof"] = .string(p) }
        if let at = h.joinedAt { o["joinedAt"] = .int(at) }
        try folder.write("\(dir(h.side))/hello.json", JSONText.data(o))
    }

    /// Rewrites [side]'s hello with a new [name], keeping its key and proof.
    public static func rename(_ folder: LinkFolder, side: String, name: String, key: String) throws {
        let h = hello(folder, side)
        if h?.name == name { return }
        var n = h ?? LinkHello(side: side, name: name, key: key)
        n.name = name
        n.key = key
        try writeHello(folder, n)
    }

    /// A side id: letters and digits only (it's part of file paths).
    public static func validSide(_ side: String) -> Bool {
        !side.isEmpty && side.count <= 32 && side.unicodeScalars.allSatisfy {
            ("a"..."z").contains($0) || ("A"..."Z").contains($0) || ("0"..."9").contains($0)
        }
    }

    /// [side]'s hello, if it's there (and says it's [side]).
    public static func hello(_ folder: LinkFolder, _ side: String) -> LinkHello? {
        guard validSide(side), let o = JSONText.object(folder.read("\(dir(side))/hello.json")),
              let s = o["side"]?.stringValue, s == side, let key = o["key"]?.stringValue else { return nil }
        let proof = o.string("proof")
        return LinkHello(side: s, name: String(o.string("name").prefix(60)), key: key,
                         proof: proof.isEmpty ? nil : proof, joinedAt: o.has("joinedAt") ? o.int("joinedAt") : nil)
    }

    /// The other sides in the folder (from their from-<side>/ folders).
    public static func sides(_ folder: LinkFolder, mySide: String) -> [String] {
        folder.names().filter { $0.hasPrefix("from-") }.map { String($0.dropFirst(5)) }
            .filter { validSide($0) && $0 != mySide }
    }

    /// Says how far [side] has read the others ([read]: other side → change number).
    public static func writeReceipts(_ folder: LinkFolder, side: String, read: [String: Int]) throws {
        var o = JSONObject()
        for k in read.keys.sorted() { o[k] = .int(read[k]!) }
        let name = "\(dir(side))/read.json"
        let bytes = JSONText.data(o)
        if folder.read(name) != bytes { try folder.write(name, bytes) }
    }

    /// How far [reader] has read [side]'s changes (0: not yet).
    public static func readUpTo(_ folder: LinkFolder, reader: String, side: String) -> Int {
        guard validSide(reader), let o = JSONText.object(folder.read("\(dir(reader))/read.json")) else { return 0 }
        return o.int(side, 0)
    }

    /// Removes [side]'s changes all of [readers] have read (the snapshot stays). With no readers, nothing goes.
    @discardableResult
    public static func cleanUp(_ folder: LinkFolder, side: String, readers: [String]) -> Int {
        guard !readers.isEmpty else { return 0 }
        let upTo = readers.map { readUpTo(folder, reader: $0, side: side) }.min() ?? 0
        guard upTo > 0 else { return 0 }
        let old = folder.names(dir(side)).filter { isChangeFile($0) && (Int($0.prefix(6)) ?? Int.max) <= upTo }
        old.forEach { folder.delete("\(dir(side))/\($0)") }
        return old.count
    }

    /// What changed from [before] to [now] (id → item JSON text): set (new or different) and removed.
    public static func diff(_ before: [String: String], _ now: [String: String]) -> (set: [String: String], removed: [String]) {
        (now.filter { before[$0.key] != $0.value }, before.keys.filter { now[$0] == nil }.sorted())
    }

    static func items(_ m: [String: String]) -> JSON {
        var o = JSONObject()
        for k in m.keys.sorted() {
            if let v = try? JSONText.parse(m[k]!) { o[k] = v }
        }
        return .object(o)
    }

    static func itemsOf(_ o: JSONObject?) -> [String: String] {
        guard let o else { return [:] }
        var m: [String: String] = [:]
        for (k, v) in o.pairs where v.objectValue != nil { m[k] = JSONText.string(v) }
        return m
    }

    /// Writes change [seq] and the snapshot of [all] after it.
    public static func publish(_ folder: LinkFolder, linkId: String, side: String, key: Data, seq: Int,
                               set: [String: String], removed: [String], all: [String: String]) throws {
        let change = JSONObject([("seq", .int(seq)), ("set", items(set)), ("removed", .array(removed.map { .string($0) }))])
        let name = "\(dir(side))/\(changeName(seq))"
        try folder.write(name, LinkCrypto.seal(key: key, label: "\(linkId)/\(name)", plain: JSONText.data(change)))
        let snap = JSONObject([("seq", .int(seq)), ("items", items(all))])
        let snapName = "\(dir(side))/snapshot.gist"
        try folder.write(snapName, LinkCrypto.seal(key: key, label: "\(linkId)/\(snapName)", plain: JSONText.data(snap)))
    }

    static func openJSON(_ folder: LinkFolder, linkId: String, key: Data, _ name: String) -> JSONObject? {
        guard let sealed = folder.read(name), let plain = LinkCrypto.open(key: key, label: "\(linkId)/\(name)", sealed: sealed)
        else { return nil }
        return JSONText.object(plain)
    }

    /// Catches up on [side]'s changes after [seq], from [have]: the new number and items. Starts
    /// from the snapshot when the next change isn't there. Files that don't open are left alone.
    public static func receive(_ folder: LinkFolder, linkId: String, side: String, key: Data, seq: Int,
                               have: [String: String]) -> (seq: Int, items: [String: String]) {
        guard validSide(side) else { return (seq, have) }
        var n = seq
        var items = have
        func next() -> JSONObject? { openJSON(folder, linkId: linkId, key: key, "\(dir(side))/\(changeName(n + 1))") }
        if next() == nil, let snap = openJSON(folder, linkId: linkId, key: key, "\(dir(side))/snapshot.gist"),
           snap.int("seq", 0) > n {
            n = snap.int("seq", 0)
            items = itemsOf(snap.object("items"))
        }
        while let change = next() {
            for (id, json) in itemsOf(change.object("set")) { items[id] = json }
            for r in change.array("removed") ?? [] { if let id = r.stringValue { items[id] = nil } }
            n += 1
        }
        return (n, items)
    }
}
