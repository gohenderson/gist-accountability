import Foundation

/// Settings kept in step on your devices (the app keeps them): by Android's keys and value
/// formats, so a phone of each kind agrees.
public protocol SyncedSettings: AnyObject {
    /// The shared settings changed since syncing: key → (value as JSON text, when).
    func synced() -> [String: (json: String, at: Int)]
    /// Takes [key] from another device if it changed there after here. True when it did.
    func take(_ key: String, json: String, at: Int) -> Bool
    /// Starting to sync (as the first device): what's set counts as changed [now].
    func stampAll(now: Int)
}

/// What a sync did, for the app to act on.
public struct DevicesSyncResult: Equatable {
    /// Anything here changed (check-ins, answers, settings, media, connections).
    public var changed = false
    /// Check-ins deleted on another device (their alarms and media go).
    public var deleted: [String] = []
    /// Times answered on another device: stop waiting (and ringing) for them here.
    public var closed: [Pending] = []
    public init() {}
}

/**
 Your own devices (say an iPhone and an Android tablet): all your check-ins, tasks and answers
 kept in step through a folder they share, exactly as on Android (Devices.kt), so devices of
 either kind can be in one group.

 Each device publishes everything it has as items: check-ins "c:<id>", deletions "d:<id>",
 each day's answers "r:<day>", the order "o:list", shared settings "s:<key>", media "m:<id>"
 (see [DeviceMedia]) and connections "l:<id>" (see [Connections.deviceItems]). The later edit
 of a check-in wins; an answer beats "rang", then the later answer wins; the later reorder and
 the later change of each setting win. One key for the group: the first device makes it, a
 joining device gets it from the device whose invite it used; removing a device makes a new
 key (one generation on) for the rest.
 */
public final class Devices {
    public static let format = "gist-devices"
    public static let marker = "gist-devices.json"
    static let order = "o:list"

    let prefs: SyncPrefs
    let openFolder: (String) throws -> LinkFolder
    let connections: Connections
    let mediaRoot: URL
    public weak var settings: SyncedSettings?
    let defaultName: String

    public init(prefs: SyncPrefs, openFolder: @escaping (String) throws -> LinkFolder, connections: Connections,
                mediaRoot: URL, defaultName: String = "This device") {
        self.prefs = prefs
        self.openFolder = openFolder
        self.connections = connections
        self.mediaRoot = mediaRoot
        self.defaultName = defaultName
    }

    static let kFolder = "folder", kSide = "side", kGroup = "group", kPub = "pub", kPriv = "priv", kKey = "key",
               kName = "name", kSentSeq = "sentSeq", kSent = "sent", kGot = "got", kInvToken = "inviteToken",
               kInvExpires = "inviteExpires", kInviter = "inviter", kEpoch = "epoch", kKeyFrom = "keyFrom",
               kRemoved = "removed", kRepublish = "republish", kKeyRemoved = "keyRemoved"

    func int(_ k: String) -> Int { prefs.string(k).flatMap { Int($0) } ?? 0 }
    func setInt(_ k: String, _ v: Int) { prefs.set(k, String(v)) }
    func strings(_ k: String) -> Set<String> {
        guard let s = prefs.string(k), case .array(let a)? = try? JSONText.parse(s) else { return [] }
        return Set(a.compactMap { $0.stringValue })
    }
    func setStrings(_ k: String, _ v: Set<String>) { prefs.set(k, JSONText.string(.array(v.sorted().map { .string($0) }))) }

    /// The folder, or nil when this device isn't syncing.
    public var folder: String? { prefs.string(Self.kFolder) }
    public var side: String {
        if let s = prefs.string(Self.kSide) { return s }
        let s = Ids.new()
        prefs.set(Self.kSide, s)
        return s
    }
    var groupId: String? { prefs.string(Self.kGroup) }
    var publicKey: String? { prefs.string(Self.kPub) }
    var privateKey: String? { prefs.string(Self.kPriv) }
    var groupKey: Data? { prefs.string(Self.kKey).flatMap { Data(base64Encoded: $0) } }
    var epoch: Int { int(Self.kEpoch) }
    var removed: Set<String> { strings(Self.kRemoved) }

