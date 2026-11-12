import XCTest
@testable import GistCore

final class LibraryTests: XCTestCase {
    func testTemplatesAreWellFormed() throws {
        XCTAssertEqual(Library.templates.count, 19)
        XCTAssertEqual(Library.categories, ["Food", "Wake-up", "Health", "Fitness", "Mind", "Money"])
        XCTAssertEqual(Set(Library.templates.map { $0.key }).count, Library.templates.count, "unique keys")
        for t in Library.templates {
            XCTAssertFalse(t.checkin.hasNoTriggers, t.key)
            XCTAssertFalse(t.blurb.isEmpty, t.key)
            if !t.checkin.isVoice { XCTAssertFalse(t.checkin.activeSteps.isEmpty, t.key) }
            // Each survives the backup format (the Android app reads the same JSON).
            var c = t.checkin
            c.id = "x"
            XCTAssertEqual(try Checkin.from(try XCTUnwrap(c.json.objectValue)), c, t.key)
        }
    }

    func testWhatWorksOnIPhone() throws {
        func works(_ key: String, places: Bool = false) throws -> Bool {
            try XCTUnwrap(Library.template(key)).worksOnIPhone(places: places)
        }
        XCTAssertTrue(try works("wake-up"))
        XCTAssertFalse(try works("gym"), "places come later")
        XCTAssertTrue(try works("gym", places: true))
        XCTAssertFalse(try works("after-run", places: true), "runs are Android only")
        XCTAssertFalse(try works("run-cheer", places: true), "voice is Android only")
    }

    func testAddingACopy() throws {
        let water = try XCTUnwrap(Library.template("water"))
        let first = Library.instantiate(water, existing: [], newId: "a")
        XCTAssertEqual(first.id, "a")
        XCTAssertEqual(first.name, "Water")
        XCTAssertEqual(first.category, "Food")
        XCTAssertEqual(first.times.map { $0.description }, ["11:00", "15:00", "19:00"])
        let second = Library.instantiate(water, existing: [first], newId: "b")
        XCTAssertEqual(second.name, "Water 2")
        XCTAssertEqual(Library.blank(existing: [first, second], newId: "c").name, "Check-in 3")
    }

    func testScanMatching() {
        XCTAssertTrue(Scan.matches("GIST-SINK-0001", "GIST-SINK-0001"))
        XCTAssertTrue(Scan.matches("GIST-SINK-0001", " GIST-SINK-0001\n"))
        XCTAssertFalse(Scan.matches("GIST-SINK-0001", "GIST-SINK-0002"))
        XCTAssertFalse(Scan.matches(nil, "x"))
        XCTAssertFalse(Scan.matches("", ""))
        // UPC-A as 12 digits (Android) vs. EAN-13 with a leading 0 (iPhone), both ways.
        XCTAssertTrue(Scan.matches("036000291452", "0036000291452"))
        XCTAssertTrue(Scan.matches("0036000291452", "036000291452"))
        XCTAssertFalse(Scan.matches("036000291452", "1036000291452"))
        XCTAssertFalse(Scan.matches("36000291452", "036000291452"), "only the 12 ↔ 13 digit case")
    }
}

final class SnoozeTests: XCTestCase {
    let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "America/New_York")!
        return c
    }()

    func at(_ h: Int, _ m: Int = 0) -> Date {
        cal.date(from: DateComponents(year: 2026, month: 11, day: 12, hour: h, minute: m))!
    }

    func testSnoozedSoftAlarmsRingAgain() {
        var soft = Checkin(id: "s", name: "Stretch", times: [AlarmTime(15, 0)])
        soft.commitment = Checkin.soft
        var data = GistData(checkins: [soft])
        let z = OneOffRing(checkinId: "s", day: "2026-11-12", time: "15:00", at: at(15, 10))
        let plan = AlarmPlan.plan(data, waiting: [], practice: [], oneOff: [z], now: at(15, 1), calendar: cal)
        XCTAssertEqual(plan.filter { $0.oneOff }.map { $0.at }, [at(15, 10)])
        XCTAssertTrue(AlarmPlan.plan(data, waiting: [], practice: [], oneOff: [z], now: at(15, 11), calendar: cal)
            .filter { $0.oneOff }.isEmpty, "past")
        data.markDismissed(day: "2026-11-12", checkinId: "s", time: "15:00")
        XCTAssertTrue(AlarmPlan.plan(data, waiting: [], practice: [], oneOff: [z], now: at(15, 1), calendar: cal)
            .filter { $0.oneOff }.isEmpty, "dismissed")
    }
}
