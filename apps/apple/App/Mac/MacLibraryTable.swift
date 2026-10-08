#if os(macOS)
import MotifKit
import SwiftUI

/// Sortable library table: # / Title / Artist / Album / Time / BPM / Key / Format.
struct MacLibraryTable: View {
    struct Row: Identifiable {
        let number: Int
        let track: Track
        var id: UUID { track.id }
    }

    let title: String
    /// Tracks to list, or nil for the whole library.
    var tracks: [Track]?
    var newestFirst = false
    /// The crate being shown, which adds Remove from Crate.
    var crate: Crate?
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var sortOrder: [KeyPathComparator<Row>] = []
    @State private var selection: Set<Row.ID> = []
    @State private var importing = false

    private var rows: [Row] {
        var list: [Track]
        if let tracks {
            let filter = LibraryFilter(query)
            list = filter.isEmpty ? tracks : tracks.filter(filter.matches)
        } else {
            list = model.tracks(matching: query)
        }
        if newestFirst { list.sort { $0.addedAt > $1.addedAt } }
        let numbered = list.enumerated().map { Row(number: $0.offset + 1, track: $0.element) }
        return sortOrder.isEmpty ? numbered : numbered.sorted(using: sortOrder)
    }

    var body: some View {
        let rows = self.rows
        Table(rows, selection: $selection, sortOrder: $sortOrder) {
            TableColumn("#", value: \.number) { row in
                Text("\(row.number)").monospacedDigit().foregroundStyle(Theme.secondary)
            }
            .width(min: 28, ideal: 36, max: 48)
            TableColumn("Title", value: \.track.sortTitle) { row in
                Text(row.track.title)
                    .fontWeight(isCurrent(row) ? .semibold : .regular)
                    .foregroundStyle(isCurrent(row) ? Theme.accent : Theme.text)
            }
            .width(min: 160, ideal: 260)
            TableColumn("Artist", value: \.track.sortArtist) { row in
                Text(row.track.artist ?? "").foregroundStyle(Color(hex: 0xB5BAC3))
            }
            TableColumn("Album", value: \.track.sortAlbum) { row in
                Text(row.track.album ?? "").foregroundStyle(Color(hex: 0xB5BAC3))
            }
            TableColumn("Time", value: \.track.durationMs) { row in
                Text(formatTime(row.track.duration)).monospacedDigit().foregroundStyle(Color(hex: 0xB5BAC3))
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .width(min: 44, ideal: 54, max: 70)
            TableColumn("BPM", value: \.track.sortBPM) { row in
                MixValue(text: row.track.bpm.map { "\(Int($0.rounded()))" } ?? "—",
                         color: Theme.bpmColor(row.track.bpm), match: matches(row, MixMatch.tempos))
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .width(min: 40, ideal: 50, max: 64)
            TableColumn("Key", value: \.track.sortKey) { row in
                MixValue(text: row.track.musicalKey ?? "—", color: Theme.keyColor(row.track.musicalKey),
                         match: matches(row, MixMatch.keys))
            }
            .width(min: 36, ideal: 44, max: 60)
            TableColumn("Format", value: \.track.format) { row in
                Text(row.track.shortQualityLabel).font(Theme.mono(11)).foregroundStyle(Theme.badge)
            }
            .width(min: 70, ideal: 90, max: 120)
        }
        .contextMenu(forSelectionType: Row.ID.self) { ids in
            let picked = rows.filter { ids.contains($0.id) }.map(\.track)
            Button("Play") { play(ids, in: rows) }
            AddToCrateMenu(tracks: picked)
            if let crate {
                Button("Remove from Crate") { Task { await model.remove(picked, from: crate) } }
            }
            Divider()
            Button("Delete from Library", role: .destructive) {
                let doomed = rows.filter { ids.contains($0.id) }.map(\.track)
                Task { for t in doomed { await model.delete(t) } }
            }
        } primaryAction: { ids in
            play(ids, in: rows)
        }
        .overlay {
            if model.tracks.isEmpty {
                ContentUnavailableView {
                    Label("No music yet", systemImage: "music.note")
                } description: {
                    Text("Import FLAC or WAV files, or download openly licensed music from Open Catalogs.")
                } actions: {
                    Button("Import…") { importing = true }
                }
            } else if let crate, crate.trackKeys.isEmpty {
                ContentUnavailableView("Empty crate", systemImage: "square.stack",
                                       description: Text("Right-click songs in your library and choose Add to Crate."))
            }
        }
        .searchable(text: $query, prompt: "Search · try bpm:120-126 key:8A")
        .navigationTitle(title)
        .navigationSubtitle(crate.map { crateSummary(tracks ?? [], missing: model.missingCount(in: $0)) }
                            ?? "\(rows.count) songs · all offline")
        .toolbar {
            if model.activeImportCount > 0 {
                ToolbarItem {
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.small)
                        Text("Importing \(model.importJobs.count - model.activeImportCount + 1) of \(model.importJobs.count)")
                            .foregroundStyle(Theme.secondary)
                    }
                }
            }
            ToolbarItem {
                Button("Import…") { importing = true }
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.audio, .folder], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls): Task { await model.importLocal(urls) }
            case .failure(let error): model.errorMessage = error.localizedDescription
            }
        }
    }

    /// Whether the row mixes well into what's playing, by `rule`. The playing track itself isn't marked.
    private func matches(_ row: Row, _ rule: (Track, Track) -> Bool) -> Bool {
        guard let current = model.player.current, current.id != row.track.id else { return false }
        return rule(current, row.track)
    }

    private func isCurrent(_ row: Row) -> Bool {
        model.player.current?.id == row.track.id
    }

    /// Plays the visible list from the first selected row, so the queue follows the table.
    private func play(_ ids: Set<Row.ID>, in rows: [Row]) {
        guard let start = rows.firstIndex(where: { ids.contains($0.id) }) else { return }
        model.player.play(rows.map(\.track), startAt: start)
    }
}
#endif
