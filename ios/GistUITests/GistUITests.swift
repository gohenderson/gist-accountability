import XCTest

/// The app on a simulator, driven like a person would. Each test starts from the demo data
/// (debug builds only: -gist-demo), optionally opening a screen with -gist-route.
final class GistUITests: XCTestCase {
    var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
    }

    @discardableResult
    func launch(_ route: String? = nil) -> XCUIApplication {
        app = XCUIApplication()
        app.launchArguments = ["-gist-demo"] + (route.map { ["-gist-route", $0] } ?? [])
        app.launch()
        return app
    }

    func element(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id] }

    func waitFor(_ e: XCUIElement, _ timeout: TimeInterval = 10, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(e.waitForExistence(timeout: timeout), "\(e) didn't appear", file: file, line: line)
    }

    /// Anything showing [text], as its label or value.
    func showing(_ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", text, text)).firstMatch
    }

    /// Scrolls the current screen until [e] is on it.
    func scrollTo(_ e: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        for _ in 0..<8 where !(e.exists && e.isHittable) { app.swipeUp() }
        XCTAssertTrue(e.exists && e.isHittable, "couldn't scroll to \(e)", file: file, line: line)
    }

    // MARK: Start page

    func testStartPageShowsCheckins() {
        launch()
        waitFor(element("checkin-title:morning"))
        XCTAssertTrue(element("checkin-title:evening").exists)
        XCTAssertTrue(element("overall-score").exists)
    }

    func testTappingTheTitleFoldsAndUnfolds() {
        launch()
        waitFor(element("card:evening"))
        element("checkin-title:evening").tap()
        waitFor(element("row:evening"))
        element("checkin-title:evening").tap()
        waitFor(element("card:evening"))
    }

    // MARK: Adding

    func testAddingFromTheLibrary() {
        launch()
        let add = element("add-checkin")
        waitFor(add)
        scrollTo(add)
        add.tap()
        let water = element("library:water")
        waitFor(water)
        water.tap()
        let name = element("checkin-name")
        waitFor(name)
        XCTAssertEqual(name.value as? String, "Water")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        waitFor(element("checkin-title:\(waterId())"))
    }

    /// The new check-in's id isn't known up front: find its title on the start page.
    func waterId() -> String {
        let titles = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'checkin-title:'"))
        for i in 0..<titles.count {
            let t = titles.element(boundBy: i)
            if t.label.hasPrefix("Water") { return String(t.identifier.dropFirst("checkin-title:".count)) }
        }
        return "none"
    }

    func testCreateYourOwn() {
        launch("library")
        let blank = element("library:blank")
        waitFor(blank)
        blank.tap()
        let name = element("checkin-name")
        waitFor(name)
        XCTAssertTrue((name.value as? String)?.hasPrefix("Check-in") == true)
    }

    // MARK: Running

    func testAnsweringQuestions() {
        launch("run:evening")
        let yes = element("q:sweetdrink:no")
        waitFor(yes)
        for (q, a) in [("sweetdrink", "no"), ("fastfood", "no"), ("sweets", "no"), ("veg", "yes"), ("homecooked", "yes")] {
            let b = element("q:\(q):\(a)")
            scrollTo(b)
            b.tap()
        }
        let submit = element("submit")
        scrollTo(submit)
        if !submit.isEnabled { print("GIST-DEBUG\n" + app.debugDescription) }
        XCTAssertTrue(submit.isEnabled, "every question is answered")
        submit.tap()
        // A test ring: done, back on the start page.
        waitFor(element("checkin-title:evening"))
    }

    func testSolvingMath() {
        launch("run:wake")
        let problem = element("math-problem")
        waitFor(problem)
        for _ in 0..<10 {
            guard problem.waitForExistence(timeout: 5), let answer = problem.value as? String else { break }
            let field = element("math-answer")
            field.tap()
            field.typeText(answer)
            element("math-check").tap()
        }
        waitFor(element("checkin-title:wake"))
    }

    func testAWrongAnswerGivesANewProblem() {
        launch("run:wake")
        let problem = element("math-problem")
        waitFor(problem)
        let answer = Int(problem.value as? String ?? "0") ?? 0
        let field = element("math-answer")
        field.tap()
        field.typeText(String(answer + 1))
        element("math-check").tap()
        waitFor(showing("Not quite"))
    }

    func testEndingATest() {
        launch("run:wake")
        let end = element("end-test")
        waitFor(end)
        end.tap()
        waitFor(element("checkin-title:wake"))
    }

    // MARK: Editing

    func testAddingAPlaceTrigger() {
        launch("edit:morning")
        let add = element("add-place-trigger")
        waitFor(add)
        scrollTo(add)
        add.tap()
        let trigger = element("place-trigger:0")
        waitFor(trigger)
        trigger.tap()
        let coords = element("place-coordinates")
        waitFor(coords)
        coords.tap()
        coords.typeText("40.7128, -74.0060")
        app.buttons["Use"].tap()
        waitFor(showing("40.71280, -74.00600"))
        XCTAssertEqual(element("place-name").value as? String, "Place 1")
    }

    func testATimeOnlyAtAPlace() {
        launch("edit:morning")
        let whereLink = element("where:0")
        waitFor(whereLink)
        scrollTo(whereLink)
        whereLink.tap()
        let at = app.buttons["Only when I'm at a place"]
        waitFor(at)
        at.tap()
        let coords = element("place-coordinates")
        waitFor(coords)
        coords.tap()
        coords.typeText("51.5007 -0.1246")
        app.buttons["Use"].tap()
        waitFor(showing("51.50070, -0.12460"))
    }

    // MARK: Backups

    func testExportChoices() {
        launch("settings")
        let export = element("export")
        waitFor(export)
        export.tap()
        waitFor(element("export-include:morning"))
        XCTAssertTrue(element("export-history:morning").exists, "history is offered for included check-ins")
        XCTAssertTrue(element("export-go").isEnabled)
        app.buttons["export-all"].firstMatch.tap()
        XCTAssertFalse(element("export-go").isEnabled, "nothing to export")
        app.buttons["Cancel"].tap()
    }
}
