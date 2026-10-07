import Foundation
import MotifDSP

/// Tag values exactly as read from a file. Kept in `track_tags` so the
/// library can be re-cleaned when the shared rules change.
public struct RawTags: Sendable, Equatable {
    public var title, artist, album, albumArtist: String?

    public init(title: String? = nil, artist: String? = nil, album: String? = nil, albumArtist: String? = nil) {
        self.title = title; self.artist = artist; self.album = album; self.albumArtist = albumArtist
    }

    var fields: [String?] { [title, artist, album, albumArtist] }

    /// `track_tags` keys (lowercased tag names) and values; the same keys Android writes.
    var entries: [(key: String, value: String)] {
        let all: [(String, String?)] = [("title", title), ("artist", artist), ("album", album), ("albumartist", albumArtist)]
        return all.compactMap { pair in pair.1.map { (key: pair.0, value: $0) } }
    }

    init(entries: [String: String]) {
        self.init(title: entries["title"], artist: entries["artist"], album: entries["album"],
                  albumArtist: entries["albumartist"])
    }
}

/// Turns messy tags into library values with the shared rules in
/// core/dsp/src/meta.rs, so Apple and Android clean and split identically:
/// strips site names appended to tags ("Jailer 2 - Site") and splits artist
/// credits ("A, B feat. C") into separate artists.
public enum TagCleaner {
    public enum Role: String, Sendable { case primary, featured }

    /// Changes when the shared rules do; the library re-cleans from raw tags then.
    public static var version: Int { Int(motif_meta_cleaner_version()) }

    /// Case- and whitespace-insensitive comparison key.
    public static func norm(_ text: String) -> String {
        take(text.withCString { motif_meta_norm($0) }) ?? text
    }

    /// A site name appended to several of this file's tags, or nil.
    public static func siteSuffix(_ raw: RawTags) -> String? {
        withCStrings(raw.fields) { motif_meta_detect_site_suffix($0, $1) }
    }

    /// Cleaned value, or nil for a missing or blank tag. `suffixes` are site names seen anywhere in the library.
    public static func clean(_ text: String?, suffixes: some Collection<String>) -> String? {
        guard let value = text?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        let cleaned = value.withCString { text in
            withCStrings(suffixes.map(Optional.some)) { motif_meta_clean_field(text, $0, $1) }
        }
        return cleaned ?? value
    }

    /// Credited artists in order. `known` holds names already passed through `norm`,
    /// used to decide whether "A & B" is a duo or two artists.
    public static func splitArtists(_ credit: String, known: some Collection<String>) -> [(name: String, role: Role)] {
        let lines = credit.withCString { credit in
            withCStrings(known.map(Optional.some)) { motif_meta_split_artists(credit, $0, $1) }
        }
        let credits = (lines ?? "").split(separator: "\n").compactMap { line -> (name: String, role: Role)? in
            let parts = line.split(separator: "\t", maxSplits: 1)
            guard parts.count == 2, let role = Role(rawValue: String(parts[0])) else { return nil }
            return (String(parts[1]), role)
        }
        let trimmed = credit.trimmingCharacters(in: .whitespacesAndNewlines)
        return credits.isEmpty && !trimmed.isEmpty ? [(trimmed, .primary)] : credits
    }

    // MARK: - C strings

    /// Copies a string the core returned and frees it.
    private static func take(_ pointer: UnsafeMutablePointer<CChar>?) -> String? {
        guard let pointer else { return nil }
        defer { motif_string_free(pointer) }
        return String(cString: pointer)
    }

    /// Calls `body` with a C array of the strings (nil entries stay NULL) and its length.
    private static func withCStrings(
        _ values: [String?],
        _ body: (UnsafePointer<UnsafePointer<CChar>?>?, Int) -> UnsafeMutablePointer<CChar>?
    ) -> String? {
        let copies: [UnsafeMutablePointer<CChar>?] = values.map { $0.flatMap { strdup($0) } }
        defer { copies.forEach { free($0) } }
        let pointers: [UnsafePointer<CChar>?] = copies.map { $0.map { UnsafePointer($0) } }
        return take(pointers.withUnsafeBufferPointer { body($0.baseAddress, $0.count) })
    }
}
