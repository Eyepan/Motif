import Foundation

/// "Read from Downloads": imports the music that lands in a folder, whatever
/// site or app put it there. It finds audio files, album folders and .zip
/// archives of audio, unpacks archives one track at a time, and keeps one copy
/// of each recording: a file already in the library at equal or better quality
/// is skipped, a better one replaces the library's file (see `Dedupe`).
/// Everything goes through `ImportService`, so tags are cleaned, art is found
/// and tracks are analyzed exactly as with a manual import. It only reads
/// local files; it never downloads anything.
///
/// Each file or archive is read once: a record of what was already handled
/// (path, size and modification date) lives in `stateFile`. Files changed in
/// the last few seconds are left for the next scan, since they may still be
/// downloading.
public struct FolderImporter: Sendable {
    public struct Report: Sendable, Equatable {
        public var added = 0
        public var upgraded = 0
        public var duplicates = 0
        public var failed = 0
        /// Something was still being written; scan again shortly.
        public var waiting = false
    }

    static let archiveExtensions: Set<String> = ["zip"]
    /// Album folders nest a level or two (Album/CD1/01.flac); deeper trees in
    /// Downloads are projects, not music.
    static let maxDepth = 3
    /// A file this recently modified may still be downloading.
    public static let settleTime: TimeInterval = 10
    /// The largest single track Motif unpacks from an archive.
    static let maxTrackBytes: UInt64 = 4 << 30

    private let importer: ImportService
    private let store: LibraryStore
    private let stateFile: URL

    public init(importer: ImportService, store: LibraryStore, stateFile: URL) {
        self.importer = importer
        self.store = store
        self.stateFile = stateFile
    }

    /// The Mac's Downloads folder, with the sandbox container's symlink resolved.
    public static var downloadsFolder: URL? {
        FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first?.resolvingSymlinksInPath()
    }

    /// Imports whatever in `folder` hasn't been handled yet. Safe to call
    /// repeatedly; overlapping calls should be avoided by the caller.
    @discardableResult
    public func scan(_ folder: URL, analyze: Bool, progress: ImportProgress? = nil,
                     now: Date = .now) async -> Report {
        let scoped = folder.startAccessingSecurityScopedResource()
        defer { if scoped { folder.stopAccessingSecurityScopedResource() } }

        var report = Report()
        var seen = loadSeen()
        var present: Set<String> = []
        var library = await LibraryIndex(store: store)

        // Loose files are imported a folder at a time, so an album folder holding
        // both MP3 and FLAC copies imports only the FLAC.
        var pending: [Item] = []
        for item in Self.items(in: folder) {
            guard now.timeIntervalSince(item.modified) >= Self.settleTime else {
                report.waiting = true
                continue
            }
            present.insert(item.fingerprint)
            if !seen.contains(item.fingerprint) { pending.append(item) }
        }
        let folders = Dictionary(grouping: pending.filter { !$0.isArchive }, by: { $0.url.deletingLastPathComponent() })
        var batches: [[Item]] = pending.filter(\.isArchive).map { [$0] } + folders.values.map { $0 }
        batches.sort { $0[0].modified < $1[0].modified }

        for batch in batches {
            if batch[0].isArchive {
                await importArchive(batch[0].url, into: &library, analyze: analyze, report: &report, progress: progress)
            } else {
                await importFiles(batch.map(\.url), into: &library, analyze: analyze, report: &report, progress: progress)
            }
            seen.formUnion(batch.map(\.fingerprint))
            saveSeen(seen)
        }
        // Forget files that are gone, so the record doesn't grow forever.
        saveSeen(seen.intersection(present))
        return report
    }

    // MARK: - Finding files

    struct Item {
        let url: URL
        let modified: Date
        /// Path, size and modification date: a re-downloaded file is new.
        let fingerprint: String

        var isArchive: Bool { FolderImporter.archiveExtensions.contains(url.pathExtension.lowercased()) }
    }

