import AVFoundation
import Foundation
import GistCore

/// Plays a voice check-in: speaks its text, or plays its recorded clip, like a navigation
/// app does: music or a podcast dips while it talks, then comes back. No alarm, no screen,
/// nothing to tap. With "only through headphones" (the default) it stays quiet when no
/// headphones are connected, so it never blurts out of a pocket.
@MainActor
final class VoicePlayer: NSObject, AVSpeechSynthesizerDelegate, AVAudioPlayerDelegate {
    static let shared = VoicePlayer()

    private let synthesizer = AVSpeechSynthesizer()
    private var player: AVAudioPlayer?
    private var queue: [Checkin] = []
    private var playing = false
    private var giveUp: Task<Void, Never>?

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    /// Headphones (wired, Bluetooth, USB, AirPods…) are connected, not the phone's speaker.
    static var headphonesConnected: Bool {
        let kinds: Set<AVAudioSession.Port> = [.headphones, .bluetoothA2DP, .bluetoothHFP, .bluetoothLE, .usbAudio]
        return AVAudioSession.sharedInstance().currentRoute.outputs.contains { kinds.contains($0.portType) }
    }

    /// Plays [c] now, or after what's playing. Returns false if it stays quiet (no
    /// headphones and it wants them).
    @discardableResult
    func play(_ c: Checkin, ignoreHeadphones: Bool = false) -> Bool {
        if c.voiceHeadphonesOnly && !ignoreHeadphones && !Self.headphonesConnected { return false }
        queue.append(c)
        if !playing { next() }
        return true
    }

    private func next() {
        guard !queue.isEmpty else {
            playing = false
            // Let the music come back up.
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            return
        }
        let c = queue.removeFirst()
        playing = true
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .voicePrompt, options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers])
            try session.setActive(true)
        } catch {
            finishOne()
            return
        }
        giveUp?.cancel()
        giveUp = Task { [weak self] in
            try? await Task.sleep(for: .seconds(90)) // a clip or voice that never reports back
            if !Task.isCancelled { self?.finishOne() }
        }
        let clip = MediaStore.voiceURL(c.id)
        if c.voiceSource == Checkin.voiceClip, MediaStore.hasVoice(c.id), let p = try? AVAudioPlayer(contentsOf: clip) {
            p.delegate = self
            player = p
            if !p.play() { finishOne() }
        } else {
            let u = AVSpeechUtterance(string: Voice.spokenText(c))
            u.voice = AVSpeechSynthesisVoice(language: Locale.current.identifier.replacingOccurrences(of: "_", with: "-"))
                ?? AVSpeechSynthesisVoice(language: AVSpeechSynthesisVoice.currentLanguageCode())
            synthesizer.speak(u)
        }
    }

    private func finishOne() {
        guard playing else { return }
        giveUp?.cancel()
        player?.stop()
        player = nil
        next()
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.finishOne() }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in self.finishOne() }
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.finishOne() }
    }

    nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        Task { @MainActor in self.finishOne() }
    }
}

/// Records a voice check-in's clip (AAC in .m4a, like Android's).
@MainActor
final class ClipRecorder: NSObject, ObservableObject {
    @Published private(set) var recording = false
    @Published private(set) var seconds = 0
    private var recorder: AVAudioRecorder?
    private var timer: Task<Void, Never>?
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("gist-recording.m4a")
    static let maxSeconds = 60

    /// Asks for the microphone; true if allowed.
    static func requestMicrophone() async -> Bool {
        await AVAudioApplication.requestRecordPermission()
    }

    func start() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
        try session.setActive(true)
        try? FileManager.default.removeItem(at: url)
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]
        let r = try AVAudioRecorder(url: url, settings: settings)
        guard r.record(forDuration: TimeInterval(Self.maxSeconds)) else { throw MediaError("Couldn't start recording.") }
        recorder = r
        recording = true
        seconds = 0
        timer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard let self, self.recording else { return }
                self.seconds += 1
                if self.seconds >= Self.maxSeconds { self.stop() }
            }
        }
    }

    /// Stops; returns the clip's file, or nil if nothing was recorded.
    @discardableResult
    func stop() -> URL? {
        timer?.cancel()
        recorder?.stop()
        recorder = nil
        recording = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
        return size > 0 ? url : nil
    }
}
