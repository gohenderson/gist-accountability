import XCTest
@testable import GistCore

final class PlacesTests: XCTestCase {
    let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "America/New_York")!
        return c
    }()
    let gym = Place(name: "Gym", lat: 40.7128, lon: -74.006, radiusM: 200)
    let home = Place(name: "Home", lat: 40.75, lon: -73.99, radiusM: 100)

    func at(_ h: Int, _ m: Int = 0, day: Int = 12) -> Date {
        cal.date(from: DateComponents(year: 2026, month: 11, day: day, hour: h, minute: m))!
    }

    func testPlaceLogKnowsWhereYouWere() {
        var log = PlaceLog()
        XCTAssertNil(log.inside(gym.key, at: at(9)))
        XCTAssertTrue(log.record(PlaceCrossing(key: gym.key, inside: true, at: at(8))))
        XCTAssertFalse(log.record(PlaceCrossing(key: gym.key, inside: true, at: at(8, 5))), "same state again")
        log.record(PlaceCrossing(key: gym.key, inside: false, at: at(10)))
        XCTAssertNil(log.inside(gym.key, at: at(7)))
        XCTAssertEqual(log.inside(gym.key, at: at(9)), true)
        XCTAssertEqual(log.inside(gym.key, at: at(11)), false)
        XCTAssertEqual(log.inside(gym.key), false)
        XCTAssertNil(log.inside(home.key))
        // Pruning keeps each place's latest crossing, and drops places no longer watched.
        log.record(PlaceCrossing(key: home.key, inside: true, at: at(9)))
        log.prune(keeping: [gym.key], now: at(12, day: 20))
        XCTAssertEqual(log.crossings, [PlaceCrossing(key: gym.key, inside: false, at: at(10))])
    }

    func testTimesAtOrAwayFromAPlace() {
        var log = PlaceLog()
        let atGym = AlarmTime(9, 0, where: Trigger.at, place: gym)
        let awayFromHome = AlarmTime(9, 0, where: Trigger.away, place: home)
        XCTAssertTrue(Places.allows(atGym, at: at(9), log: log), "unknown: rings anyway")
        XCTAssertTrue(Places.allows(AlarmTime(9, 0), at: at(9), log: log))
        log.record(PlaceCrossing(key: gym.key, inside: true, at: at(8)))
        log.record(PlaceCrossing(key: home.key, inside: true, at: at(6)))
        XCTAssertTrue(Places.allows(atGym, at: at(9), log: log))
        XCTAssertFalse(Places.allows(awayFromHome, at: at(9), log: log))
        log.record(PlaceCrossing(key: home.key, inside: false, at: at(7)))
        XCTAssertTrue(Places.allows(awayFromHome, at: at(9), log: log))
    }

    func testPlanAndDueFollowThePlace() {
        let c = Checkin(id: "g", name: "Gym", times: [AlarmTime(9, 0, where: Trigger.at, place: gym)])
        let data = GistData(checkins: [c])
        var log = PlaceLog([PlaceCrossing(key: gym.key, inside: false, at: at(7))])
        // Away from the gym: today's 9:00 isn't set (tomorrow's would be, if you're there by then).
        XCTAssertTrue(AlarmPlan.plan(data, waiting: [], practice: [], places: log, now: at(8), calendar: cal).isEmpty)
        log.record(PlaceCrossing(key: gym.key, inside: true, at: at(8, 30)))
        XCTAssertEqual(AlarmPlan.plan(data, waiting: [], practice: [], places: log, now: at(8, 31), calendar: cal).first?.at, at(9))
        // At 9:00 you were there: it's due.
        XCTAssertEqual(AlarmPlan.due(data, now: at(9, 5), places: log, calendar: cal).map { $0.time }, ["09:00"])
        XCTAssertTrue(AlarmPlan.placeSkips(data, now: at(9, 5), places: log, calendar: cal).isEmpty)
        // If you'd left at 8:45, it's skipped instead.
        log.record(PlaceCrossing(key: gym.key, inside: false, at: at(8, 45)))
        XCTAssertTrue(AlarmPlan.due(data, now: at(9, 5), places: log, calendar: cal).isEmpty)
        XCTAssertEqual(AlarmPlan.placeSkips(data, now: at(9, 5), places: log, calendar: cal).map { $0.time }, ["09:00"])
    }

    func testArriveAndLeave() {
        var c = Checkin(id: "g", name: "Gym", times: [])
        c.events = [Trigger(kind: Trigger.arrive, place: gym, windowStart: AlarmTime(6, 0), windowEnd: AlarmTime(10, 0),
                            days: Repeat(Repeat.weekly, [1, 3, 5]))]
        // 2026-11-12 is a Thursday; the 11th a Wednesday.
        func rings(_ entering: Bool, _ now: Date, rang: Bool = false, key: String? = nil) -> Bool {
            Places.eventRings(c, key: key ?? gym.key, entering: entering, now: now, rangToday: rang, calendar: cal) != nil
        }
        XCTAssertTrue(rings(true, at(7, day: 11)))
        XCTAssertFalse(rings(false, at(7, day: 11)), "leaving, not arriving")
        XCTAssertFalse(rings(true, at(7)), "not on Thursdays")
        XCTAssertFalse(rings(true, at(11, day: 11)), "outside the window")
        XCTAssertFalse(rings(true, at(7, day: 11), rang: true), "once a day")
        XCTAssertFalse(rings(true, at(7, day: 11), key: home.key), "another place")
        c.events[0].kind = Trigger.leave
        c.events[0].windowStart = AlarmTime(22, 0)
        c.events[0].windowEnd = AlarmTime(2, 0)
        XCTAssertTrue(rings(false, at(23, day: 11)), "a window across midnight")
        XCTAssertTrue(rings(false, at(1, day: 11)))
        c.enabled = false
        XCTAssertFalse(rings(false, at(23, day: 11)))
    }

    func testWatchedPlaces() {
        var a = Checkin(id: "a", name: "A", times: [AlarmTime(9, 0, where: Trigger.away, place: home)])
        a.events = [Trigger(kind: Trigger.arrive, place: gym)]
        var b = Checkin(id: "b", name: "B", times: [])
        b.events = [Trigger(kind: Trigger.leave, place: gym), Trigger(kind: Trigger.runStart)]
        var off = Checkin(id: "c", name: "C", times: [])
        off.events = [Trigger(kind: Trigger.arrive, place: Place(name: "X", lat: 1, lon: 1))]
        off.enabled = false
        XCTAssertEqual(Set(Places.watched([a, b, off]).keys), [gym.key, home.key])
    }

    func testRangToday() {
        var data = GistData(checkins: [Checkin(id: "g", name: "Gym", times: [])])
        XCTAssertFalse(Places.rang(data, checkinId: "g", day: "2026-11-12", waiting: []))
        data.markSkipped(day: "2026-11-12", checkinId: "g", time: "09:00")
        XCTAssertFalse(Places.rang(data, checkinId: "g", day: "2026-11-12", waiting: []), "skips don't count")
        XCTAssertTrue(Places.rang(data, checkinId: "g", day: "2026-11-12",
                                  waiting: [Pending(checkinId: "g", day: "2026-11-12", time: "10:00")]))
        data.markRang(day: "2026-11-12", checkinId: "g", time: "10:00")
        XCTAssertTrue(Places.rang(data, checkinId: "g", day: "2026-11-12", waiting: []))
    }
}
