import AlarmKit
import AppIntents
import Foundation
import GistCore
import SwiftUI

/// Gist's alarms, through AlarmKit (iOS 26): real alarms that sound in Silent mode and
/// Focus. The system shows them; besides Stop, each has "Check in", which opens Gist.
/// Strict check-ins also get re-rings (see AlarmPlan) until they're done. Soft ones get
/// "Snooze" instead of "Check in" (open Gist to do one).
struct GistAlarmMetadata: AlarmMetadata {}

@MainActor
enum AlarmService {
    static var manager: AlarmManager { AlarmManager.shared }

    static var authorized: Bool { manager.authorizationState == .authorized }
    static var notDetermined: Bool { manager.authorizationState == .notDetermined }

    /// Asks for permission to set alarms (once; after that, only Settings can change it).
    static func requestAuthorization() async -> Bool {
        do {
            return try await manager.requestAuthorization() == .authorized
        } catch {
            return false
        }
    }

    /// Replaces every alarm Gist has set with [plan]. Returns how many were set.
    @discardableResult
    static func replaceAll(with plan: [PlannedAlarm], checkins: [String: Checkin]) async -> Int {
        guard authorized else { return 0 }
        if let existing = try? manager.alarms {
            for alarm in existing { try? manager.cancel(id: alarm.id) }
        }
        var set = 0
        for p in plan where p.at > Date() {
            let c = checkins[p.checkinId]
            let title = c?.name ?? "Gist"
            let snooze = (c?.isSoft == true && !p.practice) ? c?.snoozeMinutes : nil
            if await schedule(p, title: p.practice ? "Test: \(title)" : title, snoozeMinutes: snooze) { set += 1 }
        }
        return set
    }

    /// Rings [p]; with [snoozeMinutes], its second button is Snooze instead of Check in.
    @discardableResult
    static func schedule(_ p: PlannedAlarm, title: String, snoozeMinutes: Int?) async -> Bool {
        let id = UUID()
        let secondary = snoozeMinutes == nil
            ? AlarmButton(text: "Check in", textColor: .white, systemImageName: "checklist")
            : AlarmButton(text: "Snooze", textColor: .white, systemImageName: "zzz")
        let alert = AlarmPresentation.Alert(
            title: LocalizedStringResource(stringLiteral: title),
            stopButton: AlarmButton(text: "Stop", textColor: .white, systemImageName: "stop.fill"),
            secondaryButton: secondary,
            secondaryButtonBehavior: .custom
        )
        let attributes = AlarmAttributes<GistAlarmMetadata>(
            presentation: AlarmPresentation(alert: alert),
            tintColor: Theme.primaryColor
        )
        let intent: any LiveActivityIntent
        if let minutes = snoozeMinutes {
            intent = SnoozeIntent(alarmID: id.uuidString, checkinId: p.checkinId, day: p.day, time: p.time,
                                  minutes: minutes, title: title)
        } else {
            intent = CheckInIntent(alarmID: id.uuidString)
        }
        let configuration = AlarmManager.AlarmConfiguration<GistAlarmMetadata>(
            schedule: .fixed(p.at),
            attributes: attributes,
            secondaryIntent: intent
        )
        do {
            _ = try await manager.schedule(id: id, configuration: configuration)
            return true
        } catch {
            return false
        }
    }
}

/// The alarm's "Check in" button: stops the ringing and opens Gist, which shows what's waiting.
struct CheckInIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Check in"
    static var description = IntentDescription("Opens Gist to do the check-in that's ringing.")
    static var openAppWhenRun: Bool = true

    @Parameter(title: "Alarm ID")
    var alarmID: String

    init() {
        alarmID = ""
    }

    init(alarmID: String) {
        self.alarmID = alarmID
    }

    func perform() async throws -> some IntentResult {
        if let id = UUID(uuidString: alarmID) {
            try? AlarmManager.shared.stop(id: id)
        }
        return .result()
    }
}

/// Snoozes saved for Gist to keep setting (every reschedule replaces all its alarms).
enum SnoozeStore {
    private static let key = "snoozes"

    static func all(now: Date = Date()) -> [Snooze] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let list = try? JSONDecoder().decode([Snooze].self, from: data) else { return [] }
        return list.filter { $0.at > now }
    }

    static func add(_ z: Snooze) {
        var list = all().filter { !($0.checkinId == z.checkinId && $0.day == z.day && $0.time == z.time) }
        list.append(z)
        UserDefaults.standard.set(try? JSONEncoder().encode(list), forKey: key)
    }
}

/// A soft alarm's "Snooze" button: stops the ringing and rings again in a few minutes,
/// without opening Gist.
struct SnoozeIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Snooze"
    static var description = IntentDescription("Rings this check-in again in a few minutes.")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Alarm ID") var alarmID: String
    @Parameter(title: "Check-in") var checkinId: String
    @Parameter(title: "Day") var day: String
    @Parameter(title: "Time") var time: String
    @Parameter(title: "Minutes") var minutes: Int
    @Parameter(title: "Title") var alarmTitle: String

    init() {
        alarmID = ""
        checkinId = ""
        day = ""
        time = ""
        minutes = Checkin.defaultSnooze
        alarmTitle = "Gist"
    }

    init(alarmID: String, checkinId: String, day: String, time: String, minutes: Int, title: String) {
        self.alarmID = alarmID
        self.checkinId = checkinId
        self.day = day
        self.time = time
        self.minutes = minutes
        self.alarmTitle = title
    }

    func perform() async throws -> some IntentResult {
        if let id = UUID(uuidString: alarmID) {
            try? AlarmManager.shared.stop(id: id)
        }
        let z = Snooze(checkinId: checkinId, day: day, time: time,
                       at: Date().addingTimeInterval(TimeInterval(max(1, minutes) * 60)))
        SnoozeStore.add(z)
        await AlarmService.schedule(PlannedAlarm(checkinId: z.checkinId, day: z.day, time: z.time, at: z.at, snoozed: true),
                                    title: alarmTitle, snoozeMinutes: minutes)
        return .result()
    }
}
