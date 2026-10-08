import Foundation

/// Audius: artists upload their own music and choose whether fans may download it,
/// often as the original WAV or FLAC. Public API, no key; `app_name` identifies Motif.
/// Search keeps only tracks the artist made downloadable and didn't gate behind a
/// purchase or follow.
public struct AudiusSource: MusicSource {
    public let id = Track.Source.audius
    public let displayName = "Audius"
    private let session: URLSession

    static let api = URL(string: "https://api.audius.co/v1")!
    static let appName = "Motif"

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func search(_ query: String) async throws -> [SourceResult] {
        let url = Self.url("tracks/search", [URLQueryItem(name: "query", value: query)])
        let (data, response) = try await session.data(from: url)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw SourceError.badResponse(http.statusCode)
        }
        return try Self.parseSearch(data)
    }

    public func downloads(for result: SourceResult) async throws -> [SourceDownload] {
        // Audius serves the original upload when there is one, else an MP3.
        let format = result.format ?? "mp3"
        return [SourceDownload(url: Self.url("tracks/\(result.id)/download", []), fileName: "\(result.id).\(format)",
                               title: result.title, format: format, sourceRef: result.id)]
    }

    static func url(_ path: String, _ items: [URLQueryItem]) -> URL {
        var components = URLComponents(url: api.appending(path: path), resolvingAgainstBaseURL: false)!
        components.queryItems = items + [URLQueryItem(name: "app_name", value: appName)]
        return components.url!
    }

    /// Downloadable tracks only.
    static func parseSearch(_ data: Data) throws -> [SourceResult] {
        try JSONDecoder().decode(Wire.self, from: data).data.compactMap { t in
            let downloadable = !(t.is_download_gated ?? false) && ((t.is_downloadable ?? false) || (t.downloadable ?? false))
            guard downloadable else { return nil }
            let format: String? = switch (t.orig_filename.map { ($0 as NSString).pathExtension.lowercased() } ?? "") {
            case "flac": "flac"
            case "wav", "wave": "wav"
            case "aif", "aiff": "aiff"
            case "mp3": "mp3"
            default: nil
            }
            let art = t.artwork?.compactMapValues { $0 }
            let cover = art?["480x480"] ?? art?["1000x1000"] ?? art?["150x150"]
            return SourceResult(id: t.id, title: t.title, artist: t.user?.name, album: nil, licenseURL: nil,
                                coverURL: cover.flatMap(URL.init(string:)), format: format)
        }
    }

    private struct Wire: Decodable {
        struct Track: Decodable {
            struct User: Decodable { let name: String? }
            let id: String
            let title: String
            let user: User?
            let artwork: [String: String?]?
            let is_downloadable: Bool?
            let downloadable: Bool?
            let is_download_gated: Bool?
            let orig_filename: String?
        }
        let data: [Track]
    }
}
