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
    public var addedAt: Date

    public init(
        id: UUID = UUID(), title: String, artist: String? = nil, album: String? = nil,
        durationMs: Int, filePath: String, format: String, sampleRate: Int? = nil,
        bitDepth: Int? = nil, channels: Int? = nil, source: Source, sourceRef: String? = nil,
        licenseURL: URL? = nil, bpm: Double? = nil, loudnessDb: Double? = nil, addedAt: Date = .now
    ) {
        self.id = id; self.title = title; self.artist = artist; self.album = album
        self.durationMs = durationMs; self.filePath = filePath; self.format = format
        self.sampleRate = sampleRate; self.bitDepth = bitDepth; self.channels = channels
        self.source = source; self.sourceRef = sourceRef; self.licenseURL = licenseURL
        self.bpm = bpm; self.loudnessDb = loudnessDb; self.addedAt = addedAt
    }

    public var isLossless: Bool { ["flac", "wav", "alac", "aiff"].contains(format) }

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
