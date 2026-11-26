import Foundation

/// Where sync state is kept (UserDefaults in the app; memory in tests): text by key.
public protocol SyncPrefs: AnyObject {
    func string(_ key: String) -> String?
    func set(_ key: String, _ value: String?)
}

/// Sync state in memory (tests).
public final class MemoryPrefs: SyncPrefs {
    public var values: [String: String] = [:]
    public init() {}
    public func string(_ key: String) -> String? { values[key] }
    public func set(_ key: String, _ value: String?) { values[key] = value }
}

/// A connection with someone else's Gist, through a folder you both can reach. As on Android.
public struct Connection: Equatable {
    public var id: String
    /// Where its folder is ("" for one kept on another of your devices).
    public var folder: String
    /// Your side's id (from-<side>/ in the folder).
    public var side: String
    public var publicKey: String
    public var privateKey: String
    /// Their side, name and key, once they've joined.
    public var peer: String?
    public var peerName: String = ""
    public var peerKey: String?
    /// Their items on the start page (this device's choice).
    public var shown = true
    /// The last of your changes written, and what it held (item id → JSON text).
    public var sentSeq = 0
    public var sent: [String: String] = [:]
    /// The last of their changes read.
    public var gotSeq = 0
    /// An invite not yet used: its token (base64) and until when; only a joiner proving it is let in.
    public var inviteToken: String?
    public var inviteExpires = 0
    public var needsProof = false
    /// The device that's its home, when it isn't this one (nil: this one).
    public var home: String?
    public var homeName = ""
    public var homeAt = 0

    public init(id: String, folder: String, side: String, publicKey: String, privateKey: String) {
        self.id = id
        self.folder = folder
        self.side = side
        self.publicKey = publicKey
        self.privateKey = privateKey
    }

    public var connected: Bool { peer != nil && peerKey != nil }
    public var isHere: Bool { home == nil }
    /// The code both sides show (compare once).
    public var code: String? { peerKey.map { LinkCrypto.code(publicKey, $0) } }
    public func key() -> Data? { peerKey.flatMap { LinkCrypto.sharedKey(myPrivate: privateKey, theirPublic: $0, linkId: id) } }

    var json: JSONObject {
        var sentObj = JSONObject()
        for k in sent.keys.sorted() { if let v = try? JSONText.parse(sent[k]!) { sentObj[k] = v } }
        return JSONObject([
            ("id", .string(id)), ("folder", .string(folder)), ("side", .string(side)),
            ("publicKey", .string(publicKey)), ("privateKey", .string(privateKey)),
            ("peer", peer.map { .string($0) } ?? .null), ("peerName", .string(peerName)),
            ("peerKey", peerKey.map { .string($0) } ?? .null),
            ("shown", .bool(shown)), ("sentSeq", .int(sentSeq)), ("gotSeq", .int(gotSeq)),
            ("sent", .object(sentObj)),
            ("inviteToken", inviteToken.map { .string($0) } ?? .null), ("inviteExpires", .int(inviteExpires)),
            ("needsProof", .bool(needsProof)), ("homeAt", .int(homeAt)),
        ])
    }

    static func from(_ o: JSONObject) -> Connection? {
        guard let id = o["id"]?.stringValue, let side = o["side"]?.stringValue,
              let pub = o["publicKey"]?.stringValue, let priv = o["privateKey"]?.stringValue else { return nil }
        var l = Connection(id: id, folder: o.string("folder"), side: side, publicKey: pub, privateKey: priv)
        func opt(_ k: String) -> String? { o[k]?.stringValue.flatMap { $0.isEmpty ? nil : $0 } }
        l.peer = opt("peer")
        l.peerName = o.string("peerName")
        l.peerKey = opt("peerKey")
        l.shown = o.bool("shown", true)
        l.sentSeq = o.int("sentSeq", 0)
        l.gotSeq = o.int("gotSeq", 0)
        if let s = o.object("sent") { for (k, v) in s.pairs { l.sent[k] = JSONText.string(v) } }
        l.inviteToken = opt("inviteToken")
        l.inviteExpires = o.int("inviteExpires", 0)
        l.needsProof = o.bool("needsProof", false)
        l.homeAt = o.int("homeAt", 0)
        return l
    }
}

