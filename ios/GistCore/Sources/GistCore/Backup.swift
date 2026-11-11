import Foundation

/// Backup files (YAML), in the same format as the Android app's, so a backup from one phone
/// imports on the other (shared/backups holds samples both apps' tests import):
///
///   format: gist-backup
///   version: 3
///   exportedAt: '2026-11-08T…'
///   checkins: [ … ]
///   entries:                      # history, only for check-ins exported with it
///     '2026-11-07':
///       <checkinId>:
///         '21:30': {s: answered, a: {soda: false}, early: false}
///   media:
///     <checkinId>: {photos: [<base64>, …], video: <base64>, voice: <base64>}
public enum Backup {
    public static let format = "gist-backup"
    public static let version = 3
    static let header = "Gist backup. Import it with Settings → Import backup… (you choose what to bring in).\n" +
        "You can edit check-ins here; times are \"HH:mm\", repeat kinds are daily / weekly / monthly."

    public struct Media: Equatable {
        public var photos: [Data]
        public var video: Data?
        public var voice: Data?

        public init(photos: [Data] = [], video: Data? = nil, voice: Data? = nil) {
            self.photos = photos
            self.video = video
            self.voice = voice
        }

        public var isEmpty: Bool { photos.isEmpty && video == nil && voice == nil }
    }

    /// A parsed backup file.
    public struct Contents: Equatable {
        public let checkins: [Checkin]
        /// day → check-in id → time → result
        public let entries: [String: [String: DayResults]]
        public let media: [String: Media]

        /// How many days of history the file has.
        public var days: Int { entries.count }

        /// [checkinId]'s history in the file.
        public func history(_ checkinId: String) -> History {
            var out: History = [:]
            for (day, byCheckin) in entries {
                if let slots = byCheckin[checkinId] { out[day] = slots }
            }
            return out
        }

        public func historyDays(_ checkinId: String) -> Int { history(checkinId).count }
    }

    /// Export [checkinId], with its history or without.
    public struct ExportChoice: Equatable {
        public let checkinId: String
        public let withHistory: Bool
        public init(_ checkinId: String, withHistory: Bool) {
            self.checkinId = checkinId
            self.withHistory = withHistory
        }
    }

    /// What to do with a check-in from a backup.
    public enum Mode: Equatable {
        /// Not on this phone yet: add it.
        case new
        /// Overwrite your check-in's settings and media; keep its history (and add the file's).
        case update
        /// Swap your check-in for the file's, history included (yours is deleted).
        case replace
        /// Keep yours and add the file's as a separate check-in.
        case copy
    }

    /// Import the file's [checkinId] this way, with its history or without.
    public struct ImportChoice: Equatable {
        public let checkinId: String
        public let mode: Mode
        public let withHistory: Bool
        public init(_ checkinId: String, mode: Mode, withHistory: Bool) {
            self.checkinId = checkinId
            self.mode = mode
            self.withHistory = withHistory
        }
    }

    /// A check-in that was imported, and which of the file's check-ins it came from (whose
    /// media, if any, the app writes for it).
    public struct Imported: Equatable {
        public let checkin: Checkin
        public let sourceId: String
    }

    // MARK: Export

    /// [data]'s chosen check-ins (everything, with history, by default) as a backup file.
    /// [media] supplies a check-in's photos, video and voice clip.
    public static func export(_ data: GistData, choices: [ExportChoice]? = nil,
                              media: (Checkin) -> Media? = { _ in nil }, now: Date = Date()) throws -> String {
        let chosen = Dictionary((choices ?? data.checkins.map { ExportChoice($0.id, withHistory: true) })
            .map { ($0.checkinId, $0) }, uniquingKeysWith: { a, _ in a })
        let checkins = data.checkins.filter { chosen[$0.id] != nil }
        var entries: [String: [String: DayResults]] = [:]
        for c in checkins where chosen[c.id]?.withHistory == true {
            for (day, slots) in data.history(c.id) { entries[day, default: [:]][c.id] = slots }
        }
        var mediaJSON = JSONObject()
        for c in checkins {
            guard let m = media(c), !m.isEmpty else { continue }
            var o = JSONObject()
            if !m.photos.isEmpty { o["photos"] = .array(m.photos.map { p -> JSON in .string(p.base64EncodedString()) }) }
            if let v = m.video { o["video"] = .string(v.base64EncodedString()) }
            if let v = m.voice { o["voice"] = .string(v.base64EncodedString()) }
            mediaJSON[c.id] = .object(o)
        }
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let root = JSONObject([
            ("format", .string(format)),
            ("version", .int(version)),
            ("exportedAt", .string(iso.string(from: now))),
            ("checkins", .array(checkins.map { c -> JSON in c.json })),
            ("entries", .object(GistData.entriesJSON(entries))),
            ("media", .object(mediaJSON)),
        ])
        return try YAMLCodec.dump(.object(root), header: header)
    }

