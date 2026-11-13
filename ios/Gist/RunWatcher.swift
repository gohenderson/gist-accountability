import CoreLocation
import CoreMotion
import Foundation
import GistCore
import Observation

/// Notices runs, like the Android app: it counts steps all day and calls it a run when the
/// pace stays high (see RunDetector). iOS only lets an app keep counting in the background
/// while it uses location, so while any check-in has a run trigger, Gist keeps location on
/// at its lowest accuracy (cell towers and Wi-Fi, not GPS) and ignores where you are. It
/// also asks iOS to wake it after a restart (significant location changes).
@MainActor
@Observable
final class RunWatcher: NSObject, CLLocationManagerDelegate {
    static let shared = RunWatcher()

    private let pedometer = CMPedometer()
    private let manager = CLLocationManager()
    private var detector = RunDetector()
    private var ticker: Task<Void, Never>?
    /// "N minutes into a run" already rung this run.
    private var fired: Set<String> = []

    private(set) var watching = false
    private(set) var running = false
    private(set) var motion: CMAuthorizationStatus = CMPedometer.authorizationStatus()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
        manager.distanceFilter = CLLocationDistanceMax
        manager.pausesLocationUpdatesAutomatically = false
        manager.activityType = .fitness
    }

    /// Can this iPhone count steps?
    var supported: Bool { CMPedometer.isStepCountingAvailable() }
    var motionDenied: Bool { motion == .denied || motion == .restricted }

    /// Why run triggers won't work right now, in a sentence ("" if they will).
    var problem: String {
        if !supported { return "This device can't count steps, so runs can't be noticed." }
        if motionDenied { return "Gist isn't allowed Motion & Fitness. Allow it in Settings → Gist." }
        if PlaceMonitor.shared.denied { return "Gist isn't allowed location, which iOS needs to let it count steps in the background. In Settings → Gist → Location, choose Always." }
        if !PlaceMonitor.shared.always { return "Allow location Always, so Gist can keep counting steps with the app closed." }
        return ""
    }

    /// Watches while any check-in has a run trigger (and it can), stops otherwise.
    func sync(_ checkins: [Checkin]) {
        let want = Runs.anyUse(checkins) && supported && !motionDenied && !PlaceMonitor.shared.denied
        if want && !watching { start() }
        if !want && watching { stop() }
    }

    private func start() {
        watching = true
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = false
        manager.startUpdatingLocation()
        manager.startMonitoringSignificantLocationChanges()
        startCounting()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(15))
                await self?.tick()
            }
        }
    }

    private func stop() {
        watching = false
        running = false
        pedometer.stopUpdates()
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        ticker?.cancel()
        ticker = nil
        detector = RunDetector()
    }

    private func startCounting() {
        let started = Date()
        pedometer.startUpdates(from: started) { [weak self] data, _ in
            guard let data else { return }
            let steps = data.numberOfSteps.intValue
            let at = data.endDate
            Task { @MainActor in
                self?.motion = CMPedometer.authorizationStatus()
                self?.handle(self?.detector.steps(steps, at: at))
            }
        }
    }

    /// Every 15 s: lets a run finish when the steps stop, rings milestones, and speaks
    /// voice check-ins whose time has come (Gist is awake, so it can).
    private func tick() async {
        let now = Date()
        handle(detector.tick(now))
        if let minutes = detector.minutes(at: now) {
            fired.formUnion(AppModel.shared.runProgress(minutes: minutes, fired: fired))
        }
        AppModel.shared.speakDueVoices()
    }

    private func handle(_ event: RunDetector.Event?) {
        guard let event else { return }
        switch event {
        case .started:
            running = true
            fired = []
            AppModel.shared.runEvent(finishedMinutes: nil)
        case .finished:
            running = false
            AppModel.shared.runEvent(finishedMinutes: event.minutes ?? 0)
        }
    }

    // MARK: Location (only to stay awake)

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {}

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in self.sync(AppModel.shared.checkins) }
    }

    /// Asks for Motion & Fitness (iOS asks the first time steps are read).
    func requestMotion() {
        let now = Date()
        pedometer.queryPedometerData(from: now.addingTimeInterval(-60), to: now) { _, _ in
            Task { @MainActor in
                self.motion = CMPedometer.authorizationStatus()
                self.sync(AppModel.shared.checkins)
            }
        }
    }
}
