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
    @State private var solved = 0
    @State private var problem = MathGen.make(2)
    @State private var input = ""
    @State private var wrong = false
    @FocusState private var answerFocused: Bool
    /// The scan step gave way to math ("Can't scan?").
    @State private var scanFallback = false
    @State private var canFallback = false
    @State private var wrongCode = false

    /// What runs. A scan step that can't work here (no code registered, or no camera
    /// scanning on this device) becomes 5 very hard problems.
    enum RunStep: Equatable {
        case math(count: Int, level: Int, note: String?)
        case scan(label: String, code: String)
        case questions([Question])
    }

    private var checkin: Checkin? { model.checkin(request.pending.checkinId) }

    private var steps: [RunStep] {
        (checkin?.activeSteps ?? []).map { s in
            switch s.kind {
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

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            if index < steps.count {
                Text("Step \(index + 1) of \(steps.count)").font(.footnote).foregroundStyle(Theme.muted)
                stepView(steps[index])
            } else {
                Spacer()
                Button {
                    model.finish(request, answers: answers)
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
                        HStack {
                            answerButton(q, true)
                            answerButton(q, false)
                        }
                    }
                }
                Button {
                    advance()
                } label: {
                    Text("Submit").frame(maxWidth: .infinity).padding(.vertical, 8)
                }
                .buttonStyle(.borderedProminent)
                .disabled(questions.contains { answers[$0.id] == nil })
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
            model.finish(request, answers: answers)
        }
    }

    private func resetMath() {
        solved = 0
        input = ""
        wrong = false
        problem = MathGen.make(currentLevel)
    }
}
