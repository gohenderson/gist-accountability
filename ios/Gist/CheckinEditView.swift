import GistCore
import SwiftUI

/// A check-in's page. Changes save as you make them.
struct CheckinEditView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let checkinId: String
    @State private var draft: Checkin?
    @State private var confirmDelete = false

    var body: some View {
        Group {
            if let draft = Binding($draft) {
                form(draft)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(draft?.name ?? "Check-in")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { if draft == nil { draft = model.checkin(checkinId) } }
        .onChange(of: draft) { _, new in
            if let new, new != model.checkin(new.id) { model.save(new) }
        }
    }

    @ViewBuilder private func form(_ c: Binding<Checkin>) -> some View {
        Form {
            if let score = model.score(c.wrappedValue) {
                Section {
                    HStack(alignment: .firstTextBaseline) {
                        Text(Score.percent(score)).font(.system(size: 34, weight: .bold, design: .rounded))
                            .foregroundStyle(Theme.scoreColor(score))
                        Text("on track over the last \(Score.days) days").foregroundStyle(Theme.muted)
                    }
                }
            }
            Section {
                LabeledContent("Name") {
                    TextField("Name", text: c.name)
                        .multilineTextAlignment(.trailing)
                        .accessibilityIdentifier("checkin-name")
                }
                Toggle("On", isOn: c.enabled)
                LabeledContent("Category") {
                    TextField("None", text: c.category)
                        .multilineTextAlignment(.trailing)
                        .accessibilityIdentifier("checkin-category")
                }
                Toggle("Answers count for the previous day", isOn: c.aboutYesterday)
            } footer: {
                Text("For a morning check-in about last night.")
            }

            timesSection(c)

            Section {
                Picker("Commitment", selection: c.commitment) {
                    Text("Strict").tag(Checkin.strict)
                    Text("Soft").tag(Checkin.soft)
                    Text("Voice").tag(Checkin.voice)
                }
                .accessibilityIdentifier("commitment")
                if c.wrappedValue.isSoft {
                    Picker("Snooze", selection: c.snoozeMinutes) {
                        ForEach(Checkin.snoozeChoices, id: \.self) { Text("\($0) minutes").tag($0) }
                    }
                    .accessibilityIdentifier("snooze-minutes")
                }
            } footer: {
                Text(commitmentText(c.wrappedValue))
            }

            if c.wrappedValue.isVoice {
                VoiceSection(checkin: c)
            } else {
                stepsSection(c)

                Section {
                    NavigationLink {
                        BackgroundEditView(checkin: c)
                    } label: {
                        LabeledContent("Background and caption", value: backgroundText(c.wrappedValue))
                    }
                    .accessibilityIdentifier("while-it-rings")
                } header: {
                    Text("While it rings")
                }
            }

            Section {
                if c.wrappedValue.isVoice {
                    Button("Play it now") { model.playVoice(c.wrappedValue) }
                        .accessibilityIdentifier("test-voice")
                } else {
                    Button("Test: ring in 10 seconds") { model.testRing(c.wrappedValue) }
                        .accessibilityIdentifier("test-ring")
                }
            } footer: {
                Text(c.wrappedValue.isVoice ? "Nothing is logged." : "A practice run: nothing is logged, and real alarms are left alone.")
            }

            Section {
                Button("Delete this check-in", role: .destructive) { confirmDelete = true }
            }
        }
        .confirmationDialog("Delete \(c.wrappedValue.name)?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete check-in and its history", role: .destructive) {
                let id = c.wrappedValue.id
                draft = nil
                model.delete(id)
                dismiss()
            }
        }
    }

    private func commitmentText(_ c: Checkin) -> String {
        if c.isVoice {
            return "No alarm and nothing to tap: when a trigger fires, it says its line (or plays your clip) through your headphones, and your music dips while it talks. Not counted in your stats. On iPhone, its times speak only while Gist is awake (open, or watching for runs); runs and places always work."
        }
        if c.isSoft { return "Rings once, with Snooze. Open Gist to do its steps or dismiss it. Not counted in your stats." }
        return "Until the steps are done, it rings again every \(AlarmPlan.nagMinutes) minutes (up to \(AlarmPlan.nagCount) times). Counted in your stats."
    }

    private func backgroundText(_ c: Checkin) -> String {
        var parts: [String] = []
        switch c.background.kind {
        case Background.photo: parts.append("Photo")
        case Background.slideshow: parts.append("Slideshow · \(MediaStore.photos(c.id).count) photos")
        case Background.video: parts.append("Video")
        default: break
        }
        if c.background.hasMedia && !MediaStore.ready(c.id, c.background) { parts[0] += " (not chosen)" }
        if !c.background.caption.isEmpty { parts.append("caption") }
        return parts.isEmpty ? "None" : parts.joined(separator: ", ")
    }

    // MARK: Times

    @ViewBuilder private func timesSection(_ c: Binding<Checkin>) -> some View {
        Section {
            ForEach(Array(c.wrappedValue.times.enumerated()), id: \.offset) { i, t in
                VStack(alignment: .leading, spacing: 4) {
                    DatePicker("Time", selection: timeBinding(c, i), displayedComponents: .hourAndMinute)
                    NavigationLink {
                        DaysEditor(days: daysBinding(c, i))
                    } label: {
                        LabeledContent("Days", value: c.wrappedValue.daysFor(t).describe())
                    }
                    NavigationLink {
                        WhereEditor(time: timeAt(c, i))
                    } label: {
                        LabeledContent("Where", value: t.usesLocation ? Places.whereText(t.where, t.place) : "Anywhere")
                    }
                    .accessibilityIdentifier("where:\(i)")
                }
            }
            .onDelete { c.wrappedValue.times.remove(atOffsets: $0) }
            Button("Add a time") {
                let last = c.wrappedValue.times.last ?? AlarmTime(11, 0)
                var t = AlarmTime((last.hour + 1) % 24, last.minute)
                while c.wrappedValue.times.contains(where: { $0.minutesOfDay == t.minutesOfDay }) { t.hour = (t.hour + 1) % 24 }
                c.wrappedValue.times.append(t)
                c.wrappedValue.times.sort()
            }
            .accessibilityIdentifier("add-time")
        } header: {
            Text("Rings at")
        } footer: {
            if c.wrappedValue.times.isEmpty && c.wrappedValue.events.isEmpty {
                Text("No times or places: it only rings when you test it.")
            }
        }
        placesSection(c)
    }

    /// Arrive and leave, and run triggers.
    @ViewBuilder private func placesSection(_ c: Binding<Checkin>) -> some View {
        Section {
            ForEach(Array(c.wrappedValue.events.enumerated()), id: \.offset) { i, t in
                if t.isPlace {
                    NavigationLink {
                        PlaceTriggerEditor(trigger: eventAt(c, i))
                    } label: {
                        VStack(alignment: .leading) {
                            Text(PlaceText.describe(t))
                            if let w = windowText(t) {
                                Text(w).font(.footnote).foregroundStyle(Theme.muted)
                            }
                        }
                    }
                    .accessibilityIdentifier("place-trigger:\(i)")
                } else {
                    NavigationLink {
                        RunTriggerEditor(trigger: eventAt(c, i))
                    } label: {
                        VStack(alignment: .leading) {
                            Text(PlaceText.describe(t))
                            if let w = windowText(t) {
                                Text(w).font(.footnote).foregroundStyle(Theme.muted)
                            }
                        }
                    }
                    .accessibilityIdentifier("run-trigger:\(i)")
                }
            }
            .onDelete { c.wrappedValue.events.remove(atOffsets: $0) }
            Button("Add arrive or leave") {
                c.wrappedValue.events.append(Trigger(kind: Trigger.arrive))
            }
            .disabled(c.wrappedValue.events.filter { $0.isPlace }.count >= 10)
            .accessibilityIdentifier("add-place-trigger")
            Button("Add a run trigger") {
                c.wrappedValue.events.append(Trigger(kind: Trigger.runEnd, minRunMinutes: 20))
            }
            .disabled(c.wrappedValue.events.filter { $0.isMovement }.count >= 10)
            .accessibilityIdentifier("add-run-trigger")
            if c.wrappedValue.events.contains(where: { $0.isMovement }) && !RunWatcher.shared.problem.isEmpty {
                Text(RunWatcher.shared.problem).font(.footnote).foregroundStyle(Theme.bad)
            }
        } header: {
            Text("Also rings")
        } footer: {
            if c.wrappedValue.events.contains(where: { $0.isPlace && $0.place == nil }) {
                Text("Choose a place for each, or it won't ring.")
            }
        }
    }

    private func windowText(_ t: Trigger) -> String? {
        var parts: [String] = []
        if let s = t.windowStart, let e = t.windowEnd {
            parts.append("\(TimeText.of(s.description))–\(TimeText.of(e.description))")
        }
        if t.days != .everyDay { parts.append(t.days.describe()) }
        if !t.oncePerDay { parts.append("every time") }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private func timeAt(_ c: Binding<Checkin>, _ i: Int) -> Binding<AlarmTime> {
        Binding {
            i < c.wrappedValue.times.count ? c.wrappedValue.times[i] : AlarmTime(12, 0)
        } set: { t in
            guard i < c.wrappedValue.times.count else { return }
            var t = t
            if t.where == Trigger.anywhere { t.place = nil }
            c.wrappedValue.times[i] = t
        }
    }

    private func eventAt(_ c: Binding<Checkin>, _ i: Int) -> Binding<Trigger> {
        Binding {
            i < c.wrappedValue.events.count ? c.wrappedValue.events[i] : Trigger(kind: Trigger.arrive)
        } set: { t in
            guard i < c.wrappedValue.events.count else { return }
            c.wrappedValue.events[i] = t
        }
    }

    private func timeBinding(_ c: Binding<Checkin>, _ i: Int) -> Binding<Date> {
        Binding {
            guard i < c.wrappedValue.times.count else { return Date() }
            let t = c.wrappedValue.times[i]
            return Calendar.current.date(bySettingHour: t.hour, minute: t.minute, second: 0, of: Date()) ?? Date()
        } set: { date in
            guard i < c.wrappedValue.times.count else { return }
            let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
            c.wrappedValue.times[i].hour = parts.hour ?? 0
            c.wrappedValue.times[i].minute = parts.minute ?? 0
        }
    }

    /// A time's own days. Setting them to the check-in's own rule clears the override.
    private func daysBinding(_ c: Binding<Checkin>, _ i: Int) -> Binding<Repeat> {
        Binding {
            i < c.wrappedValue.times.count ? c.wrappedValue.daysFor(c.wrappedValue.times[i]) : .everyDay
        } set: { days in
            guard i < c.wrappedValue.times.count else { return }
            c.wrappedValue.times[i].days = days == c.wrappedValue.repeat ? nil : days
        }
    }

    // MARK: Steps

    @ViewBuilder private func stepsSection(_ c: Binding<Checkin>) -> some View {
        Section {
            ForEach(c.steps) { $step in
                NavigationLink {
                    StepEditView(step: $step)
                } label: {
                    VStack(alignment: .leading) {
                        Text(StepText.title(step.kind))
                        Text(step.configured ? CheckinSummary.step(step) : StepText.missing(step.kind))
                            .font(.footnote)
                            .foregroundStyle(step.configured ? Theme.muted : .orange)
                    }
                }
            }
            .onMove { c.wrappedValue.steps.move(fromOffsets: $0, toOffset: $1) }
            .onDelete { c.wrappedValue.steps.remove(atOffsets: $0) }
            if c.wrappedValue.steps.count < StepConfig.maxSteps {
                Menu("Add a step") {
                    ForEach(StepConfig.kinds, id: \.self) { kind in
                        Button(StepText.title(kind)) { c.wrappedValue.steps.append(StepConfig(kind: kind)) }
                    }
                }
                .accessibilityIdentifier("add-step")
            }
        } header: {
            Text("When it rings")
        } footer: {
            Text(c.wrappedValue.steps.isEmpty
                 ? "No steps: it's just a Dismiss button."
                 : "The steps run in this order. Swipe to remove one; touch and hold to drag.")
        }
    }
}

enum StepText {
    static func title(_ kind: String) -> String {
        switch kind {
        case StepConfig.math: return "Math problems"
        case StepConfig.scan: return "Scan a code"
        default: return "Questions"
        }
    }

    static func missing(_ kind: String) -> String {
        switch kind {
        case StepConfig.scan: return "Not set up: enter the code"
        case StepConfig.questions: return "Not set up: add a question"
        default: return "Not set up"
        }
    }
}

/// One step's settings.
struct StepEditView: View {
    @Binding var step: StepConfig
    @State private var registering = false

    var body: some View {
        form
            .sheet(isPresented: $registering) {
                NavigationStack {
                    ScannerPanel { code in
                        step.scanCode = code
                        registering = false
                    }
                    .padding()
                    .navigationTitle("Scan the code")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { registering = false } }
                    }
                }
                .presentationDetents([.medium, .large])
            }
    }

    private var form: some View {
        Form {
            switch step.kind {
            case StepConfig.math:
                Section {
                    Stepper("\(step.mathCount) problem\(step.mathCount == 1 ? "" : "s")", value: $step.mathCount, in: 1...StepConfig.maxProblems)
                    Picker("Difficulty", selection: $step.mathLevel) {
                        ForEach(Array(MathGen.levels.enumerated()), id: \.offset) { i, level in
                            Text("\(level.name) (\(level.example))").tag(i + 1)
                        }
                    }
                    .pickerStyle(.inline)
                } footer: {
                    Text("A wrong answer gives you a new problem.")
                }
            case StepConfig.scan:
                Section {
                    TextField("Where it is, e.g. Bathroom sink", text: $step.scanLabel)
                    Button(step.scanCode == nil ? "Scan the code to use" : "Scan a different code") { registering = true }
                        .disabled(!CodeScanner.supported)
                        .accessibilityIdentifier("register-code")
                    TextField("The code's text", text: Binding(get: { step.scanCode ?? "" },
                                                               set: { step.scanCode = $0.isEmpty ? nil : $0 }))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } footer: {
                    Text(CodeScanner.supported
                         ? "Put a QR code or barcode somewhere you have to walk to (any product's barcode works) and scan it here. When it rings, you scan the same code to finish. If you can't after \(Int(Scan.fallbackAfter) / 60) minutes, you can solve \(Scan.fallbackProblems) very hard problems instead."
                         : "This device can't scan codes, so this step is \(Scan.fallbackProblems) very hard math problems when it rings.")
                }
            default:
                Section {
                    ForEach($step.questions) { $q in
                        VStack(alignment: .leading) {
                            TextField("Question", text: $q.text, axis: .vertical)
                            Toggle("\"Yes\" is on track", isOn: $q.yesIsGood).font(.footnote)
                        }
                    }
                    .onDelete { step.questions.remove(atOffsets: $0) }
                    .onMove { step.questions.move(fromOffsets: $0, toOffset: $1) }
                    Button("Add a question") { step.questions.append(Question(id: Ids.new(), text: "")) }
                        .accessibilityIdentifier("add-question")
                } footer: {
                    Text("These answers are logged and shown in History. Empty questions are left out.")
                }
            }
        }
        .navigationTitle(StepText.title(step.kind))
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { step.questions.removeAll { $0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } }
    }
}

