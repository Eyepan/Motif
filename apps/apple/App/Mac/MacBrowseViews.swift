#if os(macOS)
import MotifKit
import SwiftUI

/// Album covers in a grid; opening one shows the album page.
struct MacAlbumGrid: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 190), spacing: 20, alignment: .top)],
                      alignment: .leading, spacing: 24) {
                ForEach(model.albums) { album in
                    NavigationLink(value: LibraryRoute.album(album.id)) {
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
                        Button("Play") { model.player.play(album.tracks, context: .album) }
                        TrackMenuItems(tracks: album.tracks)
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
    }
}

/// Every credited artist, featured ones included; opening one shows the artist page.
struct MacArtistList: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        List(model.artists) { artist in
            NavigationLink(value: LibraryRoute.artist(artist.id)) {
                HStack(spacing: 10) {
                    ArtTile(seed: artist.name, letter: artist.monogram, size: 32, radius: 16, artwork: model.artwork(for: artist))
                    Text(artist.name).foregroundStyle(Theme.text)
                    Spacer()
                    Text("\(artist.tracks.count)").monospacedDigit().foregroundStyle(Theme.secondary)
                }
            }
            .contextMenu {
                Button("Play") { model.player.play(artist.tracks, context: .artist) }
                TrackMenuItems(tracks: artist.tracks)
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
    }
}
#endif
