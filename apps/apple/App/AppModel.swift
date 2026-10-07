import MotifKit
import SwiftUI

/// One file in the import list.
struct ImportJob: Identifiable {
    let id: UUID
    let fileName: String
    var stage: ImportEvent.Stage

    var isFinished: Bool {
        switch stage {
        case .done, .failed: true
        case .copying, .analyzing: false
        }
    }
}

struct AlbumGroup: Identifiable {
    let id: String
    let title: String
    let artist: String?
    let tracks: [Track]
    /// The first of its tracks that has art.
    var artwork: UUID?
}

struct ArtistGroup: Identifiable {
    /// Normalized name, so "Anirudh" and "anirudh " are one artist.
    let id: String
    let name: String
    let tracks: [Track]
}

@MainActor
@Observable
final class AppModel {
    enum PhoneTab: Hashable { case library, crates, mix, search }

    let store: LibraryStore
    let player: PlaybackEngine
    let importer: ImportService
    let sources: [any MusicSource]

    private(set) var tracks: [Track] = [] {
        didSet {
            albums = Self.albums(of: tracks, artwork: store.artwork)
            artists = Self.artists(of: tracks)
        }
    }
    /// Albums, newest first.
    private(set) var albums: [AlbumGroup] = []
    /// Artists by name, from split credits.
    private(set) var artists: [ArtistGroup] = []
    private(set) var importJobs: [ImportJob] = []
    var errorMessage: String?

    // Navigation shared by the phone views.
    var phoneTab: PhoneTab = .library
    var showImport = false
    var showNowPlaying = false

    var analyzeOnImport: Bool {
        didSet { UserDefaults.standard.set(analyzeOnImport, forKey: "analyzeOnImport") }
    }

    init(store: LibraryStore) {
        self.store = store
        player = PlaybackEngine(store: store)
        importer = ImportService(store: store)
        let jamendo = JamendoSource()
        sources = [InternetArchiveSource()] + (jamendo.isConfigured ? [jamendo] : [])
        analyzeOnImport = UserDefaults.standard.object(forKey: "analyzeOnImport") as? Bool ?? true
        player.mixIntoNext = UserDefaults.standard.bool(forKey: "mixIntoNext")
    }

    func setMixIntoNext(_ on: Bool) {
        player.mixIntoNext = on
        UserDefaults.standard.set(on, forKey: "mixIntoNext")
    }

    /// Bumped on every refresh, so art tiles redraw when art was added.
    private(set) var artworkRevision = 0

    func refresh() async {
        do { tracks = try await store.allTracks() } catch { errorMessage = error.localizedDescription }
        artworkRevision += 1
    }

    func tracks(matching query: String) -> [Track] {
        let filter = LibraryFilter(query)
        return filter.isEmpty ? tracks : tracks.filter(filter.matches)
    }

    /// Re-cleans tags when the shared rules changed since the last launch, then loads.
    func load() async {
        let key = "tagCleanerVersion"
        do {
            let version = try await store.prepareTagCleaning(previousVersion: UserDefaults.standard.integer(forKey: key))
            UserDefaults.standard.set(version, forKey: key)
        } catch {
            errorMessage = error.localizedDescription
        }
        await refresh()
    }

    /// Albums by title, compared without case or extra whitespace, so a soundtrack whose
    /// songs credit different singers stays one album. Its artist is the album artist or the
    /// artist every track shares, else "Various artists".
    static func albums(of tracks: [Track], artwork: ArtworkStore? = nil) -> [AlbumGroup] {
        var order: [String] = []
        var groups: [String: [Track]] = [:]
        for t in tracks {
            guard let album = t.album, !album.trimmingCharacters(in: .whitespaces).isEmpty else { continue }
            let key = Self.albumKey(album)
            if groups[key] == nil { order.append(key) }
            groups[key, default: []].append(t)
        }
        return order.compactMap { key in
            guard let items = groups[key], let first = items.first, let title = first.album else { return nil }
            func shared(_ names: [String]) -> String? {
                let distinct = Dictionary(grouping: names, by: { Self.albumKey($0) })
                return distinct.count == 1 ? names.first : nil
            }
            let albumArtists = items.compactMap(\.albumArtist)
            let artists = items.compactMap(\.artist)
            let artist = shared(albumArtists) ?? shared(artists) ?? (artists.isEmpty ? nil : "Various artists")
            return AlbumGroup(id: key, title: title.trimmingCharacters(in: .whitespaces), artist: artist, tracks: items,
                              artwork: artwork.flatMap { store in items.first { store.hasArtwork($0.id) }?.id })
        }
    }

