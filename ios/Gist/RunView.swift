import GistCore
import SwiftUI

/// A check-in being done: its steps, one after another, full screen. A strict one can't be
/// closed until they're done (and keeps re-ringing until then); a soft one can be dismissed;
/// a test can be ended.
struct RunView: View {
    @Environment(AppModel.self) private var model
    let request: RunRequest

    @State private var index = 0
    @State private var answers: [String: Bool] = [:]
    /// Typed answers (number and text questions), as typed.
    @State private var values: [String: String] = [:]
    @State private var solved = 0
    @State private var problem = MathGen.make(2)
    @State private var input = ""
    @State private var wrong = false
    @FocusState private var answerFocused: Bool
    /// The scan step gave way to math ("Can't scan?").
    @State private var scanFallback = false
    @State private var canFallback = false
    @State private var wrongCode = false
    /// Past the opening screen (background and caption), into the steps.
    @State private var started = false
    /// When the steps began (Start check-in, or the steps screen opening): for how long
    /// getting started and doing it took.
    @State private var startedAt: Date?
    /// The steps, worked out once when it opens (picking tasks mustn't make its own step vanish).
    @State private var frozen: [RunStep]?
    /// Tasks ticked in the pick-tasks step.
    @State private var picked: Set<String> = []

    /// What runs. A scan step that can't work here (no code registered, or no camera
    /// scanning on this device) becomes 5 very hard problems.
    enum RunStep: Equatable {
        case math(count: Int, level: Int, note: String?)
        case scan(label: String, code: String)
        case questions([Question])
        /// Have [count] active tasks: pick the rest from the backlog.
        case pick(count: Int)
    }

    private var checkin: Checkin? { model.checkin(request.pending.checkinId) }

    private var steps: [RunStep] { frozen ?? buildSteps() }

    /// Pick today's tasks is left out when there's nothing to pick (enough tasks are active
    /// already, or the backlog is empty).
    private func buildSteps() -> [RunStep] {
        (checkin?.activeSteps ?? []).filter { s in
            s.kind != StepConfig.pickTasks || model.data.tasksToPick(s.pickCount) > 0
        }.map { s in
            switch s.kind {
            case StepConfig.pickTasks: return .pick(count: s.pickCount)
            case StepConfig.math: return .math(count: s.mathCount, level: s.mathLevel, note: nil)
            case StepConfig.scan:
                if let code = s.scanCode, !code.isEmpty, CodeScanner.supported {
                    return .scan(label: s.scanLabel, code: code)
                }
                let why = CodeScanner.supported ? "No code is set up for this scan" : "This device can't scan codes"
                return .math(count: Scan.fallbackProblems, level: Scan.fallbackLevel,
                             note: "\(why): \(Scan.fallbackProblems) very hard problems instead.")
            default: return .questions(s.questions)
            }
        }
    }

    /// Does it open on a screen with its background or caption first (like Android's
    /// ringing screen)?
    private var hasOpening: Bool {
        guard let c = checkin else { return false }
        let media = c.background.hasMedia && MediaStore.ready(c.id, c.background)
        return media || !c.background.caption.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        Group {
            if hasOpening && !started {
                opening
            } else {
                stepsScreen.onAppear { if startedAt == nil { startedAt = Date() } }
            }
        }
        .onAppear { if frozen == nil { frozen = buildSteps() } }
    }

    /// The background full screen, with the time, name and caption at the top and one big
    /// Start check-in button at the bottom, out of the picture's way.
    private var opening: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let c = checkin {
                BackgroundMedia(checkin: c).ignoresSafeArea()
            }
            LinearGradient(colors: [.black.opacity(0.65), .black.opacity(0.1), .black.opacity(0.65)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            VStack(alignment: .leading, spacing: 10) {
                if request.pending.practice {
                    Text("TEST RING · NOTHING IS LOGGED").font(.caption.bold()).foregroundStyle(.orange)
                }
                Text(TimeText.of(request.pending.time)).font(.system(size: 56, weight: .bold)).foregroundStyle(.white)
                Text(checkin?.name ?? "Check-in").font(.title.bold()).foregroundStyle(.white)
                if let caption = checkin?.background.caption, !caption.isEmpty {
                    Text(caption).font(.title2.bold()).foregroundStyle(.white)
                        .accessibilityIdentifier("caption")
                }
                Spacer()
                Button {
                    startedAt = Date()
                    withAnimation { started = true }
                } label: {
                    Text(steps.isEmpty ? "Continue" : "Start check-in")
                        .font(.title3.bold())
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("start-checkin")
                footer.tint(.white)
            }
            .padding(24)
            .frame(maxWidth: 640)
        }
    }

    private var stepsScreen: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            if index < steps.count {
                Text("Step \(index + 1) of \(steps.count)").font(.footnote).foregroundStyle(Theme.muted)
                stepView(steps[index])
            } else {
                Spacer()
                Button {
                    model.finish(request, answers: answers, values: values, startedAt: startedAt)
                } label: {
                    Text(steps.isEmpty ? "Dismiss" : "Done").frame(maxWidth: .infinity).padding(.vertical, 12)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("finish")
            }
            Spacer(minLength: 0)
            footer
        }
        .padding(20)
        .frame(maxWidth: 640) // readable on an iPad
        .frame(maxWidth: .infinity)
        .background(Theme.surfaceVariant.opacity(0.6).ignoresSafeArea())
        .onAppear { resetMath() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            if request.pending.practice {
                Text("TEST RING · NOTHING IS LOGGED").font(.caption.bold()).foregroundStyle(.orange)
            } else if request.early {
                Text("DOING IT EARLY · THAT ALARM WON'T RING").font(.caption.bold()).foregroundStyle(Theme.primaryColor)
            }
            Text(checkin?.name ?? "Check-in").font(.largeTitle.bold())
            Text(TimeText.of(request.pending.time)).foregroundStyle(Theme.muted)
        }
    }

