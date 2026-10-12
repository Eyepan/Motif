import MotifKit
import SwiftUI

/// Opens an album or artist page in a navigation stack.
enum LibraryRoute: Hashable {
    case album(String)
    case artist(String)
}

extension View {
    /// Pushes album and artist pages for `LibraryRoute` links. Attach once per navigation stack.
    func libraryDestinations() -> some View {
        navigationDestination(for: LibraryRoute.self) { route in
            switch route {
            #if os(iOS)
            case .album(let id): PhoneAlbumView(albumID: id)
            case .artist(let id): PhoneArtistView(artistID: id)
            #else
            case .album(let id): MacAlbumView(albumID: id)
            case .artist(let id): MacArtistView(artistID: id)
            #endif
            }
        }
    }
}

/// A track's artist credit as a link to the artist page; a menu to pick one when it credits several.
struct ArtistLink: View {
    @Environment(AppModel.self) private var model
    let track: Track
    var color: Color = Theme.secondary

    var body: some View {
        let credited = model.artists(for: track)
        let label = Text(track.artist ?? "Unknown artist").foregroundStyle(color)
        if credited.count > 1 {
            Menu {
                ForEach(credited) { artist in
                    Button(artist.name) { model.open(.artist(artist.id)) }
                }
            } label: {
                label
            }
            .menuStyle(.button)
            .menuIndicator(.hidden)
            .buttonStyle(.plain)
            .accessibilityHint("Choose an artist to open")
        } else if let artist = credited.first {
            Button { model.open(.artist(artist.id)) } label: { label }
                .buttonStyle(.plain)
                .accessibilityHint("Opens the artist")
        } else {
            label
        }
    }
}

/// The track's album name as a link to the album page.
struct AlbumLink: View {
    @Environment(AppModel.self) private var model
    let track: Track
    var color: Color = Theme.secondary

    var body: some View {
        if let album = model.album(for: track) {
            Button { model.open(.album(album.id)) } label: {
                Text(track.album ?? album.title).foregroundStyle(color)
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens the album")
        } else if let name = track.album {
            Text(name).foregroundStyle(color)
        }
    }
}

/// "Artist · Album", each a link. Replaces `track.subtitle` where names should be tappable.
struct CreditLinks: View {
    let track: Track
    var color: Color = Theme.secondary

    var body: some View {
        HStack(spacing: 5) {
            ArtistLink(track: track, color: color)
            if track.album != nil {
                Text("·").foregroundStyle(color)
                AlbumLink(track: track, color: color)
            }
        }
        .lineLimit(1)
    }
}

/// Queue and go-to items for a track's context menu.
struct TrackMenuItems: View {
    @Environment(AppModel.self) private var model
    let tracks: [Track]
    /// Hidden on the page it would open.
    var hideAlbum = false
    var hideArtistID: String?

    var body: some View {
        Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") { model.player.playNext(tracks) }
        Button("Add to Queue", systemImage: "text.line.last.and.arrowtriangle.forward") { model.player.addToQueue(tracks) }
        if tracks.count == 1, let track = tracks.first {
            if !hideAlbum, let album = model.album(for: track) {
                Button("Go to Album", systemImage: "square.stack") { model.open(.album(album.id)) }
            }
            let artists = model.artists(for: track).filter { $0.id != hideArtistID && $0.id != AppModel.unknownArtistID }
            ForEach(artists) { artist in
                Button(artists.count == 1 ? "Go to Artist" : "Go to \(artist.name)", systemImage: "music.mic") {
                    model.open(.artist(artist.id))
                }
            }
        }
    }
}

extension AlbumGroup {
    /// "12 songs · 48 min"
    var summary: String {
        let minutes = Int((tracks.reduce(0) { $0 + $1.duration } / 60).rounded())
        return "\(tracks.count) \(tracks.count == 1 ? "song" : "songs") · \(minutes) min"
    }

    /// "FLAC 24/96" when every track shares one quality, else "Mixed formats".
    var qualitySummary: String {
        let labels = Set(tracks.map(\.shortQualityLabel))
        return labels.count == 1 ? labels.first! : "Mixed formats"
    }
}

extension ArtistGroup {
    var monogram: String { String(name.first ?? "♪").uppercased() }

    /// "3 albums · 27 songs"
    func summary(albums: Int) -> String {
        let songs = "\(tracks.count) \(tracks.count == 1 ? "song" : "songs")"
        return albums == 0 ? songs : "\(albums) \(albums == 1 ? "album" : "albums") · \(songs)"
    }
}

extension AppModel {
    /// The first of the artist's songs that has art.
    func artwork(for artist: ArtistGroup) -> UUID? {
        artist.tracks.first { store.artwork.hasArtwork($0.id) }?.id
    }
}