    /// Set up, and has the shared key (false while waiting to be let in).
    public var ready: Bool { folder != nil && groupKey != nil }

    /// This device's name, as your other devices list it.
    public var name: String {
        get { prefs.string(Self.kName).flatMap { $0.isEmpty ? nil : $0 } ?? defaultName }
        set { prefs.set(Self.kName, newValue.trimmingCharacters(in: .whitespaces)) }
    }

    /// The group id in a devices folder, or nil.
    public static func groupOf(_ f: LinkFolder) -> String? {
        guard let o = JSONText.object(f.read(marker)), o.string("format") == format else { return nil }
        let g = o.string("group")
        return g.isEmpty ? nil : g
    }

    func members(_ f: LinkFolder) -> [String] {
        let gone = removed
        return LinkProtocol.sides(f, mySide: side).filter { !gone.contains($0) && LinkProtocol.hello(f, $0) != nil }
    }

    /// The other devices that are in: (side, name, code to compare).
    public func others() -> [(side: String, name: String, code: String)] {
        guard let loc = folder, let f = try? openFolder(loc), let pub = publicKey else { return [] }
        return members(f).compactMap { s in LinkProtocol.hello(f, s).map { (s, $0.name, LinkCrypto.code(pub, $0.key)) } }
    }

    static func newKey() -> Data {
        var d = Data(count: 32)
        for i in 0..<32 { d[i] = UInt8.random(in: 0...255) }
        return d
    }

    /// Starts syncing in [folder] (empty): this device makes the key; others join.
    public func setUp(folder loc: String, data: inout GistData, now: Int = GistData.nowMs) throws {
        let f = try openFolder(loc)
        let id = Ids.new() + Ids.new()
        let (pub, priv) = LinkCrypto.newKeyPair()
        try f.write(Self.marker, JSONText.data(JSONObject([("format", .string(Self.format)), ("version", .int(1)), ("group", .string(id))])))
        start(loc, id, pub, priv, Self.newKey(), data: &data)
        // The devices that join take this one's order and settings.
        data.orderAt = now
        settings?.stampAll(now: now)
        try LinkProtocol.writeHello(f, LinkHello(side: side, name: name, key: pub))
    }

    /// An invite for another device to join with (this one has to be ready). Used once, within 15 minutes.
    public func invitation(now: Int = GistData.nowMs) -> (invite: LinkInvite, expires: Int)? {
        guard let pub = publicKey, ready else { return nil }
        var token = prefs.string(Self.kInvToken)
        var expires = int(Self.kInvExpires)
        if token == nil || expires < now {
            token = LinkInvites.newToken().base64EncodedString()
            expires = now + LinkInvites.validMs
            prefs.set(Self.kInvToken, token)
            setInt(Self.kInvExpires, expires)
        }
        guard let t = token.flatMap({ Data(base64Encoded: $0) }) else { return nil }
        return (LinkInvite(kind: "d", fingerprint: LinkInvites.fingerprint(pub), token: t), expires)
    }

    public enum JoinProblem: Error { case notDevices, notTheirInvite }

    /// Joins your devices in [folder] with [invite]; ready once that device lets this one in.
    public func join(folder loc: String, invite: LinkInvite, data: inout GistData, now: Int = GistData.nowMs) throws {
        let f = try openFolder(loc)
        guard let id = Self.groupOf(f) else { throw JoinProblem.notDevices }
        guard let inviter = LinkProtocol.sides(f, mySide: side).compactMap({ LinkProtocol.hello(f, $0) })
            .first(where: { LinkInvites.fingerprint($0.key) == invite.fingerprint }) else { throw JoinProblem.notTheirInvite }
        let (pub, priv) = LinkCrypto.newKeyPair()
        start(loc, id, pub, priv, nil, data: &data)
        prefs.set(Self.kInviter, inviter.side)
        try LinkProtocol.writeHello(f, LinkHello(side: side, name: name, key: pub,
            proof: LinkInvites.proof(token: invite.token, scope: id, side: side, publicKey: pub, joinedAt: now), joinedAt: now))
    }

