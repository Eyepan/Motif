#if os(macOS)
import MotifKit
import SwiftUI

enum SidebarItem: Hashable {
    case songs, albums, artists, recent, catalogs

    var title: String {
        switch self {
        case .songs: "Songs"
        case .albums: "Albums"
        case .artists: "Artists"
        case .recent: "Recently Added"
        case .catalogs: "Open Catalogs"
        }
    }
}

struct MacRootView: View {
    @Environment(AppModel.self) private var model
    @State private var selection: SidebarItem? = .songs

    var body: some View {
        NavigationSplitView {
            List(selection: $selection) {
                Section("Library") {
                    Label("Songs", systemImage: "music.note").tag(SidebarItem.songs)
                    Label("Albums", systemImage: "square.stack").tag(SidebarItem.albums)
                    Label("Artists", systemImage: "music.mic").tag(SidebarItem.artists)
                    Label("Recently Added", systemImage: "clock").tag(SidebarItem.recent)
                }
                Section("Crates") {
                    Text("No crates yet").foregroundStyle(Theme.secondary)
                }
                Section("Devices") {
                    Label("This Mac", systemImage: "laptopcomputer").foregroundStyle(Theme.secondary)
                }
                Section("Download") {
                    Label("Open Catalogs", systemImage: "building.columns").tag(SidebarItem.catalogs)
                }
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 220)
        } detail: {
            switch selection ?? .songs {
            case .catalogs:
                DiscoverView()
            case let item:
                MacLibraryTable(item: item)
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            MacPlayerBar()
        }
        .background(Theme.macGround)
    }
}
#endif