    /// Audio files and archives under `folder`, oldest first so albums import in download order.
    static func items(in folder: URL) -> [Item] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .isDirectoryKey, .fileSizeKey, .contentModificationDateKey]
        guard let walker = FileManager.default.enumerator(
            at: folder, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles, .skipsPackageDescendants]
        ) else { return [] }
        var items: [Item] = []
        while let url = walker.nextObject() as? URL {
            guard let values = try? url.resourceValues(forKeys: Set(keys)) else { continue }
            let ext = url.pathExtension.lowercased()
            if values.isDirectory == true {
                // Safari's in-progress downloads are "name.download" folders.
                if walker.level >= maxDepth || ext == "download" { walker.skipDescendants() }
                continue
            }
            guard values.isRegularFile == true,
                  ImportService.supportedExtensions.contains(ext) || archiveExtensions.contains(ext)
            else { continue }
            let modified = values.contentModificationDate ?? .distantPast
            let fingerprint = "\(url.path(percentEncoded: false))|\(values.fileSize ?? 0)|\(Int(modified.timeIntervalSince1970))"
            items.append(Item(url: url, modified: modified, fingerprint: fingerprint))
        }
        return items.sorted {
            ($0.modified, $0.url.path(percentEncoded: false)) < ($1.modified, $1.url.path(percentEncoded: false))
        }
    }

    // MARK: - Importing

    /// Unpacks an archive's audio (and cover images, kept beside it so folder
    /// art works) into a scratch folder, then imports it like a folder.
    private func importArchive(_ archive: URL, into library: inout LibraryIndex, analyze: Bool,
                               report: inout Report, progress: ImportProgress?) async {
        let job = UUID()
        guard let zip = ZipReader(archive) else {
            report.failed += 1
            await progress?(ImportEvent(jobID: job, fileName: archive.lastPathComponent,
                                        stage: .failed("Couldn't open the archive")))
            return
        }
        let scratch = FileManager.default.temporaryDirectory.appending(path: "motif-unzip-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: scratch) }

        var audio: [URL] = []
        for entry in zip.entries {
            let ext = (entry.name as NSString).pathExtension.lowercased()
            let isAudio = ImportService.supportedExtensions.contains(ext)
            guard isAudio || ArtworkStore.imageExtensions.contains(ext),
                  let destination = Self.destination(in: scratch, for: entry.name)
            else { continue }
            let limit = isAudio ? Self.maxTrackBytes : UInt64(ArtworkStore.maxBytes)
            try? FileManager.default.createDirectory(at: destination.deletingLastPathComponent(),
                                                     withIntermediateDirectories: true)
            if zip.extract(entry, to: destination, maxBytes: limit) {
                if isAudio { audio.append(destination) }
            } else if isAudio {
                report.failed += 1
                await progress?(ImportEvent(jobID: UUID(), fileName: (entry.name as NSString).lastPathComponent,
                                            stage: .failed("Couldn't unpack it from \(archive.lastPathComponent)")))
            }
        }
        await importFiles(audio, into: &library, analyze: analyze, report: &report, progress: progress)
    }

    /// `name` joined onto `folder`, or nil if it would land outside it. The core
    /// already sanitizes names; this keeps the guarantee local.
    static func destination(in folder: URL, for name: String) -> URL? {
        let parts = name.split(separator: "/").map(String.init)
        guard !parts.isEmpty, !parts.contains(where: { $0 == ".." || $0 == "." || $0.isEmpty }) else { return nil }
        return parts.reduce(folder) { $0.appending(path: $1) }
    }

    /// Reads every file first, then imports best copy first, so a download that
    /// holds an album twice (MP3 and FLAC) imports only the FLAC.
    private func importFiles(_ files: [URL], into library: inout LibraryIndex, analyze: Bool,
                             report: inout Report, progress: ImportProgress?) async {
        var probes: [(job: UUID, probe: ImportService.Probe)] = []
        for file in files {
            let job = UUID()
            do {
                probes.append((job, try await importer.probe(file)))
            } catch {
                report.failed += 1
                await progress?(ImportEvent(jobID: job, fileName: file.lastPathComponent, stage: .failed(error.localizedDescription)))
            }
        }
        probes.sort { Dedupe.isBetter($0.probe.copy, than: $1.probe.copy) }

        var covers: [URL: Data?] = [:]
        for (job, probe) in probes {
            let name = probe.file.lastPathComponent
            let folder = probe.file.deletingLastPathComponent()
            if covers[folder] == nil {
                covers[folder] = .some(ArtworkStore.folderCover(in: folder).flatMap { try? Data(contentsOf: $0) })
            }
            let cover = covers[folder] ?? nil
            let matches = library.tracks(matching: probe.copy)
            do {
                var track: Track
                switch Dedupe.resolve(probe.copy, among: matches.map(\.copy)) {
                case .duplicate(let i):
                    report.duplicates += 1
                    await progress?(ImportEvent(jobID: job, fileName: name,
                                                stage: .skipped("Already in your library as \(matches[i].track.shortQualityLabel)")))
                    continue
                case .upgrade(let i):
                    await progress?(ImportEvent(jobID: job, fileName: name, stage: .copying))
                    track = try await importer.replace(matches[i].track, with: probe, cover: cover)
                    report.upgraded += 1
                case .new:
                    await progress?(ImportEvent(jobID: job, fileName: name, stage: .copying))
                    track = try await importer.add(probe, cover: cover)
                    if analyze {
                        await progress?(ImportEvent(jobID: job, fileName: name, stage: .analyzing))
                        track = await importer.analyze(track)
                    }
                    report.added += 1
                }
                library.record(track, copy: probe.copy)
                await progress?(ImportEvent(jobID: job, fileName: name, stage: .done(track)))
            } catch {
                report.failed += 1
                await progress?(ImportEvent(jobID: job, fileName: name, stage: .failed(error.localizedDescription)))
            }
        }
    }

    // MARK: - What was already handled

    private func loadSeen() -> Set<String> {
        guard let data = try? Data(contentsOf: stateFile),
              let list = try? JSONDecoder().decode([String].self, from: data) else { return [] }
        return Set(list)
    }

    private func saveSeen(_ seen: Set<String>) {
        guard let data = try? JSONEncoder().encode(seen.sorted()) else { return }
        try? FileManager.default.createDirectory(at: stateFile.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: stateFile, options: .atomic)
    }
}