    func start(_ loc: String, _ id: String, _ pub: String, _ priv: String, _ key: Data?, data: inout GistData) {
        prefs.set(Self.kFolder, loc)
        prefs.set(Self.kGroup, id)
        prefs.set(Self.kPub, pub)
        prefs.set(Self.kPriv, priv)
        prefs.set(Self.kKey, key?.base64EncodedString())
        setInt(Self.kSentSeq, 0)
        for k in [Self.kSent, Self.kGot] + DeviceMedia.keys { prefs.set(k, nil) }
        data.tracking = true
    }

    static let allKeys = [kFolder, kSide, kGroup, kPub, kPriv, kKey, kName, kSentSeq, kSent, kGot, kInvToken, kInvExpires,
                          kInviter, kEpoch, kKeyFrom, kRemoved, kRepublish, kKeyRemoved] + DeviceMedia.keys

    /// Stops syncing here: your data stays, and this device's files leave the folder.
    public func leave(data: inout GistData) {
        if let loc = folder, let f = try? openFolder(loc) { removeFiles(f, side) }
        for k in Self.allKeys { prefs.set(k, nil) }
        data.tracking = false
    }

    func removeFiles(_ f: LinkFolder, _ s: String) {
        let dir = LinkProtocol.dir(s)
        for n in f.names(dir) { f.delete("\(dir)/\(n)") }
        f.delete(dir)
    }

    /// Removes another device (say a lost one): your devices switch to a new key it doesn't have.
    public func remove(_ other: String) throws {
        guard let loc = folder, ready, other != side else { return }
        try rotate(try openFolder(loc), removed.union([other]))
    }

    func rotate(_ f: LinkFolder, _ gone: Set<String>) throws {
        guard let id = groupId, let priv = privateKey else { return }
        let next = epoch + 1
        let key = Self.newKey()
        setStrings(Self.kRemoved, gone)
        for m in members(f) {
            guard let h = LinkProtocol.hello(f, m), let pair = LinkCrypto.sharedKey(myPrivate: priv, theirPublic: h.key, linkId: id)
            else { continue }
            let name = "\(LinkProtocol.dir(side))/rekey-\(m).gist"
            let body = JSONObject([("epoch", .int(next)), ("key", .string(key.base64EncodedString())),
                                   ("removed", .array(gone.sorted().map { .string($0) }))])
            try f.write(name, LinkCrypto.seal(key: pair, label: "\(id)/\(name)", plain: JSONText.data(body)))
        }
        for g in gone { removeFiles(f, g) }
        forgetKeys(f, for: gone)
        useKey(key, gen: next, from: side, leavesOut: gone)
    }

    func useKey(_ key: Data, gen: Int, from: String, leavesOut: Set<String>) {
        prefs.set(Self.kKey, key.base64EncodedString())
        setInt(Self.kEpoch, gen)
        prefs.set(Self.kKeyFrom, from)
        setStrings(Self.kKeyRemoved, leavesOut)
        prefs.set(Self.kRepublish, "1")
    }

    func forgetKeys(_ f: LinkFolder, for gone: Set<String>) {
        let dir = LinkProtocol.dir(side)
        for x in gone {
            f.delete("\(dir)/key-\(x).gist")
            f.delete("\(dir)/rekey-\(x).gist")
        }
    }

    /// A newer key another device sealed for this one (after it removed a device). Of two made
    /// at once, the one from the lower side wins; if it still includes a removed device, another.
    func takeNewKey(_ f: LinkFolder, id: String, priv: String) throws {
        var best: (gen: Int, from: String, key: Data)?
        var bestRemoved: Set<String> = []
        var gone = removed
        for s in members(f) {
            let name = "\(LinkProtocol.dir(s))/rekey-\(side).gist"
            guard let sealed = f.read(name), let h = LinkProtocol.hello(f, s),
                  let pair = LinkCrypto.sharedKey(myPrivate: priv, theirPublic: h.key, linkId: id),
                  let plain = LinkCrypto.open(key: pair, label: "\(id)/\(name)", sealed: sealed),
                  let o = JSONText.object(plain), let key = Data(base64Encoded: o.string("key")) else { continue }
            let gen = o.int("epoch", 0)
            let rm = Set((o.array("removed") ?? []).compactMap { $0.stringValue })
            if rm.contains(side) { continue }
            gone.formUnion(rm)
            if best == nil || gen > best!.gen || (gen == best!.gen && s < best!.from) {
                best = (gen, s, key)
                bestRemoved = rm
            }
        }
        let curFrom = prefs.string(Self.kKeyFrom) ?? ""
        setStrings(Self.kRemoved, gone)
        forgetKeys(f, for: gone)
        if let b = best, b.gen > epoch || (b.gen == epoch && !curFrom.isEmpty && b.from < curFrom) {
            useKey(b.key, gen: b.gen, from: b.from, leavesOut: bestRemoved)
        }
        // The key in use was made before one of the removals: make another without them.
        if !strings(Self.kKeyRemoved).isSuperset(of: gone) { try rotate(f, gone) }
    }

