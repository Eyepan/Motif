import Foundation
import Testing
@testable import MotifKit

@Suite struct MassTamilanTests {
    private func fixture(_ name: String) throws -> String {
        let url = try #require(Bundle.module.url(forResource: name, withExtension: "html", subdirectory: "Fixtures/MassTamilan"))
        return try String(contentsOf: url, encoding: .utf8)
    }

    @Test func searchAndDiscoveryUseCapturedMarkup() throws {
        let results = try MassTamilanSource.parseSearch(fixture("search"))
        #expect(results.count == 11)
        #expect(results.first?.id == "/996/mascara-pottu-mp3-song-1")
        #expect(results.first?.title == "Mascara Pottu")
        #expect(results.first?.album == "Salim")
        #expect(results.filter(\.isAlbum).count == 6)
        #expect(results.allSatisfy { $0.licenseURL == nil && !$0.id.contains("?") })
        #expect(results.contains { $0.title == "Maara & Paaru" })
        #expect(!results.contains { $0.title == "Maskara" }) // Other-language external host.
        let home = try MassTamilanSource.parseSearch(fixture("home"))
        #expect(home.count == 10)
        #expect(home.allSatisfy { $0.isAlbum })
        #expect(home.first?.coverURL?.path == "/i/ramayana-tamil-2026.jpg")
        #expect(home.first?.artist == "A.R.Rahman, Hans Zimmer")
    }

    @Test func extractsCurrentSongAndBestQualityOnly() throws {
        let url = URL(string: "https://www.masstamilan.dev/996/mascara-pottu-mp3-song-1")!
        let page = try MassTamilanSource.parsePage(fixture("track"), url: url)
        #expect(page.tracks.count == 1) // Excludes the other songs and ZIP downloads.
        #expect(page.downloads.count == 1)
        #expect(page.tracks.first?.durationSeconds == 296)
        let file = try #require(page.downloads.first)
        #expect(file.title == "Mascara Pottu")
        #expect(file.artist == "Supriya Joshi, Sharmila, Vijay Antony")
        #expect(file.album == "Salim")
        #expect(file.format == "mp3")
        #expect(file.sourceRef == url.path)
        #expect(file.url.path == "/downloader/fixture-2/4102444800/test")
        #expect(file.coverURL?.path == "/i/salim-2014.jpg")
    }

    @Test func browsesCapturedEmbeddedAlbumList() throws {
        // This is the actual embedded MusicAlbum fragment in the captured song page,
        // not a claim that a standalone album page has been live-tested.
        let page = try MassTamilanSource.parsePage(fixture("track"), url: URL(string: "https://www.masstamilan.dev/salim-songs-1")!)
        #expect(page.tracks.map(\.title) == ["Avala Nambithan", "Prayer", "Ulagam Unnai", "Unnai Kanda Naal Mudhal"])
        #expect(page.downloads.count == 4)
        #expect(page.tracks.allSatisfy { $0.artist != nil && $0.album == "Salim" })
    }

    @Test func malformedChallengesAndUnsafeURLs() throws {
        #expect(throws: (any Error).self) { try MassTamilanSource.parseSearch("<html>broken") }
        #expect(throws: (any Error).self) { try MassTamilanSource.parseSearch("<title>Just a moment...</title>") }
        #expect(MassTamilanSource.pageURL("https://evil.example/track") == nil)
        #expect(MassTamilanSource.pageURL("//evil.example/track") == nil)
        #expect(MassTamilanSource.pageURL("http://www.masstamilan.dev/a") == nil)
        #expect(MassTamilanSource.pageURL("https://user@www.masstamilan.dev/a") == nil)
        #expect(MassTamilanSource.pageURL("/i/a.jpg")?.host == "www.masstamilan.dev")
        let empty = try MassTamilanSource.parseSearch("<section class=bots><form action=/search><input name=keyword></form></section>")
        #expect(empty.isEmpty)
    }

    @Test func noAudioLinksMeansUnavailableAndNeverDownloadsOtherSongs() async throws {
        let html = try fixture("track").replacingOccurrences(of: "class=\"dlink\"", with: "class=\"unavailable\"")
        let source = MassTamilanSource(load: { _ in html })
        let result = SourceResult(id: "/996/mascara-pottu-mp3-song-1", title: "Mascara Pottu", artist: nil, album: nil, licenseURL: nil)
        await #expect(throws: (any Error).self) { try await source.downloads(for: result) }
    }

    @Test func fallsBackTo128AndRejectsForeignDownloaderLinks() throws {
        let html = try fixture("track")
        let url = URL(string: "https://www.masstamilan.dev/996/mascara-pottu-mp3-song-1")!
        let lower = html.replacingOccurrences(of: "<a class=\"dlink\"[^>]*>320kbps[^<]*</a>", with: "", options: .regularExpression)
        let page = try MassTamilanSource.parsePage(lower, url: url)
        #expect(page.downloads.first?.url.path == "/downloader/fixture-1/4102444800/test")
        let unsafe = html.replacingOccurrences(of: "href=\"/downloader/", with: "href=\"https://evil.example/downloader/")
        #expect(try MassTamilanSource.parsePage(unsafe, url: url).downloads.isEmpty)
        let malformedDuration = html.replacingOccurrences(of: "04:56", with: "04:bad:56")
        #expect(try MassTamilanSource.parsePage(malformedDuration, url: url).tracks.first?.durationSeconds == nil)
    }