/**
 Connections: inviting and joining, publishing what you share with each (check-ins and tasks
 whose sharedWith includes it), and reading what each shares with you (read-only). As on
 Android (Connections.kt), with the same files, so either phone can be on either side.
 Check-ins are passed in and out ([GistData]); the app saves them and sets alarms.
 */
public final class Connections {
    /// Among the items a side publishes: the tasks of the other side's it has done (id → when).
    public static let doneKey = "~done"

    let prefs: SyncPrefs
    let openFolder: (String) throws -> LinkFolder

    public init(prefs: SyncPrefs, openFolder: @escaping (String) throws -> LinkFolder) {
        self.prefs = prefs
        self.openFolder = openFolder
    }

    static let kLinks = "links", kName = "myName", kGot = "got.", kRemote = "remote", kHidden = "hiddenRemote", kGone = "gone"

    /// Your name, as the other side sees it. With your devices in step, the one they share
    /// (see Devices) wins.
    public var sharedName: (() -> String)?
    public var myName: String {
        get {
            let shared = sharedName?() ?? ""
            return shared.isEmpty ? (prefs.string(Self.kName) ?? "") : shared
        }
        set { prefs.set(Self.kName, newValue.trimmingCharacters(in: .whitespaces)) }
    }

    /// The connections this device is home for (it reads and publishes for them).
    public var here: [Connection] {
        guard let s = prefs.string(Self.kLinks), case .array(let a)? = try? JSONText.parse(s) else { return [] }
        return a.compactMap { $0.objectValue.flatMap(Connection.from) }
    }

    /// Every connection: those kept here, then those kept on your other devices.
    public var all: [Connection] {
        let h = here
        return h + remote().filter { r in !h.contains { $0.id == r.id } }
    }

    public func get(_ id: String) -> Connection? { all.first { $0.id == id } }

    func save(_ list: [Connection]) {
        prefs.set(Self.kLinks, JSONText.string(.array(list.map { .object($0.json) })))
    }

    func update(_ id: String, _ change: (inout Connection) -> Void) {
        save(here.map { l in
            var l = l
            if l.id == id { change(&l) }
            return l
        })
    }

    public func setShown(_ id: String, _ shown: Bool) {
        if here.contains(where: { $0.id == id }) { return update(id) { $0.shown = shown } }
        var h = hiddenRemote()
        if shown { h.remove(id) } else { h.insert(id) }
        prefs.set(Self.kHidden, JSONText.string(.array(h.sorted().map { .string($0) })))
    }

    func hiddenRemote() -> Set<String> {
        guard let s = prefs.string(Self.kHidden), case .array(let a)? = try? JSONText.parse(s) else { return [] }
        return Set(a.compactMap { $0.stringValue })
    }

    static func tokenText() -> String { LinkInvites.newToken().base64EncodedString() }

    /// Starts a connection in [folder] (empty, or not yet a connection), with an invite to pass on.
    public func invite(folder: String, now: Int = GistData.nowMs) throws -> Connection {
        let f = try openFolder(folder)
        let (pub, priv) = LinkCrypto.newKeyPair()
        var l = Connection(id: Ids.new() + Ids.new(), folder: folder, side: Ids.new(), publicKey: pub, privateKey: priv)
        l.inviteToken = Self.tokenText()
        l.inviteExpires = now + LinkInvites.validMs
        l.needsProof = true
        l.homeAt = now
        try LinkProtocol.invite(f, linkId: l.id, hello: LinkHello(side: l.side, name: myName, key: pub))
        save(here + [l])
        return l
    }