    @ViewBuilder private var footer: some View {
        if request.pending.practice {
            Button("End test") { model.dismiss(request) }
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("end-test")
        } else if request.early || checkin?.isSoft == true || checkin == nil {
            Button(request.early ? "Not now" : "Dismiss without the check-in") { model.dismiss(request) }
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("dismiss")
        }
    }

    // MARK: Steps

    @ViewBuilder private func stepView(_ step: RunStep) -> some View {
        switch step {
        case .scan where scanFallback:
            mathView(count: Scan.fallbackProblems, note: "Instead of the scan: \(Scan.fallbackProblems) very hard problems.")
        case let .scan(label, code):
            VStack(alignment: .leading, spacing: 14) {
                Text(label.isEmpty ? "Scan the code" : "Go to \(label) and scan the code").font(.title3)
                let at = index
                ScannerPanel { scanned in
                    guard index == at, !scanFallback else { return }
                    if Scan.matches(code, scanned) {
                        wrongCode = false
                        advance()
                    } else {
                        wrongCode = true
                    }
                }
                .id(index)
                if wrongCode {
                    Text("That's a different code.").foregroundStyle(Theme.bad)
                }
                if canFallback {
                    Button("Can't scan? Solve \(Scan.fallbackProblems) very hard problems instead") {
                        scanFallback = true
                        resetMath()
                    }
                    .accessibilityIdentifier("scan-fallback")
                }
            }
            .task(id: index) {
                try? await Task.sleep(for: .seconds(Scan.fallbackAfter))
                if !Task.isCancelled { canFallback = true }
            }
        case let .math(count, _, note):
            mathView(count: count, note: note)
        case let .questions(questions):
            questionsView(questions)
        case let .pick(count):
            pickView(count: count)
        }
    }

    /// Pick today's tasks: tick the difference between [count] and the active tasks, from
    /// the backlog; they turn on and show on the start page.
    @ViewBuilder private func pickView(count: Int) -> some View {
        let backlog = model.data.backlog
        let active = model.data.activeTasks.count
        let need = model.data.tasksToPick(count)
        let chosen = picked.intersection(backlog.map { $0.id })
        let left = need - chosen.count
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if need == 0 {
                    Text(count <= active ? "You already have \(active) active tasks." : "Your backlog is empty: nothing to pick today.")
                        .font(.title3)
                    Button {
                        advance()
                    } label: {
                        Text("Continue").frame(maxWidth: .infinity).padding(.vertical, 8)
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("pick-continue")
                } else {
                    let more = active == 0 ? "" : " more"
                    Text(need == 1 ? "Pick 1\(more) task to do today" : "Pick \(need)\(more) tasks to do today")
                        .font(.title2.bold())
                    Text((active == 0 ? "" : "You have \(active) active already. ") +
                         (backlog.count > need ? "From your backlog of \(backlog.count). They'll show on your start page."
                                               : "That's your whole backlog. They'll show on your start page."))
                        .font(.footnote).foregroundStyle(Theme.muted)
                    ForEach(backlog) { t in
                        let on = chosen.contains(t.id)
                        Button {
                            if on { picked.remove(t.id) } else { picked.insert(t.id) }
                        } label: {
                            HStack(alignment: .firstTextBaseline, spacing: 12) {
                                Image(systemName: on ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(on ? Theme.primaryColor : Theme.muted)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(t.name).font(.title3)
                                    let detail = [t.category, Checkin.timeCommitmentName(t.timeCommitment) ?? ""].filter { !$0.isEmpty }
                                    if !detail.isEmpty {
                                        Text(detail.joined(separator: " · ")).font(.footnote).foregroundStyle(Theme.muted)
                                    }
                                }
                                Spacer(minLength: 0)
                            }
                            .padding(12)
                            .background(on ? Theme.primaryContainer : Theme.surface, in: RoundedRectangle(cornerRadius: 12))
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("pick:\(t.id)")
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                    Button {
                        model.pickTasks(Array(chosen), practice: request.pending.practice)
                        picked = []
                        advance()
                    } label: {
                        Text(left > 0 ? "Pick \(left) more" : left < 0 ? "Untick \(-left)"
                             : request.pending.practice ? "Done (test: nothing changes)" : "Add to today")
                            .frame(maxWidth: .infinity).padding(.vertical, 8)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(left != 0)
                    .accessibilityIdentifier("pick-go")
                }
            }
        }
    }

    private func mathView(count: Int, note: String?) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            if let note { Text(note).font(.footnote).foregroundStyle(Theme.muted) }
            Text("Problem \(min(solved + 1, count)) of \(count)").foregroundStyle(Theme.muted)
            Text(problem.text)
                .font(.system(size: 40, weight: .bold, design: .rounded))
                .minimumScaleFactor(0.5)
                .lineLimit(1)
                .accessibilityIdentifier("math-problem")
                #if DEBUG
                .accessibilityValue(String(problem.answer)) // so UI tests can solve it
                #endif
            TextField("Answer", text: $input)
                .keyboardType(.numberPad)
                .font(.title)
                .textFieldStyle(.roundedBorder)
                .focused($answerFocused)
                .accessibilityIdentifier("math-answer")
            if wrong {
                Text("Not quite. Here's a new one.").foregroundStyle(Theme.bad)
            }
            Button {
                check(count: count)
            } label: {
                Text("Check").frame(maxWidth: .infinity).padding(.vertical, 8)
            }
            .buttonStyle(.borderedProminent)
            .disabled(input.isEmpty)
            .accessibilityIdentifier("math-check")
        }
        .onAppear { answerFocused = true }
    }

