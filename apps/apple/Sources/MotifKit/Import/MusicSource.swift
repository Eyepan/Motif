import Foundation

/// A music catalog. Availability alone does not establish download rights; see docs/sources.md.
public protocol MusicSource: Sendable {
    var id: Track.Source { get }
    var displayName: String { get }
    func search(_ query: String) async throws -> [SourceResult]
    /// Resolves a result to the files to download, best quality first.
    func downloads(for result: SourceResult) async throws -> [SourceDownload]
    /// Individual tracks for a browsable album. Other sources retain their existing behavior.
    func tracks(in result: SourceResult) async throws -> [SourceResult]
    func previewURL(for result: SourceResult) async throws -> URL?
}

public extension MusicSource {
    func tracks(in result: SourceResult) async throws -> [SourceResult] { [result] }
    func previewURL(for result: SourceResult) async throws -> URL? { nil }
}

public struct SourceResult: Identifiable, Hashable, Sendable {
    public let id: String
    public let title: String
    public let artist: String?
    public let album: String?
    public let licenseURL: URL?
    /// The release's cover image, used as art when the files have none embedded.
    public let coverURL: URL?
    /// What the download will be, when the catalog says: "flac", "wav", "mp3".
    public let format: String?
    public let isAlbum: Bool
    public let durationSeconds: Int?

    public init(id: String, title: String, artist: String?, album: String?, licenseURL: URL?, coverURL: URL? = nil,
                format: String? = nil, isAlbum: Bool = false, durationSeconds: Int? = nil) {
        self.id = id; self.title = title; self.artist = artist; self.album = album; self.licenseURL = licenseURL
        self.coverURL = coverURL; self.format = format
        self.isAlbum = isAlbum; self.durationSeconds = durationSeconds
    }
}

public struct SourceDownload: Hashable, Sendable {
    public let url: URL
    public let fileName: String
    public let title: String?
    /// Lowercased file extension: flac, wav, ...
    public let format: String
    /// Catalog id for this specific file, stored as Track.sourceRef.
    public let sourceRef: String
    public let artist: String?
    public let album: String?
    public let coverURL: URL?

    public init(url: URL, fileName: String, title: String?, format: String, sourceRef: String,
                artist: String? = nil, album: String? = nil, coverURL: URL? = nil) {
        self.url = url; self.fileName = fileName; self.title = title; self.format = format; self.sourceRef = sourceRef
        self.artist = artist; self.album = album; self.coverURL = coverURL
    }
}

enum SourceError: LocalizedError {
    case badResponse(Int)
    case notConfigured(String)

    var errorDescription: String? {
        switch self {
        case .badResponse(let code): "The catalog returned HTTP \(code)."
        case .notConfigured(let what): "\(what) is not configured."
        }
    }
}

extension URLSession {
    func decoded<T: Decodable>(_ type: T.Type, from url: URL) async throws -> T {
        let (data, response) = try await data(from: url)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw SourceError.badResponse(http.statusCode)
        }
        return try JSONDecoder().decode(T.self, from: data)
    }
}