    /// [l]'s invite to show (a new one if it ran out), or nil once someone's joined.
    public func invitation(_ l: Connection, now: Int = GistData.nowMs) -> (invite: LinkInvite, expires: Int)? {
        guard !l.connected, l.isHere else { return nil }
        var cur = get(l.id) ?? l
        if cur.inviteToken == nil || cur.inviteExpires < now {
            let token = Self.tokenText()
            update(l.id) {
                $0.inviteToken = token
                $0.inviteExpires = now + LinkInvites.validMs
                $0.needsProof = true
            }
            cur = get(l.id) ?? cur
        }
        guard let t = cur.inviteToken, let token = Data(base64Encoded: t) else { return nil }
        return (LinkInvite(kind: "c", fingerprint: LinkInvites.fingerprint(cur.publicKey), token: token), cur.inviteExpires)
    }

    /// Of the folders at [locations], the one waiting to be joined with [invite] (or nil, yet).
    public func findInvited(_ locations: [String], _ invite: LinkInvite) -> String? {
        let mine = Set(all.map { $0.id })
        return locations.first { loc in
            guard let f = try? openFolder(loc), let id = LinkProtocol.linkId(f), !mine.contains(id) else { return false }
            return LinkProtocol.sides(f, mySide: "").compactMap { LinkProtocol.hello(f, $0) }
                .contains { LinkInvites.fingerprint($0.key) == invite.fingerprint }
        }
    }

    public enum JoinProblem: Error { case notAConnection, alreadyYours, notTheirInvite }

    /// Joins the connection in [folder] with [invite]: only if the inviter's key there is the one it names.
    public func join(folder: String, invite: LinkInvite, now: Int = GistData.nowMs) throws -> Connection {
        let f = try openFolder(folder)
        guard let id = LinkProtocol.linkId(f) else { throw JoinProblem.notAConnection }
        guard !all.contains(where: { $0.id == id }) else { throw JoinProblem.alreadyYours }
        guard let inviter = LinkProtocol.sides(f, mySide: "").compactMap({ LinkProtocol.hello(f, $0) })
            .first(where: { LinkInvites.fingerprint($0.key) == invite.fingerprint }) else { throw JoinProblem.notTheirInvite }
        let (pub, priv) = LinkCrypto.newKeyPair()
        var l = Connection(id: id, folder: folder, side: Ids.new(), publicKey: pub, privateKey: priv)
        l.peer = inviter.side
        l.peerName = inviter.name
        l.peerKey = inviter.key
        l.homeAt = now
        try LinkProtocol.writeHello(f, LinkHello(side: l.side, name: myName, key: pub,
            proof: LinkInvites.proof(token: invite.token, scope: id, side: l.side, publicKey: pub, joinedAt: now), joinedAt: now))
        save(here + [l])
        return l
    }

    /// Forgets connection [id] (and what it shared) and stops sharing with it; only on its home.
    public func remove(_ id: String, data: inout GistData, now: Int = GistData.nowMs) {
        guard here.contains(where: { $0.id == id }) else { return }
        save(here.filter { $0.id != id })
        prefs.set(Self.kGot + id, nil)
        var g = gone()
        g[id] = now
        prefs.set(Self.kGone, JSONText.string(.object(JSONObject(g.keys.sorted().map { ($0, .int(g[$0]!)) }))))
        for c in data.checkins where c.sharedWith.contains(id) {
            data.update(c.id) { $0.sharedWith.remove(id) }
        }
    }

    // MARK: Syncing

    /// Reads and publishes every connection kept here; brings [data] up to date with what
    /// they share. True when what's shared with you (or your check-ins) changed.
    @discardableResult
    public func sync(_ data: inout GistData) -> Bool {
        var changed = false
        for l in here {
            if (try? syncOne(l, &data)) == true { changed = true }
        }
        return changed
    }

