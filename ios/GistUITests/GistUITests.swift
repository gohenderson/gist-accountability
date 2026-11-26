import XCTest

/// The app on a simulator, driven like a person would. Each test starts from the demo data
/// (debug builds only: -gist-demo), optionally opening a screen with -gist-route.
final class GistUITests: XCTestCase {
    var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
    }

    @discardableResult
    func launch(_ route: String? = nil, tasks: Bool = false) -> XCUIApplication {
        app = XCUIApplication()
        app.launchArguments = ["-gist-demo"] + (tasks ? ["-gist-demo-tasks"] : []) + (route.map { ["-gist-route", $0] } ?? [])
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

    /// Opens a card on a check-in's page (they open one at a time), unless it's open.
    func openSection(_ key: String, file: StaticString = #filePath, line: UInt = #line) {
        let h = element("section-header:\(key)")
        waitFor(h, file: file, line: line)
        if !h.isHittable { scrollTo(h, file: file, line: line) }
        if h.value as? String != "expanded" { h.tap() }
    }

    /// The start page's tab called [title].
    func tab(_ title: String) -> XCUIElement { app.segmentedControls["tabs"].buttons[title] }

    // MARK: Start page

    func testStartPageShowsCheckins() {
        launch()
        waitFor(element("checkin-title:morning"))
        XCTAssertTrue(element("checkin-title:evening").exists)
        XCTAssertTrue(element("overall-score").exists)
    }

    func testCardsOpenOneAtATime() {
        launch()
        // All folded to start.
        waitFor(element("row:evening"))
        XCTAssertTrue(element("row:morning").exists)
        element("checkin-title:evening").tap()
        waitFor(element("card:evening"))
        // Opening another folds the first.
        element("checkin-title:morning").tap()
        waitFor(element("card:morning"))
        waitFor(element("row:evening"))
        // Tapping the card's body (not a button) folds it too.
        element("summary:morning").tap()
        waitFor(element("row:morning"))
    }

    func testTheTabsAndTheirSummaries() {
        launch(tasks: true)
        waitFor(element("overall-score"))
        XCTAssertTrue(element("checkin-title:morning").exists)
        XCTAssertFalse(element("checkin-title:shed").exists, "tasks aren't on the Check-ins tab")
        XCTAssertTrue(element("view-mode").exists, "View is always there")
        tab("Backlog").tap()
        waitFor(element("backlog-added-count"))
        waitFor(element("checkin-title:shed"))
        XCTAssertFalse(element("checkin-title:morning").exists)
        tab("Tasks").tap()
        waitFor(element("tasks-done-count"))
        waitFor(showing("No active tasks"))
        XCTAssertTrue(element("done-tasks").exists)
        XCTAssertTrue(element("settings").exists, "the gear, top right")
    }

    func testPlusOnTheBacklogMakesABacklogTaskAndDoingOneCompletesIt() {
        launch("tab:backlog", tasks: true)
        let add = element("add")
        waitFor(add)
        add.tap()
        let name = element("checkin-name")
        waitFor(name)
        XCTAssertEqual(name.value as? String, "New task")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        // Do the shed task from its card: it moves to Done tasks.
        waitFor(element("checkin-title:shed"))
        element("checkin-title:shed").tap()
        let doIt = element("task-do:shed")
        waitFor(doIt)
        doIt.tap()
        let finish = element("finish")
        waitFor(finish)
        finish.tap()
        // Task done: a follow-up is offered; close it.
        let close = element("task-done-close")
        waitFor(close)
        close.tap()
        XCTAssertTrue(element("checkin-title:shed").waitForNonExistence(timeout: 10))
        tab("Tasks").tap()
        waitFor(element("done-tasks"))
        element("done-tasks").tap()
        waitFor(element("done-task:shed"))
        element("reopen:shed").tap()
        XCTAssertTrue(element("done-task:shed").waitForNonExistence(timeout: 10))
    }

    // MARK: Adding

    func testAddingFromTheLibrary() {
        launch()
        let add = element("add")
        waitFor(add)
        add.tap()
        let water = element("library:water")
        waitFor(water)
        water.tap()
        let name = element("checkin-name")
        waitFor(name)
        XCTAssertEqual(name.value as? String, "Water")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        waitFor(element("add"))
        var id = "none"
        for _ in 0..<20 where id == "none" {
            id = waterId()
            if id == "none" { Thread.sleep(forTimeInterval: 0.5) }
        }
        if id == "none" { print("GIST-DEBUG\n" + app.debugDescription) }
        waitFor(element("checkin-title:\(id)"))
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

    func testOpeningWithABackgroundAndCaption() {
        launch("run:stretch")
        waitFor(showing("Future you says thanks"))
        let start = element("start-checkin")
        waitFor(start)
        start.tap()
        // No steps: straight to dismissing it.
        waitFor(element("finish"))
    }

    // MARK: Editing

    func testSettingACaption() {
        launch("edit:wake")
        waitFor(element("checkin-name"))
        openSection("rings")
        let link = element("while-it-rings")
        scrollTo(link) // below the fold: the list only makes it when it's near
        link.tap()
        let caption = element("background-caption")
        waitFor(caption)
        caption.tap()
        caption.typeText("Up and at it")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        waitFor(link)
        XCTAssertFalse(link.label.contains("None"), "now has a caption: \(link.label)")
    }

    func testAddingAPlaceTrigger() {
        launch("edit:morning")
        openSection("schedule")
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
        openSection("schedule")
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

    func testAddingARunTrigger() {
        launch("edit:wake")
        waitFor(element("checkin-name"))
        openSection("schedule")
        let add = element("add-run-trigger")
        scrollTo(add)
        add.tap()
        let trigger = element("run-trigger:0")
        scrollTo(trigger)
        XCTAssertTrue(trigger.label.contains("finish a run"), trigger.label)
        trigger.tap()
        let into = app.buttons["I've been running for…"]
        waitFor(into)
        into.tap()
        waitFor(element("run-minutes"))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        waitFor(trigger)
        XCTAssertTrue(trigger.label.contains("into a run"), trigger.label)
    }

    func testMakingAVoiceCheckin() {
        launch("library")
        let blank = element("library:blank")
        waitFor(blank)
        blank.tap()
        waitFor(element("checkin-name"))
        openSection("steps")
        let commitment = element("commitment")
        scrollTo(commitment)
        commitment.tap()
        let voice = app.buttons["Voice"]
        waitFor(voice)
        voice.tap()
        let text = element("voice-text")
        scrollTo(text)
        text.tap()
        text.typeText("Halfway there")
        XCTAssertFalse(element("section-header:rings").exists, "a voice check-in doesn't ring")
        openSection("try")
        let play = element("test-voice")
        scrollTo(play)
    }

    // MARK: Backups

    func testExportChoices() {
        launch("settings")
        let export = element("export")
        waitFor(export)
        export.tap()
        waitFor(element("export-include:morning"))
        XCTAssertTrue(element("export-history:morning").exists, "history is offered for included check-ins")
        XCTAssertTrue(app.buttons["export-go"].firstMatch.isEnabled)
        app.buttons["export-all"].firstMatch.tap()
        XCTAssertFalse(app.buttons["export-go"].firstMatch.isEnabled, "nothing to export")
        app.buttons["Cancel"].tap()
    }

    // MARK: Tasks

    func testPickTodaysTasks() {
        launch("run:plan", tasks: true)
        let go = element("pick-go")
        waitFor(go)
        XCTAssertTrue(showing("Pick 2 tasks to do today").exists)
        XCTAssertFalse(go.isEnabled, "pick 2 first")
        element("pick:shed").tap()
        element("pick:milk").tap()
        XCTAssertTrue(go.isEnabled)
        XCTAssertEqual(go.label, "Done (test: nothing changes)")
        element("pick:call").tap()
        XCTAssertFalse(go.isEnabled, "one too many")
        element("pick:call").tap()
        go.tap()
        // A test ring: the steps are done and nothing changed.
        XCTAssertTrue(element("pick-go").waitForNonExistence(timeout: 10))
    }

    func testSectionsOpenOneAtATimeAndTheNewFields() {
        launch("edit:morning")
        let desc = element("checkin-description")
        waitFor(desc)
        desc.tap()
        desc.typeText("Before coffee")
        XCTAssertTrue(element("time-commitment").exists)
        XCTAssertFalse(element("add-time").exists, "Schedule is folded")
        openSection("schedule")
        waitFor(element("add-time"))
        XCTAssertFalse(element("checkin-description").exists, "Details folded")
        XCTAssertTrue(element("task-switch").exists)
        element("section-header:schedule").tap() // the open one: now everything's folded
        XCTAssertTrue(element("add-time").waitForNonExistence(timeout: 5))
        let delete = element("delete-checkin")
        scrollTo(delete)
        XCTAssertEqual(delete.label, "Delete")
    }

    func testFinishingATaskOffersAFollowUp() {
        launch("tab:backlog", tasks: true)
        waitFor(element("checkin-title:shed"))
        element("checkin-title:shed").tap()
        let doIt = element("task-do:shed")
        waitFor(doIt)
        doIt.tap()
        let finish = element("finish")
        waitFor(finish)
        finish.tap()
        let today = element("follow-up:today")
        waitFor(today)
        XCTAssertTrue(element("task-done-title").exists)
        today.tap()
        // The follow-up's page opens, named so it's plain it's the new one.
        let name = element("checkin-name")
        waitFor(name)
        XCTAssertEqual(name.value as? String, "(Follow-Up) Organize shed")
        XCTAssertTrue(element("follows").exists)
    }

    // MARK: Connections and your devices

    /// Connections, from a fresh start, with a Gist folder in temporary storage.
    private func launchConnections() {
        app = XCUIApplication()
        app.launchArguments = ["-gist-demo", "-gist-sync-test", "-gist-route", "connections"]
        app.launch()
        waitFor(element("gist-folder"))
    }

    func testSettingUpYourDevicesShowsAnInvite() {
        launchConnections()
        let setUp = element("devices-setup")
        scrollTo(setUp)
        setUp.tap()
        waitFor(app.alerts.firstMatch)
        app.alerts.buttons["OK"].tap()
        let add = element("devices-add")
        scrollTo(add)
        add.tap()
        let code = element("invite-code")
        waitFor(code)
        // 36 letters and digits in groups of 4.
        XCTAssertEqual((code.label as String).count, 44)
        XCTAssertTrue(element("invite-qr").exists)
        app.buttons["Done"].tap()
        scrollTo(element("ring-here"))
    }

    func testInvitingSomeoneMakesTheirFolderAndShowsAnInvite() {
        launchConnections()
        let invite = element("invite")
        scrollTo(invite)
        invite.tap()
        let who = app.alerts.textFields.firstMatch
        waitFor(who)
        who.typeText("Jess")
        app.alerts.buttons["Invite"].tap()
        let code = element("invite-code")
        waitFor(code)
        XCTAssertTrue(showing("Share People/Jess").exists)
        app.buttons["Done"].tap()
        waitFor(showing("Waiting for someone to join"))
    }

    func testJoiningWithATypedCodeWaitsForTheFolder() {
        launchConnections()
        let join = element("join")
        scrollTo(join)
        join.tap()
        let field = element("invite-field")
        waitFor(field)
        field.tap()
        // A real-looking invite for a connection nobody has shared yet.
        field.typeText("AFRQ-WFAP-R3IT-K5P3-TZPQ-CAQD-AQCQ-MBYI-BEFA")
        element("invite-next").tap()
        waitFor(element("join-waiting"))
    }
}
