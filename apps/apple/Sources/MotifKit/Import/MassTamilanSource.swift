import Foundation
import SwiftSoup

/// Parses masstamilan.dev's public HTML. Never retains signed audio URLs in its metadata cache.
/// The website can refuse native requests with an access challenge; no clearance is replayed.
public actor MassTamilanSource: MusicSource {
    public nonisolated let id = Track.Source.masstamilan
    public nonisolated let displayName = "MassTamilan"
    static let origin = URL(string: "https://www.masstamilan.dev")!
    private let load: @Sendable (URL) async throws -> String
    private let now: @Sendable () -> Date
    private var cache: [String: (Date, [SourceResult])] = [:]

    public init(session: URLSession = .shared) {
        load = { url in
            var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
            request.setValue("Motif/0.1 (https://github.com/Eyepan/Motif)", forHTTPHeaderField: "User-Agent")
            request.setValue("text/html", forHTTPHeaderField: "Accept")
            let (bytes, response) = try await session.bytes(for: request, delegate: MassTamilanRedirects(allowMedia: false))
            guard let http = response as? HTTPURLResponse else { throw MassTamilanError.invalidPage }
            if http.statusCode == 403 || http.value(forHTTPHeaderField: "cf-mitigated") == "challenge" {
                throw MassTamilanError.challenge
            }
            if http.statusCode == 404 || http.statusCode == 410 { throw MassTamilanError.unavailable }
            guard (200..<300).contains(http.statusCode) else { throw SourceError.badResponse(http.statusCode) }
            guard let final = http.url, Self.pageURL(final.absoluteString) != nil,
                  http.mimeType == "text/html" else { throw MassTamilanError.invalidPage }
            var data = Data()
            for try await byte in bytes {
                guard data.count < 2 * 1024 * 1024 else { throw MassTamilanError.invalidPage }
                data.append(byte)
            }
            guard let html = String(data: data, encoding: .utf8) else { throw MassTamilanError.invalidPage }
            return html
        }
        now = { Date() }
    }

    /// Injected transport and clock keep parser/cache/error tests offline.
    init(load: @escaping @Sendable (URL) async throws -> String, now: @escaping @Sendable () -> Date = { Date() }) {
        self.load = load; self.now = now
    }

    public func search(_ query: String) async throws -> [SourceResult] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        var url = URLComponents(url: Self.origin.appending(path: "search"), resolvingAgainstBaseURL: false)!
        url.queryItems = [URLQueryItem(name: "keyword", value: q)]
        // Empty search discovers the homepage's latest albums; no guessed discovery endpoint.
        return try await listing(q.isEmpty ? Self.origin : url.url!, key: "search:\(q)") {
            try Self.parseSearch($0)
        }
    }

    public func tracks(in result: SourceResult) async throws -> [SourceResult] {
        guard result.isAlbum else { return [result] }
        let url = try Self.resultURL(result.id)
        return try await listing(url, key: "album:\(result.id)") { try Self.parsePage($0, url: url).tracks }
    }

    public func downloads(for result: SourceResult) async throws -> [SourceDownload] {
        let url = try Self.resultURL(result.id)
        // Always fetch again here: downloader signatures expire and may be IP-bound.
        let page = try Self.parsePage(try await load(url), url: url)
        guard !page.downloads.isEmpty else { throw MassTamilanError.unavailable }
        return page.downloads
    }

    public func previewURL(for result: SourceResult) async throws -> URL? {
        try await downloads(for: result).first?.url
    }

    private func listing(_ url: URL, key: String, parse: (String) throws -> [SourceResult]) async throws -> [SourceResult] {
        if let (date, results) = cache[key], now().timeIntervalSince(date) < 300 { return results }
        let results = try parse(try await load(url))
        if cache.count >= 16, let oldest = cache.min(by: { $0.value.0 < $1.value.0 })?.key { cache.removeValue(forKey: oldest) }
        cache[key] = (now(), results)
        return results
    }

    /// HTML is untrusted. Only same-host HTTPS pages and downloader links are accepted.
    static func pageURL(_ value: String) -> URL? {
        guard let url = URL(string: value, relativeTo: origin)?.absoluteURL,
              url.scheme == "https", url.host == origin.host, url.user == nil, url.password == nil,
              url.port == nil || url.port == 443 else { return nil }
        return url
    }

    private static func resultURL(_ id: String) throws -> URL {
        guard id.range(of: "^/(?:[0-9]+/)?[A-Za-z0-9_-]+$", options: .regularExpression) != nil,
              let url = pageURL(id), url.query == nil,
              url.fragment == nil, !url.path.hasPrefix("/downloader/"), url.path != "/" else { throw MassTamilanError.invalidPage }
        return url
    }

    private static func stablePath(_ value: String) -> String? {
        guard let url = pageURL(value) else { return nil }
        return url.path
    }

    private static func document(_ html: String) throws -> Document {
        guard html.utf8.count <= 2 * 1024 * 1024 else { throw MassTamilanError.invalidPage }
        if html.contains("cf_chl_") || html.contains("<title>Just a moment") { throw MassTamilanError.challenge }
        return try SwiftSoup.parse(html)
    }

    static func parseSearch(_ html: String) throws -> [SourceResult] {
        let doc = try document(html)
        guard try doc.select(".bots").first() != nil,
              try doc.select("form[action='/search'] input[name=keyword]").first() != nil else { throw MassTamilanError.invalidPage }
        var results: [SourceResult] = []
        for item in try doc.select(".bots .srg .srl li") {
            guard let a = try item.select("a").first(), let path = stablePath(try a.attr("href")),
                  path.range(of: "^/[0-9]+/[^/]+$", options: .regularExpression) != nil else { continue }
            let title = try a.text()
            guard !title.isEmpty else { continue }
            let album = try item.select(".srm").first()?.text().components(separatedBy: " · ").first
            results.append(SourceResult(id: path, title: title, artist: nil, album: album, licenseURL: nil, format: "mp3"))
        }
        for item in try doc.select(".bots .a-i") {
            guard let a = try item.select("a").first(), let path = stablePath(try a.attr("href")),
                  let heading = try a.select("h2").first() else { continue }
            let title = try heading.text()
            guard !title.isEmpty else { continue }
            let music = try field("Music", in: a)
            results.append(SourceResult(id: path, title: title, artist: music, album: title, licenseURL: nil,
                                        coverURL: try image(in: a), format: "mp3", isAlbum: true))
        }
        var seen: Set<String> = []
        return results.filter { seen.insert($0.id).inserted }.prefix(50).map { $0 }
    }

    struct Page { let tracks: [SourceResult]; let downloads: [SourceDownload] }

    static func parsePage(_ html: String, url: URL) throws -> Page {
        let doc = try document(html)
        guard let article = try doc.select("article.post-content").first() else { throw MassTamilanError.invalidPage }
        let albumElement = try article.select("[itemtype='http://schema.org/MusicAlbum']").first()
        let album = try albumElement?.select("meta[itemprop=name]").first()?.attr("content")
        let cover = try image(in: article)
        let isSong = url.path.range(of: "^/[0-9]+/[^/]+$", options: .regularExpression) != nil
        // Song pages also contain other album tracks. Resolve only the first recording table.
        let root: Element
        if isSong {
            guard let recording = try article.select("[itemtype='http://schema.org/MusicRecording']").first() else { throw MassTamilanError.invalidPage }
            root = recording
        } else {
            guard let albumElement else { throw MassTamilanError.invalidPage }
            root = albumElement
        }
        var tracks: [SourceResult] = []
        var downloads: [SourceDownload] = []
        for row in try root.select("table tr") {
            guard let name = try row.select("h2 [itemprop=name]").first() else { continue }
            let title = try name.text()
            guard !title.isEmpty else { continue }
            let artist = try row.select("[itemprop=byArtist]").first()?.text()
            let duration = try row.select("[itemprop=duration]").first()?.text().split(separator: ":", omittingEmptySubsequences: false)
            let seconds = duration.flatMap { parts -> Int? in
                guard parts.count == 2, let minutes = Int(parts[0]), let seconds = Int(parts[1]),
                      minutes >= 0, minutes < Int.max / 60, (0..<60).contains(seconds) else { return nil }
                return minutes * 60 + seconds
            }
            let path = isSong ? url.path : try name.select("a").first().flatMap { stablePath(try $0.attr("href")) }
            guard let path else { continue }
            let result = SourceResult(id: path, title: title, artist: artist, album: album, licenseURL: nil,
                                      coverURL: cover, format: "mp3", durationSeconds: seconds)
            tracks.append(result)
            // Never use ZIP links or infer audio filenames/CDN signatures.
            var choices: [(Int, URL)] = []
            for a in try row.select("a.dlink") {
                let text = try a.text().lowercased()
                guard !text.contains("zip"), !text.contains("rar"),
                      let bitrate = [320, 128].first(where: { text.hasPrefix("\($0)kbps") }),
                      let link = pageURL(try a.attr("href")), link.path.hasPrefix("/downloader/") else { continue }
                choices.append((bitrate, link))
            }
            if let best = choices.max(by: { $0.0 < $1.0 }) {
                downloads.append(SourceDownload(url: best.1, fileName: "\(URL(fileURLWithPath: path).lastPathComponent).mp3",
                                                title: title, format: "mp3", sourceRef: path,
                                                artist: artist, album: album, coverURL: cover))
            }
        }
        return Page(tracks: tracks, downloads: downloads)
    }

    private static func image(in element: Element) throws -> URL? {
        guard let img = try element.select("img[src]").first() else { return nil }
        return pageURL(try img.attr("src"))
    }

    private static func field(_ label: String, in element: Element) throws -> String? {
        guard let p = try element.select("p").first() else { return nil }
        let lines = try p.html().replacingOccurrences(of: "<br\\s*/?>", with: "\n", options: .regularExpression)
        for line in lines.components(separatedBy: "\n") {
            let text = try SwiftSoup.parseBodyFragment(line).text()
            if text.hasPrefix(label + ":") {
                let value = text.dropFirst(label.count + 1).trimmingCharacters(in: .whitespacesAndNewlines)
                return value.isEmpty || value == "Not Available" ? nil : value
            }
        }
        return nil
    }
}

enum MassTamilanError: LocalizedError {
    case challenge, invalidPage, unavailable
    var errorDescription: String? {
        switch self {
        case .challenge: "MassTamilan challenged this request. Native access may be unavailable; Motif does not bypass website protection."
        case .invalidPage: "MassTamilan returned an unrecognized page. Its website may have changed."
        case .unavailable: "This MassTamilan track is unavailable or has no public MP3 download."
        }
    }
}

/// Only the hosts actually seen in the captured public media redirect are accepted.
final class MassTamilanRedirects: NSObject, URLSessionTaskDelegate, Sendable {
    private let allowedHosts: Set<String>
    init(allowMedia: Bool = true) {
        allowedHosts = allowMedia ? ["www.masstamilan.dev", "masstamilan.download"] : ["www.masstamilan.dev"]
    }
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        guard let url = request.url, url.scheme == "https", url.user == nil, url.password == nil,
              url.port == nil || url.port == 443,
              allowedHosts.contains(url.host ?? "") else {
            completionHandler(nil); return
        }
        completionHandler(request)
    }
}
