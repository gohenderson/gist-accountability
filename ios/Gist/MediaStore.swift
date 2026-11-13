import AVFoundation
import CoreTransferable
import GistCore
import UIKit
import UniformTypeIdentifiers

/// A check-in's media, copied into the app (so deleting the originals doesn't break it):
/// background photos and video, and a voice check-in's recorded clip. Same limits and
/// formats as Android, so they move between phones in backups: photos as JPEG (longest side
/// 1600), video as MP4 (H.264), clips as M4A.
enum MediaStore {
    static let maxPhotos = 20
    static let maxVideoBytes = 30 * 1024 * 1024
    static let maxClipBytes = 10 * 1024 * 1024
    private static let maxSide: CGFloat = 1600

    private static var root: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("backgrounds", isDirectory: true)
    }

    static func dir(_ checkinId: String) -> URL {
        let d = root.appendingPathComponent(checkinId, isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    static func photoURL(_ checkinId: String, _ index: Int) -> URL { dir(checkinId).appendingPathComponent("photo-\(index).jpg") }
    static func videoURL(_ checkinId: String) -> URL { dir(checkinId).appendingPathComponent("video.mp4") }
    static func voiceURL(_ checkinId: String) -> URL { dir(checkinId).appendingPathComponent("voice.m4a") }

    /// The photos, in order.
    static func photos(_ checkinId: String) -> [URL] {
        (0..<maxPhotos).map { photoURL(checkinId, $0) }.filter { FileManager.default.fileExists(atPath: $0.path) }
    }

    static func hasVideo(_ checkinId: String) -> Bool { exists(videoURL(checkinId)) }
    static func hasVoice(_ checkinId: String) -> Bool { exists(voiceURL(checkinId)) }

    private static func exists(_ url: URL) -> Bool {
        ((try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0) > 0
    }

    /// Is [bg]'s media there?
    static func ready(_ checkinId: String, _ bg: Background) -> Bool {
        switch bg.kind {
        case Background.photo, Background.slideshow: return !photos(checkinId).isEmpty
        case Background.video: return hasVideo(checkinId)
        default: return true
        }
    }

    // MARK: Setting

    /// Replaces the photos with [images] (first [maxPhotos]), shrunk to JPEG.
    static func setPhotos(_ checkinId: String, _ images: [Data]) throws {
        let jpegs = try images.prefix(maxPhotos).map { data -> Data in
            guard let image = UIImage(data: data), let jpeg = shrink(image).jpegData(compressionQuality: 0.85) else {
                throw MediaError("One of those isn't a photo Gist can read.")
            }
            return jpeg
        }
        try writePhotos(checkinId, jpegs)
    }

    private static func writePhotos(_ checkinId: String, _ jpegs: [Data]) throws {
        clearPhotos(checkinId)
        for (i, d) in jpegs.enumerated() { try d.write(to: photoURL(checkinId, i), options: .atomic) }
    }

    /// Converts the video at [source] to MP4 (so Android can play it too) and keeps it.
    static func setVideo(_ checkinId: String, from source: URL) async throws {
        let asset = AVURLAsset(url: source)
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mp4")
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPreset1280x720) else {
            throw MediaError("Gist can't convert that video.")
        }
        do {
            try await export.export(to: tmp, as: .mp4)
        } catch {
            throw MediaError("Gist couldn't convert that video: \(error.localizedDescription)")
        }
        defer { try? FileManager.default.removeItem(at: tmp) }
        let size = (try? FileManager.default.attributesOfItem(atPath: tmp.path)[.size] as? Int) ?? 0
        guard size <= maxVideoBytes else {
            throw MediaError("That video is too big (max \(maxVideoBytes / 1024 / 1024) MB). Try a shorter clip.")
        }
        let dest = videoURL(checkinId)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.copyItem(at: tmp, to: dest)
    }

    static func setVoice(_ checkinId: String, from source: URL) throws {
        let size = (try? FileManager.default.attributesOfItem(atPath: source.path)[.size] as? Int) ?? 0
        guard size <= maxClipBytes else {
            throw MediaError("That clip is too big (max \(maxClipBytes / 1024 / 1024) MB). Try a shorter one.")
        }
        let dest = voiceURL(checkinId)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.copyItem(at: source, to: dest)
    }

    static func clearPhotos(_ checkinId: String) {
        for u in photos(checkinId) { try? FileManager.default.removeItem(at: u) }
    }

    static func clearVideo(_ checkinId: String) { try? FileManager.default.removeItem(at: videoURL(checkinId)) }
    static func clearVoice(_ checkinId: String) { try? FileManager.default.removeItem(at: voiceURL(checkinId)) }

    /// Everything of [checkinId]'s (when it's deleted).
    static func clearAll(_ checkinId: String) {
        try? FileManager.default.removeItem(at: root.appendingPathComponent(checkinId, isDirectory: true))
    }

    /// Drops media [c] no longer uses (a photo background's extra photos, a video after
    /// switching to photos, a clip after switching off a recorded clip), like Android.
    static func keepOnly(_ c: Checkin) {
        let kind = c.background.kind
        if kind != Background.photo && kind != Background.slideshow { clearPhotos(c.id) }
        if kind == Background.photo {
            for u in photos(c.id).dropFirst() { try? FileManager.default.removeItem(at: u) }
        }
        if kind != Background.video { clearVideo(c.id) }
        if !(c.isVoice && c.voiceSource == Checkin.voiceClip) { clearVoice(c.id) }
    }

    // MARK: Backups

    static func media(_ c: Checkin) -> Backup.Media? {
        let photos = photos(c.id).compactMap { try? Data(contentsOf: $0) }
        let video = hasVideo(c.id) ? try? Data(contentsOf: videoURL(c.id)) : nil
        let voice = hasVoice(c.id) ? try? Data(contentsOf: voiceURL(c.id)) : nil
        let m = Backup.Media(photos: photos, video: video, voice: voice)
        return m.isEmpty ? nil : m
    }

    /// Writes a backup's media for an imported check-in (replacing what was there).
    static func store(_ m: Backup.Media, for checkinId: String) {
        if !m.photos.isEmpty { try? writePhotos(checkinId, Array(m.photos.prefix(maxPhotos))) }
        if let v = m.video { try? v.write(to: videoURL(checkinId), options: .atomic) }
        if let v = m.voice { try? v.write(to: voiceURL(checkinId), options: .atomic) }
    }

    // MARK: Helpers

    private static func shrink(_ image: UIImage) -> UIImage {
        let side = max(image.size.width, image.size.height)
        guard side > maxSide else { return image }
        let scale = maxSide / side
        let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }
}

struct MediaError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// A video from the photo library, as a file Gist can read.
struct PickedMovie: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let copy = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension(received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension)
            try FileManager.default.copyItem(at: received.file, to: copy)
            return PickedMovie(url: copy)
        }
    }
}
