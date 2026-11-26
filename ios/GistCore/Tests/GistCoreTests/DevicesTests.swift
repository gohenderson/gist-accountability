import XCTest
@testable import GistCore

/// Your devices: a phone and a tablet (each with its own state, check-ins and media) through
/// one folder, as two devices would through a shared one.
final class DevicesTests: XCTestCase {
    final class Settings: SyncedSettings {
        var values: [String: String] = [:]
        var at: [String: Int] = [:]
        func set(_ k: String, _ json: String, now: Int = GistData.nowMs) { values[k] = json; at[k] = now }
        func synced() -> [String: (json: String, at: Int)] {
            var out: [String: (json: String, at: Int)] = [:]
            for (k, t) in at { if let v = values[k] { out[k] = (v, t) } }
            return out
        }
        func take(_ key: String, json: String, at t: Int) -> Bool {
            guard t > (at[key] ?? 0) else { return false }
            values[key] = json
            at[key] = t
            return true
        }
        func stampAll(now: Int) { for k in values.keys { at[k] = now } }
    }

    final class Device {
        let prefs = MemoryPrefs()
        let media: URL
        let settings = Settings()
        let devices: Devices
        var data = GistData(checkins: [])
        init(_ name: String, open: @escaping (String) throws -> LinkFolder) {
            media = FileManager.default.temporaryDirectory.appendingPathComponent("media-\(UUID().uuidString)")
            let links = Connections(prefs: MemoryPrefs(), openFolder: open)
            devices = Devices(prefs: prefs, openFolder: open, connections: links, mediaRoot: media, defaultName: name)
            devices.settings = settings
        }
        @discardableResult
        func sync() throws -> DevicesSyncResult { try devices.sync(&data) }
    }

    private var dir: URL!
    private var phone: Device!
    private var tablet: Device!
    private var open: (String) throws -> LinkFolder { { FileFolder(URL(fileURLWithPath: $0)) } }

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("devices-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        phone = Device("Phone", open: open)
        tablet = Device("Tablet", open: open)
    }

    override func tearDown() {
        for d in [phone, tablet] { try? FileManager.default.removeItem(at: d!.media) }
        try? FileManager.default.removeItem(at: dir)
    }

    /// The phone sets up; [d] joins with its invite and is let in.
    private func joins(_ d: Device) throws {
        let (inv, _) = try XCTUnwrap(phone.devices.invitation())
        try d.devices.join(folder: dir.path, invite: inv, data: &d.data)
        XCTAssertFalse(d.devices.ready)
        try phone.sync()
        try d.sync()
        XCTAssertTrue(d.devices.ready)
    }

    private func setUp(_ checkins: [Checkin]) throws {
        phone.data.checkins = checkins
        try phone.devices.setUp(folder: dir.path, data: &phone.data)
        try joins(tablet)
    }

    func testEverythingGoesToADeviceThatJoins() throws {
        phone.settings.set("theme", "2")
        try setUp([Checkin(id: "a", name: "Alpha"), Checkin(id: "b", name: "Beta")])
        XCTAssertTrue(phone.data.tracking)
        XCTAssertEqual(tablet.data.checkins.map { $0.id }, ["a", "b"])
        XCTAssertEqual(tablet.settings.values["theme"], "2")
        XCTAssertEqual(phone.devices.others().map { $0.name }, ["Tablet"])
        XCTAssertEqual(phone.devices.others().first?.code, tablet.devices.others().first?.code)
    }

    func testTheLaterEditWinsAndDeletionsGoEverywhere() throws {
        try setUp([Checkin(id: "a", name: "Alpha"), Checkin(id: "b", name: "Beta")])
        tablet.data.update("a") { $0.name = "Alpha (tablet)" }
        try tablet.sync()
        try phone.sync()
        XCTAssertEqual(phone.data.checkin("a")?.name, "Alpha (tablet)")
        // An older edit doesn't win.
        var old = try XCTUnwrap(phone.data.checkin("a"))
        old.name = "Stale"
        old.updatedAt = 1
        phone.data.tracking = false
        phone.data.update("a") { $0 = old }
        phone.data.tracking = true
        try phone.sync()
        try tablet.sync()
        XCTAssertEqual(tablet.data.checkin("a")?.name, "Alpha (tablet)")
        // Deleted on the phone: gone on the tablet.
        phone.data.checkins.removeAll { $0.id == "b" }
        XCTAssertNotNil(phone.data.deleted["b"])
        try phone.sync()
        let r = try tablet.sync()
        XCTAssertEqual(r.deleted, ["b"])
        XCTAssertNil(tablet.data.checkin("b"))
    }

    func testAnAnswerBeatsRangAndClosesTheRing() throws {
        try setUp([Checkin(id: "a", name: "Alpha")])
        phone.data.markRang(day: "2026-11-26", checkinId: "a", time: "07:00")
        tablet.data.markRang(day: "2026-11-26", checkinId: "a", time: "07:00")
        tablet.data.markDone(day: "2026-11-26", checkinId: "a", time: "07:00")
        XCTAssertNotNil(tablet.data.result(day: "2026-11-26", checkinId: "a", time: "07:00")?.w)
        try tablet.sync()
        let r = try phone.sync()
        XCTAssertEqual(phone.data.result(day: "2026-11-26", checkinId: "a", time: "07:00")?.status, SlotResult.done)
        XCTAssertEqual(r.closed, [Pending(checkinId: "a", day: "2026-11-26", time: "07:00")])
        // The phone's "rang" doesn't undo it.
        try phone.sync()
        try tablet.sync()
        XCTAssertEqual(tablet.data.result(day: "2026-11-26", checkinId: "a", time: "07:00")?.status, SlotResult.done)
    }

