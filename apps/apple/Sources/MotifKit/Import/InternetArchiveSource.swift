import Foundation

/// Internet Archive audio items that carry FLAC or WAV files.
/// APIs: advancedsearch.php (search) and /metadata/{identifier} (files).
public struct InternetArchiveSource: MusicSource {
    public let id = Track.Source.internetArchive
    public let displayName = "Internet Archive"
    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func search(_ query: String) async throws -> [SourceResult] {
        var components = URLComponents(string: "https://archive.org/advancedsearch.php")!
        let q = "(\(query)) AND mediatype:audio AND (format:Flac OR format:\"24bit Flac\" OR format:WAVE)"
        components.queryItems = [
            URLQueryItem(name: "q", value: q),
            URLQueryItem(name: "rows", value: "50"),
            URLQueryItem(name: "output", value: "json"),
        ] + ["identifier", "title", "creator", "licenseurl"].map { URLQueryItem(name: "fl[]", value: $0) }
        let response = try await session.decoded(SearchResponse.self, from: components.url!)
        return response.response.docs.map {
            SourceResult(id: $0.identifier, title: $0.title?.first ?? $0.identifier,
                         artist: $0.creator?.first, album: $0.title?.first,
                         licenseURL: $0.licenseurl?.first.flatMap(URL.init(string:)),
                         coverURL: URL(string: "https://archive.org/services/img/")?.appending(path: $0.identifier))
        }
    }

    public func downloads(for result: SourceResult) async throws -> [SourceDownload] {
        let url = URL(string: "https://archive.org/metadata/")!.appending(path: result.id)
        let item = try await session.decoded(ItemMetadata.self, from: url)
        let base = URL(string: "https://archive.org/download/")!.appending(path: result.id)

        // Items often carry the same recording in several formats; keep the best per track.
        let ranked = item.files.compactMap { file -> (rank: Int, file: ItemMetadata.File)? in
            guard let rank = Self.formatRank[file.format ?? ""] else { return nil }
            return (rank, file)
        }
        var best: [String: (rank: Int, file: ItemMetadata.File)] = [:]
        for entry in ranked {
            let key = (entry.file.name as NSString).deletingPathExtension
            if let existing = best[key], existing.rank <= entry.rank { continue }
            best[key] = entry
        }
        return best.values
            .sorted { ($0.file.trackNumber ?? .max, $0.file.name) < ($1.file.trackNumber ?? .max, $1.file.name) }
            .map { entry in
                SourceDownload(
                    url: base.appending(path: entry.file.name),
                    fileName: (entry.file.name as NSString).lastPathComponent,
                    title: entry.file.title,
                    format: (entry.file.name as NSString).pathExtension.lowercased(),
                    sourceRef: "\(result.id)/\(entry.file.name)"
                )
            }
    }

    /// Lower is better.
    private static let formatRank = ["24bit Flac": 0, "Flac": 1, "WAVE": 2]

    // MARK: - Wire types

    private struct SearchResponse: Decodable {
        struct Body: Decodable { let docs: [Doc] }
        struct Doc: Decodable {
            let identifier: String
            let title: OneOrMany?
            let creator: OneOrMany?
            let licenseurl: OneOrMany?
        }
        let response: Body
    }

    private struct ItemMetadata: Decodable {
        struct File: Decodable {
            let name: String
            let format: String?
            let title: String?
            let track: String?
            /// "3" or "3/12".
            var trackNumber: Int? { track.flatMap { Int($0.split(separator: "/").first ?? "") } }
        }
        let files: [File]
    }

    /// Archive metadata fields are a string or an array of strings depending on the item.
    private struct OneOrMany: Decodable {
        let values: [String]
        var first: String? { values.first }

        init(from decoder: Decoder) throws {
            let c = try decoder.singleValueContainer()
            if let many = try? c.decode([String].self) {
                values = many
            } else {
                values = [try c.decode(String.self)]
            }
        }
    }
}
