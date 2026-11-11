import XCTest
@testable import GistCore

/// Ports of the Android app's unit tests (android/app/src/test/…/LogicTest.kt).
final class LogicTests: XCTestCase {
    /// New York, so daylight-saving changes are real.
    let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "America/New_York")!
        return c
    }()

    func at(_ y: Int, _ mo: Int, _ d: Int, _ h: Int = 0, _ mi: Int = 0) -> Date {
        cal.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi))!
    }

    // MARK: Days

    func testBefore4AMCountsForThePreviousDay() {
        XCTAssertEqual(Days.forAlarm(aboutYesterday: false, at: at(2026, 11, 10, 3, 59), calendar: cal), "2026-11-09")
        XCTAssertEqual(Days.forAlarm(aboutYesterday: false, at: at(2026, 11, 10, 4, 0), calendar: cal), "2026-11-10")
        XCTAssertEqual(Days.forAlarm(aboutYesterday: true, at: at(2026, 1, 1, 7, 0), calendar: cal), "2025-12-31")
        XCTAssertEqual(Days.forAlarm(aboutYesterday: true, at: at(2026, 3, 1, 2, 0), calendar: cal), "2026-02-27")
        XCTAssertEqual(Days.lastN(3, today: at(2026, 3, 1, 12), calendar: cal), ["2026-03-01", "2026-02-28", "2026-02-27"])
    }

    // MARK: Repeat

    func testRepeatRules() {
        let weekdays = Repeat(Repeat.weekly, Repeat.weekdays)
        XCTAssertTrue(weekdays.matches(at(2026, 11, 13), calendar: cal)) // Friday
        XCTAssertFalse(weekdays.matches(at(2026, 11, 14), calendar: cal)) // Saturday
        let lastDay = Repeat(Repeat.monthly, [Repeat.lastDay])
        XCTAssertTrue(lastDay.matches(at(2028, 2, 29), calendar: cal), "leap year")
        XCTAssertFalse(lastDay.matches(at(2028, 2, 28), calendar: cal))
        XCTAssertTrue(lastDay.matches(at(2027, 2, 28), calendar: cal))
        XCTAssertFalse(Repeat(Repeat.monthly, [31]).matches(at(2026, 11, 30), calendar: cal), "skipped, not moved")
        XCTAssertEqual(weekdays.describe(), "Mon–Fri")
        XCTAssertEqual(Repeat(Repeat.weekly, [1, 3, 4, 5, 6, 7]).describe(), "every day but Tue")
        XCTAssertEqual(Repeat(Repeat.monthly, [1, 15, Repeat.lastDay]).describe(), "monthly on the 1st, 15th and last day")
        XCTAssertEqual(Repeat.ordinal(11), "11th")
        XCTAssertEqual(Repeat.ordinal(22), "22nd")
        XCTAssertEqual(Repeat.weeklyOn(Set(1...7)), .everyDay)
    }

    // MARK: Scheduling

    func testEachTimeFollowsItsOwnDays() {
        // 4:00 AM Tuesdays, 4:45 AM every other day.
        let c = Checkin(id: "w", name: "Wake", times: [
            AlarmTime(4, 0, days: Repeat(Repeat.weekly, [2])),
            AlarmTime(4, 45, days: Repeat(Repeat.weekly, [1, 3, 4, 5, 6, 7])),
        ])
        let next = Scheduler.occurrences(c, now: at(2026, 11, 9, 12), calendar: cal, count: 3) // Monday noon
        XCTAssertEqual(next.map { Days.string($0.at, calendar: cal) + " " + $0.timeKey },
                       ["2026-11-10 04:00", "2026-11-11 04:45", "2026-11-12 04:45"])
        // 4:00 and 4:45 AM count for the day itself (the rollover is before 4 AM).
        XCTAssertEqual(next[0].day, "2026-11-10")
    }

    func testNextAlarmWrapsAndSkipsClosedTimes() {
        let c = Checkin(id: "c", name: "C", times: [AlarmTime(7, 0), AlarmTime(21, 30)])
        let now = at(2026, 11, 9, 22, 0)
        XCTAssertEqual(Scheduler.next(c, now: now, calendar: cal)?.timeKey, "07:00")
        let skipped = Scheduler.next(c, now: now, calendar: cal) { $0.timeKey == "07:00" }
        XCTAssertEqual(skipped?.timeKey, "21:30")
        XCTAssertTrue(Scheduler.occurrences(Checkin(id: "e", name: "E", times: []), now: now, calendar: cal).isEmpty)
    }

    func testDaylightSavingKeepsTheClockTime() {
        // US clocks go back at 2 AM on Nov 1, 2026: Oct 31 7:00 AM to Nov 1 7:00 AM is 25 hours.
        let c = Checkin(id: "c", name: "C", times: [AlarmTime(7, 0)])
        let next = Scheduler.occurrences(c, now: at(2026, 10, 30, 12), calendar: cal, count: 2)
        XCTAssertEqual(next.map { cal.component(.hour, from: $0.at) }, [7, 7])
        XCTAssertEqual(next[1].at.timeIntervalSince(next[0].at), 25 * 3600)
    }

    func testMonthlyLooksFarAhead() {
        let c = Checkin(id: "c", name: "C", times: [AlarmTime(9, 0)], repeat: Repeat(Repeat.monthly, [29]))
        // February 2027 has no 29th: it's skipped that month, not moved.
        let next = Scheduler.occurrences(c, now: at(2027, 1, 30), calendar: cal, count: 2)
        XCTAssertEqual(next.map { Days.string($0.at, calendar: cal) }, ["2027-03-29", "2027-04-29"])
    }

    // MARK: Math

    func testMathAnswersAreRight() {
        var g = SystemRandomNumberGenerator()
        for level in 1...4 {
            for _ in 0..<200 {
                let p = MathGen.make(level, using: &g)
                let value = p.text.components(separatedBy: " + ").map { term in
                    term.components(separatedBy: " × ").map { Int($0)! }.reduce(1, *)
                }.reduce(0, +)
                XCTAssertEqual(value, p.answer, p.text)
                XCTAssertGreaterThan(p.answer, 0)
            }
        }
        XCTAssertEqual(MathGen.levelName(4), "Very hard")
        XCTAssertEqual(MathGen.levelName(99), "Very hard")
    }

    // MARK: Stats and scores

    private let soda = Question(id: "soda", text: "Soda?")

    private func sodaCheckin() -> Checkin {
        Checkin(id: "s", name: "Soda", steps: [StepConfig(id: "q", kind: StepConfig.questions, questions: [soda])])
    }

    func testDayMarks() {
        XCTAssertEqual(Stats.dayMark(soda, ["12:00": SlotResult(SlotResult.answered, answers: ["soda": false])]), .onTrack)
        XCTAssertEqual(Stats.dayMark(soda, [
            "08:00": SlotResult(SlotResult.answered, answers: ["soda": false]),
            "20:00": SlotResult(SlotResult.answered, answers: ["soda": true]),
        ]), .offTrack)
        XCTAssertEqual(Stats.dayMark(soda, ["12:00": SlotResult(SlotResult.rang)]), .offTrack, "missed")
        XCTAssertEqual(Stats.dayMark(soda, ["12:00": SlotResult(SlotResult.skipped)]), .skipped)
        XCTAssertEqual(Stats.dayMark(soda, [:]), .none)
    }

    func testScores() {
        let c = sodaCheckin()
        let days = ["d4", "d3", "d2", "d1"]
        let results: History = [
            "d1": ["12:00": SlotResult(SlotResult.answered, answers: ["soda": false])],
            "d2": ["12:00": SlotResult(SlotResult.answered, answers: ["soda": true])],
            "d3": ["12:00": SlotResult(SlotResult.skipped)],
        ]
        XCTAssertEqual(Score.of(c, results, days: days), 0.5, "skipped days don't count")
        XCTAssertNil(Score.of(c, [:], days: days))
        XCTAssertEqual(Score.average([0.5, nil, 1.0]), 0.75)
        XCTAssertNil(Score.average([nil]))
        XCTAssertEqual(Score.percent(0.666), "67%")
        XCTAssertEqual(Stats.line(c, results, days: days), "Last 4 days: 2 logged, 1 fully on track")
    }

    func testScoreColorsRunFromBadToGood() {
        let bad = Score.RGB(0xD3, 0x2F, 0x2F), good = Score.RGB(0x2E, 0x7D, 0x32)
        XCTAssertEqual(Score.color(0, bad: bad, good: good), bad)
        XCTAssertEqual(Score.color(1, bad: bad, good: good), good)
        let mid = Score.color(0.5, bad: bad, good: good)
        XCTAssertGreaterThan(mid.r, mid.b, "amber, not a muddy brown")
        XCTAssertGreaterThan(mid.g, mid.b)
    }

    // MARK: Logging

    func testLoggingRules() {
        var d = GistData(checkins: [sodaCheckin(), Checkin(id: "soft", name: "Soft", commitment: Checkin.soft)])
        d.markRang(day: "2026-11-09", checkinId: "s", time: "12:00")
        d.saveAnswers(day: "2026-11-09", checkinId: "s", time: "12:00", answers: ["soda": false])
        d.markRang(day: "2026-11-09", checkinId: "s", time: "12:00")
        XCTAssertEqual(d.result(day: "2026-11-09", checkinId: "s", time: "12:00")?.status, SlotResult.answered, "rang doesn't overwrite")
        XCTAssertTrue(d.isClosed(day: "2026-11-09", checkinId: "s", time: "12:00"))
        d.saveAnswers(day: "2026-11-09", checkinId: "s", time: "12:00", answers: ["other": true])
        XCTAssertEqual(d.result(day: "2026-11-09", checkinId: "s", time: "12:00")?.answers, ["soda": false, "other": true], "merged")

        d.markDismissed(day: "2026-11-09", checkinId: "soft", time: "12:00")
        XCTAssertEqual(d.result(day: "2026-11-09", checkinId: "soft", time: "12:00")?.soft, true)
        XCTAssertTrue(d.allResults("soft").isEmpty)
        XCTAssertEqual(d.allResults("soft", includeSoft: true).count, 1)

        d.clearResult(day: "2026-11-09", checkinId: "soft", time: "12:00")
        XCTAssertNil(d.results["2026-11-09"]?["soft"])
    }

    func testSavedDataRoundTrips() throws {
        var d = GistData()
        d.saveAnswers(day: "2026-11-09", checkinId: "morning", time: "07:00", answers: ["latefood": true], early: true)
        let back = try GistData.from(try YAMLCodec.load(try YAMLCodec.dump(d.json)))
        XCTAssertEqual(back, d)
    }

    // MARK: Sorting and grouping

    func testSorting() {
        let list = [Checkin(id: "a", name: "walk"), Checkin(id: "b", name: "Bed"),
                    Checkin(id: "c", name: "Coffee"), Checkin(id: "d", name: "ab")]
        XCTAssertEqual(Grouping.sort(list, Grouping.sortCustom, next: { _ in nil }, score: { _ in nil }).map { $0.id }, ["a", "b", "c", "d"])
        let next: [String: Date] = ["a": at(2026, 1, 1, 3), "c": at(2026, 1, 1, 1)]
        XCTAssertEqual(Grouping.sort(list, Grouping.sortNext, next: { next[$0.id] }, score: { _ in nil }).map { $0.id },
                       ["c", "a", "b", "d"], "no next ring goes last, in your order")
        let score: [String: Double] = ["a": 0.9, "b": 0.2, "d": 0.2]
        XCTAssertEqual(Grouping.sort(list, Grouping.sortAttention, next: { _ in nil }, score: { score[$0.id] }).map { $0.id },
                       ["b", "d", "a", "c"], "ties keep your order")
        XCTAssertEqual(Grouping.sort(list, Grouping.sortName, next: { _ in nil }, score: { _ in nil }).map { $0.id }, ["d", "b", "c", "a"])
    }

    func testGroupingByCategory() {
        let list = [Checkin(id: "1", name: "1", category: "Health"), Checkin(id: "2", name: "2"),
                    Checkin(id: "3", name: "3", category: "fitness"), Checkin(id: "4", name: "4", category: "health ")]
        let groups = Grouping.byCategory(list)
        XCTAssertEqual(groups.map { $0.0 }, ["fitness", "Health", "Other"])
        XCTAssertEqual(groups[1].1.map { $0.id }, ["1", "4"])
        XCTAssertEqual(Grouping.byCategory(list, order: ["health"]).map { $0.0 }, ["Health", "fitness", "Other"])
        XCTAssertEqual(Grouping.categories(list), ["fitness", "Health"])
        XCTAssertTrue(Grouping.allFolded(groups: ["a"], foldedGroups: ["a", "b"], ids: ["x"], foldedIds: ["x"]))
        XCTAssertFalse(Grouping.allFolded(groups: ["a"], foldedGroups: [], ids: ["x"], foldedIds: ["x"]))
    }

    func testPlacesAndRunText() {
        XCTAssertNotNil(Place.parseCoordinates("(40.7128, -74.0060)"))
        XCTAssertNil(Place.parseCoordinates("91, 0"))
        XCTAssertEqual(Trigger.distanceM(40.7128, -74.006, 40.7128, -74.006), 0)
        XCTAssertEqual(Trigger.distanceM(0, 0, 0, 1), 111_195, accuracy: 10)
        XCTAssertEqual(Trigger.runForText(150), "2 hours 30 minutes")
        XCTAssertEqual(Trigger.runForText(1), "1 minute")
    }
}
