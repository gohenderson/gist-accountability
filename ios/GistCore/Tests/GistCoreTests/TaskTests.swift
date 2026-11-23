import XCTest
@testable import GistCore

/// Tasks, the backlog, pick-tasks, "On a specific date" and timing: the Android app's rules.
final class TaskTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_763_900_000)

    override func setUp() {
        super.setUp()
        let now = start
        GistData.clock = { now }
    }

    override func tearDown() {
        GistData.clock = { Date() }
        super.tearDown()
    }

    private func task(_ id: String, on: Bool = false) -> Checkin {
        var t = Library.task(newId: id)
        t.name = id
        t.enabled = on
        return t
    }

    func testANewTaskIsASoftQuietBacklogItem() {
        let t = Library.task(newId: "t")
        XCTAssertTrue(t.isTask)
        XCTAssertTrue(t.inBacklog)
        XCTAssertTrue(t.isQuiet)
        XCTAssertTrue(t.isSoft)
        XCTAssertTrue(t.hasNoTriggers)
        XCTAssertEqual(t.category, "Tasks")
    }

    func testGoingIntoTheBacklogIsStamped() {
        let now = Int(start.timeIntervalSince1970 * 1000)
        var data = GistData(checkins: [])
        var restored = task("restored")
        restored.backlogAt = 5
        data.checkins += [task("new"), restored, task("active", on: true)]
        XCTAssertEqual(data.checkin("new")?.backlogAt, now)
        XCTAssertEqual(data.checkin("restored")?.backlogAt, 5, "its own time is kept")
        XCTAssertNil(data.checkin("active")?.backlogAt)
        XCTAssertEqual(data.tasksBacklogged(inLast: 7), 1)

        // Turning an active task off puts it in the backlog.
        data.update("active") { $0.enabled = false }
        XCTAssertEqual(data.checkin("active")?.backlogAt, now)
        // Picking it (on again) still counts as added this week.
        data.update("active") { $0.enabled = true }
        XCTAssertEqual(data.tasksBacklogged(inLast: 7), 2)
    }

    func testLoadingSavedDataDoesntStamp() throws {
        let saved = GistData(checkins: [task("t")]).json
        let loaded = try GistData.from(saved)
        XCTAssertNil(loaded.checkin("t")?.backlogAt)
    }

    func testDoingATaskCompletesIt() {
        for finish in ["done", "answered", "dismissed"] {
            var data = GistData(checkins: [task("t", on: true)])
            switch finish {
            case "done": data.markDone(day: "2026-11-23", checkinId: "t", time: "")
            case "answered": data.saveAnswers(day: "2026-11-23", checkinId: "t", time: "", answers: [:])
            default: data.markDismissed(day: "2026-11-23", checkinId: "t", time: "")
            }
            let t = data.checkin("t")!
            XCTAssertTrue(t.taskDone, finish)
            XCTAssertFalse(t.enabled, finish)
            XCTAssertEqual(data.tasksDone(inLast: 7), 1, finish)
        }
        // Ringing or skipping doesn't.
        var data = GistData(checkins: [task("t", on: true)])
        data.markRang(day: "2026-11-23", checkinId: "t", time: "09:00")
        data.markSkipped(day: "2026-11-24", checkinId: "t", time: "09:00")
        XCTAssertFalse(data.checkin("t")!.taskDone)
        // Reopening puts it back on.
        data.completeTask("t")
        data.reopenTask("t")
        XCTAssertTrue(data.checkin("t")!.isActiveTask)
    }

    func testDoneTasksOlderThanAWeekDontCount() {
        var old = task("old")
        old.doneAt = Int(start.timeIntervalSince1970 * 1000) - 8 * 24 * 60 * 60 * 1000
        let data = GistData(checkins: [old])
        XCTAssertEqual(data.tasksDone(inLast: 7), 0)
    }

    func testPickTasksPicksTheDifference() {
        var data = GistData(checkins: [task("a", on: true), task("b"), task("c"), task("d")])
        XCTAssertEqual(data.tasksToPick(3), 2)
        XCTAssertEqual(data.tasksToPick(1), 0, "enough are active already: skipped")
        XCTAssertEqual(data.tasksToPick(10), 3, "no more than the backlog has")
        data.update("b") { $0.enabled = true }
        data.update("c") { $0.enabled = true }
        XCTAssertEqual(data.tasksToPick(3), 0)
    }

    func testOnASpecificDate() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let r = Repeat.onDate("2026-11-24")
        XCTAssertTrue(r.matches(Days.date("2026-11-24", calendar: cal)!, calendar: cal))
        XCTAssertFalse(r.matches(Days.date("2026-11-25", calendar: cal)!, calendar: cal))
        XCTAssertFalse(r.matches(Days.date("2025-11-24", calendar: cal)!, calendar: cal))
        let today = Days.date("2026-11-23", calendar: cal)!
        XCTAssertEqual(Repeat.dateText("2026-11-24", today: today, calendar: cal), "Tue, Nov 24")
        XCTAssertEqual(Repeat.dateText("2027-01-05", today: today, calendar: cal), "Tue, Jan 5, 2027")
        XCTAssertEqual(Repeat.from(r.json.objectValue), r)
        XCTAssertEqual(Repeat.from(JSONObject([("kind", .string("once")), ("date", .string("nope"))])), .everyDay)
    }

    func testTimingAndTook() {
        var data = GistData(checkins: [Checkin(id: "c", name: "C")])
        data.markRang(day: "2026-11-23", checkinId: "c", time: "12:00", at: 1_000_000)
        let took = data.took(day: "2026-11-23", checkinId: "c", time: "12:00", startedAt: 1_090_000, doneAt: 1_300_000)
        XCTAssertEqual(took, Took(start: 90, steps: 210))
        data.markDone(day: "2026-11-23", checkinId: "c", time: "12:00", took: took)
        XCTAssertEqual(data.result(day: "2026-11-23", checkinId: "c", time: "12:00")?.took, took)
        // Done early: no getting started.
        XCTAssertEqual(data.took(day: "2026-11-24", checkinId: "c", time: "12:00", startedAt: 0, doneAt: 60_000),
                       Took(start: nil, steps: 60))
        // Soft check-ins aren't timed.
        var soft = Checkin(id: "s", name: "S")
        soft.commitment = Checkin.soft
        data.checkins.append(soft)
        XCTAssertNil(data.took(day: "2026-11-23", checkinId: "s", time: "12:00", startedAt: 0))
        XCTAssertEqual(Took.text(42), "42s")
        XCTAssertEqual(Took.text(185), "3m 05s")
        XCTAssertEqual(Took.text(3720), "1h 02m")
        XCTAssertEqual(Timing.median([5, 1, 3]), 3)
        XCTAssertEqual(Timing.median([1, 2, 3, 4]), 2)
        XCTAssertNil(Timing.line([]))
        XCTAssertEqual(Timing.line([Timing.Day(day: "d", start: nil, steps: 30)]), "Doing it 30s")
    }

    func testBackupsAreVersion4UnlessSomethingNeeds5() throws {
        let plain = try Backup.export(GistData(checkins: [Checkin(id: "c", name: "C")]))
        XCTAssertTrue(plain.contains("version: 4"))
        var described = Checkin(id: "c", name: "C")
        described.description = "Notes"
        described.timeCommitment = "week"
        let still4 = try Backup.export(GistData(checkins: [described]))
        XCTAssertTrue(still4.contains("version: 4"), "description and time commitment don't need 5")
        let back = try Backup.parse(still4).checkins.first!
        XCTAssertEqual(back.description, "Notes")
        XCTAssertEqual(back.timeCommitment, "week")
        XCTAssertTrue(try Backup.export(GistData(checkins: [task("t")])).contains("version: 5"))
        var picker = Checkin(id: "p", name: "P")
        picker.steps = [StepConfig(kind: StepConfig.pickTasks, pickCount: 2)]
        XCTAssertTrue(try Backup.export(GistData(checkins: [picker])).contains("version: 5"))
        var dated = Checkin(id: "d", name: "D", times: [AlarmTime(9, 0, days: .onDate("2026-12-01"))])
        dated.enabled = true
        XCTAssertTrue(try Backup.export(GistData(checkins: [dated])).contains("version: 5"))
    }

    func testTheStartTabs() {
        var done = task("done")
        done.doneAt = 1
        let list = [Checkin(id: "c", name: "C"), task("active", on: true), task("back"), done]
        XCTAssertEqual(StartTab.checkins.items(list).map { $0.id }, ["c"])
        XCTAssertEqual(StartTab.tasks.items(list).map { $0.id }, ["active"])
        XCTAssertEqual(StartTab.backlog.items(list).map { $0.id }, ["back"])
    }

    func testAFollowUpCopiesTheTaskButNotItsTimes() {
        var src = task("patch", on: true)
        src.name = "Sand the pool patch"
        src.description = "Use the 120 grit."
        src.category = "Pool"
        src.timeCommitment = "hours"
        src.steps = [StepConfig(id: "s1", kind: StepConfig.math, mathCount: 2)]
        src.times = [AlarmTime(9, 0, days: .onDate("2026-11-24"))]
        var data = GistData(checkins: [Checkin(id: "other", name: "Other"), src, Checkin(id: "last", name: "Last")])
        let f = data.createFollowUp("patch", active: false, newId: "f1")!
        XCTAssertEqual(f.name, "(Follow-Up) Sand the pool patch")
        XCTAssertEqual(f.description, "Use the 120 grit.")
        XCTAssertEqual(f.category, "Pool")
        XCTAssertEqual(f.timeCommitment, "hours")
        XCTAssertTrue(f.isQuiet)
        XCTAssertEqual(f.steps.map { $0.kind }, [StepConfig.math])
        XCTAssertNotEqual(f.steps.first?.id, "s1")
        XCTAssertTrue(f.times.isEmpty && f.events.isEmpty)
        XCTAssertTrue(data.checkin("f1")!.inBacklog)
        XCTAssertNotNil(data.checkin("f1")!.backlogAt, "stamped going into the backlog")
        XCTAssertEqual(f.followsUp, "patch")
        XCTAssertEqual(data.checkins.map { $0.id }, ["other", "patch", "f1", "last"])
        XCTAssertEqual(data.followUps(of: "patch").map { $0.id }, ["f1"])
        let g = data.createFollowUp("f1", active: true, newId: "f2")!
        XCTAssertEqual(g.name, "(Follow-Up) Sand the pool patch", "one prefix")
        XCTAssertTrue(g.enabled)
        // Kept in the JSON (and so in backups).
        XCTAssertEqual(try? Checkin.from(g.json.objectValue!).followsUp, "f1")
    }
}