    /// The key in a let-in file: just the key (older devices), or JSON with its generation.
    public static func keyOf(_ bytes: Data) -> Data? {
        if bytes.count == 32 { return bytes }
        return JSONText.object(bytes).flatMap { Data(base64Encoded: $0.string("key")) }
    }

    // MARK: Syncing

    /// Reads the other devices, takes in what's newer, lets in a device that joined with this
    /// one's invite, and publishes everything here. Brings [data] up to date.
    public func sync(_ data: inout GistData) throws -> DevicesSyncResult {
        var result = DevicesSyncResult()
        guard let loc = folder, let id = groupId, let pub = publicKey, let priv = privateKey else { return result }
        let f = try openFolder(loc)
        try LinkProtocol.rename(f, side: side, name: name, key: pub)
        // Not let in yet: look for the key, sealed for this device by the one whose invite it used.
        if groupKey == nil {
            guard let inviter = prefs.string(Self.kInviter), let h = LinkProtocol.hello(f, inviter),
                  let pair = LinkCrypto.sharedKey(myPrivate: priv, theirPublic: h.key, linkId: id) else { return result }
            let name = "\(LinkProtocol.dir(inviter))/key-\(side).gist"
            guard let sealed = f.read(name), let bytes = LinkCrypto.open(key: pair, label: "\(id)/\(name)", sealed: sealed),
                  let k = Self.keyOf(bytes) else { return result }
            let o = bytes.count == 32 ? nil : JSONText.object(bytes)
            let rm = Set((o?.array("removed") ?? []).compactMap { $0.stringValue })
            prefs.set(Self.kKey, k.base64EncodedString())
            setInt(Self.kEpoch, o?.int("epoch", 0) ?? 0)
            prefs.set(Self.kKeyFrom, inviter)
            setStrings(Self.kRemoved, rm)
            setStrings(Self.kKeyRemoved, rm)
        }
        // A device removed elsewhere: a newer key.
        try takeNewKey(f, id: id, priv: priv)
        guard let key = groupKey else { return result }
        let others = members(f)
        // Let in the device that joined with this one's invite (proving it had the token), once.
        if let token = prefs.string(Self.kInvToken).flatMap({ Data(base64Encoded: $0) }) {
            for s in others {
                let name = "\(LinkProtocol.dir(side))/key-\(s).gist"
                if f.read(name) != nil { continue }
                guard let h = LinkProtocol.hello(f, s), LinkInvites.proves(h, token: token, scope: id, expires: int(Self.kInvExpires)),
                      let pair = LinkCrypto.sharedKey(myPrivate: priv, theirPublic: h.key, linkId: id) else { continue }
                let body = JSONObject([("key", .string(key.base64EncodedString())), ("epoch", .int(epoch)),
                                       ("removed", .array(removed.sorted().map { .string($0) }))])
                try f.write(name, LinkCrypto.seal(key: pair, label: "\(id)/\(name)", plain: JSONText.data(body)))
                prefs.set(Self.kInvToken, nil)
                prefs.set(Self.kInvExpires, nil)
                break
            }
        }
        // Take in theirs.
        let media = DeviceMedia(root: mediaRoot, prefs: prefs, epoch: epoch)
        var got = gotSeqs()
        for s in others {
            let (seq, items) = LinkProtocol.receive(f, linkId: id, side: s, key: key, seq: got[s] ?? 0, have: [:])
            if seq == (got[s] ?? 0) { continue }
            merge(items, &data, &result)
            media.want(items)
            got[s] = seq
        }
        prefs.set(Self.kGot, JSONText.string(JSONObject(got.keys.sorted().map { ($0, .int(got[$0]!)) })))
        // Their photos, videos and clips (once the files are there); then put up any of ours.
        if media.takeWaiting(f, group: id, key: key) { result.changed = true }
        media.refresh(data.checkins.map { $0.id })
        media.upload(f, side: side, group: id, key: key)
        // Publish everything here (only what changed since last time; all of it with a new key).
        let now = everything(data, media)
        let (set, rem) = LinkProtocol.diff(sentItems(), now)
        let sentSeq = int(Self.kSentSeq)
        let all = sentSeq == 0 || prefs.string(Self.kRepublish) != nil
        if !set.isEmpty || !rem.isEmpty || all {
            let seq = sentSeq + 1
            try LinkProtocol.publish(f, linkId: id, side: side, key: key, seq: seq, set: all ? now : set,
                                     removed: all ? [] : rem, all: now)
            setInt(Self.kSentSeq, seq)
            prefs.set(Self.kSent, JSONText.string(LinkProtocol.items(now)))
            prefs.set(Self.kRepublish, nil)
        }
        try LinkProtocol.writeReceipts(f, side: side, read: got)
        if others.isEmpty {
            // The only device: no one to wait for (a device that joins starts from the snapshot).
            for n in f.names(LinkProtocol.dir(side)) where LinkProtocol.isChangeFile(n) { f.delete("\(LinkProtocol.dir(side))/\(n)") }
        } else {
            LinkProtocol.cleanUp(f, side: side, readers: others)
        }
        media.cleanUp(f, side: side, readers: others, seq: int(Self.kSentSeq))
        forgetPickedUpKeys(f, id: id, key: key, others: others)
        return result
    }

