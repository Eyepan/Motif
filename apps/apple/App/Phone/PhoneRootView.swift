#if os(iOS)
import MotifKit
import SwiftUI

struct PhoneRootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        TabView(selection: $model.phoneTab) {
            NavigationStack { PhoneLibraryView() }
                .withMiniPlayer()
                .tabItem { Label("Library", systemImage: "music.note.list") }
                .tag(AppModel.PhoneTab.library)
            NavigationStack { PhoneCratesView() }
            .withMiniPlayer()
            .tabItem { Label("Crates", systemImage: "square.stack") }
            .tag(AppModel.PhoneTab.crates)
            NavigationStack {
                ComingSoonView(title: "Mix", systemImage: "dial.medium",
                               detail: "The two-deck DJ mix is on its way. Until then, turn on Mix into next in Now Playing for automatic blends.")
            }
            .withMiniPlayer()
            .tabItem { Label("Mix", systemImage: "dial.medium") }
            .tag(AppModel.PhoneTab.mix)
            NavigationStack { PhoneSearchView() }
                .withMiniPlayer()
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
                .tag(AppModel.PhoneTab.search)
        }
        .sheet(isPresented: $model.showImport) { ImportSheet() }
        .fullScreenCover(isPresented: $model.showNowPlaying) { NowPlayingView() }
        .crateNamePrompt()
    }
}

private struct ComingSoonView: View {
    let title: String
    let systemImage: String
    let detail: String

    var body: some View {
        ContentUnavailableView(title, systemImage: systemImage, description: Text(detail))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.ground)
            .navigationTitle(title)
    }
}

extension View {
    /// Floats the mini player above the tab bar while something is loaded.
    func withMiniPlayer() -> some View {
        modifier(MiniPlayerInset())
    }
}

private struct MiniPlayerInset: ViewModifier {
    @Environment(AppModel.self) private var model

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom, spacing: 0) {
            if model.player.current != nil {
                MiniPlayer()
                    .padding(.horizontal, 8)
                    .padding(.bottom, 6)
            }
        }
    }
}

struct MiniPlayer: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let player = model.player
        HStack(spacing: 10) {
            Button { model.showNowPlaying = true } label: {
                HStack(spacing: 10) {
                    if let track = player.current {
                        ArtTile(seed: track.artSeed, letter: track.monogram, size: 44, radius: 8, artwork: track.id)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(track.title).font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
                            Text([track.artist, track.shortQualityLabel].compactMap { $0 }.joined(separator: " · "))
                                .font(.system(size: 12)).foregroundStyle(Theme.secondary)
                        }
                        .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Now playing")

            Button { player.togglePlayPause() } label: {
                Image(systemName: player.state == .playing ? "pause.fill" : "play.fill")
                    .font(.system(size: 22)).frame(width: 44, height: 44)
            }
            .accessibilityLabel(player.state == .playing ? "Pause" : "Play")
            Button { player.next() } label: {
                Image(systemName: "forward.fill").font(.system(size: 20)).frame(width: 44, height: 44)
            }
            .accessibilityLabel("Next")
        }
        .buttonStyle(.plain)
        .foregroundStyle(Theme.text)
        .padding(8)
        .background(Theme.raised, in: RoundedRectangle(cornerRadius: 14))
        .shadow(color: .black.opacity(0.5), radius: 12, y: 8)
    }
}
#endif