/// Which days: every day, weekdays, weekends, chosen days of the week, or dates of the month.
struct DaysEditor: View {
    @Binding var days: Repeat

    private enum Choice: String, CaseIterable, Identifiable {
        case every = "Every day", weekdays = "Weekdays", weekends = "Weekends", week = "Days of the week", month = "Days of the month"
        var id: String { rawValue }
    }

    private var choice: Choice {
        switch days.kind {
        case Repeat.weekly:
            if days.days == Repeat.weekdays { return .weekdays }
            if days.days == Repeat.weekends { return .weekends }
            return .week
        case Repeat.monthly: return .month
        default: return .every
        }
    }

    var body: some View {
        Form {
            Section {
                Picker("Days", selection: Binding(get: { choice }, set: { apply($0) })) {
                    ForEach(Choice.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }
            if choice == .week {
                Section("Days of the week") {
                    ForEach(1...7, id: \.self) { d in toggle(Repeat.dayName(d), d) }
                }
            }
            if choice == .month {
                Section {
                    ForEach(Array(1...31) + [Repeat.lastDay], id: \.self) { d in
                        toggle(d == Repeat.lastDay ? "Last day of the month" : "The \(Repeat.ordinal(d))", d)
                    }
                } header: {
                    Text("Days of the month")
                } footer: {
                    Text("A month without that date (the 31st in November) is skipped. Use the last day for month ends.")
                }
            }
        }
        .navigationTitle("Days")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func toggle(_ title: String, _ d: Int) -> some View {
        Toggle(title, isOn: Binding(get: { days.days.contains(d) }, set: { on in
            var chosen = days.days
            if on { chosen.insert(d) } else { chosen.remove(d) }
            if chosen.isEmpty { return } // at least one day
            days = days.kind == Repeat.monthly ? Repeat.monthlyOn(chosen) : Repeat.weeklyOn(chosen)
        }))
    }

    private func apply(_ c: Choice) {
        switch c {
        case .every: days = .everyDay
        case .weekdays: days = Repeat(Repeat.weekly, Repeat.weekdays)
        case .weekends: days = Repeat(Repeat.weekly, Repeat.weekends)
        case .week: days = Repeat(Repeat.weekly, days.kind == Repeat.weekly ? days.days : [1, 3, 5])
        case .month: days = Repeat(Repeat.monthly, days.kind == Repeat.monthly ? days.days : [1])
        }
    }
}
