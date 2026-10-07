import Foundation

/// A catalog Motif may legally download from. See docs/sources.md.
public protocol MusicSource: Sendable {
    var id: Track.Source { get }
    var displayName: String { get }
    func search(_ query: String) async throws -> [SourceResult]
    /// Resolves a result to the files to download, best quality first.
    func downloads(for result: SourceResult) async throws -> [SourceDownload]
}

public struct SourceResult: Identifiable, Hashable, Sendable {
    public let id: String
    public let title: String
    public let artist: String?
    public let album: String?
    public let licenseURL: URL?
    /// The release's cover image, used as art when the files have none embedded.
    public let coverURL: URL?

    public init(id: String, title: String, artist: String?, album: String?, licenseURL: URL?, coverURL: URL? = nil) {
        self.id = id; self.title = title; self.artist = artist; self.album = album; self.licenseURL = licenseURL
        self.coverURL = coverURL
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

    public init(url: URL, fileName: String, title: String?, format: String, sourceRef: String) {
        self.url = url; self.fileName = fileName; self.title = title; self.format = format; self.sourceRef = sourceRef
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
