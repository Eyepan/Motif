#if os(iOS)
import MotifKit
import SwiftUI

/// An album: cover, credits and its songs in album order. Tap a song to play the album from it;
/// long-press or swipe to queue it instead.
struct PhoneAlbumView: View {
    @Environment(AppModel.self) private var model
    let albumID: String

    var body: some View {
        if let album = model.album(id: albumID) {
            List {
                VStack(spacing: 14) {
                    ArtTile(seed: album.title, letter: album.tracks[0].monogram, size: 240, radius: 12, artwork: album.artwork)
                        .shadow(color: .black.opacity(0.5), radius: 20, y: 14)
                    VStack(spacing: 4) {
                        Text(album.title)
                            .font(.system(size: 22, weight: .bold))
                            .foregroundStyle(Theme.text)
                            .multilineTextAlignment(.center)
                        if let artist = model.artist(for: album) {
                            Button { model.open(.artist(artist.id)) } label: {
                                Text(artist.name).font(.system(size: 17)).foregroundStyle(Theme.accent)
                            }
                            .buttonStyle(.plain)
                            .accessibilityHint("Opens the artist")
                        } else {
                            Text(album.artist ?? "Unknown artist").font(.system(size: 17)).foregroundStyle(Theme.secondary)
                        }
                        Text("\(album.summary) · \(album.qualitySummary)")
                            .font(Theme.mono(12))
                            .foregroundStyle(Theme.secondary)
                    }
                    PlayShuffleButtons(tracks: album.tracks, context: .album)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)

                ForEach(Array(album.tracks.enumerated()), id: \.element.id) { index, track in
                    Button { model.player.play(album.tracks, startAt: index, context: .album) } label: {
                        AlbumTrackRow(number: track.trackNumber ?? index + 1, track: track,
                                      isCurrent: model.player.current?.id == track.id,
                                      showsArtist: album.artist == nil || model.artist(for: album) == nil)
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Color.clear)
                    .swipeActions(edge: .leading) {
                        Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") { model.player.playNext([track]) }
                            .tint(Theme.accent)
                    }
                    .swipeActions(edge: .trailing) {
                        Button("Add to Queue", systemImage: "text.line.last.and.arrowtriangle.forward") { model.player.addToQueue([track]) }
                            .tint(Theme.raised)
                    }
                    .contextMenu {
                        TrackMenuItems(tracks: [track], hideAlbum: true)
                        AddToCrateMenu(tracks: [track])
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Theme.ground)
            .navigationTitle(album.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        TrackMenuItems(tracks: album.tracks)
                        AddToCrateMenu(tracks: album.tracks)
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel("Album actions")
                }
            }
        } else {
            ContentUnavailableView("Album not in library", systemImage: "square.stack")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.ground)
        }
    }
}

/// An artist: their albums, then every song that credits them.
struct PhoneArtistView: View {
    @Environment(AppModel.self) private var model
    let artistID: String

    var body: some View {
        if let artist = model.artist(id: artistID) {
            let albums = model.albums(for: artist)
            List {
                VStack(spacing: 12) {
                    ArtTile(seed: artist.name, letter: artist.monogram, size: 160, radius: 80, artwork: model.artwork(for: artist))
                    Text(artist.name)
                        .font(.system(size: 26, weight: .bold))
                        .foregroundStyle(Theme.text)
                        .multilineTextAlignment(.center)
                    Text(artist.summary(albums: albums.count))
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.secondary)
                    PlayShuffleButtons(tracks: artist.tracks, context: .artist)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)

                if !albums.isEmpty {
                    Section {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(alignment: .top, spacing: 12) {
                                ForEach(albums) { album in
                                    NavigationLink(value: LibraryRoute.album(album.id)) {
                                        AlbumTile(title: album.title, artist: album.summary, letter: album.tracks[0].monogram,
                                                  artwork: album.artwork)
                                    }
                                    .buttonStyle(.plain)
                                    .contextMenu {
                                        Button("Play", systemImage: "play.fill") { model.player.play(album.tracks, context: .album) }
                                        TrackMenuItems(tracks: album.tracks)
                                    }
                                }
                            }
                            .padding(.horizontal, 20)
                        }
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                    } header: {
                        SectionHeader(title: "Albums", detail: "\(albums.count)")
                    }
                }

                Section {
                    ForEach(Array(artist.tracks.enumerated()), id: \.element.id) { index, track in
                        Button { model.player.play(artist.tracks, startAt: index, context: .artist) } label: {
                            TrackRow(track: track, isCurrent: model.player.current?.id == track.id)
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(Color.clear)
                        .swipeActions(edge: .leading) {
                            Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") { model.player.playNext([track]) }
                                .tint(Theme.accent)
                        }
                        .swipeActions(edge: .trailing) {
                            Button("Add to Queue", systemImage: "text.line.last.and.arrowtriangle.forward") { model.player.addToQueue([track]) }
                                .tint(Theme.raised)
                        }
                        .contextMenu {
                            TrackMenuItems(tracks: [track], hideArtistID: artist.id)
                            AddToCrateMenu(tracks: [track])
                        }
                    }
                } header: {
                    SectionHeader(title: "Songs", detail: "\(artist.tracks.count)")
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Theme.ground)
            .navigationTitle(artist.name)
            .navigationBarTitleDisplayMode(.inline)
        } else {
            ContentUnavailableView("Artist not in library", systemImage: "music.mic")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.ground)
        }
    }
}

/// Play and Shuffle, side by side under a page header.
struct PlayShuffleButtons: View {
    @Environment(AppModel.self) private var model
    let tracks: [Track]
    let context: PlayContext

    var body: some View {
        HStack(spacing: 12) {
            Button { model.player.play(tracks, context: context) } label: {
                Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .foregroundStyle(Theme.onAccent)
            Button { model.player.play(tracks.shuffled(), context: context) } label: {
                Label("Shuffle", systemImage: "shuffle").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .foregroundStyle(Theme.accent)
        }
        .font(.system(size: 16, weight: .semibold))
        .controlSize(.large)
        .disabled(tracks.isEmpty)
        .padding(.horizontal, 20)
    }
}

/// A song on an album page: track number instead of art, since the cover is above.
struct AlbumTrackRow: View {
    let number: Int
    let track: Track
    var isCurrent = false
    var showsArtist = false

    var body: some View {
        HStack(spacing: 12) {
            Group {
                if isCurrent {
                    Image(systemName: "speaker.wave.2.fill").foregroundStyle(Theme.accent)
                } else {
                    Text("\(number)").foregroundStyle(Theme.secondary)
                }
            }
            .font(Theme.mono(13))
            .frame(width: 24, alignment: .trailing)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(.system(size: 16))
                    .foregroundStyle(isCurrent ? Theme.accent : Theme.text)
                if showsArtist, let artist = track.artist {
                    Text(artist).font(.system(size: 13)).foregroundStyle(Theme.secondary)
                }
            }
            .lineLimit(1)
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(track.bpmText).foregroundStyle(Theme.bpmColor(track.bpm))
                Text(track.musicalKey ?? "—").foregroundStyle(Theme.keyColor(track.musicalKey))
            }
            .font(Theme.mono(12))
            Text(formatTime(track.duration))
                .font(Theme.mono(12))
                .foregroundStyle(Theme.secondary)
                .frame(minWidth: 36, alignment: .trailing)
        }
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
#endif
