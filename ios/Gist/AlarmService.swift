import AlarmKit
import AppIntents
import Foundation
import GistCore
import SwiftUI

/// Gist's alarms, through AlarmKit (iOS 26): real alarms that sound in Silent mode and
/// Focus. The system shows them; besides Stop, each has "Check in", which opens Gist.
/// Strict check-ins also get re-rings (see AlarmPlan) until they're done.
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
    static func replaceAll(with plan: [PlannedAlarm], names: [String: String]) async -> Int {
        guard authorized else { return 0 }
        if let existing = try? manager.alarms {
            for alarm in existing { try? manager.cancel(id: alarm.id) }
        }
        var set = 0
        for p in plan where p.at > Date() {
            let title = names[p.checkinId] ?? "Gist"
            if await schedule(p, title: p.practice ? "Test: \(title)" : title) { set += 1 }
        }
        return set
    }

    private static func schedule(_ p: PlannedAlarm, title: String) async -> Bool {
        let id = UUID()
        let alert = AlarmPresentation.Alert(
            title: LocalizedStringResource(stringLiteral: title),
            stopButton: AlarmButton(text: "Stop", textColor: .white, systemImageName: "stop.fill"),
            secondaryButton: AlarmButton(text: "Check in", textColor: .white, systemImageName: "checklist"),
            secondaryButtonBehavior: .custom
        )
        let attributes = AlarmAttributes<GistAlarmMetadata>(
            presentation: AlarmPresentation(alert: alert),
            tintColor: Theme.primaryColor
        )
        let configuration = AlarmManager.AlarmConfiguration<GistAlarmMetadata>(
            schedule: .fixed(p.at),
            attributes: attributes,
            secondaryIntent: CheckInIntent(alarmID: id.uuidString)
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
