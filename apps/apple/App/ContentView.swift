import MotifKit
import SwiftUI

struct ContentView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        TabView {
            NavigationStack { LibraryView() }
                .tabItem { Label("Library", systemImage: "music.note.list") }
            NavigationStack { DiscoverView() }
                .tabItem { Label("Discover", systemImage: "arrow.down.circle") }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if model.player.current != nil { NowPlayingBar() }
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
}