    /// Unicode-normalized, single-spaced, case-folded. Combining marks are kept:
    /// in scripts like Tamil they are vowels, not accents.
    static func albumKey(_ text: String) -> String {
        text.precomposedStringWithCompatibilityMapping
            .split(whereSeparator: \.isWhitespace).joined(separator: " ")
            .lowercased()
    }

    /// A track counts for each credited artist, primary or featured, so "A, B feat. C"
    /// lists the song under A, B and C. "A & B" splits only when both names also appear alone.
    static func artists(of tracks: [Track]) -> [ArtistGroup] {
        let credited = tracks.filter { !($0.artist ?? "").trimmingCharacters(in: .whitespaces).isEmpty }
        let credits = credited.map { TagCleaner.splitArtists($0.artist!, known: [String]()) }
        let known = Set(credits.flatMap { $0.map { TagCleaner.norm($0.name) } })
        var names: [String: String] = [:]
        var byKey: [String: [Track]] = [:]
        for track in credited {
            for credit in TagCleaner.splitArtists(track.artist!, known: known) {
                let key = TagCleaner.norm(credit.name)
                if names[key] == nil { names[key] = credit.name }
                if byKey[key]?.last?.id != track.id { byKey[key, default: []].append(track) }
            }
        }
        var groups = byKey.map { ArtistGroup(id: $0.key, name: names[$0.key] ?? $0.key, tracks: $0.value) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        let unknown = tracks.filter { ($0.artist ?? "").trimmingCharacters(in: .whitespaces).isEmpty }
        if !unknown.isEmpty { groups.append(ArtistGroup(id: "\u{0}unknown", name: "Unknown artist", tracks: unknown)) }
        return groups
    }

    // MARK: - Import

    var activeImportCount: Int { importJobs.filter { !$0.isFinished }.count }

    func importLocal(_ urls: [URL]) async {
        await importer.importLocal(urls, analyze: analyzeOnImport) { event in
            await MainActor.run { self.apply(event) }
        }
        await refresh()
    }

    func clearFinishedImports() {
        importJobs.removeAll { $0.isFinished }
    }

    private func apply(_ event: ImportEvent) {
        if let i = importJobs.firstIndex(where: { $0.id == event.jobID }) {
            importJobs[i].stage = event.stage
        } else {
            importJobs.append(ImportJob(id: event.jobID, fileName: event.fileName, stage: event.stage))
        }
        if case .done = event.stage { Task { await refresh() } }
    }

    /// Analyzes tracks imported before analysis existed, or with it switched off.
    func analyzeMissing() async {
        guard analyzeOnImport else { return }
        let pending = tracks.filter { $0.waveform == nil }
        for track in pending {
            _ = await importer.analyze(track)
        }
        if !pending.isEmpty { await refresh() }
    }

    /// Finds art for tracks imported before art was kept.
    func backfillArtwork() async {
        let artwork = store.artwork
        var found = false
        for track in tracks where !artwork.wasChecked(track.id) {
            let url = await store.url(for: track)
            if await artwork.extract(track.id, from: url) { found = true }
        }
        if found { await refresh() }
    }

    func delete(_ track: Track) async {
        if player.current?.id == track.id { player.stop() }
        do { try await store.delete(track) } catch { errorMessage = error.localizedDescription }
        await refresh()
    }
}