    /// Removes the keys sealed for others once they have them (their snapshot opens with the key).
    func forgetPickedUpKeys(_ f: LinkFolder, id: String, key: Data, others: [String]) {
        let dir = LinkProtocol.dir(side)
        let mine = Set(f.names(dir))
        for s in others {
            let sealed = ["key-\(s).gist", "rekey-\(s).gist"].filter { mine.contains($0) }
            if sealed.isEmpty { continue }
            let snap = "\(LinkProtocol.dir(s))/snapshot.gist"
            if let b = f.read(snap), LinkCrypto.open(key: key, label: "\(id)/\(snap)", sealed: b) != nil {
                for n in sealed { f.delete("\(dir)/\(n)") }
            }
        }
    }

    func gotSeqs() -> [String: Int] {
        guard let o = prefs.string(Self.kGot).flatMap({ (try? JSONText.parse($0))?.objectValue }) else { return [:] }
        var m: [String: Int] = [:]
        for (k, v) in o.pairs { if let n = v.intValue { m[k] = n } }
        return m
    }

    func sentItems() -> [String: String] {
        LinkProtocol.itemsOf(prefs.string(Self.kSent).flatMap { (try? JSONText.parse($0))?.objectValue })
    }

    /// Everything this device has, as items.
    func everything(_ data: GistData, _ media: DeviceMedia) -> [String: String] {
        var out: [String: String] = [:]
        for c in data.checkins { out["c:\(c.id)"] = JSONText.string(c.json) }
        for (id, at) in data.deleted { out["d:\(id)"] = JSONText.string(JSONObject([("at", .int(at))])) }
        for (day, d) in GistData.entriesJSON(data.results).pairs { out["r:\(day)"] = JSONText.string(d) }
        if data.orderAt > 0 {
            out[Self.order] = JSONText.string(JSONObject([("ids", .array(data.checkins.map { .string($0.id) })), ("at", .int(data.orderAt))]))
        }
        for (k, v) in settings?.synced() ?? [:] {
            if let j = try? JSONText.parse(v.json) {
                out["s:\(k)"] = JSONText.string(JSONObject([("v", .string(JSONText.string(j))), ("at", .int(v.at))]))
            }
        }
        for (k, v) in media.items() { out[k] = v }
        for (k, v) in connections.deviceItems(side: side, deviceName: name) { out[k] = v }
        return out
    }