    func syncOne(_ start: Connection, _ data: inout GistData) throws -> Bool {
        let f = try openFolder(start.folder)
        var l = start
        if !l.connected {
            // Someone joined: with an invite, only the one that proves it had the token.
            let token = l.inviteToken.flatMap { Data(base64Encoded: $0) }
            let h = LinkProtocol.sides(f, mySide: l.side).compactMap { LinkProtocol.hello(f, $0) }.first { h in
                l.needsProof ? (token.map { LinkInvites.proves(h, token: $0, scope: l.id, expires: l.inviteExpires) } ?? false) : true
            }
            if let h {
                l.peer = h.side
                l.peerName = h.name
                l.peerKey = h.key
                l.inviteToken = nil
                update(l.id) {
                    $0.peer = h.side
                    $0.peerName = h.name
                    $0.peerKey = h.key
                    $0.inviteToken = nil
                }
            }
        } else if let p = l.peer, let h = LinkProtocol.hello(f, p), h.key == l.peerKey, h.name != l.peerName {
            // Their name changed (their key never does).
            l.peerName = h.name
            update(l.id) { $0.peerName = h.name }
        }
        // Your name changed: say so.
        try LinkProtocol.rename(f, side: l.side, name: myName, key: l.publicKey)
        guard let key = l.key(), let peer = l.peer else { return false }
        // Read their changes since last time; then bring yours up to date with them.
        let before = received(l.id)
        let (gotSeq, got) = LinkProtocol.receive(f, linkId: l.id, side: peer, key: key, seq: l.gotSeq, have: before)
        if gotSeq != l.gotSeq {
            prefs.set(Self.kGot + l.id, JSONText.string(LinkProtocol.items(got)))
            update(l.id) { $0.gotSeq = gotSeq }
        }
        apply(l, got, &data)
        // Publish what you share with them now (and the tasks of theirs you've done).
        var now: [String: String] = [:]
        for c in data.checkins where c.sharedWith.contains(l.id) && !c.isLinked { now[c.id] = shareJSON(c, l) }
        let done = doneForThem(l, data.checkins)
        if !done.isEmpty {
            now[Self.doneKey] = JSONText.string(JSONObject(done.keys.sorted().map { ($0, .int(done[$0]!)) }))
        }
        let (set, removed) = LinkProtocol.diff(l.sent, now)
        if !set.isEmpty || !removed.isEmpty || l.sentSeq == 0 {
            let seq = l.sentSeq + 1
            try LinkProtocol.publish(f, linkId: l.id, side: l.side, key: key, seq: seq,
                                     set: l.sentSeq == 0 ? now : set, removed: removed, all: now)
            update(l.id) {
                $0.sentSeq = seq
                $0.sent = now
            }
        }
        try LinkProtocol.writeReceipts(f, side: l.side, read: [peer: gotSeq])
        LinkProtocol.cleanUp(f, side: l.side, readers: [peer])
        return got != before
    }

    /// Brings your check-ins up to date with what [l] shares: tasks of yours they finished (if
    /// they may), and what you added from them (name, description, who may do it, done, gone).
    func apply(_ l: Connection, _ got: [String: String], _ data: inout GistData) {
        var theirs: [String: Checkin] = [:]
        for (id, json) in got where id != Self.doneKey {
            if let o = (try? JSONText.parse(json))?.objectValue, let c = try? Checkin.from(o) { theirs[id] = c }
        }
        var done: [String: Int] = [:]
        if let s = got[Self.doneKey], let o = (try? JSONText.parse(s))?.objectValue {
            for (k, v) in o.pairs { if let n = v.intValue { done[k] = n } }
        }
        let who = l.peerName.isEmpty ? "them" : l.peerName
        var list = data.checkins
        for i in list.indices {
            let c = list[i]
            if !c.isLinked, c.isTask, !c.taskDone, c.sharedWith.contains(l.id), let at = done[c.id],
               c.assignee.isEmpty || c.assignee == l.id {
                // Yours, shared with them, done by them.
                list[i].enabled = false
                list[i].doneAt = at
                list[i].doneBy = who
            } else if c.linkId == l.id {
                // Added from them: keep up with their side.
                guard let src = c.sourceId.flatMap({ theirs[$0] }) else {
                    list[i].linkedGone = true
                    continue
                }
                var u = c
                u.name = src.name
                u.description = src.description
                u.linkedAssignee = src.linkedAssignee
                u.linkedGone = false
                u.linkedDoneAt = src.doneAt
                // Done (or put back) on their side since last time: the same here.
                if c.isTask && src.doneAt != c.linkedDoneAt {
                    if let at = src.doneAt {
                        if !u.taskDone {
                            u.enabled = false
                            u.doneAt = at
                            u.doneBy = src.doneBy ?? who
                        }
                    } else if u.taskDone {
                        u.enabled = true
                        u.doneAt = nil
                        u.doneBy = nil
                    }
                }
                list[i] = u
            }
        }
        if list != data.checkins { data.checkins = list }
    }

