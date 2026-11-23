import Foundation

/// The start page's tabs (the Android app's): Check-ins (everything that isn't a task; they
/// still ring while another tab is shown), Tasks (active ones) and Backlog (tasks that are off).
public enum StartTab: String, CaseIterable, Identifiable {
    case checkins, tasks, backlog

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .checkins: return "Check-ins"
        case .tasks: return "Tasks"
        case .backlog: return "Backlog"
        }
    }

    /// What this tab lists (done tasks are on their own page).
    public func items(_ checkins: [Checkin]) -> [Checkin] {
        checkins.filter { c in
            switch self {
            case .checkins: return !c.isTask
            case .tasks: return c.isTask && !c.inBacklog && !c.taskDone
            case .backlog: return c.inBacklog
            }
        }
    }

    /// What the tab says when it has nothing to list.
    public func empty(anyCheckins: Bool) -> String {
        switch self {
        case .checkins: return anyCheckins ? "No check-ins here: they're all tasks. Tap + to add one." : "No check-ins yet. Add one to get started."
        case .tasks: return "No active tasks. Tap + for a new one, or pick some from the Backlog."
        case .backlog: return "The backlog is empty. Tap + to add a task to it."
        }
    }
}
