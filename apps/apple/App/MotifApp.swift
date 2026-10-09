import MotifKit
import SwiftUI

@main
struct MotifApp: App {
    @State private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

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
                await model.account.start()
                model.startWatchingFolder()
                await model.backfillArtwork()
                await model.analyzeMissing()
            }
            .onChange(of: scenePhase) { _, phase in
                // iOS can't watch a folder in the background; catch up on what arrived meanwhile.
                if phase == .active {
                    Task {
                        await model.scanWatchedFolder()
                        await model.account.syncNow()
                    }
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .historyDidSync)) { _ in
                // Crates from other devices arrive through the history log.
                Task { await model.refreshCrates() }
            }
            .alert("Something went wrong", isPresented: Binding(
                get: { model.errorMessage != nil || model.account.errorMessage != nil },
                set: { if !$0 { model.errorMessage = nil; model.account.errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(model.errorMessage ?? model.account.errorMessage ?? "")
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

        #if os(macOS)
        Settings {
            MacSettingsView()
                .environment(model)
                .tint(Theme.accent)
                .preferredColorScheme(.dark)
        }
        #endif
    }
}
