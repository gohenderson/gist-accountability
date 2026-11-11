import XCTest
@testable import GistCore

final class YAMLCodecTests: XCTestCase {
    func testPlainScalarsFollowTheCoreSchema() throws {
        let v = try YAMLCodec.load("""
        time: 20:09
        day: 2026-11-08
        n: 12
        neg: -3
        x: 4.5
        yes: true
        no: False
        nothing: ~
        quoted: '12'
        word: hello
        """)
        let o = try XCTUnwrap(v.objectValue)
        XCTAssertEqual(o["time"], .string("20:09"), "not a YAML 1.1 sexagesimal number")
        XCTAssertEqual(o["day"], .string("2026-11-08"), "not a date")
        XCTAssertEqual(o["n"], .int(12))
        XCTAssertEqual(o["neg"], .int(-3))
        XCTAssertEqual(o["x"], .double(4.5))
        XCTAssertEqual(o["yes"], .bool(true))
        XCTAssertEqual(o["no"], .bool(false))
        XCTAssertEqual(o["nothing"], .null)
        XCTAssertEqual(o["quoted"], .string("12"))
        XCTAssertEqual(o["word"], .string("hello"))
    }

    func testTextThatLooksLikeSomethingElseIsQuoted() throws {
        let texts = ["true", "False", "12", "-1.5", "", "~", "null", "20:09", "2026-11-08", " padded ", "a: b", "# not a comment",
                     "line one\nline two", "Ünïcödé ✓", "'quotes' and \"quotes\""]
        let original = JSON.object(JSONObject(texts.enumerated().map { ("k\($0.offset)", JSON.string($0.element)) }))
        let back = try YAMLCodec.load(try YAMLCodec.dump(original))
        XCTAssertEqual(back, original)
    }

    func testOrderAndTypesRoundTrip() throws {
        let original = JSON.object(JSONObject([
            ("z", .int(1)), ("a", .bool(false)), ("m", .double(40.7128)),
            ("list", .array([.int(1), .string("two"), .object(JSONObject())])),
            ("empty", .array([])),
        ]))
        let text = try YAMLCodec.dump(original, header: "Line one\nLine two")
        XCTAssertTrue(text.hasPrefix("# Line one\n# Line two\n"))
        let back = try YAMLCodec.load(text)
        XCTAssertEqual(back, original)
        XCTAssertEqual(back.objectValue?.keys, ["z", "a", "m", "list", "empty"])
    }

    func testDuplicateKeysAndBadYAMLAreRejected() {
        XCTAssertThrowsError(try YAMLCodec.load("a: 1\na: 2\n"))
        XCTAssertThrowsError(try YAMLCodec.load("a: [1, 2\n")) { error in
            XCTAssertTrue((error as? BackupError)?.message.hasPrefix("This isn't a Gist backup file") == true)
        }
    }
}

final class BackupTests: XCTestCase {
    private func wake() -> Checkin {
        Checkin(id: "w", name: "Wake", times: [AlarmTime(6, 0)], steps: [
            StepConfig(id: "q", kind: StepConfig.questions, questions: [Question(id: "soda", text: "Soda?")]),
        ])
    }

    func testRejectsFilesThatArentBackups() {
        func message(_ text: String) -> String? {
            do {
                _ = try Backup.parse(text)
                return nil
            } catch {
                return (error as? BackupError)?.message
            }
        }
        XCTAssertEqual(message("hello: world"), "This isn't a Gist backup file.")
        XCTAssertEqual(message("format: gist-backup\nversion: 4\ncheckins: []"),
                       "This backup is from a newer version of Gist. Update the app first.")
        XCTAssertEqual(message("format: gist-backup\nversion: 2\ncheckins: []"),
                       "This backup is from an older version of Gist that this one can't read.")
        XCTAssertEqual(message("format: gist-backup\nversion: 3\ncheckins:\n- {id: a, name: A}\n- {id: a, name: B}"),
                       "The backup has duplicate check-ins.")
    }

    func testExportsOnlyWhatsChosen() throws {
        var data = GistData(checkins: [wake(), Checkin(id: "e", name: "Evening")])
        data.saveAnswers(day: "2026-11-08", checkinId: "w", time: "06:00", answers: ["soda": false])
        data.markDone(day: "2026-11-08", checkinId: "e", time: "12:00")
        let text = try Backup.export(data, choices: [Backup.ExportChoice("w", withHistory: false)])
        let c = try Backup.parse(text)
        XCTAssertEqual(c.checkins.map { $0.id }, ["w"])
        XCTAssertEqual(c.days, 0, "no history")
        let all = try Backup.parse(try Backup.export(data))
        XCTAssertEqual(all.historyDays("w"), 1)
        XCTAssertEqual(all.history("e")["2026-11-08"]?["12:00"]?.status, SlotResult.done)
    }

    func testMediaRoundTrips() throws {
        let data = GistData(checkins: [wake()])
        let media = Backup.Media(photos: [Data([1, 2, 3]), Data([4])], video: Data([9, 9]), voice: nil)
        let c = try Backup.parse(try Backup.export(data, media: { _ in media }))
        XCTAssertEqual(c.media["w"], media)
    }

    func testImportModes() throws {
        let file = try Backup.parse(try Backup.export({ () -> GistData in
            var d = GistData(checkins: [wake()])
            d.saveAnswers(day: "2026-11-08", checkinId: "w", time: "06:00", answers: ["soda": true])
            return d
        }()))

        // New: keeps its id.
        var empty = GistData(checkins: [])
        Backup.importing(file, choices: [Backup.ImportChoice("w", mode: .new, withHistory: true)], into: &empty)
        XCTAssertEqual(empty.checkins.map { $0.id }, ["w"])
        XCTAssertEqual(empty.history("w").count, 1)

        // Copy: a new id and "(imported)".
        var mine = GistData(checkins: [wake()])
        mine.saveAnswers(day: "2026-11-07", checkinId: "w", time: "06:00", answers: ["soda": false])
        var copied = mine
        var n = 0
        Backup.importing(file, choices: [Backup.ImportChoice("w", mode: .copy, withHistory: true)], into: &copied,
                         newId: { n += 1; return "new\(n)" })
        XCTAssertEqual(copied.checkins.map { $0.name }, ["Wake", "Wake (imported)"])
        XCTAssertEqual(copied.checkins[1].id, "new1")
        XCTAssertEqual(copied.history("w").count, 1, "yours untouched")

        // Update: the file's history is added to yours.
        var updated = mine
        Backup.importing(file, choices: [Backup.ImportChoice("w", mode: .update, withHistory: true)], into: &updated)
        XCTAssertEqual(updated.history("w").keys.sorted(), ["2026-11-07", "2026-11-08"])

        // Replace: only the file's history.
        var replaced = mine
        Backup.importing(file, choices: [Backup.ImportChoice("w", mode: .replace, withHistory: true)], into: &replaced)
        XCTAssertEqual(replaced.history("w").keys.sorted(), ["2026-11-08"])

        // Replace without history: none.
        var bare = mine
        Backup.importing(file, choices: [Backup.ImportChoice("w", mode: .replace, withHistory: false)], into: &bare)
        XCTAssertTrue(bare.history("w").isEmpty)

        // Matching by name when the ids differ.
        var renamed = GistData(checkins: [Checkin(id: "other", name: "wake")])
        Backup.importing(file, choices: [Backup.ImportChoice("w", mode: .update, withHistory: false)], into: &renamed)
        XCTAssertEqual(renamed.checkins.map { $0.id }, ["other"])
        XCTAssertEqual(renamed.checkins[0].name, "Wake")
    }
}