    // MARK: Parse

    /// Reads and checks a backup without changing anything. Throws BackupError with a
    /// message to show.
    public static func parse(_ text: String) throws -> Contents {
        guard let root = try YAMLCodec.load(text).objectValue, root.string("format") == format else {
            throw BackupError("This isn't a Gist backup file.")
        }
        let v = root.int("version", 0)
        if v > version { throw BackupError("This backup is from a newer version of Gist. Update the app first.") }
        if v < version { throw BackupError("This backup is from an older version of Gist that this one can't read.") }
        guard let arr = root.array("checkins") else { throw BackupError("The backup file is damaged: no check-ins.") }
        let checkins = try arr.map { (item: JSON) -> Checkin in
            guard let o = item.objectValue else { throw BackupError("The backup file is damaged: a check-in isn't readable.") }
            return try Checkin.from(o)
        }
        if Set(checkins.map { $0.id }).count != checkins.count {
            throw BackupError("The backup has duplicate check-ins.")
        }
        let entriesObject = root.object("entries") ?? JSONObject()
        for (_, d) in entriesObject.pairs where d.objectValue == nil {
            throw BackupError("The backup file is damaged: a day of history isn't readable.")
        }
        var media: [String: Media] = [:]
        for (id, mv) in (root.object("media") ?? JSONObject()).pairs {
            guard let m = mv.objectValue else { continue }
            let photos = try (m.array("photos") ?? []).map { try unb64($0.stringValue ?? "") }
            let video = m.string("video").isEmpty ? nil : try unb64(m.string("video"))
            let voice = m.string("voice").isEmpty ? nil : try unb64(m.string("voice"))
            media[id] = Media(photos: photos, video: video, voice: voice)
        }
        return Contents(checkins: checkins, entries: GistData.entries(from: entriesObject), media: media)
    }

    /// Your check-in that the file's [c] would update or replace: same id, else same name.
    public static func match(_ local: [Checkin], _ c: Checkin) -> Checkin? {
        local.first { $0.id == c.id } ?? local.first { $0.name.caseInsensitiveCompare(c.name) == .orderedSame }
    }

    // MARK: Import

    /// Applies [choices] to [data]. Returns what it wrote, so the app can store media and
    /// re-arm alarms. Check-ins not chosen, and yours not in the file, are left alone.
    @discardableResult
    public static func importing(_ contents: Contents, choices: [ImportChoice], into data: inout GistData,
                                 newId: () -> String = Ids.new) -> [Imported] {
        var written: [Imported] = []
        for choice in choices {
            guard let src = contents.checkins.first(where: { $0.id == choice.checkinId }) else { continue }
            let list = data.checkins
            let local = match(list, src)
            let mode: Mode = (local == nil && choice.mode != .copy) ? .new : choice.mode
            let history = contents.history(src.id)
            var target = src
            switch mode {
            case .new, .copy:
                let idFree = !list.contains { $0.id == src.id }
                target.id = (mode == .new && idFree) ? src.id : newId()
                let names = Set(list.map { $0.name.lowercased() })
                if names.contains(src.name.lowercased()) {
                    let candidates = ["\(src.name) (imported)"] + (2...99).map { "\(src.name) (imported \($0))" }
                    target.name = candidates.first { !names.contains($0.lowercased()) } ?? src.name
                }
                data.checkins.append(target)
            case .update, .replace:
                target.id = local!.id
                data.update(target.id) { $0 = target }
            }
            switch mode {
            case .replace: data.putHistory(target.id, choice.withHistory ? history : [:], merge: false)
            case .update: if choice.withHistory { data.putHistory(target.id, history, merge: true) }
            default: if choice.withHistory { data.putHistory(target.id, history, merge: false) }
            }
            written.append(Imported(checkin: target, sourceId: src.id))
        }
        return written
    }

    static func unb64(_ s: String) throws -> Data {
        guard let d = Data(base64Encoded: s, options: .ignoreUnknownCharacters) else {
            throw BackupError("The backup file is damaged (bad photo or video data).")
        }
        return d
    }
}
