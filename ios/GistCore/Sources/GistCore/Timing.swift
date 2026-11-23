import Foundation

/// How long check-ins take (from SlotResult.took): getting started (first ring to Start
/// check-in) and doing it (Start check-in to done). A day with several times uses their
/// average; summaries use the median, so one slow morning doesn't swamp the rest. (The
/// Android app's Timing.)
public enum Timing {
    /// A day's average, in seconds. [start] is nil when every timed check-in that day was early.
    public struct Day: Equatable {
        public let day: String
        public let start: Int?
        public let steps: Int

        public init(day: String, start: Int?, steps: Int) {
            self.day = day
            self.start = start
            self.steps = steps
        }
    }

    /// Days among [days] (most recent first, as given) that have timings.
    public static func days(_ results: History, _ days: [String]) -> [Day] {
        days.compactMap { d in
            let took = (results[d] ?? [:]).values.compactMap { $0.took }
            guard !took.isEmpty else { return nil }
            let starts = took.compactMap { $0.start }
            return Day(day: d, start: starts.isEmpty ? nil : starts.reduce(0, +) / starts.count,
                       steps: took.map { $0.steps }.reduce(0, +) / took.count)
        }
    }

    public static func median(_ values: [Int]) -> Int? {
        guard !values.isEmpty else { return nil }
        let s = values.sorted()
        return s.count % 2 == 1 ? s[s.count / 2] : (s[s.count / 2 - 1] + s[s.count / 2]) / 2
    }

    /// e.g. "Getting started 1m 10s · doing it 3m 05s" (medians), or nil with nothing timed.
    public static func line(_ days: [Day]) -> String? {
        guard !days.isEmpty else { return nil }
        let start = median(days.compactMap { $0.start })
        let steps = median(days.map { $0.steps })!
        let parts = [start.map { "Getting started \(Took.text($0))" }, "doing it \(Took.text(steps))"].compactMap { $0 }
        let s = parts.joined(separator: " · ")
        return s.prefix(1).uppercased() + s.dropFirst()
    }
}
