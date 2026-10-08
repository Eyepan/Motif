#if os(iOS)
import MotifKit
import SwiftUI

/// The Crates tab: every crate, newest last.
struct PhoneCratesView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        List {
            CrateRows()
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Theme.ground)
        .overlay {
            if model.crates.isEmpty {
                ContentUnavailableView {
                    Label("No crates yet", systemImage: "square.stack")
                } description: {
                    Text("Group tracks into sets for a gig or a mood. Long-press a song to add it to a crate.")
                } actions: {
                    Button("New Crate") { model.crateNaming = .create([]) }
                        .buttonStyle(.borderedProminent)
                        .foregroundStyle(Theme.onAccent)
                }
            }
        }
        .navigationTitle("Crates")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { model.crateNaming = .create([]) } label: { Image(systemName: "plus") }
                    .accessibilityLabel("New crate")
            }
        }
        .crateDestination()
    }
}

/// One row per crate, linking to its songs. Used by the Crates tab and the Library's Crates segment.
struct CrateRows: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ForEach(model.crates) { crate in
            let tracks = model.tracks(in: crate)
            NavigationLink(value: CrateRoute(id: crate.id)) {
                HStack(spacing: 12) {
                    ArtTile(seed: crate.name, letter: String(crate.name.first ?? "♪").uppercased(), size: 56, radius: 8,
                            artwork: tracks.first { model.store.artwork.hasArtwork($0.id) }?.id)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(crate.name).foregroundStyle(Theme.text)
                        Text(crateSummary(tracks, missing: model.missingCount(in: crate)))
                            .font(.footnote).foregroundStyle(Theme.secondary)
                    }
                    .lineLimit(1)
                }
            }
            .listRowBackground(Color.clear)
            .swipeActions {
                Button("Delete", role: .destructive) { Task { await model.deleteCrate(crate) } }
                Button("Rename") { model.crateNaming = .rename(crate) }
            }
            .contextMenu {
                Button("Play") { model.player.play(tracks) }
                Button("Rename…") { model.crateNaming = .rename(crate) }
                Button("Delete Crate", role: .destructive) { Task { await model.deleteCrate(crate) } }
            }
        }
    }
}

/// A crate's songs, sorted for building a set.
struct PhoneCrateDetailView: View {
    enum Order: String, CaseIterable { case added = "Added", bpm = "BPM", key = "Key" }

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let crateID: String
    @State private var order = Order.added

    var body: some View {
        if let crate = model.crates.first(where: { $0.id == crateID }) {
            let songs = sorted(model.tracks(in: crate))
            let missing = model.missingCount(in: crate)
            List {
                Picker("Order", selection: $order) {
                    ForEach(Order.allCases, id: \.self) { Text($0.rawValue) }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                Section {
                    ForEach(Array(songs.enumerated()), id: \.element.id) { index, track in
                        Button { model.player.play(songs, startAt: index) } label: {
                            TrackRow(track: track, isCurrent: model.player.current?.id == track.id)
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(Color.clear)
                        .swipeActions {
                            Button("Remove", role: .destructive) { Task { await model.remove([track], from: crate) } }
                        }
                    }
                } header: {
                    SectionHeader(title: "Songs", detail: crateSummary(songs, missing: missing))
                } footer: {
                    if missing > 0 {
                        Text("\(missing) \(missing == 1 ? "song was" : "songs were") added on another device and \(missing == 1 ? "isn't" : "aren't") imported here.")
                            .font(.footnote).foregroundStyle(Theme.secondary)
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Theme.ground)
            .overlay {
                if crate.trackKeys.isEmpty {
                    ContentUnavailableView("Empty crate", systemImage: "square.stack",
                                           description: Text("Long-press a song in your library and choose Add to Crate."))
                }
            }
            .navigationTitle(crate.name)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Play", systemImage: "play.fill") { model.player.play(songs) }
                            .disabled(songs.isEmpty)
                        Button("Rename…", systemImage: "pencil") { model.crateNaming = .rename(crate) }
                        Button("Delete Crate", systemImage: "trash", role: .destructive) {
                            Task { await model.deleteCrate(crate) }
                            dismiss()
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel("Crate actions")
                }
            }
        } else {
            ContentUnavailableView("Crate deleted", systemImage: "square.stack")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.ground)
        }
    }

    private func sorted(_ tracks: [Track]) -> [Track] {
        switch order {
        case .added: tracks
        case .bpm: tracks.sorted { ($0.bpm ?? .infinity) < ($1.bpm ?? .infinity) }
        case .key: tracks.sorted { $0.sortKey < $1.sortKey }
        }
    }
}

extension View {
    /// Pushes a crate's songs for a `CrateRoute` link.
    func crateDestination() -> some View {
        navigationDestination(for: CrateRoute.self) { PhoneCrateDetailView(crateID: $0.id) }
    }
}
#endif
