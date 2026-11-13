import Foundation

/// Ready-made check-ins to pick from (start page → Add check-in), the same as the Android
/// app's (Library.kt). Adding one copies it into your list with a new id; after that it's
/// yours to change like any other.
public enum Library {
    public struct Template: Equatable, Identifiable {
        public let key: String
        public let category: String
        public let blurb: String
        public let checkin: Checkin
        public var id: String { key }

        /// Can the iPhone app do everything this template needs? (Places need [places].)
        public func worksOnIPhone(places: Bool) -> Bool {
            checkin.events.allSatisfy { places || !$0.isPlace }
                && (places || checkin.times.allSatisfy { $0.where == Trigger.anywhere })
        }
    }

    private static func q(_ id: String, _ text: String, _ yesIsGood: Bool = false) -> Question {
        Question(id: id, text: text, yesIsGood: yesIsGood)
    }

    private static func t(_ h: Int, _ m: Int = 0) -> AlarmTime { AlarmTime(h, m) }

    private static func qsteps(_ qs: Question...) -> [StepConfig] {
        [StepConfig(id: "q", kind: StepConfig.questions, questions: qs)]
    }

    private static func template(_ key: String, _ category: String, _ blurb: String, _ c: Checkin) -> Template {
        Template(key: key, category: category, blurb: blurb, checkin: c)
    }

    public static let templates: [Template] = [
        template("diet-evening", "Food", "The original: five yes/no questions about what you ate today.",
                 Checkin(id: "", name: "Diet gist", times: [t(21, 30)], steps: qsteps(
                     q("sweetdrink", "Did you drink any sweetened beverages today?"),
                     q("fastfood", "Did you eat fast food today?"),
                     q("sweets", "Did you eat anything sweet today?"),
                     q("veg", "Did you eat vegetables or fruit today?", true),
                     q("homecooked", "Did you eat a home-cooked meal today?", true)))),
        template("last-night", "Food", "A morning look back at last night. Answers count for the previous day.",
                 Checkin(id: "", name: "Last night", times: [t(7)], aboutYesterday: true, steps: qsteps(
                     q("latefood", "Did you eat anything after 9 PM last night?"),
                     q("alcohol", "Did you drink alcohol last night?")))),
        template("water", "Food", "Three nudges a day to drink water.",
                 Checkin(id: "", name: "Water", times: [t(11), t(15), t(19)],
                         steps: qsteps(q("water", "Have you had a glass of water in the last few hours?", true)))),
        template("wake-up", "Wake-up",
                 "Hard to dismiss: solve three math problems. Tip: add a scan of something in another room so you have to get up.",
                 Checkin(id: "", name: "Wake up", times: [t(6, 30)], repeat: Repeat.weeklyOn(Repeat.weekdays),
                         steps: [StepConfig(id: "m", kind: StepConfig.math, mathCount: 3, mathLevel: 3)])),
        template("weekend-wake-up", "Wake-up", "A gentler weekend alarm: one easy math problem.",
                 Checkin(id: "", name: "Weekend wake up", times: [t(8, 30)], repeat: Repeat.weeklyOn(Repeat.weekends),
                         steps: [StepConfig(id: "m", kind: StepConfig.math, mathCount: 1, mathLevel: 1)])),
        template("medication", "Health",
                 "A daily reminder that won't go away until you confirm. Tip: register a scan of the pill bottle.",
                 Checkin(id: "", name: "Medication", times: [t(8)], steps: qsteps(q("meds", "Did you take your medication?", true)))),
        template("teeth", "Health", "Morning and night. Tip: register a scan of your toothbrush holder.",
                 Checkin(id: "", name: "Brush teeth", times: [t(7, 30), t(22, 15)], steps: qsteps(q("floss", "Did you floss?", true)))),
        template("bedtime", "Health", "Wind down for better sleep.",
                 Checkin(id: "", name: "Bedtime", times: [t(22, 30)], steps: qsteps(
                     q("screens", "Are screens off for the night?", true),
                     q("caffeine", "Did you have caffeine after 2 PM?")))),
        template("workout", "Fitness", "Mon, Wed and Fri evenings.",
                 Checkin(id: "", name: "Workout", times: [t(18)], repeat: Repeat.weeklyOn([1, 3, 5]),
                         steps: qsteps(q("workout", "Did you work out today?", true)))),
        template("stretch", "Fitness", "Twice each work day: get up and move.",
                 Checkin(id: "", name: "Stand & stretch", times: [t(10, 30), t(14, 30)], repeat: Repeat.weeklyOn(Repeat.weekdays),
                         steps: qsteps(q("stretch", "Have you stood up and stretched in the last hour?", true)))),
        template("steps", "Fitness", "An evening check on your daily movement.",
                 Checkin(id: "", name: "Daily steps", times: [t(20)], steps: qsteps(
                     q("walk", "Did you walk for 30 minutes or more today?", true),
                     q("outside", "Did you spend time outside today?", true)))),
        template("gym", "Fitness",
                 "Rings when you arrive at the gym (on work-out days), with a quick question. Pick your gym after adding it.",
                 Checkin(id: "", name: "At the gym", times: [],
                         steps: qsteps(q("warmup", "Did you plan today's workout before you started?", true)),
                         events: [Trigger(kind: Trigger.arrive, oncePerDay: true, days: Repeat.weeklyOn([1, 3, 5]))])),
        template("after-run", "Fitness",
                 "Rings when you finish a run of 10 minutes or more (noticed from your steps): a cool-down check.",
                 Checkin(id: "", name: "After my run", times: [],
                         steps: qsteps(q("stretch-after", "Did you stretch after your run?", true),
                                       q("water-after", "Did you drink water after your run?", true)),
                         events: [Trigger(kind: Trigger.runEnd, oncePerDay: true, minRunMinutes: 10)])),
        template("run-cheer", "Fitness",
                 "A voice in your headphones 30 minutes into a run. No alarm, nothing to tap. Change the line, or record your own.",
                 Checkin(id: "", name: "30 minutes in", times: [],
                         events: [Trigger(kind: Trigger.runFor, oncePerDay: false, minRunMinutes: 30)],
                         commitment: Checkin.voice, voiceText: "Thirty minutes in. Look at you go.")),
        template("weigh-in", "Fitness", "Sunday mornings.",
                 Checkin(id: "", name: "Weekly weigh-in", times: [t(8)], repeat: Repeat.weeklyOn([7]),
                         steps: qsteps(q("weighed", "Did you weigh yourself and note it down?", true)))),
        template("journal", "Mind", "A few minutes of reflection at the end of the day.",
                 Checkin(id: "", name: "Journal", times: [t(21)], steps: qsteps(
                     q("journal", "Did you write in your journal today?", true),
                     q("grateful", "Did you note one thing you're grateful for?", true)))),
        template("reading", "Mind", "Read a little every day.",
                 Checkin(id: "", name: "Reading", times: [t(21, 30)],
                         steps: qsteps(q("read", "Did you read for at least 15 minutes today?", true)))),
        template("money", "Money", "On the 1st of each month, look back at last month's spending.",
                 Checkin(id: "", name: "Monthly money check", times: [t(9)], repeat: Repeat.monthlyOn([1]), steps: qsteps(
                     q("reviewed", "Did you review last month's spending?", true),
                     q("budget", "Did you stay within budget last month?", true)))),
        template("bills", "Money", "Mid-month and month-end bill reminders.",
                 Checkin(id: "", name: "Pay bills", times: [t(19)], repeat: Repeat.monthlyOn([15, Repeat.lastDay]),
                         steps: qsteps(q("paid", "Are all bills due this week paid?", true)))),
    ]

