import MotifKit
import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var importing = false

    private var visible: [Track] {
        guard !query.isEmpty else { return model.tracks }
        return model.tracks.filter {
            [$0.title, $0.artist, $0.album].compactMap { $0 }.contains { $0.localizedCaseInsensitiveContains(query) }
        }
    }

    var body: some View {
        List {
            ForEach(Array(visible.enumerated()), id: \.element.id) { index, track in
                Button {
                    model.player.play(visible, startAt: index)
                } label: {
                    TrackRow(track: track, isCurrent: model.player.current?.id == track.id)
                }
                .buttonStyle(.plain)
                .contextMenu {
                    Button("Delete", role: .destructive) { Task { await model.delete(track) } }
                }
            }
            .onDelete { offsets in
                let doomed = offsets.map { visible[$0] }
                Task { for t in doomed { await model.delete(t) } }
            }
        }
        .overlay {
            if model.tracks.isEmpty {
                ContentUnavailableView {
                    Label("No music yet", systemImage: "music.note")
                } description: {
                    Text("Import FLAC or WAV files, or download open-licensed music from Discover.")
                } actions: {
                    Button("Import Files") { importing = true }
                }
            }
        }
        .searchable(text: $query)
        .navigationTitle("Library")
        .toolbar {
            Button { importing = true } label: { Label("Import", systemImage: "plus") }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.audio, .folder], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls): Task { await model.importLocal(urls) }
            case .failure(let error): model.errorMessage = error.localizedDescription
            }
        }
    }
}

struct TrackRow: View {
    let track: Track
    var isCurrent = false

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .fontWeight(isCurrent ? .semibold : .regular)
                    .foregroundStyle(isCurrent ? Color.accentColor : .primary)
                Text([track.artist, track.album].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if track.isLossless {
                Text(track.qualityLabel)
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
            }
        }
        .lineLimit(1)
        .contentShape(Rectangle())
    }
}