    @Test func mediaRedirectsUseOnlyVerifiedHTTPSHosts() async {
        let delegate = MassTamilanRedirects()
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let url = URL(string: "https://www.masstamilan.dev/downloader/test")!
        let task = session.dataTask(with: url)
        let response = HTTPURLResponse(url: url, statusCode: 302, httpVersion: nil, headerFields: nil)!
        for (address, expected) in [("https://masstamilan.download/t/a.mp3", true), ("https://evil.example/a.mp3", false),
                                    ("http://masstamilan.download/t/a.mp3", false), ("https://user@masstamilan.download/a.mp3", false)] {
            let accepted = await withCheckedContinuation { continuation in
                delegate.urlSession(session, task: task, willPerformHTTPRedirection: response,
                                    newRequest: URLRequest(url: URL(string: address)!)) {
                    continuation.resume(returning: $0 != nil)
                }
            }
            #expect(accepted == expected)
        }
    }

    @Test func cachesOnlyMetadataAndResolvesFreshForEveryPreviewAndDownload() async throws {
        let transport = CaptureTransport(search: try fixture("search"), track: try fixture("track"))
        let source = MassTamilanSource(load: { try await transport.load($0) })
        let hits = try await source.search("Mascara & Pottu")
        _ = try await source.search("Mascara & Pottu")
        #expect(await transport.count == 1)
        let hit = try #require(hits.first)
        let preview = try await source.previewURL(for: hit)
        let files = try await source.downloads(for: hit)
        #expect(await transport.count == 3)
        #expect(preview != files.first?.url)
        #expect(await transport.searchQuery == "Mascara & Pottu")
    }

    @Test func propagatesHTTPChallenge() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ChallengeProtocol.self]
        let source = MassTamilanSource(session: URLSession(configuration: config))
        await #expect(throws: (any Error).self) { try await source.search("test") }
    }
}

private actor CaptureTransport {
    let search: String
    let track: String
    var count = 0
    var searchQuery: String?
    init(search: String, track: String) { self.search = search; self.track = track }
    func load(_ url: URL) throws -> String {
        count += 1
        if url.path == "/search" {
            searchQuery = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first?.value
            return search
        }
        return track.replacingOccurrences(of: "fixture-2/", with: "fresh-\(count)/")
    }
}

private final class ChallengeProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let response = HTTPURLResponse(url: request.url!, statusCode: 403, httpVersion: nil, headerFields: ["cf-mitigated": "challenge"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

/// Explicit opt-in connectivity probe. Website challenges are a real failure, not a skipped result.
@Suite(.serialized) struct MassTamilanLiveTests {
    @Test(.enabled(if: ProcessInfo.processInfo.environment["MOTIF_MASSTAMILAN_LIVE"] == "1"))
    func nativeSearch() async throws {
        let results = try await MassTamilanSource().search("mascara")
        #expect(!results.isEmpty)
    }

    @Test(.enabled(if: ProcessInfo.processInfo.environment["MOTIF_MASSTAMILAN_LIVE"] == "1"))
    func nativeAlbumBrowsing() async throws {
        let source = MassTamilanSource()
        let results = try await source.search("mascara")
        let album = try #require(results.first(where: { $0.isAlbum }))
        let tracks = try await source.tracks(in: album)
        #expect(!tracks.isEmpty)
        #expect(tracks.allSatisfy { !$0.isAlbum && $0.album != nil })
    }

    @Test(.enabled(if: ProcessInfo.processInfo.environment["MOTIF_MASSTAMILAN_LIVE_MEDIA"] == "1"))
    func nativeDownloadAndImport() async throws {
        let source = MassTamilanSource()
        let results = try await source.search("mascara")
        let song = try #require(results.first(where: { !$0.isAlbum }))
        let files = try await source.downloads(for: song)
        #expect(files.count == 1)
        let file = try #require(files.first)
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try LibraryStore(directory: directory)
        let imported = try await ImportService(store: store).importDownload(file, from: .masstamilan, result: song)
        #expect(imported.source == .masstamilan)
        #expect(imported.sourceRef == song.id)
        #expect(imported.durationMs > 0 && imported.artist != nil && imported.album != nil)
        #expect(imported.waveform != nil && imported.loudnessDb != nil)
        #expect(imported.licenseURL == nil)
        #expect(store.artwork.hasArtwork(imported.id))
        #expect(imported.format == "mp3")
        #expect(try await store.allTracks().count == 1)
    }
}
