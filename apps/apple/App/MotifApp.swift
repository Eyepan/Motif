import MotifKit
import SwiftUI

@main
struct MotifApp: App {
    @State private var model: AppModel

    init() {
        do {
            _model = State(initialValue: AppModel(store: try LibraryStore.makeDefault()))
        } catch {
            fatalError("Couldn't open the library: \(error)")
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(model)
                .task { await model.refresh() }
        }
        #if os(macOS)
        .commands {
            CommandMenu("Playback") {
                Button("Play/Pause") { model.player.togglePlayPause() }
                    .keyboardShortcut(.space, modifiers: [])
                Button("Next") { model.player.next() }
                    .keyboardShortcut(.rightArrow, modifiers: .command)
                Button("Previous") { model.player.previous() }
                    .keyboardShortcut(.leftArrow, modifiers: .command)
            }
        }
        #endif
    }
}

@MainActor
@Observable
final class AppModel {
    let store: LibraryStore
    let player: PlaybackEngine
    let importer: ImportService
    let sources: [any MusicSource]

    private(set) var tracks: [Track] = []
    var errorMessage: String?

    init(store: LibraryStore) {
        self.store = store
        player = PlaybackEngine(store: store)
        importer = ImportService(store: store)
        let jamendo = JamendoSource()
        sources = [InternetArchiveSource()] + (jamendo.isConfigured ? [jamendo] : [])
    }

    func refresh() async {
        do { tracks = try await store.allTracks() } catch { errorMessage = error.localizedDescription }
    }

    func importLocal(_ urls: [URL]) async {
        do { try await importer.importLocal(urls) } catch { errorMessage = error.localizedDescription }
        await refresh()
    }

    func delete(_ track: Track) async {
        if player.current?.id == track.id { player.stop() }
        do { try await store.delete(track) } catch { errorMessage = error.localizedDescription }
        await refresh()
    }
}