    @ViewBuilder private func questionsView(_ questions: [Question]) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                ForEach(questions) { q in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(q.text).font(.title3)
                        if q.takesValue {
                            TextField(q.type == Question.number ? "A number (optional)" : "Your answer (optional)",
                                      text: Binding(get: { values[q.id] ?? "" }, set: { values[q.id] = $0 }),
                                      axis: q.type == Question.text ? .vertical : .horizontal)
                                .keyboardType(q.type == Question.number ? .decimalPad : .default)
                                .font(.title3)
                                .textFieldStyle(.roundedBorder)
                                .accessibilityIdentifier("q:\(q.id):value")
                            Text("Leave it empty if you can't answer: that counts as off track.")
                                .font(.footnote).foregroundStyle(Theme.muted)
                        } else {
                            HStack {
                                answerButton(q, true)
                                answerButton(q, false)
                            }
                        }
                    }
                }
                let left = questions.filter { !$0.takesValue && answers[$0.id] == nil }.count
                let badNumber = questions.contains { q in
                    q.type == Question.number && !(values[q.id] ?? "").trimmingCharacters(in: .whitespaces).isEmpty
                        && Question.cleanNumber(values[q.id] ?? "") == nil
                }
                Button {
                    advance()
                } label: {
                    Text(left > 0 ? "\(left) left to answer" : badNumber ? "Fix the number" : "Submit")
                        .frame(maxWidth: .infinity).padding(.vertical, 8)
                }
                .buttonStyle(.borderedProminent)
                .disabled(left > 0 || badNumber)
                .accessibilityIdentifier("submit")
            }
        }
    }

    private func answerButton(_ q: Question, _ value: Bool) -> some View {
        let chosen = answers[q.id] == value
        return Button {
            answers[q.id] = value
        } label: {
            Text(value ? "Yes" : "No")
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(chosen ? Theme.primaryContainer : Theme.surface, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(chosen ? Theme.primaryColor : Theme.outline, lineWidth: chosen ? 2 : 1))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("q:\(q.id):\(value ? "yes" : "no")")
    }

    private func check(count: Int) {
        if Int(input.trimmingCharacters(in: .whitespaces)) == problem.answer {
            solved += 1
            wrong = false
            if solved >= count {
                advance()
                return
            }
        } else {
            wrong = true
        }
        input = ""
        problem = MathGen.make(currentLevel)
    }

    private var currentLevel: Int {
        guard index < steps.count else { return 2 }
        switch steps[index] {
        case let .math(_, level, _): return level
        case .scan: return Scan.fallbackLevel
        default: return 2
        }
    }

    private func advance() {
        index += 1
        scanFallback = false
        canFallback = false
        wrongCode = false
        resetMath()
        if index >= steps.count && !steps.isEmpty {
            model.finish(request, answers: answers, values: values, startedAt: startedAt)
        }
    }

    private func resetMath() {
        solved = 0
        input = ""
        wrong = false
        problem = MathGen.make(currentLevel)
    }
}
