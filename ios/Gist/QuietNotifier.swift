import GistCore
import UserNotifications

/// "No alarm" check-ins and tasks: instead of an alarm, a quiet notification (no sound, not
/// lighting the screen) when one comes due; it then waits on the start page. (The Android
/// app's silent "checkin_quiet" notification.) Each notification's id names the ring, so
/// it's cleared once that ring is done, dismissed or skipped.
enum QuietNotifier {
    static let prefix = "quiet|"

    static func id(_ p: Pending) -> String { prefix + p.id }

    /// Schedules [rings] (each No alarm check-in's next one), replacing any scheduled before.
    static func sync(_ rings: [PlannedAlarm], names: [String: String]) async {
        let center = UNUserNotificationCenter.current()
        let old = await center.pendingNotificationRequests().map { $0.identifier }.filter { $0.hasPrefix(prefix) }
        center.removePendingNotificationRequests(withIdentifiers: old)
        guard !rings.isEmpty, await allowed() else { return }
        for r in rings {
            let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: r.at)
            let request = UNNotificationRequest(identifier: id(r.pending), content: content(names[r.checkinId] ?? "Gist"),
                                                trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: false))
            try? await center.add(request)
        }
    }

    /// Shows [p]'s reminder now (a place or a run set it off).
    static func showNow(_ p: Pending, name: String) async {
        guard await allowed() else { return }
        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id(p), content: content(name), trigger: nil))
    }

    /// Takes away reminders for rings that aren't waiting any more.
    static func clear(keeping waiting: [Pending]) async {
        let center = UNUserNotificationCenter.current()
        let keep = Set(waiting.map { id($0) })
        let gone = await center.deliveredNotifications().map { $0.request.identifier }
            .filter { $0.hasPrefix(prefix) && !keep.contains($0) }
        center.removeDeliveredNotifications(withIdentifiers: gone)
    }

    private static func content(_ name: String) -> UNMutableNotificationContent {
        let c = UNMutableNotificationContent()
        c.title = name
        c.body = "Waiting for you in Gist."
        c.sound = nil
        c.interruptionLevel = .passive
        c.threadIdentifier = "quiet"
        return c
    }

    /// Asks once (alerts only: they're quiet anyway); after that, whatever was chosen.
    private static func allowed() async -> Bool {
        let center = UNUserNotificationCenter.current()
        switch await center.notificationSettings().authorizationStatus {
        case .authorized, .provisional, .ephemeral: return true
        case .notDetermined: return (try? await center.requestAuthorization(options: [.alert, .badge])) ?? false
        default: return false
        }
    }
}
