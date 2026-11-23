import XCTest
@testable import GistCore

/// Imports the sample backups in the repository's shared/backups folder, the same files the
/// Android app's SharedBackupTest imports, so a backup made on one phone imports on the other.
final class SharedBackupTests: XCTestCase {
    /// shared/backups, found from this file: ios/GistCore/Tests/GistCoreTests/<this file>.
    static let samples: URL = {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        return url.appendingPathComponent("shared/backups")
    }()

    private func sample(_ name: String) throws -> String {
        try String(contentsOf: Self.samples.appendingPathComponent(name), encoding: .utf8)
    }

    private func importAll(_ contents: Backup.Contents, into data: inout GistData) {
        Backup.importing(contents, choices: contents.checkins.map { Backup.ImportChoice($0.id, mode: .new, withHistory: true) },
                         into: &data)
    }

    func testEveryFeatureImports() throws {
        let contents = try Backup.parse(try sample("every-feature.yaml"))
        var data = GistData(checkins: [])
        importAll(contents, into: &data)
        XCTAssertEqual(data.checkins.map { $0.id }, ["wake01", "gym01", "run01"])

        // Times with their own days, a monthly time with a place condition.
        let wake = try XCTUnwrap(data.checkin("wake01"))
        XCTAssertEqual(wake.times.map { $0.description }, ["04:00", "04:45", "09:00"])
        XCTAssertEqual(wake.times[0].days, Repeat(Repeat.weekly, [2]))
        XCTAssertEqual(wake.times[1].days, Repeat(Repeat.weekly, [1, 3, 4, 5, 6, 7]))
        XCTAssertEqual(wake.times[2].days, Repeat(Repeat.monthly, [1, 15, Repeat.lastDay]))
        XCTAssertEqual(wake.times[2].where, Trigger.at)
        XCTAssertEqual(wake.times[2].place?.name, "Home")
        XCTAssertEqual(wake.times[2].place?.radiusM, 200)
        XCTAssertEqual(wake.times[2].place?.lat, 40.7128)
        // Every kind of step, in order.
        XCTAssertEqual(wake.steps.map { $0.kind }, [StepConfig.math, StepConfig.scan, StepConfig.questions])
        XCTAssertEqual(wake.steps[0].mathCount, 10)
        XCTAssertEqual(wake.steps[0].mathLevel, 4)
        XCTAssertEqual(wake.steps[1].scanCode, "GIST-SINK-0001")
        XCTAssertEqual(wake.steps[1].scanLabel, "Bathroom sink")
        XCTAssertEqual(wake.questions.map { $0.id }, ["soda", "water", "weight", "note"])
        XCTAssertEqual(wake.questions.map { $0.type }, [Question.yesNo, Question.yesNo, Question.number, Question.text])
        XCTAssertFalse(wake.questions[0].yesIsGood)
        XCTAssertTrue(wake.questions[1].yesIsGood)
        XCTAssertEqual(wake.background.caption, "Future you says thanks")
        XCTAssertEqual(wake.category, "Morning routine")
        XCTAssertTrue(wake.counted)

        // A soft check-in set off by arriving somewhere, in a time window.
        let gym = try XCTUnwrap(data.checkin("gym01"))
        XCTAssertTrue(gym.isSoft)
        XCTAssertEqual(gym.snoozeMinutes, 15)
        XCTAssertTrue(gym.times.isEmpty)
        XCTAssertEqual(gym.events.count, 1)
        let arrive = gym.events[0]
        XCTAssertEqual(arrive.kind, Trigger.arrive)
        XCTAssertEqual(arrive.place?.name, "Gym")
        XCTAssertEqual(arrive.days, Repeat(Repeat.weekly, [1, 3, 5]))
        XCTAssertEqual(arrive.windowStart?.description, "05:00")
        XCTAssertEqual(arrive.windowEnd?.description, "10:00")

        // A voice line partway into a run.
        let run = try XCTUnwrap(data.checkin("run01"))
        XCTAssertTrue(run.isVoice)
        XCTAssertFalse(run.enabled)
        XCTAssertEqual(run.voiceText, "Is that Forrest Gump?")
        XCTAssertFalse(run.voiceHeadphonesOnly)
        XCTAssertEqual(run.events.first?.kind, Trigger.runFor)
        XCTAssertEqual(run.events.first?.minRunMinutes, 60)
        XCTAssertEqual(run.events.first?.oncePerDay, false)

        // History: each status, and a soft result kept out of the stats.
        let answered = try XCTUnwrap(data.result(day: "2026-11-08", checkinId: "wake01", time: "04:45"))
        XCTAssertEqual(answered.status, SlotResult.answered)
        XCTAssertEqual(answered.answers, ["soda": false, "water": true])
        XCTAssertEqual(answered.values, ["weight": "182.4", "note": "Slept well"])
        // Typed questions: answered is on track, left empty is off track.
        let weight = wake.questions[2]
        XCTAssertEqual(Stats.dayMark(weight, data.results["2026-11-08"]?["wake01"] ?? [:]), .onTrack)
        XCTAssertEqual(data.result(day: "2026-11-11", checkinId: "wake01", time: "04:45")?.values, ["weight": "", "note": ""])
        XCTAssertEqual(Stats.dayMark(weight, data.results["2026-11-11"]?["wake01"] ?? [:]), .offTrack)
        XCTAssertEqual(data.result(day: "2026-11-09", checkinId: "wake01", time: "04:45")?.status, SlotResult.rang)
        XCTAssertEqual(data.result(day: "2026-11-10", checkinId: "wake01", time: "04:00")?.status, SlotResult.skipped)
        let dismissed = try XCTUnwrap(data.result(day: "2026-11-08", checkinId: "gym01", time: "07:12"))
        XCTAssertEqual(dismissed.status, SlotResult.dismissed)
        XCTAssertTrue(dismissed.soft)
        XCTAssertTrue(data.allResults("gym01").isEmpty, "soft: no stats")
        XCTAssertEqual(contents.days, 4)
    }

