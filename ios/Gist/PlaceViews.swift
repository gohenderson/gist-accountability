import CoreLocation
import GistCore
import SwiftUI

/// Choosing a place: your current location, coordinates from a maps app, or one you've
/// already used; then its name and how close counts.
struct PlacePicker: View {
    @Environment(AppModel.self) private var model
    @Binding var place: Place?
    @State private var coordinates = ""
    @State private var locating = false
    @State private var problem: String?

    /// Places used by any check-in, by key.
    private var used: [Place] {
        var seen = Set<String>()
        var out: [Place] = []
        for c in model.checkins {
            let ps = c.times.compactMap { $0.place } + c.events.compactMap { $0.place }
            for p in ps where seen.insert(p.key).inserted { out.append(p) }
        }
        return out.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        Section {
            if let p = place {
                LabeledContent("Name") {
                    TextField("Name", text: Binding(get: { p.name }, set: { place?.name = $0 }))
                        .multilineTextAlignment(.trailing)
                        .accessibilityIdentifier("place-name")
                }
                Picker("How close counts", selection: Binding(get: { p.radiusM }, set: { place?.radiusM = $0 })) {
                    ForEach(Array(Set(Place.radii + [p.radiusM])).sorted(), id: \.self) { r in
                        Text(r >= 1000 ? "\(r / 1000) km" : "\(r) m").tag(r)
                    }
                }
                LabeledContent("Coordinates", value: String(format: "%.5f, %.5f", p.lat, p.lon))
            } else {
                Text("No place chosen yet.").foregroundStyle(Theme.muted)
            }
        } header: {
            Text("Place")
        } footer: {
            Text("Location is usually accurate to 50–200 m, and noticing an arrival can take a few minutes, so 200 m is a good start.")
        }
        Section {
            Button {
                locating = true
                problem = nil
                Task {
                    if let l = await PlaceMonitor.shared.currentLocation() {
                        set(l.coordinate.latitude, l.coordinate.longitude)
                    } else {
                        problem = PlaceMonitor.shared.denied
                            ? "Gist isn't allowed to use your location. Allow it in Settings → Gist → Location."
                            : "Couldn't find your location. Try again, or enter coordinates."
                    }
                    locating = false
                }
            } label: {
                HStack {
                    Label("Use my current location", systemImage: "location.fill")
                    if locating { Spacer(); ProgressView() }
                }
            }
            .disabled(locating)
            .accessibilityIdentifier("use-current-location")
            HStack {
                TextField("Coordinates, e.g. 40.7128, -74.0060", text: $coordinates)
                    .keyboardType(.numbersAndPunctuation)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("place-coordinates")
                Button("Use") {
                    if let (lat, lon) = Place.parseCoordinates(coordinates) {
                        set(lat, lon)
                        coordinates = ""
                        problem = nil
                    } else {
                        problem = "Those aren't coordinates. Copy them from a maps app (touch and hold a spot)."
                    }
                }
                .disabled(coordinates.isEmpty)
            }
            if let problem { Text(problem).foregroundStyle(Theme.bad).font(.footnote) }
        } header: {
            Text(place == nil ? "Choose a place" : "Move it")
        }
        let others = used.filter { $0.key != place?.key }
        if !others.isEmpty {
            Section("Places you've used") {
                ForEach(others, id: \.key) { p in
                    Button(p.name) { place = p }
                }
            }
        }
    }

    private func set(_ lat: Double, _ lon: Double) {
        if place == nil {
            place = Place(name: "Place \(used.count + 1)", lat: lat, lon: lon)
        } else {
            place?.lat = lat
            place?.lon = lon
        }
    }
}

/// A time's "where": anywhere, or only when you're at or away from a place.
struct WhereEditor: View {
    @Binding var time: AlarmTime

    var body: some View {
        Form {
            Section {
                Picker("Rings", selection: $time.where) {
                    Text("Anywhere").tag(Trigger.anywhere)
                    Text("Only when I'm at a place").tag(Trigger.at)
                    Text("Only when I'm away from a place").tag(Trigger.away)
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } footer: {
                Text("Checked against where you were at that time. If it doesn't hold, that time is skipped (–). If Gist doesn't know where you were, it rings anyway.")
            }
            if time.where != Trigger.anywhere {
                PlacePicker(place: $time.place)
                LocationPermissionSection()
            }
        }
        .navigationTitle("Where")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// An arrive or leave trigger.
struct PlaceTriggerEditor: View {
    @Binding var trigger: Trigger
    @State private var editingDays = false

    var body: some View {
        Form {
            Section {
                Picker("When", selection: $trigger.kind) {
                    Text("When I arrive").tag(Trigger.arrive)
                    Text("When I leave").tag(Trigger.leave)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            }
            PlacePicker(place: $trigger.place)
            Section {
                Toggle("Only between two times", isOn: Binding(
                    get: { trigger.windowStart != nil && trigger.windowEnd != nil },
                    set: { on in
                        trigger.windowStart = on ? AlarmTime(6, 0) : nil
                        trigger.windowEnd = on ? AlarmTime(10, 0) : nil
                    }))
                if trigger.windowStart != nil {
                    DatePicker("From", selection: windowBinding(\.windowStart), displayedComponents: .hourAndMinute)
                    DatePicker("To", selection: windowBinding(\.windowEnd), displayedComponents: .hourAndMinute)
                }
                NavigationLink {
                    DaysEditor(days: $trigger.days)
                } label: {
                    LabeledContent("Days", value: trigger.days.describe())
                }
                Toggle("At most once a day", isOn: $trigger.oncePerDay)
            } footer: {
                Text("A window can cross midnight (10:00 PM to 2:00 AM).")
            }
            LocationPermissionSection()
        }
        .navigationTitle(trigger.kind == Trigger.arrive ? "Arrive" : "Leave")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func windowBinding(_ key: WritableKeyPath<Trigger, AlarmTime?>) -> Binding<Date> {
        Binding {
            let t = trigger[keyPath: key] ?? AlarmTime(12, 0)
            return Calendar.current.date(bySettingHour: t.hour, minute: t.minute, second: 0, of: Date()) ?? Date()
        } set: { date in
            let c = Calendar.current.dateComponents([.hour, .minute], from: date)
            trigger[keyPath: key] = AlarmTime(c.hour ?? 0, c.minute ?? 0)
        }
    }
}

/// Asks for location "always" when it isn't, so places work while Gist is closed.
struct LocationPermissionSection: View {
    private var monitor: PlaceMonitor { PlaceMonitor.shared }

    var body: some View {
        if !monitor.always {
            Section {
                Button(monitor.denied ? "Open Settings" : "Allow location") { monitor.requestAlways() }
                    .accessibilityIdentifier("allow-location")
            } footer: {
                Text(monitor.denied
                     ? "Location is off for Gist. In Settings → Gist → Location, choose Always."
                     : "To notice places while Gist is closed, iOS needs location allowed Always. Your location never leaves the phone.")
            }
        }
    }
}

enum PlaceText {
    /// "When you arrive at Gym", "When you leave Home"
    static func describe(_ t: Trigger) -> String {
        let name = t.place?.name ?? "a place (not set)"
        switch t.kind {
        case Trigger.arrive: return "When you arrive at \(name)"
        case Trigger.leave: return "When you leave \(name)"
        case Trigger.runStart: return "When you start running"
        case Trigger.runEnd: return "When you finish running"
        default: return "\(Trigger.runForText(t.minRunMinutes)) into a run"
        }
    }
}
