import GistCore
import SwiftUI

@main
struct GistApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var model = AppModel.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .tint(Theme.primaryColor)
        }
    }
}

/// The start page, with whatever's ringing shown over it full screen.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var phase

    var body: some View {
        @Bindable var model = model
        StartView()
            .fullScreenCover(item: $model.running) { request in
                RunView(request: request)
                    .environment(model)
                    .interactiveDismissDisabled()
            }
            .sheet(item: $model.justDone, onDismiss: { model.doneWithTaskDone() }) { task in
                TaskDoneSheet(task: task)
                    .environment(model)
                    .presentationDetents([.medium])
            }
            .onChange(of: phase) { _, now in
                if now == .active { Task { await model.refresh() } }
            }
            .task {
                #if DEBUG
                let args = ProcessInfo.processInfo.arguments
                if args.contains("-gist-demo") { model.loadDemo() }
                if args.contains("-gist-demo-tasks") { model.loadDemoTasks() }
                if args.contains("-gist-sync-test") { model.debugSyncTest() }
                if let i = args.firstIndex(of: "-gist-route"), i + 1 < args.count {
                    model.debugRoute = args[i + 1]
                }
                #endif
                await model.refresh()
                // Keep in step with your other devices and connections while Gist is open.
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 30_000_000_000)
                    if phase == .active { model.syncNow() }
                }
            }
            .alert("Gist", isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(model.notice ?? "")
            }
    }
}
