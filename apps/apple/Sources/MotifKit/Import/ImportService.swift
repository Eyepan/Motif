import AVFoundation

/// Copies audio into the library's media directory, reads its tags and format,
/// and records it. Every source funnels through here.
public struct ImportService: Sendable {
    public static let supportedExtensions: Set<String> = ["flac", "wav", "wave", "aif", "aiff", "m4a", "caf", "mp3"]

    private let store: LibraryStore
    private let session: URLSession

    public init(store: LibraryStore, session: URLSession = .shared) {
        self.store = store
        self.session = session
    }

    /// Imports files the user picked (Files app, Finder, a Bandcamp download).
    /// Folders are walked recursively. Returns the tracks added.
    @discardableResult
    public func importLocal(_ urls: [URL]) async throws -> [Track] {
        var added: [Track] = []
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            for file in Self.audioFiles(at: url) {
                added.append(try await ingest(copyFrom: file, source: .local, sourceRef: nil, licenseURL: nil, fallbackTitle: nil))
            }
        }
        return added
    }

    /// Downloads one file from a catalog and adds it.
    @discardableResult
    public func importDownload(_ download: SourceDownload, from source: Track.Source, result: SourceResult) async throws -> Track {
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
                                     licenseURL: result.licenseURL, fallbackTitle: download.title ?? result.title)
        if track.artist == nil || track.album == nil {
            track.artist = track.artist ?? result.artist
            track.album = track.album ?? result.album
            try await store.upsert(track)
        }
        return track
    }

    private func ingest(copyFrom file: URL, source: Track.Source, sourceRef: String?, licenseURL: URL?,
                        fallbackTitle: String?) async throws -> Track {
        let ext = file.pathExtension.lowercased()
        let relative = "\(UUID().uuidString).\(ext)"
        let destination = store.mediaDirectory.appending(path: relative)
        try FileManager.default.copyItem(at: file, to: destination)

        do {
            let tags = await Self.tags(of: destination)
            let audio = try AVAudioFile(forReading: destination)
            let format = audio.fileFormat
            let track = Track(
                title: tags.title ?? fallbackTitle ?? file.deletingPathExtension().lastPathComponent,
                artist: tags.artist,
                album: tags.album,
                durationMs: Int(Double(audio.length) / format.sampleRate * 1000),
                filePath: relative,
                format: Self.formatName(ext),
                sampleRate: Int(format.sampleRate),
                bitDepth: Self.bitDepth(of: format),
                channels: Int(format.channelCount),
                source: source,
                sourceRef: sourceRef,
                licenseURL: licenseURL
            )
            try await store.upsert(track)
            return track
        } catch {
            try? FileManager.default.removeItem(at: destination)
            throw error
        }
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

    static func formatName(_ ext: String) -> String {
        switch ext {
        case "wave": "wav"
        case "aif": "aiff"
        case "m4a", "caf": "alac" // Treated as lossless; AAC-in-m4a is refined once we read the codec.
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

    private struct Tags { var title, artist, album: String? }

    private static func tags(of url: URL) async -> Tags {
        let asset = AVURLAsset(url: url)
        guard let items = try? await asset.load(.commonMetadata) else { return Tags() }
        func value(_ id: AVMetadataIdentifier) async -> String? {
            guard let item = AVMetadataItem.metadataItems(from: items, filteredByIdentifier: id).first else { return nil }
            return try? await item.load(.stringValue)
        }
        return Tags(title: await value(.commonIdentifierTitle),
                    artist: await value(.commonIdentifierArtist),
                    album: await value(.commonIdentifierAlbumName))
    }
}
