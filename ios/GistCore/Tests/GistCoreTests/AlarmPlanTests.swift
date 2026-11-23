import XCTest
@testable import GistCore

final class AlarmPlanTests: XCTestCase {
    let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "America/New_York")!
        return c
    }()

    func at(_ d: Int, _ h: Int, _ m: Int = 0) -> Date {
        cal.date(from: DateComponents(year: 2026, month: 11, day: d, hour: h, minute: m))!
    }

    private func strict(_ id: String, _ h: Int, _ m: Int = 0) -> Checkin {
        Checkin(id: id, name: id, times: [AlarmTime(h, m)])
    }

    func testStrictRingsComeWithReRings() {
        let data = GistData(checkins: [strict("s", 7)])
        let plan = AlarmPlan.plan(data, waiting: [], practice: [], now: at(10, 6), calendar: cal)
        XCTAssertEqual(plan.count, 1 + AlarmPlan.nagCount)
        XCTAssertEqual(plan.map { $0.nag }, Array(0...AlarmPlan.nagCount))
        XCTAssertEqual(plan[0].at, at(10, 7))
        XCTAssertEqual(plan[1].at, at(10, 7, AlarmPlan.nagMinutes))
        XCTAssertTrue(plan.allSatisfy { $0.day == "2026-11-10" && $0.time == "07:00" })
    }

    func testSoftOffAndVoiceCheckins() {
        var soft = strict("soft", 8)
        soft.commitment = Checkin.soft
        var off = strict("off", 8)
        off.enabled = false
        var voice = strict("voice", 8)
        voice.commitment = Checkin.voice
        let plan = AlarmPlan.plan(GistData(checkins: [soft, off, voice]), waiting: [], practice: [], now: at(10, 6), calendar: cal)
        XCTAssertEqual(plan.map { $0.checkinId }, ["soft"], "soft rings once; off and voice don't ring")
    }

    func testNoAlarmCheckinsRemindQuietlyInstead() {
        var quiet = strict("q", 8)
        quiet.soundUri = Checkin.quiet
        let data = GistData(checkins: [quiet, strict("s", 9)])
        let plan = AlarmPlan.plan(data, waiting: [Pending(checkinId: "q", day: "2026-11-10", time: "08:00")], practice: [],
                                  oneOff: [OneOffRing(checkinId: "q", day: "2026-11-10", time: "08:00", at: at(10, 8, 30))],
                                  now: at(10, 6), calendar: cal)
        XCTAssertFalse(plan.contains { $0.checkinId == "q" }, "no alarm, no re-rings, no one-off rings")
        let quietRings = AlarmPlan.quietRings(data, now: at(10, 6), calendar: cal)
        XCTAssertEqual(quietRings.map { $0.checkinId }, ["q"])
        XCTAssertEqual(quietRings.first?.at, at(10, 8))
        // Once it's rung it still waits like any other (shown on the start page).
        XCTAssertEqual(AlarmPlan.due(data, now: at(10, 8, 5), calendar: cal).map { $0.checkinId }, ["q"])
    }

    func testDueRingsSayWhenTheyRang() {
        let data = GistData(checkins: [strict("s", 7)])
        let rings = AlarmPlan.dueRings(data, now: at(10, 9), calendar: cal)
        XCTAssertEqual(rings.map { $0.0.checkinId }, ["s"])
        XCTAssertEqual(rings.first?.1, at(10, 7))
    }

    func testDoItNowOnATaskUsesItsNearestTime() {
        var t = Library.task(newId: "t")
        XCTAssertEqual(Scheduler.nearestTime(t, now: at(10, 14, 25), calendar: cal).description, "14:25", "no times: now")
        t.times = [AlarmTime(9, 0), AlarmTime(15, 0)]
        XCTAssertEqual(Scheduler.nearestTime(t, now: at(10, 14, 25), calendar: cal).description, "15:00")
        XCTAssertEqual(Scheduler.nearestTime(t, now: at(10, 1, 0), calendar: cal).description, "09:00")
    }

    func testDoneOrSkippedRingsMoveToTheNextOne() {
        var data = GistData(checkins: [strict("s", 7)])
        data.markSkipped(day: "2026-11-10", checkinId: "s", time: "07:00")
        let plan = AlarmPlan.plan(data, waiting: [], practice: [], now: at(10, 6), calendar: cal)
        XCTAssertEqual(plan.first?.at, at(11, 7))
    }

    func testWaitingStrictCheckinsKeepRinging() {
        let data = GistData(checkins: [strict("s", 7)])
        let now = at(10, 7, 10)
        let waiting = [Pending(checkinId: "s", day: "2026-11-10", time: "07:00")]
        let reRings = AlarmPlan.plan(data, waiting: waiting, practice: [], now: now, calendar: cal).filter { $0.day == "2026-11-10" }
        XCTAssertEqual(reRings.count, AlarmPlan.nagCount)
        XCTAssertEqual(reRings.first?.at, now.addingTimeInterval(Double(AlarmPlan.nagMinutes * 60)))

        // Done: no more.
        var done = data
        done.markDone(day: "2026-11-10", checkinId: "s", time: "07:00")
        XCTAssertTrue(AlarmPlan.plan(done, waiting: waiting, practice: [], now: now, calendar: cal).filter { $0.day == "2026-11-10" }.isEmpty)
    }

    func testPracticeRings() {
        let data = GistData(checkins: [strict("s", 7)])
        let ring = PracticeRing(checkinId: "s", at: at(10, 12, 0))
        let plan = AlarmPlan.plan(data, waiting: [], practice: [ring], now: at(10, 11, 59), calendar: cal)
        XCTAssertEqual(plan.filter { $0.practice }.count, 1)
        XCTAssertTrue(AlarmPlan.duePractice([ring], now: at(10, 11, 59), calendar: cal).isEmpty)
        XCTAssertEqual(AlarmPlan.duePractice([ring], now: at(10, 12, 1), calendar: cal),
                       [Pending(checkinId: "s", day: "2026-11-10", time: "12:00", practice: true)])
    }

    func testDueRings() {
        var data = GistData(checkins: [strict("a", 7), strict("b", 9), strict("c", 13)])
        data.markDone(day: "2026-11-10", checkinId: "b", time: "09:00")
        let due = AlarmPlan.due(data, now: at(10, 12), calendar: cal)
        XCTAssertEqual(due, [Pending(checkinId: "a", day: "2026-11-10", time: "07:00")], "b is done; c hasn't rung")
        XCTAssertTrue(AlarmPlan.due(data, now: at(10, 12), since: at(10, 8), calendar: cal).isEmpty, "only since then")
    }
}