    /// The categories, in the library's order.
    public static var categories: [String] {
        var seen = Set<String>()
        return templates.map { $0.category }.filter { seen.insert($0).inserted }
    }

    public static func template(_ key: String) -> Template? { templates.first { $0.key == key } }

    /// A copy of [template] ready to add to [existing]: a fresh id, and a number after the
    /// name if you already have one called that ("Water 2").
    public static func instantiate(_ template: Template, existing: [Checkin], newId: String = Ids.new()) -> Checkin {
        let names = Set(existing.map { $0.name })
        let base = template.checkin.name
        let name = names.contains(base) ? ((2...999).map { "\(base) \($0)" }.first { !names.contains($0) } ?? base) : base
        var c = template.checkin
        c.id = newId
        c.name = name
        c.enabled = true
        c.category = template.category
        return c
    }

    /// A blank check-in for "Create your own".
    public static func blank(existing: [Checkin], newId: String = Ids.new()) -> Checkin {
        Checkin(id: newId, name: "Check-in \(existing.count + 1)", times: [AlarmTime(12, 0)])
    }
}

/// Matching a scanned code against a scan step's registered one.
public enum Scan {
    /// The same code, allowing for how scanners report a UPC-A barcode: some as its 12
    /// digits (the Android app's), others as the equivalent 13-digit EAN with a leading 0
    /// (the iPhone's), so a code registered on one phone still matches on the other.
    public static func matches(_ registered: String?, _ scanned: String) -> Bool {
        guard let registered, !registered.isEmpty else { return false }
        let a = registered.trimmingCharacters(in: .whitespacesAndNewlines)
        let b = scanned.trimmingCharacters(in: .whitespacesAndNewlines)
        if a == b { return true }
        func digits(_ s: String) -> Bool { !s.isEmpty && s.allSatisfy { $0.isASCII && $0.isNumber } }
        guard digits(a), digits(b) else { return false }
        return (a.count == 13 && b.count == 12 && a == "0" + b) || (b.count == 13 && a.count == 12 && b == "0" + a)
    }

    /// After this long on a scan step, a "Can't scan?" way out appears (5 very hard problems).
    public static let fallbackAfter: TimeInterval = 120
    public static let fallbackProblems = 5
    public static let fallbackLevel = 4
}