    /// Takes in another device's [items].
    func merge(_ items: [String: String], _ data: inout GistData, _ result: inout DevicesSyncResult) {
        let before = data.checkins
        var list = before
        var deleted = data.deleted
        // Deletions: unless it's been changed since.
        for (k, json) in items where k.hasPrefix("d:") {
            let id = String(k.dropFirst(2))
            guard let at = JSONText.object(Data(json.utf8))?.int("at") else { continue }
            if (deleted[id] ?? 0) < at { deleted[id] = at }
            list.removeAll { $0.id == id && ($0.updatedAt ?? 0) <= at }
        }
        // Check-ins: the later edit wins; new ones go at the end.
        for (k, json) in items.sorted(by: { $0.key < $1.key }) where k.hasPrefix("c:") {
            guard let o = JSONText.object(Data(json.utf8)), let c = try? Checkin.from(o) else { continue }
            if (deleted[c.id] ?? -1) >= (c.updatedAt ?? 0) { continue }
            if let i = list.firstIndex(where: { $0.id == c.id }) {
                if (c.updatedAt ?? 0) > (list[i].updatedAt ?? 0) { list[i] = c }
            } else {
                list.append(c)
            }
        }
        // Order: the later reorder wins (any not in it keep their order, after).
        let order = items[Self.order].flatMap { JSONText.object(Data($0.utf8)) }
        let orderAt = order?.int("at", 0) ?? 0
        let takeOrder = orderAt > data.orderAt
        if takeOrder {
            let ids = (order?.array("ids") ?? []).compactMap { $0.stringValue }
            var pos: [String: Int] = [:]
            for (i, x) in ids.enumerated() where pos[x] == nil { pos[x] = i }
            list = list.filter { pos[$0.id] != nil }.sorted { pos[$0.id]! < pos[$1.id]! } + list.filter { pos[$0.id] == nil }
        }
        // Connections kept on your other devices (or taken over from this one).
        if connections.takeDeviceItems(items, side: side) { result.changed = true }
        // Settings: the later change of each wins.
        for (k, json) in items where k.hasPrefix("s:") {
            guard let o = JSONText.object(Data(json.utf8)) else { continue }
            if settings?.take(String(k.dropFirst(2)), json: o.string("v"), at: o.int("at", 0)) == true { result.changed = true }
        }
        data.deleted = deleted
        if list != before {
            data.checkins = list
            result.changed = true
            let ids = Set(list.map { $0.id })
            result.deleted += before.map { $0.id }.filter { !ids.contains($0) }
            for id in result.deleted { try? FileManager.default.removeItem(at: mediaRoot.appendingPathComponent(id)) }
        }
        if takeOrder { data.orderAt = orderAt } // theirs, not the time it was taken in here
        // Answers: an answer beats "rang"; between answers, the later wins.
        for (k, json) in items where k.hasPrefix("r:") {
            let day = String(k.dropFirst(2))
            guard let theirs = JSONText.object(Data(json.utf8)) else { continue }
            for (cid, sv) in theirs.pairs {
                guard let slots = sv.objectValue else { continue }
                for (time, tv) in slots.pairs {
                    guard let t = tv.objectValue else { continue }
                    let mine = data.results[day]?[cid]?[time]
                    let theirsR = SlotResult.from(t)
                    if mine == nil || Self.wins(theirsR, mine!) {
                        data.results[day, default: [:]][cid, default: [:]][time] = theirsR
                        result.changed = true
                        if theirsR.status != SlotResult.rang { result.closed.append(Pending(checkinId: cid, day: day, time: time)) }
                    }
                }
            }
        }
    }

    /// Does [theirs] beat [mine] for the same time? An answer beats "rang"; then the later.
    static func wins(_ theirs: SlotResult, _ mine: SlotResult) -> Bool {
        let tRang = theirs.status == SlotResult.rang, mRang = mine.status == SlotResult.rang
        if tRang != mRang { return mRang }
        return (theirs.w ?? 0) > (mine.w ?? 0)
    }
}
