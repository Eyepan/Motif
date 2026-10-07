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
