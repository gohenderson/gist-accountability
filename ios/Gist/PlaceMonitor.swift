import CoreLocation
import Foundation
import GistCore
import Observation
import UIKit

/// Location for places: permission, "where am I now", and watching places. iOS watches
/// each place's circle itself (CLMonitor) and wakes Gist when you cross it, even when Gist
/// isn't running. Nothing leaves the phone.
@MainActor
@Observable
final class PlaceMonitor: NSObject, CLLocationManagerDelegate {
    static let shared = PlaceMonitor()

    private(set) var authorization: CLAuthorizationStatus
    private let manager = CLLocationManager()
    private var monitor: CLMonitor?
    private var listening: Task<Void, Never>?
    private var session: CLServiceSession?
    private let defaults = UserDefaults.standard

    override init() {
        authorization = manager.authorizationStatus
        super.init()
        manager.delegate = self
    }

    /// Allowed all the time: places work while Gist is closed.
    var always: Bool { authorization == .authorizedAlways }
    var denied: Bool { authorization == .denied || authorization == .restricted }
    var notDetermined: Bool { authorization == .notDetermined }

    /// Asks for location "always" (iOS first offers "While using", then later asks to upgrade;
    /// asking again upgrades it). Once denied, only Settings can change it.
    func requestAlways() {
        if denied {
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        } else if notDetermined {
            manager.requestWhenInUseAuthorization()
        } else {
            manager.requestAlwaysAuthorization()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            let wasNotDetermined = self.authorization == .notDetermined
            self.authorization = status
            // Right after "While using", ask for "always" too (iOS shows it once).
            if wasNotDetermined && status == .authorizedWhenInUse { self.manager.requestAlwaysAuthorization() }
            await AppModel.shared.placesChanged()
        }
    }

    /// Where the phone is now (a fresh fix within [timeout] seconds), or nil.
    func currentLocation(timeout: TimeInterval = 15) async -> CLLocation? {
        if notDetermined {
            manager.requestWhenInUseAuthorization()
            return nil
        }
        guard !denied else { return nil }
        return await withTaskGroup(of: CLLocation?.self) { group in
            group.addTask {
                do {
                    for try await update in CLLocationUpdate.liveUpdates() {
                        if let l = update.location, l.horizontalAccuracy >= 0, l.horizontalAccuracy < 500 { return l }
                    }
                } catch {}
                return nil
            }
            group.addTask {
                try? await Task.sleep(for: .seconds(timeout))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    // MARK: Watching

    /// Watches exactly [places] (by key); stops watching the rest. Starts listening for
    /// crossings the first time. Called at launch and whenever check-ins change.
    func sync(_ places: [String: Place]) async {
        guard !places.isEmpty, !denied else {
            if let monitor { for id in await monitor.identifiers { await monitor.remove(id) } }
            return
        }
        if notDetermined { return }
        let monitor = await ensureMonitor()
        let have = Set(await monitor.identifiers)
        for id in have where places[id] == nil { await monitor.remove(id) }
        var added = addedAt
        for (key, p) in places.sorted(by: { $0.key < $1.key }).prefix(Places.maxWatched) where !have.contains(key) {
            let condition = CLMonitor.CircularGeographicCondition(
                center: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon), radius: CLLocationDistance(p.radiusM))
            await monitor.add(condition, identifier: key)
            added[key] = Date()
        }
        addedAt = added.filter { places[$0.key] != nil }
        if always && session == nil { session = CLServiceSession(authorization: .always) }
    }

    /// At launch (including when iOS wakes Gist for a crossing): listen again.
    func start() {
        Task { await sync(Places.watched(AppModel.shared.checkins)) }
    }

    private func ensureMonitor() async -> CLMonitor {
        if let monitor { return monitor }
        let m = await CLMonitor("gist-places")
        monitor = m
        listening = Task { [weak self] in
            do {
                for try await event in await m.events {
                    guard let self else { return }
                    let inside: Bool
                    switch event.state {
                    case .satisfied: inside = true
                    case .unsatisfied: inside = false
                    default: continue
                    }
                    let settling = (self.addedAt[event.identifier]).map { Date().timeIntervalSince($0) < Places.settleSeconds } ?? false
                    await AppModel.shared.placeCrossed(
                        PlaceCrossing(key: event.identifier, inside: inside, at: event.date), settling: settling)
                }
            } catch {}
        }
        return m
    }

    /// When each place started being watched (for the settling period).
    private var addedAt: [String: Date] {
        get { (defaults.dictionary(forKey: "placeAddedAt") as? [String: Date]) ?? [:] }
        set { defaults.set(newValue, forKey: "placeAddedAt") }
    }
}

/// Starts watching places and runs at launch (including when iOS wakes Gist in the
/// background), so a crossing or a run is heard.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        MainActor.assumeIsolated {
            PlaceMonitor.shared.start()
            RunWatcher.shared.sync(AppModel.shared.checkins)
        }
        return true
    }
}
