import MotifKit
import SwiftUI

/// Search open-licensed catalogs and download lossless files into the library.
struct DiscoverView: View {
    @Environment(AppModel.self) private var model
    @State private var sourceIndex = 0
    @State private var query = ""
    @State private var results: [SourceResult] = []
    @State private var searching = false
    @State private var downloading: Set<String> = []
    @State private var done: Set<String> = []

    private var source: any MusicSource { model.sources[sourceIndex] }

    var body: some View {
        List {
            if model.sources.count > 1 {
                Picker("Source", selection: $sourceIndex) {
                    ForEach(model.sources.indices, id: \.self) { Text(model.sources[$0].displayName).tag($0) }
                }
                .pickerStyle(.segmented)
            }
            ForEach(results) { result in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(result.title).lineLimit(1)
                        if let artist = result.artist {
                            Text(artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        if let license = result.licenseURL {
                            Link("License", destination: license).font(.caption2)
                        }
                    }
                    Spacer()
                    downloadButton(for: result)
                }
            }
        }
        .overlay {
            if searching {
                ProgressView()
            } else if results.isEmpty {
                ContentUnavailableView("Find open-licensed music",
                                       systemImage: "magnifyingglass",
                                       description: Text("Search \(source.displayName) for FLAC and WAV releases you're free to download."))
            }
        }
        .searchable(text: $query, prompt: "Artist, album or genre")
        .onSubmit(of: .search) { Task { await search() } }
        .onChange(of: sourceIndex) { results = [] }
        .navigationTitle("Discover")
    }

    @ViewBuilder
    private func downloadButton(for result: SourceResult) -> some View {
        if done.contains(result.id) {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.done)
        } else if downloading.contains(result.id) {
            ProgressView().controlSize(.small)
        } else {
            Button { Task { await download(result) } } label: {
                Image(systemName: "arrow.down.circle")
            }
            .buttonStyle(.borderless)
        }
    }

    private func search() async {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        searching = true
        defer { searching = false }
        do { results = try await source.search(query) } catch { model.errorMessage = error.localizedDescription }
    }

    private func download(_ result: SourceResult) async {
        let source = source
        downloading.insert(result.id)
        defer { downloading.remove(result.id) }
        do {
            for file in try await source.downloads(for: result) {
                try await model.importer.importDownload(file, from: source.id, result: result, analyze: model.analyzeOnImport)
                await model.refresh()
            }
            done.insert(result.id)
        } catch {
            model.errorMessage = error.localizedDescription
        }
    }
}
