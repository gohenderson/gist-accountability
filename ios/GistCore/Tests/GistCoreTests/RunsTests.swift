import XCTest
@testable import GistCore

final class RunDetectorTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    /// Steps at [cadence] a minute for [seconds], reported every 5 s, from [from] and [total].
    func feed(_ d: inout RunDetector, cadence: Double, seconds: Int, from: inout Date, total: inout Double) -> [RunDetector.Event] {
        var events: [RunDetector.Event] = []
        for _ in 0..<(seconds / 5) {
            from = from.addingTimeInterval(5)
            total += cadence / 12
            if let e = d.steps(Int(total), at: from) { events.append(e) }
        }
        return events
    }

    func testARunStartsAndFinishes() {
        var d = RunDetector()
        var now = t0
        var total = 0.0
        _ = d.steps(0, at: now)
        XCTAssertTrue(feed(&d, cadence: 110, seconds: 300, from: &now, total: &total).isEmpty, "walking isn't running")
        let started = feed(&d, cadence: 165, seconds: 120, from: &now, total: &total)
        XCTAssertEqual(started.count, 1)
        guard case .started = started.first else { return XCTFail("\(started)") }
        XCTAssertTrue(d.running)
        XCTAssertTrue(feed(&d, cadence: 165, seconds: 30 * 60, from: &now, total: &total).isEmpty)
        XCTAssertGreaterThanOrEqual(d.minutes(at: now) ?? 0, 30)
        // A traffic light: a minute standing still doesn't end it.
        XCTAssertTrue(feed(&d, cadence: 0, seconds: 60, from: &now, total: &total).isEmpty)
        XCTAssertTrue(feed(&d, cadence: 165, seconds: 120, from: &now, total: &total).isEmpty)
        // Walking home: finished after 90 s.
        let finished = feed(&d, cadence: 100, seconds: 180, from: &now, total: &total)
        XCTAssertEqual(finished.count, 1)
        XCTAssertGreaterThanOrEqual(finished.first?.minutes ?? 0, 33)
        XCTAssertFalse(d.running)
    }

    func testADashForTheBusIsntARun() {
        var d = RunDetector()
        var now = t0
        var total = 0.0
        _ = d.steps(0, at: now)
        XCTAssertTrue(feed(&d, cadence: 170, seconds: 40, from: &now, total: &total).isEmpty)
        XCTAssertTrue(feed(&d, cadence: 90, seconds: 120, from: &now, total: &total).isEmpty)
        XCTAssertFalse(d.running)
    }

    func testARunFinishesWithNoMoreSteps() {
        var d = RunDetector()
        var now = t0
        var total = 0.0
        _ = d.steps(0, at: now)
        _ = feed(&d, cadence: 165, seconds: 600, from: &now, total: &total)
        XCTAssertTrue(d.running)
        XCTAssertNil(d.tick(now.addingTimeInterval(60)))
        XCTAssertNotNil(d.tick(now.addingTimeInterval(200)), "the phone stopped counting: finished")
    }
}

final class RunsTests: XCTestCase {
    let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "America/New_York")!
        return c
    }()

    func at(_ h: Int, _ m: Int = 0, day: Int = 12) -> Date {
        cal.date(from: DateComponents(year: 2026, month: 11, day: day, hour: h, minute: m))!
    }

    func testStartAndFinish() {
        var c = Checkin(id: "r", name: "After run", times: [])
        c.events = [Trigger(kind: Trigger.runEnd, minRunMinutes: 20)]
        XCTAssertTrue(Runs.anyUse([c]))
        XCTAssertFalse(Runs.eventRings(c, finishedMinutes: nil, now: at(7), rangToday: false, calendar: cal), "not on start")
        XCTAssertFalse(Runs.eventRings(c, finishedMinutes: 15, now: at(7), rangToday: false, calendar: cal), "too short")
        XCTAssertTrue(Runs.eventRings(c, finishedMinutes: 25, now: at(7), rangToday: false, calendar: cal))
        XCTAssertFalse(Runs.eventRings(c, finishedMinutes: 25, now: at(7), rangToday: true, calendar: cal), "once a day")
        c.events = [Trigger(kind: Trigger.runStart)]
        XCTAssertTrue(Runs.eventRings(c, finishedMinutes: nil, now: at(7), rangToday: false, calendar: cal))
        c.enabled = false
        XCTAssertFalse(Runs.eventRings(c, finishedMinutes: nil, now: at(7), rangToday: false, calendar: cal))
        XCTAssertFalse(Runs.anyUse([c]))
    }

    func testMilestones() {
        var c = Checkin(id: "v", name: "Cheer", times: [])
        c.commitment = Checkin.voice
        var every = Trigger(kind: Trigger.runFor, minRunMinutes: 15)
        every.oncePerDay = false
        var thirty = Trigger(kind: Trigger.runFor, minRunMinutes: 30)
        thirty.oncePerDay = false
        c.events = [every, thirty]
        XCTAssertEqual(Runs.progressRings(c, minutes: 10, fired: [], now: at(7), rangToday: true, calendar: cal), [])
        let at15 = Runs.progressRings(c, minutes: 15, fired: [], now: at(7), rangToday: true, calendar: cal)
        XCTAssertEqual(at15, ["v:0:15"])
        XCTAssertEqual(Runs.progressRings(c, minutes: 31, fired: Set(at15), now: at(7), rangToday: true, calendar: cal), ["v:1:30"])
    }

    func testVoiceTimesDue() {
        var c = Checkin(id: "v", name: "Stand up", times: [AlarmTime(10, 0)])
        c.commitment = Checkin.voice
        var data = GistData(checkins: [c])
        XCTAssertEqual(Voice.spokenText(c), "Stand up")
        c.voiceText = "  Time to stand  "
        XCTAssertEqual(Voice.spokenText(c), "Time to stand")
        XCTAssertTrue(Voice.due(data, now: at(9, 59), calendar: cal).isEmpty)
        XCTAssertEqual(Voice.due(data, now: at(10, 1), calendar: cal).map { $0.time }, ["10:00"])
        XCTAssertTrue(Voice.due(data, now: at(10, 5), calendar: cal).isEmpty, "too late: stays quiet")
        data.markDone(day: "2026-11-12", checkinId: "v", time: "10:00")
        XCTAssertTrue(Voice.due(data, now: at(10, 1), calendar: cal).isEmpty, "already spoken")
    }
}
