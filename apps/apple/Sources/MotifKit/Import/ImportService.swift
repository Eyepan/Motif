import AVFoundation

/// Progress for one file moving through an import.
public struct ImportEvent: Sendable {
    public enum Stage: Sendable, Equatable {
        case copying
        case analyzing
        case done(Track)
        /// Not imported, for the reason given (a copy at equal or better quality is in the library).
        case skipped(String)
        case failed(String)
    }

    /// Stable for the life of one file's import.
    public let jobID: UUID
    public let fileName: String
    public let stage: Stage
}

public typealias ImportProgress = @Sendable (ImportEvent) async -> Void

/// Copies audio into the library's media directory, reads its tags and format,
/// records it, then runs on-device analysis (BPM, key, loudness, waveform).
/// Files are copied bit for bit; nothing is transcoded. Every source funnels through here.
public struct ImportService: Sendable {
    public static let supportedExtensions: Set<String> = ["flac", "wav", "wave", "aif", "aiff", "m4a", "caf", "mp3"]

    private let store: LibraryStore
    private let session: URLSession

    public init(store: LibraryStore, session: URLSession = .shared) {
        self.store = store
        self.session = session
    }

    /// Imports files the user picked (Files app, Finder, USB drives, a Bandcamp download).
    /// Folders are walked recursively. A file that fails is reported and skipped.
    /// Returns the tracks added.
    @discardableResult
    public func importLocal(_ urls: [URL], analyze: Bool = true, progress: ImportProgress? = nil) async -> [Track] {
        var added: [Track] = []
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            // A folder's tracks share its cover image when they have no embedded art.
            var covers: [URL: Data?] = [:]
            for file in Self.audioFiles(at: url) {
                let job = UUID()
                let name = file.lastPathComponent
                await progress?(ImportEvent(jobID: job, fileName: name, stage: .copying))
                let folder = file.deletingLastPathComponent()
                if covers[folder] == nil {
                    covers[folder] = .some(ArtworkStore.folderCover(in: folder).flatMap { try? Data(contentsOf: $0) })
                }
                do {
                    var track = try await ingest(copyFrom: file, source: .local, sourceRef: nil, licenseURL: nil,
                                                 fallbackTitle: nil, cover: covers[folder] ?? nil)
                    if analyze {
                        await progress?(ImportEvent(jobID: job, fileName: name, stage: .analyzing))
                        track = await self.analyze(track)
                    }
                    added.append(track)
                    await progress?(ImportEvent(jobID: job, fileName: name, stage: .done(track)))
                } catch {
                    await progress?(ImportEvent(jobID: job, fileName: name, stage: .failed(error.localizedDescription)))
                }
            }
        }
        return added
    }

    /// Runs DSP analysis and stores the result. Analysis failures leave the track as it was.
    public func analyze(_ track: Track) async -> Track {
        let url = await store.url(for: track)
        guard let analysis = try? TrackAnalyzer.analyze(url) else { return track }
        try? await store.updateAnalysis(id: track.id, analysis)
        var updated = track
        updated.bpm = analysis.bpm
        updated.loudnessDb = analysis.loudnessDb
        updated.musicalKey = analysis.musicalKey
        updated.waveform = analysis.waveform
        updated.firstDownbeat = analysis.firstDownbeat
        updated.analyzerVersion = TrackAnalyzer.version
        return updated
    }

    /// The catalog's cover for a release, or nil if it can't be fetched. Fetch once per release.
    public func cover(for result: SourceResult) async -> Data? {
        guard let url = result.coverURL, let fetched = try? await session.data(from: url) else { return nil }
        if let http = fetched.1 as? HTTPURLResponse, !(200..<300).contains(http.statusCode) { return nil }
        return fetched.0.count <= ArtworkStore.maxBytes ? fetched.0 : nil
    }

    /// Downloads one file from a catalog and adds it. `cover` is the release's art from `cover(for:)`.
    @discardableResult
    public func importDownload(_ download: SourceDownload, from source: Track.Source, result: SourceResult,
                               analyze: Bool = true, cover: Data? = nil) async throws -> Track {
        let (tempURL, response) = try await session.download(from: download.url)
        defer { try? FileManager.default.removeItem(at: tempURL) }
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw SourceError.badResponse(http.statusCode)
        }
        // URLSession's temp file has no extension, and AVAudioFile sniffs by extension.
        let named = tempURL.deletingLastPathComponent().appending(path: "\(UUID().uuidString).\(download.format)")
        try FileManager.default.moveItem(at: tempURL, to: named)
        defer { try? FileManager.default.removeItem(at: named) }
        var track = try await ingest(copyFrom: named, source: source, sourceRef: download.sourceRef,
                                     licenseURL: result.licenseURL, fallbackTitle: download.title ?? result.title,
                                     cover: cover)
        if track.artist == nil || track.album == nil {
            track.artist = track.artist ?? result.artist
            track.album = track.album ?? result.album
            try await store.upsert(track)
        }
        if analyze { return await self.analyze(track) }
        return track
    }

    /// Copies the file in, reads and cleans its tags, saves its art (embedded, else
    /// `cover`), and records it with the raw tags it was cleaned from.
    private func ingest(copyFrom file: URL, source: Track.Source, sourceRef: String?, licenseURL: URL?,
                        fallbackTitle: String?, cover: Data?) async throws -> Track {
        let probe = try await self.probe(file, source: source, sourceRef: sourceRef, licenseURL: licenseURL,
                                         fallbackTitle: fallbackTitle)
        return try await add(probe, cover: cover)
    }

    /// A file read in place: the track it would become, before anything is copied.
    public struct Probe: Sendable {
        public let file: URL
        /// `filePath` is empty until the file is added.
        public let track: Track
        let raw: RawTags
        public let fileBytes: Int

        /// What duplicate detection compares.
        public var copy: Dedupe.Copy {
            var copy = Dedupe.Copy(track)
            if !track.isLossless { copy.bitrateKbps = Dedupe.bitrateKbps(bytes: fileBytes, durationMs: track.durationMs) }
            return copy
        }
    }

    /// Reads tags and format without copying. Throws for files AVFoundation can't read.
    public func probe(_ file: URL, source: Track.Source = .local, sourceRef: String? = nil, licenseURL: URL? = nil,
                      fallbackTitle: String? = nil) async throws -> Probe {
        let ext = file.pathExtension.lowercased()
        let (raw, position) = await Self.tags(of: file)
        let audio = try AVAudioFile(forReading: file)
        let format = audio.fileFormat
        var suffixes = await store.siteSuffixes
        if let suffix = TagCleaner.siteSuffix(raw) { suffixes.insert(suffix) }
        let albumArtist = TagCleaner.clean(raw.albumArtist, suffixes: suffixes)
        let track = Track(
            title: TagCleaner.clean(raw.title, suffixes: suffixes) ?? fallbackTitle ?? file.deletingPathExtension().lastPathComponent,
            artist: TagCleaner.clean(raw.artist, suffixes: suffixes) ?? albumArtist,
            album: TagCleaner.clean(raw.album, suffixes: suffixes),
            durationMs: Int(Double(audio.length) / format.sampleRate * 1000),
            filePath: "",
            format: Self.formatName(ext, codec: format.streamDescription.pointee.mFormatID),
            sampleRate: Int(format.sampleRate),
            bitDepth: Self.bitDepth(of: format),
            channels: Int(format.channelCount),
            source: source,
            sourceRef: sourceRef,
            licenseURL: licenseURL,
            albumArtist: albumArtist,
            trackNumber: position.track,
            discNumber: position.disc
        )
        let bytes = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        return Probe(file: file, track: track, raw: raw, fileBytes: bytes)
    }

    /// Copies a probed file into the library and records it, with its art
    /// (embedded, else `cover`) and the raw tags its values were cleaned from.
    public func add(_ probe: Probe, cover: Data?) async throws -> Track {
        var track = probe.track
        track.filePath = try copyIntoLibrary(probe.file)
        let destination = store.mediaDirectory.appending(path: track.filePath)
        do {
            // Art first, so the row shows it as soon as the track appears.
            await store.artwork.extract(track.id, from: destination, fallback: cover)
            try await store.insert(track, raw: probe.raw.isEmpty ? nil : probe.raw)
            return track
        } catch {
            try? FileManager.default.removeItem(at: destination)
            store.artwork.delete(track.id)
            throw error
        }
    }

    /// Swaps `existing`'s audio for a better copy of the same recording. The
    /// track keeps its id, tags, edits, analysis and history; only the file and
    /// its format change. Art is taken from the new file if the track had none.
    public func replace(_ existing: Track, with probe: Probe, cover: Data?) async throws -> Track {
        let relative = try copyIntoLibrary(probe.file)
        let destination = store.mediaDirectory.appending(path: relative)
        var track = existing
        track.filePath = relative
        track.format = probe.track.format
        track.sampleRate = probe.track.sampleRate
        track.bitDepth = probe.track.bitDepth
        track.channels = probe.track.channels
        track.durationMs = probe.track.durationMs
        do {
            try await store.upsert(track)
        } catch {
            try? FileManager.default.removeItem(at: destination)
            throw error
        }
        try? FileManager.default.removeItem(at: store.mediaDirectory.appending(path: existing.filePath))
        if !store.artwork.hasArtwork(track.id) {
            await store.artwork.extract(track.id, from: destination, fallback: cover)
        }
        return track
    }

    /// Copies bit for bit into the media directory; returns the path relative to it.
    private func copyIntoLibrary(_ file: URL) throws -> String {
        let relative = "\(UUID().uuidString).\(file.pathExtension.lowercased())"
        try FileManager.default.copyItem(at: file, to: store.mediaDirectory.appending(path: relative))
        return relative
    }

    // MARK: - Helpers

    static func audioFiles(at url: URL) -> [URL] {
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path(percentEncoded: false), isDirectory: &isDirectory) else { return [] }
        guard isDirectory.boolValue else {
            return supportedExtensions.contains(url.pathExtension.lowercased()) ? [url] : []
        }
        let walker = FileManager.default.enumerator(at: url, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])
        return (walker?.allObjects as? [URL] ?? [])
            .filter { supportedExtensions.contains($0.pathExtension.lowercased()) }
            .sorted { $0.path < $1.path }
    }

    /// `codec` tells ALAC from AAC in .m4a and .caf files.
    static func formatName(_ ext: String, codec: AudioFormatID? = nil) -> String {
        switch ext {
        case "wave": "wav"
        case "aif": "aiff"
        case "m4a", "caf": codec == kAudioFormatMPEG4AAC ? "aac" : "alac"
        default: ext
        }
    }

    /// PCM reports bits per channel directly; FLAC and ALAC encode the source depth in the format flags.
    static func bitDepth(of format: AVAudioFormat) -> Int? {
        let asbd = format.streamDescription.pointee
        if asbd.mFormatID == kAudioFormatLinearPCM { return asbd.mBitsPerChannel > 0 ? Int(asbd.mBitsPerChannel) : nil }
        switch asbd.mFormatFlags {
        case kAppleLosslessFormatFlag_16BitSourceData: return 16
        case kAppleLosslessFormatFlag_20BitSourceData: return 20
        case kAppleLosslessFormatFlag_24BitSourceData: return 24
        case kAppleLosslessFormatFlag_32BitSourceData: return 32
        default: return nil
        }
    }

    /// Where a track sits on its album, as tagged.
    public struct AlbumPosition: Sendable, Equatable {
        public var track: Int?
        public var disc: Int?
    }

    /// Title, artist, album and album artist as tagged, and the track and disc numbers.
    /// Album artist and the numbers have no common identifier, so they're looked up by
    /// the key each tag format uses.
    static func tags(of url: URL) async -> (RawTags, AlbumPosition) {
        let asset = AVURLAsset(url: url)
        let common = (try? await asset.load(.commonMetadata)) ?? []
        let all = (try? await asset.load(.metadata)) ?? []
        func text(_ item: AVMetadataItem) async -> String? {
            guard let value = try? await item.load(.stringValue) else { return nil }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        func value(_ id: AVMetadataIdentifier) async -> String? {
            guard let item = AVMetadataItem.metadataItems(from: common, filteredByIdentifier: id).first else { return nil }
            return await text(item)
        }
        var albumArtist: String?
        var position = AlbumPosition()
        for item in all {
            let key = Self.tagKey(item)
            if albumArtist == nil, item.identifier == .iTunesMetadataAlbumArtist || item.identifier == .id3MetadataBand
                || Self.matches(key, albumArtistKeys) {
                albumArtist = await text(item)
            } else if position.track == nil, item.identifier == .iTunesMetadataTrackNumber
                || item.identifier == .id3MetadataTrackNumber || Self.matches(key, trackNumberKeys) {
                position.track = await number(item)
            } else if position.disc == nil, item.identifier == .iTunesMetadataDiscNumber
                || item.identifier == .id3MetadataPartOfASet || Self.matches(key, discNumberKeys) {
                position.disc = await number(item)
            }
        }
        let raw = RawTags(title: await value(.commonIdentifierTitle),
                          artist: await value(.commonIdentifierArtist),
                          album: await value(.commonIdentifierAlbumName),
                          albumArtist: albumArtist)
        return (raw, position)
    }

    /// Just the track and disc numbers, for tracks imported before they were kept.
    public static func albumPosition(of url: URL) async -> AlbumPosition {
        await tags(of: url).1
    }

    private static func tagKey(_ item: AVMetadataItem) -> String {
        (item.key as? String ?? item.identifier?.rawValue ?? "").lowercased()
    }

    private static func matches(_ key: String, _ names: Set<String>) -> Bool {
        names.contains(key) || names.contains(where: { key.hasSuffix("/\($0)") })
    }

    /// "3", "3/12" or "03"; or MP4's binary trkn/disk atom (number in bytes 2-3).
    private static func number(_ item: AVMetadataItem) async -> Int? {
        if let string = try? await item.load(.stringValue) {
            let lead = string.trimmingCharacters(in: .whitespaces).prefix { $0.isNumber }
            if let n = Int(lead), n > 0 { return n }
        }
        if let data = try? await item.load(.dataValue), data.count >= 4 {
            let bytes = [UInt8](data)
            let n = Int(bytes[2]) << 8 | Int(bytes[3])
            return n > 0 ? n : nil
        }
        return nil
    }

    private static let trackNumberKeys: Set<String> = ["tracknumber", "track", "trck", "trkn"]
    private static let discNumberKeys: Set<String> = ["discnumber", "disc", "tpos", "disk"]

    /// Vorbis comment (FLAC) and other spellings of the album artist key.
    private static let albumArtistKeys: Set<String> = ["albumartist", "album artist", "album_artist", "tpe2", "aart"]
}

extension RawTags {
    var isEmpty: Bool { fields.allSatisfy { $0 == nil } }
}
