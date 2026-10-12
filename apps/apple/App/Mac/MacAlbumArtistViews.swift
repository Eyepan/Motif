#if os(macOS)
import MotifKit
import SwiftUI

/// An album: cover and credits above its songs in album order. Double-click a song to play
/// the album from it; right-click to queue it instead.
struct MacAlbumView: View {
    @Environment(AppModel.self) private var model
    let albumID: String

    var body: some View {
        if let album = model.album(id: albumID) {
            let artist = model.artist(for: album)
            MacLibraryTable(title: album.title, tracks: album.tracks, context: .album, albumPage: true,
                            header: AnyView(header(album, artist: artist)))
        } else {
            ContentUnavailableView("Album not in library", systemImage: "square.stack")
        }
    }

    private func header(_ album: AlbumGroup, artist: ArtistGroup?) -> some View {
        HStack(alignment: .bottom, spacing: 20) {
            ArtTile(seed: album.title, letter: album.tracks[0].monogram, size: 168, radius: 8, artwork: album.artwork)
                .shadow(color: .black.opacity(0.4), radius: 12, y: 8)
            VStack(alignment: .leading, spacing: 6) {
                Text("Album").font(.system(size: 11, weight: .semibold)).textCase(.uppercase).foregroundStyle(Theme.secondary)
                Text(album.title).font(.system(size: 26, weight: .bold)).foregroundStyle(Theme.text).lineLimit(2)
                if let artist {
                    Button { model.open(.artist(artist.id)) } label: {
                        Text(artist.name).font(.system(size: 15, weight: .medium)).foregroundStyle(Theme.accent)
                    }
                    .buttonStyle(.plain)
                    .help("Go to \(artist.name)")
                } else {
                    Text(album.artist ?? "Unknown artist").font(.system(size: 15)).foregroundStyle(Theme.secondary)
                }
                Text("\(album.summary) · \(album.qualitySummary)").font(Theme.mono(11)).foregroundStyle(Theme.secondary)
                MacPageButtons(tracks: album.tracks, context: .album)
                    .padding(.top, 6)
            }
            Spacer(minLength: 0)
        }
        .padding(24)
    }
}

/// An artist: their albums in a row, then every song that credits them in the table.
struct MacArtistView: View {
    @Environment(AppModel.self) private var model
    let artistID: String

    var body: some View {
        if let artist = model.artist(id: artistID) {
            MacLibraryTable(title: artist.name, tracks: artist.tracks, context: .artist, artistID: artist.id,
                            header: AnyView(header(artist, albums: model.albums(for: artist))))
        } else {
            ContentUnavailableView("Artist not in library", systemImage: "music.mic")
        }
    }

    private func header(_ artist: ArtistGroup, albums: [AlbumGroup]) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .center, spacing: 20) {
                ArtTile(seed: artist.name, letter: artist.monogram, size: 120, radius: 60, artwork: model.artwork(for: artist))
                VStack(alignment: .leading, spacing: 6) {
                    Text("Artist").font(.system(size: 11, weight: .semibold)).textCase(.uppercase).foregroundStyle(Theme.secondary)
                    Text(artist.name).font(.system(size: 26, weight: .bold)).foregroundStyle(Theme.text).lineLimit(2)
                    Text(artist.summary(albums: albums.count)).font(.system(size: 13)).foregroundStyle(Theme.secondary)
                    MacPageButtons(tracks: artist.tracks, context: .artist)
                        .padding(.top, 6)
                }
                Spacer(minLength: 0)
            }
            if !albums.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 16) {
                        ForEach(albums) { album in
                            NavigationLink(value: LibraryRoute.album(album.id)) {
                                VStack(alignment: .leading, spacing: 4) {
                                    ArtTile(seed: album.title, letter: album.tracks[0].monogram, size: 120, radius: 6,
                                            artwork: album.artwork)
                                    Text(album.title).fontWeight(.semibold).foregroundStyle(Theme.text)
                                    Text(album.summary).font(.system(size: 11)).foregroundStyle(Theme.secondary)
                                }
                                .lineLimit(1)
                                .frame(width: 120, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .contextMenu {
                                Button("Play") { model.player.play(album.tracks, context: .album) }
                                TrackMenuItems(tracks: album.tracks)
                            }
                        }
                    }
                }
            }
        }
        .padding(24)
    }
}

/// Play, Shuffle and a queue menu under a page header.
private struct MacPageButtons: View {
    @Environment(AppModel.self) private var model
    let tracks: [Track]
    let context: PlayContext

    var body: some View {
        HStack(spacing: 8) {
            Button { model.player.play(tracks, context: context) } label: {
                Label("Play", systemImage: "play.fill").padding(.horizontal, 6)
            }
            .buttonStyle(.borderedProminent)
            .foregroundStyle(Theme.onAccent)
            Button { model.player.play(tracks.shuffled(), context: context) } label: {
                Label("Shuffle", systemImage: "shuffle")
            }
            .buttonStyle(.bordered)
            Menu {
                Button("Play Next") { model.player.playNext(tracks) }
                Button("Add to Queue") { model.player.addToQueue(tracks) }
                AddToCrateMenu(tracks: tracks)
            } label: {
                Image(systemName: "ellipsis")
            }
            .menuIndicator(.hidden)
            .fixedSize()
            .accessibilityLabel("More")
        }
        .controlSize(.large)
        .disabled(tracks.isEmpty)
    }
}
#endif