    func testEveryFeatureSurvivesARoundTrip() throws {
        // Import the sample, export it again, and import that: nothing is lost on the way.
        let first = try Backup.parse(try sample("every-feature.yaml"))
        var data = GistData(checkins: [])
        importAll(first, into: &data)
        let exported = try Backup.export(data)
        let again = try Backup.parse(exported)
        var copy = GistData(checkins: [])
        importAll(again, into: &copy)
        XCTAssertEqual(copy, data)
        XCTAssertEqual(again.days, first.days)
    }

    func testTasksImport() throws {
        let contents = try Backup.parse(try sample("tasks.yaml"))
        var data = GistData(checkins: [])
        importAll(contents, into: &data)
        XCTAssertEqual(data.checkins.map { $0.id }, ["plan01", "call01", "shed01", "milk01"])

        // The pick-tasks step, a description and a time commitment.
        let plan = try XCTUnwrap(data.checkin("plan01"))
        XCTAssertEqual(plan.steps.first?.kind, StepConfig.pickTasks)
        XCTAssertEqual(plan.steps.first?.pickCount, 3)
        XCTAssertEqual(plan.description, "Look at the backlog.\nPick what fits today.")
        XCTAssertEqual(plan.timeCommitment, "minutes")
        XCTAssertFalse(plan.isTask)

        // An active task on a specific date, with No alarm.
        let call = try XCTUnwrap(data.checkin("call01"))
        XCTAssertTrue(call.isActiveTask)
        XCTAssertTrue(call.isQuiet)
        XCTAssertEqual(call.times.first?.days, Repeat.onDate("2026-11-24"))
        XCTAssertEqual(call.backlogAt, 1763800000000)
        XCTAssertEqual(Checkin.timeCommitmentName(call.timeCommitment), "An hour")

        // The backlog and a done task.
        XCTAssertEqual(data.backlog.map { $0.id }, ["shed01"])
        XCTAssertEqual(data.backlog.first?.backlogAt, 1763850000000, "a restored time is kept")
        XCTAssertEqual(data.doneTasks.map { $0.id }, ["milk01"])
        XCTAssertEqual(data.checkin("milk01")?.doneAt, 1763900000000)

        // Timing: rang and took (with and without getting started).
        let timed = try XCTUnwrap(data.result(day: "2026-11-22", checkinId: "plan01", time: "07:30"))
        XCTAssertEqual(timed.rangAt, 1763793000000)
        XCTAssertEqual(timed.took, Took(start: 70, steps: 185))
        XCTAssertEqual(data.result(day: "2026-11-23", checkinId: "plan01", time: "07:30")?.took, Took(start: nil, steps: 120))
        XCTAssertEqual(data.result(day: "2026-11-21", checkinId: "plan01", time: "07:30")?.rangAt, 1763706600000)
        let days = Timing.days(data.allResults("plan01"), ["2026-11-23", "2026-11-22", "2026-11-21"])
        XCTAssertEqual(Timing.line(days), "Getting started 1m 10s · doing it 2m 32s")
    }

    func testTasksSurviveARoundTripAsVersion5() throws {
        let first = try Backup.parse(try sample("tasks.yaml"))
        var data = GistData(checkins: [])
        importAll(first, into: &data)
        let exported = try Backup.export(data)
        XCTAssertTrue(exported.contains("version: 5"))
        let again = try Backup.parse(exported)
        var copy = GistData(checkins: [])
        importAll(again, into: &copy)
        XCTAssertEqual(copy, data)
    }

    func testEverySampleParses() throws {
        let files = try FileManager.default.contentsOfDirectory(atPath: Self.samples.path).filter { $0.hasSuffix(".yaml") }
        XCTAssertFalse(files.isEmpty, "no samples found in \(Self.samples.path)")
        for f in files {
            XCTAssertNoThrow(try Backup.parse(try sample(f)), f)
        }
    }
}
