import XCTest
@testable import GistCore

/// Connections: two people (Alex and Jess, each with their own state and check-ins) through
/// one folder, as two phones would through a shared one.
final class ConnectionsTests: XCTestCase {
    private var dir: URL!
    private var alex: Connections!
    private var jess: Connections!
    private var alexData = GistData(checkins: [])
    private var jessData = GistData(checkins: [])

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("conn-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let open: (String) throws -> LinkFolder = { FileFolder(URL(fileURLWithPath: $0)) }
        alex = Connections(prefs: MemoryPrefs(), openFolder: open)
        jess = Connections(prefs: MemoryPrefs(), openFolder: open)
        alex.myName = "Alex"
        jess.myName = "Jess"
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
    }

    private func task(_ id: String, _ name: String, assignee: String = "", shareWith: String? = nil) -> Checkin {
        var c = Checkin(id: id, name: name, times: [], enabled: true, task: true)
        c.assignee = assignee
        if let s = shareWith { c.sharedWith = [s] }
        return c
    }

    /// Alex invites, Jess joins with the invite, Alex lets her in.
    private func connect() throws -> (Link, Link) {
        let a = try alex.invite(folder: dir.path)
        let (inv, _) = try XCTUnwrap(alex.invitation(a))
        XCTAssertEqual(jess.findInvited([dir.path], inv), dir.path)
        let j = try jess.join(folder: dir.path, invite: inv)
        alex.sync(&alexData)
        let a2 = try XCTUnwrap(alex.get(a.id))
        XCTAssertTrue(a2.connected)
        return (a2, j)
    }

    func testCheckinsKeepTheirConnectionFields() throws {
        var c = task("t1", "Mow")
        c.sharedWith = ["L2", "L1"]
        c.assignee = "me"
        c.doneBy = "Jess"
        c.linkedFrom = "L9/x"
        c.linkedAssignee = Checkin.linkYou
        c.linkedGone = true
        c.linkedDoneAt = 5
        c.updatedAt = 7
        let o = try XCTUnwrap(c.json.objectValue)
        XCTAssertEqual(o.array("sharedWith"), [.string("L1"), .string("L2")])
        XCTAssertEqual(try Checkin.from(o), c)
        XCTAssertEqual(c.linkId, "L9")
        XCTAssertEqual(c.sourceId, "x")
    }

    func testInvitingAndJoiningShowTheSameCode() throws {
        let (a, j) = try connect()
        XCTAssertEqual(a.peerName, "Jess")
        XCTAssertEqual(j.peerName, "Alex")
        XCTAssertEqual(a.code, j.code)
        XCTAssertNil(a.inviteToken, "used up")
        XCTAssertNil(alex.invitation(a), "nothing more to show")
        XCTAssertThrowsError(try jess.join(folder: dir.path, invite: LinkInvite(kind: "c", fingerprint: Data(count: 10), token: Data(count: 10))))
    }

    func testOnlyTheOneWithTheInviteIsLetIn() throws {
        let a = try alex.invite(folder: dir.path)
        let f = FileFolder(dir)
        // Someone who saw the folder but not the invite.
        try LinkProtocol.writeHello(f, LinkHello(side: "eve", name: "Eve", key: LinkCrypto.newKeyPair().publicKey))
        alex.sync(&alexData)
        XCTAssertFalse(try XCTUnwrap(alex.get(a.id)).connected)
        // A guessed token.
        try LinkProtocol.writeHello(f, LinkHello(side: "mallory", name: "M", key: "K",
            proof: LinkInvites.proof(token: LinkInvites.newToken(), scope: a.id, side: "mallory", publicKey: "K", joinedAt: 1), joinedAt: 1))
        alex.sync(&alexData)
        XCTAssertFalse(try XCTUnwrap(alex.get(a.id)).connected)
    }

    func testAnExpiredInviteIsntAcceptedButIsRenewed() throws {
        let then = GistData.nowMs - LinkInvites.validMs - 60_000
        let a = try alex.invite(folder: dir.path, now: then)
        let (old, _) = try XCTUnwrap(alex.invitation(a, now: then))
        _ = try jess.join(folder: dir.path, invite: old)
        alex.sync(&alexData)
        XCTAssertFalse(try XCTUnwrap(alex.get(a.id)).connected, "too late")
        let (fresh, until) = try XCTUnwrap(alex.invitation(try XCTUnwrap(alex.get(a.id))))
        XCTAssertNotEqual(fresh.token, old.token)
        XCTAssertGreaterThan(until, GistData.nowMs)
    }