    /// Tasks of [l]'s you've done that they let you do: their id → when.
    func doneForThem(_ l: Connection, _ mine: [Checkin]) -> [String: Int] {
        var out: [String: Int] = [:]
        for c in mine where c.linkId == l.id && c.isTask && c.youCanDo && c.taskDone && c.doneBy == nil {
            if let s = c.sourceId, let at = c.doneAt { out[s] = at }
        }
        return out
    }

    /// Adds [item] (shared by [l]) to your list: yours to set alarms and steps for; its name
    /// and description stay theirs. Nil if it's already there.
    @discardableResult
    public func addToMyList(_ l: Connection, _ item: Checkin, data: inout GistData) -> Checkin? {
        let key = "\(l.id)/\(item.id)"
        guard !data.checkins.contains(where: { $0.linkedFrom == key }) else { return nil }
        var c = item
        c.id = Ids.new()
        c.sharedWith = []
        c.assignee = ""
        c.followsUp = nil
        c.linkedFrom = key
        c.linkedGone = false
        c.linkedDoneAt = item.doneAt
        c.backlogAt = nil
        data.checkins.append(c)
        return c
    }

    /// What you've added from [l] (their id → yours).
    public func added(_ l: Connection, _ data: GistData) -> [String: Checkin] {
        var out: [String: Checkin] = [:]
        for c in data.checkins where c.linkId == l.id { if let s = c.sourceId { out[s] = c } }
        return out
    }

    /// What [linkId] shares with you: id → item JSON text.
    func received(_ linkId: String) -> [String: String] {
        LinkProtocol.itemsOf(prefs.string(Self.kGot + linkId).flatMap { (try? JSONText.parse($0))?.objectValue })
    }

    /// What [l] shares with you, read-only.
    public func shared(_ l: Connection) -> [Checkin] {
        received(l.id).filter { $0.key != Self.doneKey }.sorted { $0.key < $1.key }.compactMap { _, json in
            (try? JSONText.parse(json))?.objectValue.flatMap { try? Checkin.from($0) }
        }
    }

    /// An item as [l] sees it: without who else it's shared with, and who may do it as they see it.
    func shareJSON(_ c: Checkin, _ l: Connection) -> String {
        let who: String
        switch c.assignee {
        case "": who = Checkin.linkAnyone
        case Checkin.assignMe: who = Checkin.linkOwner
        case l.id: who = Checkin.linkYou
        default: who = Checkin.linkOther
        }
        var s = c
        s.sharedWith = []
        s.assignee = ""
        s.linkedAssignee = c.isTask ? who : ""
        return JSONText.string(s.json)
    }

    // MARK: On your other devices

    func gone() -> [String: Int] {
        guard let o = prefs.string(Self.kGone).flatMap({ (try? JSONText.parse($0))?.objectValue }) else { return [:] }
        var m: [String: Int] = [:]
        for (k, v) in o.pairs { if let n = v.intValue { m[k] = n } }
        return m
    }

    func remoteItems() -> [String: JSONObject] {
        guard let o = prefs.string(Self.kRemote).flatMap({ (try? JSONText.parse($0))?.objectValue }) else { return [:] }
        var m: [String: JSONObject] = [:]
        for (k, v) in o.pairs { if let x = v.objectValue { m[k] = x } }
        return m
    }

    func saveRemote(_ m: [String: JSONObject]) {
        prefs.set(Self.kRemote, JSONText.string(JSONObject(m.keys.sorted().map { ($0, .object(m[$0]!)) })))
    }

