import Foundation
import MotifDSP

/// Duplicate detection and quality ranking with the shared rules in
/// core/dsp/src/dedupe.rs, so Apple and Android keep the same copy of a song.
/// Two files are the same recording when lead artist and title match (without
/// case, punctuation, track numbers, "feat." or "(320kbps)") and durations agree
/// within about two seconds. Lossless beats lossy; then sample rate and bit
/// depth, or bitrate between lossy copies.
public enum Dedupe {
    public enum Verdict: Equatable, Sendable {
        /// No copy in the library yet.
        case new
        /// `existing[i]` is as good or better; skip the incoming file.
        case duplicate(Int)
        /// `existing[i]` is worse; replace its file with the incoming one.
        case upgrade(Int)
    }

    /// What the rules look at for one file or library track.
    public struct Copy: Sendable, Equatable {
        public var title: String
        public var artist: String?
        public var durationMs: Int
        public var format: String
        public var sampleRate: Int?
        public var bitDepth: Int?
        /// Average bitrate; only ranks lossy copies.
        public var bitrateKbps: Int?

        public init(title: String, artist: String?, durationMs: Int, format: String,
                    sampleRate: Int? = nil, bitDepth: Int? = nil, bitrateKbps: Int? = nil) {
            self.title = title; self.artist = artist; self.durationMs = durationMs; self.format = format
            self.sampleRate = sampleRate; self.bitDepth = bitDepth; self.bitrateKbps = bitrateKbps
        }

        /// A track as stored. `fileBytes` gives lossy tracks a bitrate to rank by.
        public init(_ track: Track, fileBytes: Int? = nil) {
            self.init(title: track.title, artist: track.artist, durationMs: track.durationMs, format: track.format,
                      sampleRate: track.sampleRate, bitDepth: track.bitDepth,
                      bitrateKbps: fileBytes.flatMap { Dedupe.bitrateKbps(bytes: $0, durationMs: track.durationMs) })
        }
    }

    /// Equal keys are the same song; use it to index the library.
    public static func key(title: String, artist: String?) -> String {
        let key = title.withCString { title in
            withOptionalCString(artist) { motif_dedupe_key(title, $0) }
        }
        guard let key else { return title }
        defer { motif_string_free(key) }
        return String(cString: key)
    }

    public static func resolve(_ incoming: Copy, among existing: [Copy]) -> Verdict {
        let tracks = CTracks([incoming] + existing)
        var index = 0
        let code = tracks.values.withUnsafeBufferPointer { all in
            motif_dedupe_resolve(all.baseAddress, existing.isEmpty ? nil : all.baseAddress! + 1, existing.count, &index)
        }
        switch code { // MotifDedupeVerdict
        case 1: return .duplicate(index)
        case 2: return .upgrade(index)
        default: return .new
        }
    }

    /// Whether `a` is a better copy than `b`.
    public static func isBetter(_ a: Copy, than b: Copy) -> Bool {
        let tracks = CTracks([a, b])
        return tracks.values.withUnsafeBufferPointer { motif_dedupe_compare_quality($0.baseAddress, $0.baseAddress! + 1) } > 0
    }

    /// Average bitrate from file size, for formats whose headers Motif doesn't read it from.
    public static func bitrateKbps(bytes: Int, durationMs: Int) -> Int? {
        durationMs > 0 ? Int((Double(bytes) * 8 / Double(durationMs)).rounded()) : nil
    }

    /// `MotifDedupeTrack`s and the C strings they point at, freed together.
    private final class CTracks {
        private(set) var values: [MotifDedupeTrack] = []
        private var strings: [UnsafeMutablePointer<CChar>] = []

        init(_ copies: [Copy]) {
            values = copies.map { c in
                MotifDedupeTrack(title: keep(c.title), artist: c.artist.flatMap(keep), duration_ms: Int64(c.durationMs),
                                 format: keep(c.format), sample_rate: UInt32(clamping: c.sampleRate ?? 0),
                                 bit_depth: UInt32(clamping: c.bitDepth ?? 0), bitrate_kbps: UInt32(clamping: c.bitrateKbps ?? 0))
            }
        }

        deinit { strings.forEach { free($0) } }

        private func keep(_ text: String) -> UnsafePointer<CChar>? {
            guard let copy = strdup(text) else { return nil }
            strings.append(copy)
            return UnsafePointer(copy)
        }
    }

    private static func withOptionalCString<R>(_ text: String?, _ body: (UnsafePointer<CChar>?) -> R) -> R {
        guard let text else { return body(nil) }
        return text.withCString { body($0) }
    }
}

/// A .zip read one entry at a time through the shared core (iOS has no public
/// unzip API). Listed names are relative and safe to join onto a folder;
/// hidden files, `__MACOSX` and directories are left out.
final class ZipReader {
    struct Entry {
        let index: Int
        /// '/'-separated path inside the archive.
        let name: String
        let size: UInt64
    }

    private let handle: OpaquePointer
    let entries: [Entry]

    init?(_ url: URL) {
        guard let handle = url.path(percentEncoded: false).withCString({ motif_zip_open($0) }) else { return nil }
        self.handle = handle
        entries = (0..<motif_zip_count(handle)).compactMap { i in
            guard let name = motif_zip_name(handle, i) else { return nil }
            defer { motif_string_free(name) }
            return Entry(index: i, name: String(cString: name), size: motif_zip_size(handle, i))
        }
    }

    deinit { motif_zip_free(handle) }

    /// Writes the entry to `destination`; false (and nothing written) on failure or over `maxBytes`.
    func extract(_ entry: Entry, to destination: URL, maxBytes: UInt64) -> Bool {
        destination.path(percentEncoded: false).withCString { motif_zip_extract(handle, entry.index, $0, maxBytes) } == 0
    }
}