/// The library's tracks by `Dedupe.key`, so each incoming file is compared
/// only with tracks that could be the same song.
struct LibraryIndex {
    struct Entry {
        var track: Track
        var copy: Dedupe.Copy
    }

    private var byKey: [String: [Entry]] = [:]
    private let store: LibraryStore

    init(store: LibraryStore) async {
        self.store = store
        for track in (try? await store.allTracks()) ?? [] {
            byKey[Dedupe.key(title: track.title, artist: track.artist), default: []].append(Entry(track: track, copy: Dedupe.Copy(track)))
        }
    }

    /// Tracks sharing the copy's key. Lossy ones get a bitrate from their file size, to rank against.
    mutating func tracks(matching copy: Dedupe.Copy) -> [Entry] {
        let key = Dedupe.key(title: copy.title, artist: copy.artist)
        guard var entries = byKey[key] else { return [] }
        for i in entries.indices where entries[i].copy.bitrateKbps == nil && !entries[i].track.isLossless {
            let url = store.mediaDirectory.appending(path: entries[i].track.filePath)
            let bytes = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize
            entries[i].copy = Dedupe.Copy(entries[i].track, fileBytes: bytes)
        }
        byKey[key] = entries
        return entries
    }

    /// Adds or updates a track imported during this scan.
    mutating func record(_ track: Track, copy: Dedupe.Copy) {
        let key = Dedupe.key(title: track.title, artist: track.artist)
        var entries = byKey[key, default: []]
        var updated = copy
        updated.title = track.title
        updated.artist = track.artist
        if let i = entries.firstIndex(where: { $0.track.id == track.id }) {
            entries[i] = Entry(track: track, copy: updated)
        } else {
            entries.append(Entry(track: track, copy: updated))
        }
        byKey[key] = entries
    }
}
