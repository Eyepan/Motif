#if os(macOS)
import MotifKit
import SwiftUI

enum SidebarItem: Hashable {
    case songs, albums, artists, recent, catalogs, djMix
    case crate(String)

    var title: String {
        switch self {
        case .songs: "Songs"
        case .albums: "Albums"
        case .artists: "Artists"
        case .recent: "Recently Added"
        case .catalogs: "Open Catalogs"
        case .djMix: "DJ Mix"
        case .crate: "Crate"
        }
    }
}

struct MacRootView: View {
    @Environment(AppModel.self) private var model
    @State private var selection: SidebarItem? = .songs

    var body: some View {
        @Bindable var model = model
        @Bindable var account = model.account
        NavigationSplitView {
            List(selection: $selection) {
                Section("Library") {
                    Label("Songs", systemImage: "music.note").tag(SidebarItem.songs)
                    Label("Albums", systemImage: "square.stack").tag(SidebarItem.albums)
                    Label("Artists", systemImage: "music.mic").tag(SidebarItem.artists)
                    Label("Recently Added", systemImage: "clock").tag(SidebarItem.recent)
                }
                Section("Mix") {
                    Label("DJ Mix", systemImage: "dial.medium").tag(SidebarItem.djMix)
                }
                Section("Crates") {
                    ForEach(model.crates) { crate in
                        Label(crate.name, systemImage: "square.stack")
                            .tag(SidebarItem.crate(crate.id))
                            .contextMenu {
                                Button("Play") { model.player.play(model.tracks(in: crate), context: .crate, contextRef: crate.id) }
                                Button("Rename…") { model.crateNaming = .rename(crate) }
                                Divider()
                                Button("Delete Crate", role: .destructive) {
                                    if selection == .crate(crate.id) { selection = .songs }
                                    Task { await model.deleteCrate(crate) }
                                }
                            }
                    }
                    Button { model.crateNaming = .create([]) } label: {
                        Label("New Crate", systemImage: "plus")
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Theme.secondary)
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
            // A stack per sidebar item, so switching items starts at the top level.
            NavigationStack {
                switch selection ?? .songs {
                case .songs: MacLibraryTable(title: "Songs")
                case .recent: MacLibraryTable(title: "Recently Added", newestFirst: true)
                case .albums: MacAlbumGrid()
                case .artists: MacArtistList()
                case .catalogs: DiscoverView()
                case .djMix: DJMixView()
                case .crate(let id):
                    if let crate = model.crates.first(where: { $0.id == id }) {
                        MacLibraryTable(title: crate.name, tracks: model.tracks(in: crate), crate: crate)
                    } else {
                        ContentUnavailableView("Crate deleted", systemImage: "square.stack")
                    }
                }
            }
            .id(selection)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            MacPlayerBar()
        }
        .background(Theme.macGround)
        .crateNamePrompt()
        .sheet(isPresented: $account.showWelcome) { MacWelcomeView() }
    }
}
#endif
