import MotifKit
import SwiftUI

/// One file in the import list.
struct ImportJob: Identifiable {
    let id: UUID
    let fileName: String
    var stage: ImportEvent.Stage

    var isFinished: Bool {
        switch stage {
        case .done, .skipped, .failed: true
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
    let crateStore: CrateStore
    let player: PlaybackEngine
    let importer: ImportService
    let folderImporter: FolderImporter
    let sources: [any MusicSource]

    private(set) var tracks: [Track] = [] {
        didSet {
            albums = Self.albums(of: tracks, artwork: store.artwork)
            artists = Self.artists(of: tracks)
            tracksByKey = Dictionary(tracks.compactMap { t in t.contentHash.map { ($0, t) } }, uniquingKeysWith: { first, _ in first })
        }
    }
    /// Tracks by content hash, for resolving crate members.
    private var tracksByKey: [String: Track] = [:]
    /// Crates in the order they were made.
    private(set) var crates: [Crate] = []
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
    /// A crate being created or renamed; the root view shows the name prompt while set.
    var crateNaming: CrateNaming?

    var analyzeOnImport: Bool {
        didSet { UserDefaults.standard.set(analyzeOnImport, forKey: "analyzeOnImport") }
    }

    init(store: LibraryStore, history: HistoryStore) {
        self.store = store
        crateStore = CrateStore(history: history, library: store)
        player = PlaybackEngine(store: store)
        importer = ImportService(store: store)
        folderImporter = FolderImporter(importer: importer, store: store,
                                        stateFile: store.mediaDirectory.deletingLastPathComponent().appending(path: "folder-import.json"))
        let jamendo = JamendoSource()
        sources = (jamendo.isConfigured ? [jamendo] : []) + [InternetArchiveSource(), AudiusSource()]
        analyzeOnImport = UserDefaults.standard.object(forKey: "analyzeOnImport") as? Bool ?? true
        player.mixIntoNext = UserDefaults.standard.bool(forKey: "mixIntoNext")
        #if os(macOS)
        readFromDownloads = UserDefaults.standard.bool(forKey: "readFromDownloads")
        #else
        watchedFolder = Self.resolveWatchedFolder()
        #endif
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
        await refreshCrates()
    }

    /// Albums by title, compared without case or extra whitespace, so a soundtrack whose
    /// songs credit different singers stays one album. Its artist is the album artist or the
    /// artist every track shares, else "Various artists".
    nonisolated static func albums(of tracks: [Track], artwork: ArtworkStore? = nil) -> [AlbumGroup] {
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
    nonisolated static func albumKey(_ text: String) -> String {
        text.precomposedStringWithCompatibilityMapping
            .split(whereSeparator: \.isWhitespace).joined(separator: " ")
            .lowercased()
    }

    /// A track counts for each credited artist, primary or featured, so "A, B feat. C"
    /// lists the song under A, B and C. "A & B" splits only when both names also appear alone.
    nonisolated static func artists(of tracks: [Track]) -> [ArtistGroup] {
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

    // MARK: - Crates

    func refreshCrates() async {
        do { crates = try await crateStore.crates() } catch { errorMessage = error.localizedDescription }
    }

    /// The crate's tracks that are on this device, in crate order.
    func tracks(in crate: Crate) -> [Track] {
        crate.trackKeys.compactMap { tracksByKey[$0] }
    }

    /// Tracks in the crate that were added on another device and aren't imported here.
    func missingCount(in crate: Crate) -> Int {
        crate.trackKeys.count - tracks(in: crate).count
    }

    func createCrate(named name: String, with tracks: [Track] = []) async {
        await editCrates { _ = try await crateStore.create(name: name, with: tracks) }
    }

    func renameCrate(_ crate: Crate, to name: String) async {
        await editCrates { try await crateStore.rename(crate.id, to: name) }
    }

    func deleteCrate(_ crate: Crate) async {
        await editCrates { try await crateStore.delete(crate.id) }
    }

    func add(_ tracks: [Track], to crate: Crate) async {
        await editCrates { try await crateStore.add(tracks, to: crate.id) }
    }

    func remove(_ tracks: [Track], from crate: Crate) async {
        let keys = tracks.compactMap(\.contentHash)
        await editCrates { try await crateStore.remove(keys: keys, from: crate.id) }
    }

    /// Runs an edit, then reloads crates, and tracks too, since adding a track can save its content hash.
    private func editCrates(_ edit: () async throws -> Void) async {
        do { try await edit() } catch { errorMessage = error.localizedDescription }
        await refresh()
        await refreshCrates()
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

    // MARK: - Read from Downloads

    #if os(macOS)
    /// Watches ~/Downloads and imports music that lands there (files, album folders, .zip archives).
    var readFromDownloads: Bool {
        didSet {
            UserDefaults.standard.set(readFromDownloads, forKey: "readFromDownloads")
            startWatchingFolder()
        }
    }
    private var watcher: FolderWatcher?

    var watchedFolder: URL? { readFromDownloads ? FolderImporter.downloadsFolder : nil }
    #else
    /// A folder the user picked (iOS can't watch Downloads in the background); rescanned on launch and on return to the app.
    private(set) var watchedFolder: URL?

    func watch(_ folder: URL) {
        let scoped = folder.startAccessingSecurityScopedResource()
        defer { if scoped { folder.stopAccessingSecurityScopedResource() } }
        do {
            UserDefaults.standard.set(try folder.bookmarkData(), forKey: "watchedFolderBookmark")
            watchedFolder = folder
            Task { await scanWatchedFolder() }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func stopWatchingFolder() {
        UserDefaults.standard.removeObject(forKey: "watchedFolderBookmark")
        watchedFolder = nil
    }

    private static func resolveWatchedFolder() -> URL? {
        guard let data = UserDefaults.standard.data(forKey: "watchedFolderBookmark") else { return nil }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: data, bookmarkDataIsStale: &stale) else { return nil }
        if stale, url.startAccessingSecurityScopedResource() {
            defer { url.stopAccessingSecurityScopedResource() }
            if let fresh = try? url.bookmarkData() { UserDefaults.standard.set(fresh, forKey: "watchedFolderBookmark") }
        }
        return url
    }
    #endif

    private var folderScanRunning = false
    private var folderScanAgain = false
    private var pendingFolderScan: Task<Void, Never>?

    /// Scans now and, on the Mac, whenever something lands in Downloads.
    func startWatchingFolder() {
        #if os(macOS)
        // The model lives as long as the app; turning the setting off drops the watcher.
        watcher = watchedFolder.flatMap { folder in
            FolderWatcher(folder) {
                Task { @MainActor in self.scheduleFolderScan(after: 3) }
            }
        }
        #endif
        Task { await scanWatchedFolder() }
    }

    /// Imports what's new in the watched folder. Calls while a scan runs fold into one more pass.
    func scanWatchedFolder() async {
        guard let folder = watchedFolder else { return }
        if folderScanRunning {
            folderScanAgain = true
            return
        }
        folderScanRunning = true
        defer { folderScanRunning = false }
        repeat {
            folderScanAgain = false
            let report = await folderImporter.scan(folder, analyze: analyzeOnImport) { event in
                await MainActor.run { self.apply(event) }
            }
            // Something was still downloading; look again once it has settled.
            if report.waiting { scheduleFolderScan(after: FolderImporter.settleTime + 2) }
        } while folderScanAgain
        await refresh()
    }

    private func scheduleFolderScan(after seconds: Double) {
        pendingFolderScan?.cancel()
        pendingFolderScan = Task {
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            await scanWatchedFolder()
        }
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
