import AVFoundation
import GistCore
import SwiftUI
import UniformTypeIdentifiers

/// A run trigger: starting a run, finishing one (of at least so many minutes), or so many
/// minutes into one.
struct RunTriggerEditor: View {
    @Binding var trigger: Trigger
    private var watcher: RunWatcher { RunWatcher.shared }

    /// Minutes offered for "into a run" and "at least".
    static let minuteChoices = [5, 10, 15, 20, 30, 45, 60, 90, 120, 180]

    var body: some View {
        Form {
            Section {
                Picker("When", selection: $trigger.kind) {
                    Text("I start running").tag(Trigger.runStart)
                    Text("I finish a run").tag(Trigger.runEnd)
                    Text("I've been running for…").tag(Trigger.runFor)
                }
                .pickerStyle(.inline)
                .labelsHidden()
                .accessibilityIdentifier("run-kind")
                if trigger.kind == Trigger.runEnd {
                    Picker("Of at least", selection: $trigger.minRunMinutes) {
                        Text("Any length").tag(0)
                        ForEach(choices, id: \.self) { Text(Trigger.runForText($0)).tag($0) }
                    }
                } else if trigger.kind == Trigger.runFor {
                    Picker("Running for", selection: Binding(get: { max(1, trigger.minRunMinutes) },
                                                             set: { trigger.minRunMinutes = $0 })) {
                        ForEach(choices, id: \.self) { Text(Trigger.runForText($0)).tag($0) }
                    }
                    .accessibilityIdentifier("run-minutes")
                }
            } footer: {
                Text("A run is a fast, steady pace (about 140+ steps a minute) for a minute or more. It's finished after 90 seconds below that, so a traffic light doesn't end it. \(trigger.kind == Trigger.runFor ? "Each runs once per run; add several for a running commentary." : "")")
            }
            TriggerConditions(trigger: $trigger)
            if !watcher.problem.isEmpty {
                Section {
                    if watcher.motionDenied || PlaceMonitor.shared.denied {
                        Button("Open Settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        }
                    } else if watcher.motion == .notDetermined {
                        Button("Allow Motion & Fitness") { watcher.requestMotion() }
                    } else {
                        Button("Allow location") { PlaceMonitor.shared.requestAlways() }
                    }
                } footer: {
                    Text(watcher.problem)
                }
            }
            Section {
            } footer: {
                Text("Like on Android, Gist counts steps all day while a check-in has a run trigger. iOS only allows that with location on, so Gist keeps it on at its lowest accuracy (no GPS) and never looks at where you are. It uses some battery; turn the check-in off to stop.")
            }
        }
        .navigationTitle("Run trigger")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: trigger.kind) { _, kind in
            if kind == Trigger.runFor && trigger.minRunMinutes < 1 { trigger.minRunMinutes = 30 }
        }
    }

    private var choices: [Int] {
        Array(Set(Self.minuteChoices + (trigger.minRunMinutes > 0 ? [trigger.minRunMinutes] : []))).sorted()
    }
}

/// What a voice check-in says: typed text, or a clip recorded here or picked from Files.
struct VoiceSection: View {
    @Binding var checkin: Checkin
    @StateObject private var recorder = ClipRecorder()
    @State private var picking = false
    @State private var problem: String?
    @State private var version = 0

    var body: some View {
        Section {
            Picker("Voice", selection: $checkin.voiceSource) {
                Text("Spoken text").tag(Checkin.voiceText)
                Text("Recorded clip").tag(Checkin.voiceClip)
            }
            .accessibilityIdentifier("voice-source")
            if checkin.voiceSource == Checkin.voiceClip {
                LabeledContent("Clip", value: clipStatus).id(version)
                    .accessibilityIdentifier("voice-clip-status")
                if recorder.recording {
                    Button("Stop recording (\(recorder.seconds) s)", role: .destructive) { finishRecording() }
                        .accessibilityIdentifier("voice-stop")
                } else {
                    Button("Record a clip") { record() }
                        .accessibilityIdentifier("voice-record")
                    Button("Choose an audio file") { picking = true }
                        .accessibilityIdentifier("voice-pick")
                }
            } else {
                TextField("What it says, e.g. Halfway there", text: $checkin.voiceText, axis: .vertical)
                    .accessibilityIdentifier("voice-text")
            }
            Toggle("Only through headphones", isOn: $checkin.voiceHeadphonesOnly)
                .accessibilityIdentifier("voice-headphones")
            if let problem { Text(problem).foregroundStyle(Theme.bad).font(.footnote) }
        } header: {
            Text("What it says")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                if checkin.voiceSource == Checkin.voiceClip {
                    Text("Record it here (you, or anyone handy), up to \(ClipRecorder.maxSeconds) seconds, or pick an audio file (up to \(MediaStore.maxClipBytes / 1024 / 1024) MB).")
                } else {
                    Text("Read aloud by the phone's voice. Empty: it says the check-in's name.")
                }
                Text(checkin.voiceHeadphonesOnly
                     ? "Stays quiet when no headphones are connected, so it never talks out of your pocket."
                     : "Plays through the phone's speaker when no headphones are connected.")
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.audio]) { result in
            guard case let .success(url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                try MediaStore.setVoice(checkin.id, from: url)
                problem = nil
                version += 1
            } catch {
                problem = error.localizedDescription
            }
        }
        .onDisappear {
            if recorder.recording { finishRecording() }
        }
    }

    private var clipStatus: String {
        guard MediaStore.hasVoice(checkin.id) else { return "None yet" }
        let seconds = Int(AVAudioDuration.of(MediaStore.voiceURL(checkin.id)).rounded())
        return seconds > 0 ? "\(seconds) s" : "Set"
    }

    private func record() {
        problem = nil
        Task {
            guard await ClipRecorder.requestMicrophone() else {
                problem = "Gist can't use the microphone. Allow it in Settings → Gist."
                return
            }
            do {
                try recorder.start()
            } catch {
                problem = error.localizedDescription
            }
        }
    }

    private func finishRecording() {
        guard let url = recorder.stop() else { return }
        do {
            try MediaStore.setVoice(checkin.id, from: url)
            version += 1
        } catch {
            problem = error.localizedDescription
        }
    }
}

enum AVAudioDuration {
    /// A clip's length in seconds (0 if unreadable).
    static func of(_ url: URL) -> Double {
        (try? AVAudioPlayer(contentsOf: url))?.duration ?? 0
    }
}
