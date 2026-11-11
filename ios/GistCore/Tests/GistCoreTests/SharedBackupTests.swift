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
        XCTAssertEqual(wake.questions.map { $0.id }, ["soda", "water"])
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
        XCTAssertEqual(data.result(day: "2026-11-09", checkinId: "wake01", time: "04:45")?.status, SlotResult.rang)
        XCTAssertEqual(data.result(day: "2026-11-10", checkinId: "wake01", time: "04:00")?.status, SlotResult.skipped)
        let dismissed = try XCTUnwrap(data.result(day: "2026-11-08", checkinId: "gym01", time: "07:12"))
        XCTAssertEqual(dismissed.status, SlotResult.dismissed)
        XCTAssertTrue(dismissed.soft)
        XCTAssertTrue(data.allResults("gym01").isEmpty, "soft: no stats")
        XCTAssertEqual(contents.days, 3)
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

    func testEverySampleParses() throws {
        let files = try FileManager.default.contentsOfDirectory(atPath: Self.samples.path).filter { $0.hasSuffix(".yaml") }
        XCTAssertFalse(files.isEmpty, "no samples found in \(Self.samples.path)")
        for f in files {
            XCTAssertNoThrow(try Backup.parse(try sample(f)), f)
        }
    }
}
