import AVFoundation
import GistCore
import PhotosUI
import SwiftUI

/// What's behind a check-in when it opens: one photo, a slideshow, or a muted looping
/// video, filling the screen. Nothing when it has none (or its media is missing).
struct BackgroundMedia: View {
    let checkin: Checkin

    var body: some View {
        let bg = checkin.background
        switch bg.kind {
        case Background.photo, Background.slideshow:
            let photos = MediaStore.photos(checkin.id)
            if bg.kind == Background.photo || photos.count == 1, let first = photos.first {
                PhotoFill(url: first)
            } else if !photos.isEmpty {
                Slideshow(photos: photos, seconds: max(1, bg.intervalSec))
            }
        case Background.video where MediaStore.hasVideo(checkin.id):
            LoopingVideo(url: MediaStore.videoURL(checkin.id))
                .accessibilityIdentifier("background-video")
        default:
            EmptyView()
        }
    }
}

private struct PhotoFill: View {
    let url: URL

    var body: some View {
        GeometryReader { geo in
            if let image = UIImage(contentsOfFile: url.path) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: geo.size.width, height: geo.size.height)
                    .clipped()
            }
        }
        .accessibilityHidden(true)
    }
}

/// Photos cross-fading every [seconds].
private struct Slideshow: View {
    let photos: [URL]
    let seconds: Int
    @State private var index = 0

    var body: some View {
        ZStack {
            PhotoFill(url: photos[index % photos.count])
                .id(index)
                .transition(.opacity)
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(seconds))
                withAnimation(.easeInOut(duration: 1)) { index += 1 }
            }
        }
    }
}

/// A muted video, looping.
struct LoopingVideo: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> PlayerView {
        let view = PlayerView()
        // Muted and mixable: it doesn't stop music, a podcast or the alarm.
        try? AVAudioSession.sharedInstance().setCategory(.ambient, options: .mixWithOthers)
        let player = AVQueuePlayer()
        player.isMuted = true
        player.audiovisualBackgroundPlaybackPolicy = .pauses
        context.coordinator.looper = AVPlayerLooper(player: player, templateItem: AVPlayerItem(url: url))
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspectFill
        player.play()
        return view
    }

    func updateUIView(_ view: PlayerView, context: Context) {}

    static func dismantleUIView(_ view: PlayerView, coordinator: Coordinator) {
        view.playerLayer.player?.pause()
        coordinator.looper = nil
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        var looper: AVPlayerLooper?
    }

    final class PlayerView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
}

/// "While it rings": the background and caption.
struct BackgroundEditView: View {
    @Binding var checkin: Checkin
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var videoItem: PhotosPickerItem?
    @State private var working = false
    @State private var problem: String?
    /// Bumped when media changes, to redraw the previews.
    @State private var version = 0

    private var bg: Background { checkin.background }

    var body: some View {
        Form {
            Section {
                Picker("Background", selection: $checkin.background.kind) {
                    Text("None").tag(Background.none)
                    Text("One photo").tag(Background.photo)
                    Text("Slideshow").tag(Background.slideshow)
                    Text("Video").tag(Background.video)
                }
                .accessibilityIdentifier("background-kind")
                if bg.kind == Background.slideshow {
                    Picker("Change every", selection: $checkin.background.intervalSec) {
                        ForEach(Array(Set([3, 5, 10, 30, 60, bg.intervalSec])).sorted(), id: \.self) { Text("\($0) s").tag($0) }
                    }
                }
            } footer: {
                Text("Shown when the check-in opens, with its name and caption, and a Start check-in button. Media is copied into Gist, so deleting the originals doesn't break it. iOS shows its own alarm screen, so the background appears once you open the check-in.")
            }

            if bg.kind == Background.photo || bg.kind == Background.slideshow {
                photosSection
            } else if bg.kind == Background.video {
                videoSection
            }

            Section {
                TextField("e.g. Future you says thanks", text: $checkin.background.caption, axis: .vertical)
                    .accessibilityIdentifier("background-caption")
            } header: {
                Text("Caption")
            } footer: {
                Text("Shown in large type, with or without a background.")
            }

            if working {
                Section { HStack { ProgressView(); Text("Copying…") } }
            }
            if let problem {
                Section { Text(problem).foregroundStyle(Theme.bad) }
            }
        }
        .navigationTitle("While it rings")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: photoItems) { _, items in
            guard !items.isEmpty else { return }
            load(photos: items)
        }
        .onChange(of: videoItem) { _, item in
            guard let item else { return }
            load(video: item)
        }
        .onDisappear { MediaStore.keepOnly(checkin) }
    }

    private var photosSection: some View {
        Section {
            let photos = MediaStore.photos(checkin.id)
            let shown = bg.kind == Background.photo ? Array(photos.prefix(1)) : photos
            if !shown.isEmpty {
                ScrollView(.horizontal) {
                    HStack {
                        ForEach(shown, id: \.self) { url in
                            if let image = UIImage(contentsOfFile: url.path) {
                                Image(uiImage: image).resizable().scaledToFill()
                                    .frame(width: 72, height: 96).clipShape(RoundedRectangle(cornerRadius: 8))
                            }
                        }
                    }
                }
                .id(version)
            }
            PhotosPicker(selection: $photoItems,
                         maxSelectionCount: bg.kind == Background.photo ? 1 : MediaStore.maxPhotos,
                         selectionBehavior: .ordered, matching: .images) {
                Text(shown.isEmpty ? (bg.kind == Background.photo ? "Choose a photo" : "Choose photos")
                     : (bg.kind == Background.photo ? "Choose a different photo" : "Choose different photos"))
            }
            .accessibilityIdentifier("choose-photos")
        } footer: {
            if bg.kind == Background.slideshow {
                Text("Up to \(MediaStore.maxPhotos) photos, in the order you pick them.")
            }
        }
    }

    private var videoSection: some View {
        Section {
            if MediaStore.hasVideo(checkin.id) {
                LoopingVideo(url: MediaStore.videoURL(checkin.id))
                    .frame(height: 200)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .id(version)
            }
            PhotosPicker(selection: $videoItem, matching: .videos) {
                Text(MediaStore.hasVideo(checkin.id) ? "Choose a different video" : "Choose a video")
            }
            .accessibilityIdentifier("choose-video")
        } footer: {
            Text("Up to \(MediaStore.maxVideoBytes / 1024 / 1024) MB once converted. It plays muted and loops.")
        }
    }

    private func load(photos items: [PhotosPickerItem]) {
        working = true
        problem = nil
        Task {
            do {
                var datas: [Data] = []
                for item in items {
                    if let d = try await item.loadTransferable(type: Data.self) { datas.append(d) }
                }
                try MediaStore.setPhotos(checkin.id, datas)
                version += 1
            } catch {
                problem = error.localizedDescription
            }
            photoItems = []
            working = false
        }
    }

    private func load(video item: PhotosPickerItem) {
        working = true
        problem = nil
        Task {
            do {
                guard let movie = try await item.loadTransferable(type: PickedMovie.self) else {
                    throw MediaError("Gist couldn't read that video.")
                }
                defer { try? FileManager.default.removeItem(at: movie.url) }
                try await MediaStore.setVideo(checkin.id, from: movie.url)
                version += 1
            } catch {
                problem = error.localizedDescription
            }
            videoItem = nil
            working = false
        }
    }
}
