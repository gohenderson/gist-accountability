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
            .onChange(of: phase) { _, now in
                if now == .active { Task { await model.refresh() } }
            }
            .task {
                #if DEBUG
                let args = ProcessInfo.processInfo.arguments
                if args.contains("-gist-demo") { model.loadDemo() }
                if let i = args.firstIndex(of: "-gist-route"), i + 1 < args.count {
                    model.debugRoute = args[i + 1]
                }
                #endif
                await model.refresh()
            }
            .alert("Gist", isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(model.notice ?? "")
            }
    }
}
