import Foundation

/// A library track. Mirrors `schemas/library.sql` and `schemas/track.schema.json`.
public struct Track: Identifiable, Hashable, Codable, Sendable {
    public enum Source: String, Codable, Sendable, CaseIterable {
        case local
        case internetArchive = "internet_archive"
        case jamendo
        case bandcamp
    }

    public var id: UUID
    public var title: String
    public var artist: String?
    public var album: String?
    public var durationMs: Int
    /// Path relative to `LibraryStore.mediaDirectory`.
    public var filePath: String
    public var format: String
    public var sampleRate: Int?
    public var bitDepth: Int?
    public var channels: Int?
    public var source: Source
    public var sourceRef: String?
    public var licenseURL: URL?
    public var bpm: Double?
    public var loudnessDb: Double?
    /// Camelot notation, e.g. "8A".
    public var musicalKey: String?
    /// Loudness overview for drawing, `LibraryStore.waveformLength` bytes. Local cache, not synced.
    public var waveform: [UInt8]?
    public var addedAt: Date
    /// The album's credited artist (TPE2 / ALBUMARTIST), when tagged.
    public var albumArtist: String?
    /// SHA-256 of the audio file, lowercase hex: the track's identity across devices (`track_key`).
    /// Filled lazily by `LibraryStore.contentHash(for:)`; `upsert` never overwrites it.
    public var contentHash: String?

    public init(
        id: UUID = UUID(), title: String, artist: String? = nil, album: String? = nil,
        durationMs: Int, filePath: String, format: String, sampleRate: Int? = nil,
        bitDepth: Int? = nil, channels: Int? = nil, source: Source, sourceRef: String? = nil,
        licenseURL: URL? = nil, bpm: Double? = nil, loudnessDb: Double? = nil, musicalKey: String? = nil,
        waveform: [UInt8]? = nil, addedAt: Date = .now, albumArtist: String? = nil, contentHash: String? = nil
    ) {
        self.id = id; self.title = title; self.artist = artist; self.album = album
        self.durationMs = durationMs; self.filePath = filePath; self.format = format
        self.sampleRate = sampleRate; self.bitDepth = bitDepth; self.channels = channels
        self.source = source; self.sourceRef = sourceRef; self.licenseURL = licenseURL
        self.bpm = bpm; self.loudnessDb = loudnessDb; self.musicalKey = musicalKey
        self.waveform = waveform; self.addedAt = addedAt; self.albumArtist = albumArtist
        self.contentHash = contentHash
    }

    public var isLossless: Bool { ["flac", "wav", "alac", "aiff"].contains(format) }

    /// "FLAC 24/96", for dense lists.
    public var shortQualityLabel: String {
        guard let rate = sampleRate else { return format.uppercased() }
        let khz = Double(rate) / 1000
        let rateText = khz.rounded() == khz ? "\(Int(khz))" : String(format: "%.1f", khz)
        return bitDepth.map { "\(format.uppercased()) \($0)/\(rateText)" } ?? "\(format.uppercased()) \(rateText)k"
    }

    public var duration: TimeInterval { Double(durationMs) / 1000 }

    /// "FLAC · 24-bit / 96 kHz"
    public var qualityLabel: String {
        var parts = [format.uppercased()]
        if let rate = sampleRate {
            let khz = Double(rate) / 1000
            let rateText = khz.rounded() == khz ? "\(Int(khz)) kHz" : String(format: "%.1f kHz", khz)
            parts.append(bitDepth.map { "\($0)-bit / \(rateText)" } ?? rateText)
        }
        return parts.joined(separator: " · ")
    }
}
