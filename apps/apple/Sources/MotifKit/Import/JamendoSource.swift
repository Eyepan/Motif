import Foundation

/// Jamendo's Creative Commons catalog (API v3). Needs a free client id from
/// developer.jamendo.com, read from the `JAMENDO_CLIENT_ID` Info.plist key.
public struct JamendoSource: MusicSource {
    public let id = Track.Source.jamendo
    public let displayName = "Jamendo"
    private let clientID: String
    private let session: URLSession

    public init(clientID: String? = Bundle.main.object(forInfoDictionaryKey: "JAMENDO_CLIENT_ID") as? String,
                session: URLSession = .shared) {
        self.clientID = clientID ?? ""
        self.session = session
    }

    public var isConfigured: Bool { !clientID.isEmpty }

    public func search(_ query: String) async throws -> [SourceResult] {
        try await tracks(["search": query, "limit": "50"]).map(\.result)
    }

    public func downloads(for result: SourceResult) async throws -> [SourceDownload] {
        guard let t = try await tracks(["id": result.id]).first, t.audiodownload_allowed,
              let url = URL(string: t.audiodownload) else { return [] }
        return [SourceDownload(url: url, fileName: "\(t.id).flac", title: t.name, format: "flac", sourceRef: t.id)]
    }

    private func tracks(_ params: [String: String]) async throws -> [Wire.Track] {
        guard isConfigured else { throw SourceError.notConfigured("Jamendo client id") }
        var components = URLComponents(string: "https://api.jamendo.com/v3.0/tracks/")!
        components.queryItems = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "format", value: "json"),
            URLQueryItem(name: "audiodlformat", value: "flac"),
        ] + params.map { URLQueryItem(name: $0.key, value: $0.value) }
        return try await session.decoded(Wire.self, from: components.url!).results
    }

    private struct Wire: Decodable {
        struct Track: Decodable {
            let id: String
            let name: String
            let artist_name: String?
            let album_name: String?
            let audiodownload: String
            let audiodownload_allowed: Bool
            let license_ccurl: String?
            let album_image: String?

            var result: SourceResult {
                SourceResult(id: id, title: name, artist: artist_name, album: album_name,
                             licenseURL: license_ccurl.flatMap(URL.init(string:)),
                             coverURL: album_image.flatMap(URL.init(string:)))
            }
        }
        let results: [Track]
    }
}
