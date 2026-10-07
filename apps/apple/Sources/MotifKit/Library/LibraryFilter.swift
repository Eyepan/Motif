import Foundation

/// Library search with DJ filters: free text plus `bpm:120`, `bpm:120-126`
/// and `key:8A` (Camelot). Used by both the iPhone and Mac search fields.
public struct LibraryFilter: Equatable, Sendable {
    public var text: String = ""
    public var bpm: ClosedRange<Double>?
    public var key: String?

    public init(_ query: String) {
        var words: [String] = []
        for token in query.split(whereSeparator: \.isWhitespace) {
            let lower = token.lowercased()
            if lower.hasPrefix("bpm:") {
                let parts = lower.dropFirst(4).split(separator: "-").compactMap { Double($0) }
                switch parts.count {
                case 1: bpm = (parts[0] - 0.5)...(parts[0] + 0.5)
                case 2: bpm = min(parts[0], parts[1])...max(parts[0], parts[1])
                default: words.append(String(token))
                }
            } else if lower.hasPrefix("key:"), lower.count > 4 {
                key = String(lower.dropFirst(4)).uppercased()
            } else {
                words.append(String(token))
            }
        }
        text = words.joined(separator: " ")
    }

    public var isEmpty: Bool { text.isEmpty && bpm == nil && key == nil }

    public func matches(_ track: Track) -> Bool {
        if let bpm, !(track.bpm.map(bpm.contains) ?? false) { return false }
        if let key, track.musicalKey?.uppercased() != key { return false }
        guard !text.isEmpty else { return true }
        return [track.title, track.artist, track.album].compactMap { $0 }
            .contains { $0.localizedCaseInsensitiveContains(text) }
    }
}

public extension Track {
    /// Keys that mix harmonically with this one on the Camelot wheel:
    /// the same key, one step either way, and the relative major/minor.
    var compatibleKeys: Set<String> {
        guard let key = musicalKey, let letter = key.last, let n = Int(key.dropLast()) else { return [] }
        let other: Character = letter == "A" ? "B" : "A"
        let up = n % 12 + 1, down = (n + 10) % 12 + 1
        return ["\(n)\(letter)", "\(up)\(letter)", "\(down)\(letter)", "\(n)\(other)"]
    }
}

/// Whether two tracks mix well, for highlighting matches in lists. The rule is
/// shared across apps in `schemas/mix-colours.json`.
public enum MixMatch {
    /// `incoming`'s key is the same as `outgoing`'s, a wheel neighbour, or its relative major/minor.
    public static func keys(_ outgoing: Track, _ incoming: Track) -> Bool {
        incoming.musicalKey.map { outgoing.compatibleKeys.contains($0.uppercased()) } ?? false
    }

    /// `incoming` can be beatmatched to `outgoing` within `PlaybackEngine.maxTempoAdjust`,
    /// counting half and double time.
    public static func tempos(_ outgoing: Track, _ incoming: Track) -> Bool {
        guard let a = outgoing.bpm, let b = incoming.bpm, a > 0, b > 0 else { return false }
        return [a / b, a * 2 / b, a / (2 * b)].contains { abs($0 - 1) <= PlaybackEngine.maxTempoAdjust }
    }
}
