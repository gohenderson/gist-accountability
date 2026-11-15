import XCTest
@testable import GistCore

final class TypedQuestionTests: XCTestCase {
    let weight = Question(id: "w", text: "What did you weigh?", yesIsGood: true, type: Question.number)

    func testCleaningNumbers() {
        XCTAssertEqual(Question.cleanNumber("182.4"), "182.4")
        XCTAssertEqual(Question.cleanNumber(" 182.40 "), "182.4")
        XCTAssertEqual(Question.cleanNumber("180"), "180")
        XCTAssertEqual(Question.cleanNumber("180.0"), "180")
        XCTAssertEqual(Question.cleanNumber("1,5"), "1.5")
        XCTAssertEqual(Question.cleanNumber("-3"), "-3")
        XCTAssertEqual(Question.cleanNumber(".5"), "0.5")
        XCTAssertNil(Question.cleanNumber("12 lb"))
        XCTAssertNil(Question.cleanNumber(""))
        XCTAssertNil(Question.cleanNumber("1e5"))
    }

    func testAnsweredIsOnTrackEmptyIsOff() {
        let answered = SlotResult(SlotResult.answered, values: ["w": "182.4"])
        let empty = SlotResult(SlotResult.answered, values: ["w": ""])
        XCTAssertEqual(weight.onTrack(answered), true)
        XCTAssertEqual(weight.onTrack(empty), false)
        // Not asked yet (added after that day): says nothing.
        XCTAssertNil(weight.onTrack(SlotResult(SlotResult.answered)))
        // Answered back when it was a yes/no question: judged as then.
        XCTAssertEqual(weight.onTrack(SlotResult(SlotResult.answered, answers: ["w": true])), true)
        XCTAssertEqual(weight.onTrack(SlotResult(SlotResult.answered, answers: ["w": false])), false)
    }

    func testValuesKeepTheirTypeInBackups() throws {
        let r = SlotResult(SlotResult.answered, values: ["w": "182.4", "n": "180", "t": "Slept well", "e": ""])
        let o = try XCTUnwrap(r.json.objectValue?.object("v"))
        XCTAssertEqual(o["w"], .double(182.4))
        XCTAssertEqual(o["n"], .int(180))
        XCTAssertEqual(o["t"], .string("Slept well"))
        XCTAssertEqual(o["e"], .string(""))
        XCTAssertEqual(SlotResult.from(try XCTUnwrap(r.json.objectValue)), r)
        // No typed answers: no "v" (so older files look the same).
        XCTAssertNil(SlotResult(SlotResult.answered, answers: ["a": true]).json.objectValue?.object("v"))
    }

    func testSavingReplacesAValue() {
        var data = GistData(checkins: [])
        data.saveAnswers(day: "2026-11-15", checkinId: "c", time: "08:00", answers: [:], values: ["w": "182.4"])
        data.saveAnswers(day: "2026-11-15", checkinId: "c", time: "08:00", answers: [:], values: ["w": ""])
        XCTAssertEqual(data.result(day: "2026-11-15", checkinId: "c", time: "08:00")?.values, ["w": ""])
    }

    func testQuestionTypeSurvivesJSON() throws {
        let q = try XCTUnwrap(weight.json.objectValue)
        XCTAssertEqual(Question.from(q, id: "w"), weight)
        let yn = try XCTUnwrap(Question(id: "a", text: "A?").json.objectValue)
        XCTAssertNil(yn["type"], "yes/no questions look as they always did")
        XCTAssertEqual(Question.from(yn, id: "a").type, Question.yesNo)
    }
}