    func testTheLaterReorderAndSettingWin() throws {
        try setUp([Checkin(id: "a", name: "A"), Checkin(id: "b", name: "B"), Checkin(id: "c", name: "C")])
        tablet.data.checkins = [tablet.data.checkin("c")!, tablet.data.checkin("a")!, tablet.data.checkin("b")!]
        tablet.settings.set("sort", "\"name\"", now: GistData.nowMs + 1000)
        phone.settings.set("theme", "1", now: GistData.nowMs + 1000)
        try tablet.sync()
        try phone.sync()
        try tablet.sync()
        XCTAssertEqual(phone.data.checkins.map { $0.id }, ["c", "a", "b"])
        XCTAssertEqual(phone.settings.values["sort"], "\"name\"")
        XCTAssertEqual(tablet.settings.values["theme"], "1", "one setting at a time")
        XCTAssertEqual(tablet.settings.values["sort"], "\"name\"")
    }

    func testPhotosAndClipsGoToo() throws {
        try setUp([Checkin(id: "a", name: "A")])
        let d = phone.media.appendingPathComponent("a")
        try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        try Data("photo".utf8).write(to: d.appendingPathComponent("photo-0.jpg"))
        try Data("voice".utf8).write(to: d.appendingPathComponent("voice.m4a"))
        try Data("half".utf8).write(to: d.appendingPathComponent("video.mp4.sync"))
        try phone.sync()
        XCTAssertTrue(try tablet.sync().changed)
        let t = tablet.media.appendingPathComponent("a")
        XCTAssertEqual(try Data(contentsOf: t.appendingPathComponent("photo-0.jpg")), Data("photo".utf8))
        XCTAssertEqual(try Data(contentsOf: t.appendingPathComponent("voice.m4a")), Data("voice".utf8))
        XCTAssertFalse(FileManager.default.fileExists(atPath: t.appendingPathComponent("video.mp4.sync").path))
        // Removed on the tablet: removed on the phone.
        try FileManager.default.removeItem(at: t.appendingPathComponent("voice.m4a"))
        try tablet.sync()
        try phone.sync()
        XCTAssertFalse(FileManager.default.fileExists(atPath: d.appendingPathComponent("voice.m4a").path))
    }

    func testRemovingADeviceChangesTheKey() throws {
        try setUp([Checkin(id: "a", name: "A")])
        let lost = Device("Lost", open: open)
        try joins(lost)
        XCTAssertEqual(Set(phone.devices.others().map { $0.name }), ["Tablet", "Lost"])
        let oldKey = try XCTUnwrap(phone.devices.groupKey)
        try phone.devices.remove(lost.devices.side)
        XCTAssertNotEqual(phone.devices.groupKey, oldKey)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.appendingPathComponent("from-\(lost.devices.side)").path))
        try phone.sync()
        try tablet.sync()
        XCTAssertEqual(tablet.devices.groupKey, phone.devices.groupKey, "the tablet takes the new key")
        XCTAssertEqual(tablet.devices.others().map { $0.name }, ["Phone"])
        // Still in step.
        phone.data.update("a") { $0.name = "A2" }
        try phone.sync()
        try tablet.sync()
        XCTAssertEqual(tablet.data.checkin("a")?.name, "A2")
        // The lost one can't read on.
        try? lost.sync()
        XCTAssertEqual(lost.data.checkin("a")?.name, "A")
    }

    func testLeavingTakesThisDevicesFilesOut() throws {
        try setUp([Checkin(id: "a", name: "A")])
        let side = tablet.devices.side
        tablet.devices.leave(data: &tablet.data)
        XCTAssertNil(tablet.devices.folder)
        XCTAssertFalse(tablet.data.tracking)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.appendingPathComponent("from-\(side)").path))
        XCTAssertTrue(phone.devices.others().isEmpty)
        // Alone: only the snapshot is kept.
        phone.data.update("a") { $0.name = "A2" }
        try phone.sync()
        XCTAssertEqual(FileFolder(dir).names("from-\(phone.devices.side)").filter { LinkProtocol.isChangeFile($0) }, [])
    }

    func testTheLetInKeyGoesOnceUsed() throws {
        try setUp([Checkin(id: "a", name: "A")])
        try phone.sync()
        XCTAssertNil(FileFolder(dir).read("from-\(phone.devices.side)/key-\(tablet.devices.side).gist"))
    }

    func testAJoinNeedsTheInvite() throws {
        phone.data.checkins = [Checkin(id: "a", name: "A")]
        try phone.devices.setUp(folder: dir.path, data: &phone.data)
        // Someone who saw the folder, with a made-up invite naming the phone.
        let (inv, _) = try XCTUnwrap(phone.devices.invitation())
        let fake = LinkInvite(kind: "d", fingerprint: inv.fingerprint, token: LinkInvites.newToken())
        try tablet.devices.join(folder: dir.path, invite: fake, data: &tablet.data)
        try phone.sync()
        try tablet.sync()
        XCTAssertFalse(tablet.devices.ready)
        XCTAssertThrowsError(try Device("x", open: open).devices.join(folder: dir.path,
            invite: LinkInvite(kind: "d", fingerprint: Data(count: 10), token: inv.token), data: &tablet.data))
    }
}
