#if os(iOS)
import MotifKit
import SwiftUI

struct PhoneLibraryView: View {
    enum Segment: String, CaseIterable { case songs = "Songs", albums = "Albums", artists = "Artists", crates = "Crates" }

    @Environment(AppModel.self) private var model
    @State private var segment = Segment.songs
    @State private var query = ""

    var body: some View {
        let songs = model.tracks(matching: query)
        List {
            Picker("View", selection: $segment) {
                ForEach(Segment.allCases, id: \.self) { Text($0.rawValue) }
            }
            .pickerStyle(.segmented)
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)

            switch segment {
            case .songs:
                if query.isEmpty, !model.albums.isEmpty {
                    Section {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(alignment: .top, spacing: 12) {
                                ForEach(model.albums.prefix(10)) { album in
                                    Button { model.player.play(album.tracks, context: .album) } label: {
                                        AlbumTile(title: album.title, artist: album.artist, letter: album.tracks[0].monogram, artwork: album.artwork)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal, 20)
                        }
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                    } header: {
                        SectionHeader(title: "Recently added", detail: "All on device")
                    }
                }
                Section {
                    ForEach(Array(songs.enumerated()), id: \.element.id) { index, track in
                        Button { model.player.play(songs, startAt: index) } label: {
                            TrackRow(track: track, isCurrent: model.player.current?.id == track.id)
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(Color.clear)
                        .swipeActions {
                            Button("Delete", role: .destructive) { Task { await model.delete(track) } }
                        }
                        .contextMenu {
                            AddToCrateMenu(tracks: [track])
                            Button("Delete from Library", role: .destructive) { Task { await model.delete(track) } }
                        }
                    }
                } header: {
                    SectionHeader(title: "Songs", detail: "\(songs.count) · \(songs.allSatisfy(\.isLossless) ? "lossless" : "on device")")
                }
            case .albums:
                ForEach(model.albums) { album in
                    Button { model.player.play(album.tracks, context: .album) } label: {
                        HStack(spacing: 12) {
                            ArtTile(seed: album.title, letter: album.tracks[0].monogram, size: 56, radius: 8, artwork: album.artwork)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(album.title).foregroundStyle(Theme.text)
                                Text("\(album.artist ?? "Unknown artist") · \(album.tracks.count) songs")
                                    .font(.footnote).foregroundStyle(Theme.secondary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Color.clear)
                }
            case .artists:
                ForEach(model.artists) { artist in
                    Button { model.player.play(artist.tracks, context: .artist) } label: {
                        LabeledContent(artist.name, value: "\(artist.tracks.count)")
                    }
                    .listRowBackground(Color.clear)
                }
            case .crates:
                if model.crates.isEmpty {
                    ContentUnavailableView {
                        Label("No crates yet", systemImage: "square.stack")
                    } description: {
                        Text("Long-press a song and choose Add to Crate.")
                    } actions: {
                        Button("New Crate") { model.crateNaming = .create([]) }
                    }
                    .listRowBackground(Color.clear)
                } else {
                    CrateRows()
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Theme.ground)
        .overlay {
            if model.tracks.isEmpty {
                ContentUnavailableView {
                    Label("No music yet", systemImage: "music.note")
                } description: {
                    Text("Add FLAC or WAV files, or download openly licensed music.")
                } actions: {
                    Button("Add Music") { model.showImport = true }
                        .buttonStyle(.borderedProminent)
                        .foregroundStyle(Theme.onAccent)
                }
            }
        }
        .navigationTitle("Library")
        .crateDestination()
        .searchable(text: $query, prompt: "Songs, artists, BPM, key")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { model.showSettings = true } label: { Image(systemName: "person.crop.circle") }
                    .accessibilityLabel("Settings and account")
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { model.phoneTab = .mix } label: { Image(systemName: "dial.medium") }
                    .accessibilityLabel("Open DJ mix")
                Button { model.showImport = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Add music")
            }
        }
    }
}

struct SectionHeader: View {
    let title: String
    var detail: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).font(.system(size: 20, weight: .bold)).foregroundStyle(Theme.text)
            Spacer()
            if let detail { Text(detail).font(.system(size: 13)).foregroundStyle(Theme.secondary) }
        }
        .textCase(nil)
    }
}

struct AlbumTile: View {
    let title: String
    let artist: String?
    let letter: String
    var artwork: UUID?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ArtTile(seed: title, letter: letter, size: 132, radius: 10, artwork: artwork)
            Text(title).font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.text)
            if let artist { Text(artist).font(.system(size: 12)).foregroundStyle(Theme.secondary) }
        }
        .lineLimit(1)
        .frame(width: 132, alignment: .leading)
    }
}

struct TrackRow: View {
    let track: Track
    var isCurrent = false

    var body: some View {
        HStack(spacing: 12) {
            ArtTile(seed: track.artSeed, letter: track.monogram, artwork: track.id)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(.system(size: 16))
                    .foregroundStyle(isCurrent ? Theme.accent : Theme.text)
                HStack(spacing: 6) {
                    FormatBadge(text: track.format.uppercased())
                    Text(track.artist ?? "Unknown artist")
                }
                .font(.system(size: 13))
                .foregroundStyle(Theme.secondary)
            }
            .lineLimit(1)
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(track.bpmText).foregroundStyle(Theme.bpmColor(track.bpm))
                Text(track.musicalKey ?? "—").foregroundStyle(Theme.keyColor(track.musicalKey))
            }
            .font(Theme.mono(12))
        }
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
#endif
