import MotifKit
import SwiftUI

/// Search open catalogs and download into the library. "All" searches every catalog
/// at once; one catalog failing doesn't hide the others' results. A search that finds
/// nothing says so, names any catalog that couldn't be reached, and points to stores.
struct DiscoverView: View {
    @Environment(AppModel.self) private var model
    /// 0 is every catalog; i + 1 is `model.sources[i]`.
    @State private var scope = 0
    @State private var query = ""
    @State private var hits: [Hit] = []
    @State private var failures: [String] = []
    @State private var searchedFor: String?
    @State private var searching = false
    @State private var downloading: Set<String> = []
    @State private var done: Set<String> = []

    struct Hit: Identifiable {
        let source: any MusicSource
        let result: SourceResult
        var id: String { "\(source.id.rawValue):\(result.id)" }
    }

    private var targets: [any MusicSource] {
        scope == 0 ? model.sources : [model.sources[scope - 1]]
    }

    private var catalogNames: String {
        ListFormatter.localizedString(byJoining: targets.map(\.displayName))
    }

    var body: some View {
        List {
            Picker("Catalog", selection: $scope) {
                Text("All").tag(0)
                ForEach(model.sources.indices, id: \.self) { Text(model.sources[$0].displayName).tag($0 + 1) }
            }
            .pickerStyle(.segmented)
            if !hits.isEmpty, !failures.isEmpty {
                failureNote
            }
            ForEach(hits) { hit in
                row(hit)
            }
        }
        .overlay {
            if searching {
                ProgressView()
            } else if let searchedFor, hits.isEmpty {
                noResults(searchedFor)
            } else if hits.isEmpty {
                ContentUnavailableView("Find music you're free to keep",
                                       systemImage: "magnifyingglass",
                                       description: Text("Search \(catalogNames). Downloads go straight into your library with tags, art, BPM and key."))
            }
        }
        .searchable(text: $query, prompt: "Artist, album or genre")
        .onSubmit(of: .search) { Task { await search() } }
        .onChange(of: scope) {
            hits = []
            failures = []
            searchedFor = nil
            if !query.trimmingCharacters(in: .whitespaces).isEmpty { Task { await search() } }
        }
        .navigationTitle("Discover")
    }

    private func row(_ hit: Hit) -> some View {
        let result = hit.result
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(result.title).lineLimit(1)
                HStack(spacing: 6) {
                    if let format = result.format {
                        Text(format.uppercased()).font(Theme.mono(10, weight: .bold)).foregroundStyle(Theme.badge)
                    }
                    if scope == 0 {
                        Text(hit.source.displayName.uppercased()).font(Theme.mono(10, weight: .bold)).foregroundStyle(Theme.secondary)
                    }
                    if let artist = result.artist {
                        Text(artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                if let license = result.licenseURL {
                    Link("License", destination: license).font(.caption2)
                }
            }
            Spacer()
            downloadButton(for: hit)
        }
    }

    private var failureNote: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(failures, id: \.self) { Text($0) }
        }
        .font(.caption)
        .foregroundStyle(.red)
    }

    private func noResults(_ query: String) -> some View {
        ContentUnavailableView {
            Label("No results for “\(query)”", systemImage: "magnifyingglass")
        } description: {
            VStack(spacing: 8) {
                if !failures.isEmpty {
                    failureNote
                }
                Text("Nothing on \(catalogNames) matches. These catalogs only carry music the artists or archives chose to share freely, so most label releases aren't here. Check the spelling, or look for it in a store, then add the files with Add Music.")
            }
        } actions: {
            ForEach(Self.storeSearches(query)) { store in
                Link(store.name, destination: store.url)
            }
        }
    }

    struct Store: Identifiable {
        let name: String
        let url: URL
        var id: String { name }
    }

    /// Stores that sell lossless downloads, searched for `query`.
    static func storeSearches(_ query: String) -> [Store] {
        let q = query.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? query
        return [
            Store(name: "Search Qobuz (FLAC)", url: URL(string: "https://www.qobuz.com/search?q=\(q)")!),
            Store(name: "Search Bandcamp (FLAC)", url: URL(string: "https://bandcamp.com/search?q=\(q)")!),
        ]
    }

    @ViewBuilder
    private func downloadButton(for hit: Hit) -> some View {
        if done.contains(hit.id) {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.done)
        } else if downloading.contains(hit.id) {
            ProgressView().controlSize(.small)
        } else {
            Button { Task { await download(hit) } } label: {
                Image(systemName: "arrow.down.circle")
            }
            .buttonStyle(.borderless)
        }
    }

    private func search() async {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        searching = true
        defer { searching = false }
        let outcome = await Self.searchAll(targets, q)
        hits = outcome.hits
        failures = outcome.failures
        searchedFor = q
    }

    /// Searches side by side, interleaving so each catalog's best matches come first.
    private static func searchAll(_ sources: [any MusicSource], _ query: String) async -> (hits: [Hit], failures: [String]) {
        let answers = await withTaskGroup(of: (Int, Result<[SourceResult], Error>).self) { group in
            for (i, source) in sources.enumerated() {
                group.addTask {
                    do { return (i, .success(try await source.search(query))) } catch { return (i, .failure(error)) }
                }
            }
            var all: [(Int, Result<[SourceResult], Error>)] = []
            for await answer in group { all.append(answer) }
            return all.sorted { $0.0 < $1.0 }
        }
        var lists: [[Hit]] = []
        var failures: [String] = []
        for (i, answer) in answers {
            switch answer {
            case .success(let results): lists.append(results.map { Hit(source: sources[i], result: $0) })
            case .failure(let error): failures.append("\(sources[i].displayName) couldn't be searched: \(error.localizedDescription)")
            }
        }
        let longest = lists.map(\.count).max() ?? 0
        let hits = (0..<longest).flatMap { i in lists.compactMap { i < $0.count ? $0[i] : nil } }
        return (hits, failures)
    }

    private func download(_ hit: Hit) async {
        let (source, result) = (hit.source, hit.result)
        downloading.insert(hit.id)
        defer { downloading.remove(hit.id) }
        do {
            let files = try await source.downloads(for: result)
            if files.isEmpty { throw DiscoverError.nothingToDownload }
            let cover = await model.importer.cover(for: result)
            for file in files {
                try await model.importer.importDownload(file, from: source.id, result: result,
                                                        analyze: model.analyzeOnImport, cover: cover)
                await model.refresh()
            }
            done.insert(hit.id)
        } catch {
            model.errorMessage = error.localizedDescription
        }
    }

    enum DiscoverError: LocalizedError {
        case nothingToDownload
        var errorDescription: String? { "The artist only allows streaming this one." }
    }
}
