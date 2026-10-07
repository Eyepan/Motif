#if os(macOS)
import MotifKit
import SwiftUI

/// Album covers in a grid; opening one lists its songs in the library table.
struct MacAlbumGrid: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 190), spacing: 20, alignment: .top)],
                      alignment: .leading, spacing: 24) {
                ForEach(model.albums) { album in
                    NavigationLink(value: album.id) {
                        VStack(alignment: .leading, spacing: 4) {
                            ArtTile(seed: album.title, letter: album.tracks[0].monogram, size: 150, radius: 8,
                                    artwork: album.artwork)
                            Text(album.title).fontWeight(.semibold).foregroundStyle(Theme.text)
                            Text("\(album.artist ?? "Unknown artist") · \(album.tracks.count) songs")
                                .font(.system(size: 12)).foregroundStyle(Theme.secondary)
                        }
                        .lineLimit(1)
                        .frame(width: 150, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button("Play") { model.player.play(album.tracks) }
                    }
                }
            }
            .padding(24)
        }
        .overlay {
            if model.albums.isEmpty {
                ContentUnavailableView("No albums yet", systemImage: "square.stack",
                                       description: Text("Albums appear here as you import tagged music."))
            }
        }
        .navigationTitle("Albums")
        .navigationSubtitle("\(model.albums.count) albums")
        .navigationDestination(for: String.self) { id in
            if let album = model.albums.first(where: { $0.id == id }) {
                MacLibraryTable(title: album.title, tracks: album.tracks)
            }
        }
    }
}

/// Every credited artist, featured ones included; opening one lists their songs.
struct MacArtistList: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        List(model.artists) { artist in
            NavigationLink(value: artist.id) {
                HStack(spacing: 10) {
                    ArtTile(seed: artist.name, letter: String(artist.name.first ?? "♪").uppercased(), size: 32, radius: 16,
                            artwork: artist.tracks.first { model.store.artwork.hasArtwork($0.id) }?.id)
                    Text(artist.name).foregroundStyle(Theme.text)
                    Spacer()
                    Text("\(artist.tracks.count)").monospacedDigit().foregroundStyle(Theme.secondary)
                }
            }
            .contextMenu {
                Button("Play") { model.player.play(artist.tracks) }
            }
        }
        .scrollContentBackground(.hidden)
        .overlay {
            if model.artists.isEmpty {
                ContentUnavailableView("No artists yet", systemImage: "music.mic",
                                       description: Text("Artists appear here as you import tagged music."))
            }
        }
        .navigationTitle("Artists")
        .navigationSubtitle("\(model.artists.count) artists")
        .navigationDestination(for: String.self) { id in
            if let artist = model.artists.first(where: { $0.id == id }) {
                MacLibraryTable(title: artist.name, tracks: artist.tracks)
            }
        }
    }
}
#endif