    /// The connections kept on your other devices.
    func remote() -> [Connection] {
        let hidden = hiddenRemote()
        return remoteItems().keys.sorted().compactMap { k in
            guard let o = remoteItems()[k], let lo = o.object("link"), var l = Connection.from(lo),
                  let home = o["home"]?.stringValue else { return nil }
            l.folder = ""
            l.sent = [:]
            l.home = home
            l.homeName = o.string("homeName")
            l.homeAt = o.int("at", 0)
            l.shown = !hidden.contains(l.id)
            return l
        }
    }

    /// For your other devices (as device [side], named [deviceName]): each connection kept
    /// here, "l:<id>" (its keys, who it's with, what they share), and each one removed.
    public func deviceItems(side: String, deviceName: String) -> [String: String] {
        var out: [String: String] = [:]
        for l in here {
            var x = l
            x.folder = ""
            x.sent = [:]
            out["l:\(l.id)"] = JSONText.string(JSONObject([
                ("at", .int(l.homeAt)), ("home", .string(side)), ("homeName", .string(deviceName)),
                ("link", .object(x.json)), ("shared", LinkProtocol.items(received(l.id))),
            ]))
        }
        for (id, at) in gone() {
            out["l:\(id)"] = JSONText.string(JSONObject([("gone", .bool(true)), ("at", .int(at))]))
        }
        return out
    }

    /// Takes in your other devices' connections ([items]; this device is [side]): ones they
    /// keep, one taken over from here, and ones removed. True when anything changed.
    @discardableResult
    public func takeDeviceItems(_ items: [String: String], side: String) -> Bool {
        var changed = false
        var remote = remoteItems()
        for (k, text) in items where k.hasPrefix("l:") {
            let id = String(k.dropFirst(2))
            guard let o = (try? JSONText.parse(text))?.objectValue else { continue }
            let at = o.int("at", 0)
            if o.bool("gone", false) {
                if let r = remote[id], r.int("at", 0) <= at {
                    remote[id] = nil
                    prefs.set(Self.kGot + id, nil)
                    changed = true
                }
                continue
            }
            if o.string("home") == side || !o.has("link") || (gone()[id] ?? -1) >= at { continue }
            if let mine = here.first(where: { $0.id == id }) {
                if at <= mine.homeAt { continue }
                // Taken over by another of your devices: it reads and publishes now.
                save(here.filter { $0.id != id })
            }
            if let cur = remote[id], cur.int("at", 0) > at { continue }
            if let cur = remote[id], cur == o { continue }
            remote[id] = o
            prefs.set(Self.kGot + id, JSONText.string(.object(o.object("shared") ?? JSONObject())))
            changed = true
        }
        if changed { saveRemote(remote) }
        return changed
    }

    public enum AdoptProblem: Error { case notKeptElsewhere, notItsFolder }

    /// Makes this device the home of connection [id] (kept on another of your devices), using
    /// its [folder] as reached from here: carries on from what was last published there.
    public func adopt(_ id: String, folder: String, now: Int = GistData.nowMs) throws {
        guard let r = remote().first(where: { $0.id == id }) else { throw AdoptProblem.notKeptElsewhere }
        let f = try openFolder(folder)
        guard LinkProtocol.linkId(f) == id else { throw AdoptProblem.notItsFolder }
        let (seq, sent) = r.key().map { LinkProtocol.receive(f, linkId: id, side: r.side, key: $0, seq: 0, have: [:]) } ?? (0, [:])
        var l = r
        l.folder = folder
        l.sentSeq = seq
        l.sent = sent
        l.home = nil
        l.homeName = ""
        l.homeAt = now
        save(here.filter { $0.id != id } + [l])
        var rest = remoteItems()
        rest[id] = nil
        saveRemote(rest)
        var h = hiddenRemote()
        h.remove(id)
        prefs.set(Self.kHidden, JSONText.string(.array(h.sorted().map { .string($0) })))
    }
}
