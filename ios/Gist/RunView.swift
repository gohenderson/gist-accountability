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

    /// What runs. A scan step becomes 5 very hard problems (no camera scanning on iPhone yet).
    enum RunStep: Equatable {
        case math(count: Int, level: Int, note: String?)
        case questions([Question])
    }

    private var checkin: Checkin? { model.checkin(request.pending.checkinId) }

    private var steps: [RunStep] {
        (checkin?.activeSteps ?? []).map { s in
            switch s.kind {
            case StepConfig.math: return .math(count: s.mathCount, level: s.mathLevel, note: nil)
            case StepConfig.scan:
                return .math(count: 5, level: 4, note: "Scanning \(s.scanLabel.isEmpty ? "a code" : "at \(s.scanLabel)") isn't on iPhone yet: 5 very hard problems instead.")
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
        case let .math(count, _, note):
            VStack(alignment: .leading, spacing: 14) {
                if let note { Text(note).font(.footnote).foregroundStyle(Theme.muted) }
                Text("Problem \(min(solved + 1, count)) of \(count)").foregroundStyle(Theme.muted)
                Text(problem.text)
                    .font(.system(size: 40, weight: .bold, design: .rounded))
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                    .accessibilityIdentifier("math-problem")
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
        case let .questions(questions):
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
        if index < steps.count, case let .math(_, level, _) = steps[index] { return level }
        return 2
    }

    private func advance() {
        index += 1
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
