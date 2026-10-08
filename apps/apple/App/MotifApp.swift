import MotifKit
import SwiftUI

@main
struct MotifApp: App {
    @State private var model: AppModel

    init() {
        do {
            _model = State(initialValue: AppModel(store: try LibraryStore.makeDefault(), history: try HistoryStore.makeDefault()))
        } catch {
            fatalError("Couldn't open the library: \(error)")
        }
    }

    var body: some Scene {
        WindowGroup {
            Group {
                #if os(macOS)
                MacRootView()
                #else
                PhoneRootView()
                #endif
            }
            .environment(model)
            .tint(Theme.accent)
            .preferredColorScheme(.dark)
            .task {
                await model.load()
                await model.backfillArtwork()
                await model.analyzeMissing()
            }
            .alert("Something went wrong", isPresented: Binding(
                get: { model.errorMessage != nil },
                set: { if !$0 { model.errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(model.errorMessage ?? "")
            }
        }
        #if os(macOS)
        .defaultSize(width: 1280, height: 800)
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