    func testSharedTasksDoneByThemAndYourCopies() throws {
        let (a, j) = try connect()
        alexData.checkins = [task("mow", "Mow the lawn", shareWith: a.id), task("mine", "Only me", assignee: Checkin.assignMe, shareWith: a.id)]
        alex.sync(&alexData)
        jess.sync(&jessData)
        let shared = jess.shared(j)
        XCTAssertEqual(Set(shared.map { $0.name }), ["Only me", "Mow the lawn"])
        let mow = try XCTUnwrap(shared.first { $0.id == "mow" })
        XCTAssertEqual(mow.linkedAssignee, Checkin.linkAnyone)
        XCTAssertEqual(mow.sharedWith, [], "who else it's shared with stays private")
        XCTAssertEqual(shared.first { $0.id == "mine" }?.linkedAssignee, Checkin.linkOwner)

        // Jess adds it to her list and does it.
        let copy = try XCTUnwrap(jess.addToMyList(j, mow, data: &jessData))
        XCTAssertNil(jess.addToMyList(j, mow, data: &jessData), "already there")
        XCTAssertEqual(copy.linkedFrom, "\(j.id)/mow")
        XCTAssertTrue(copy.youCanDo)
        jessData.completeTask(copy.id, at: 1234)
        jess.sync(&jessData)
        alex.sync(&alexData)
        let done = try XCTUnwrap(alexData.checkin("mow"))
        XCTAssertEqual(done.doneAt, 1234)
        XCTAssertEqual(done.doneBy, "Jess")
        XCTAssertFalse(done.enabled)

        // Alex renames his; Jess's copy follows; he stops sharing it: hers is marked gone.
        alexData.update("mine") { $0.name = "Only me, really" }
        alex.sync(&alexData)
        let mine = try XCTUnwrap(jess.shared(j).first { $0.id == "mine" })
        let mineCopy = try XCTUnwrap(jess.addToMyList(j, mine, data: &jessData))
        XCTAssertFalse(mineCopy.youCanDo)
        XCTAssertEqual(mineCopy.name, "Only me, really")
        alexData.update("mine") { $0.sharedWith = [] }
        alex.sync(&alexData)
        jess.sync(&jessData)
        XCTAssertTrue(try XCTUnwrap(jessData.checkin(mineCopy.id)).linkedGone)
    }

    func testANameChangeReachesTheOtherSide() throws {
        let (a, _) = try connect()
        jess.myName = "Jessie"
        jess.sync(&jessData)
        alex.sync(&alexData)
        XCTAssertEqual(alex.get(a.id)?.peerName, "Jessie")
    }

    func testRemovingStopsSharing() throws {
        let (a, _) = try connect()
        alexData.checkins = [task("mow", "Mow", shareWith: a.id)]
        alex.remove(a.id, data: &alexData)
        XCTAssertTrue(alex.all.isEmpty)
        XCTAssertEqual(alexData.checkin("mow")?.sharedWith, [])
        XCTAssertEqual(alex.deviceItems(side: "phone", deviceName: "Phone").keys.sorted(), ["l:\(a.id)"])
    }

    func testKeptOnAnotherDeviceThenTakenOver() throws {
        let (a, j) = try connect()
        alexData.checkins = [task("mow", "Mow", shareWith: a.id)]
        alex.sync(&alexData)
        jess.sync(&jessData)
        // Jess's tablet hears about the connection from her phone.
        let tablet = Connections(prefs: MemoryPrefs(), openFolder: { FileFolder(URL(fileURLWithPath: $0)) })
        let items = jess.deviceItems(side: "phone", deviceName: "Phone")
        XCTAssertTrue(tablet.takeDeviceItems(items, side: "tablet"))
        let r = try XCTUnwrap(tablet.get(j.id))
        XCTAssertFalse(r.isHere)
        XCTAssertEqual(r.homeName, "Phone")
        XCTAssertEqual(tablet.shared(r).map { $0.name }, ["Mow"])
        XCTAssertFalse(tablet.takeDeviceItems(items, side: "tablet"), "nothing new")
        // The tablet takes it over: carries on from the folder.
        XCTAssertThrowsError(try tablet.adopt(j.id, folder: FileManager.default.temporaryDirectory.path))
        try tablet.adopt(j.id, folder: dir.path, now: GistData.nowMs + 1000)
        let t = try XCTUnwrap(tablet.get(j.id))
        XCTAssertTrue(t.isHere)
        XCTAssertEqual(t.sentSeq, try XCTUnwrap(jess.get(j.id)).sentSeq)
        // The phone hears the later claim and stops.
        XCTAssertTrue(jess.takeDeviceItems(tablet.deviceItems(side: "tablet", deviceName: "Tablet"), side: "phone"))
        XCTAssertTrue(jess.here.isEmpty)
        XCTAssertEqual(jess.get(j.id)?.homeName, "Tablet")
    }
}
