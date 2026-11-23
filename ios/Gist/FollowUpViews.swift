import GistCore
import SwiftUI

/// After a task is done: "Task done", and a follow-up offered every time, to the backlog or
/// today's list; the new task's page opens to change it. (The Android app's Task done screen.)
struct TaskDoneSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let task: Checkin

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("Task done", systemImage: "checkmark.circle.fill")
                .font(.title.bold())
                .foregroundStyle(Theme.good)
                .accessibilityIdentifier("task-done-title")
            Text(task.name).font(.title3.bold())
            Text("Is there a next step? A follow-up starts as a copy of this task (named \"\(GistData.followUpName(task.name))\"), so you only have to change what's new.")
                .font(.subheadline)
                .foregroundStyle(Theme.muted)
            Spacer(minLength: 0)
            Button {
                follow(active: false)
            } label: {
                Text("Follow-up to the backlog").frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("follow-up:backlog")
            Button {
                follow(active: true)
            } label: {
                Text("Follow-up for today").frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("follow-up:today")
            Button("Close") { dismiss() }
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("task-done-close")
        }
        .padding(24)
    }

    private func follow(active: Bool) {
        model.createFollowUp(task.id, active: active)
        dismiss()
    }
}

/// "Create follow-up", with where it goes: the backlog or today's list. For a task's page and
/// Done tasks.
struct FollowUpButton: View {
    @Environment(AppModel.self) private var model
    let task: Checkin
    @State private var asking = false

    var body: some View {
        Button("Create follow-up") { asking = true }
            .accessibilityIdentifier("create-follow-up:\(task.id)")
            .confirmationDialog("Follow up \"\(task.name)\"", isPresented: $asking, titleVisibility: .visible) {
                Button("Add to backlog") { model.createFollowUp(task.id, active: false) }
                    .accessibilityIdentifier("follow-up:backlog")
                Button("Add to today") { model.createFollowUp(task.id, active: true) }
                    .accessibilityIdentifier("follow-up:today")
            } message: {
                Text("A new task with this one's name, description, category and settings, but no set time. Its page opens so you can change it.")
            }
    }
}
