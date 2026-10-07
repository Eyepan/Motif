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
}

struct ArtistGroup: Identifiable {
    var id: String { name }
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

    private(set) var tracks: [Track] = []
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

    func refresh() async {
        do { tracks = try await store.allTracks() } catch { errorMessage = error.localizedDescription }
    }

    func tracks(matching query: String) -> [Track] {
        let filter = LibraryFilter(query)
        return filter.isEmpty ? tracks : tracks.filter(filter.matches)
    }

    /// Albums, newest first.
    var albums: [AlbumGroup] {
        var order: [String] = []
        var groups: [String: [Track]] = [:]
        for t in tracks {
            let key = "\(t.album ?? "")\u{1F}\(t.artist ?? "")"
            if groups[key] == nil { order.append(key) }
            groups[key, default: []].append(t)
        }
        return order.compactMap { key in
            guard let items = groups[key], let first = items.first, let title = first.album else { return nil }
            return AlbumGroup(id: key, title: title, artist: first.artist, tracks: items)
        }
    }

    var artists: [ArtistGroup] {
        Dictionary(grouping: tracks.filter { $0.artist != nil }, by: { $0.artist! })
            .map { ArtistGroup(name: $0.key, tracks: $0.value) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
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

    func delete(_ track: Track) async {
        if player.current?.id == track.id { player.stop() }
        do { try await store.delete(track) } catch { errorMessage = error.localizedDescription }
        await refresh()
    }
}
