#if os(iOS)
import MotifKit
import SwiftUI

struct PhoneSearchView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""

    var body: some View {
        let results = query.isEmpty ? [] : model.tracks(matching: query)
        List {
            if query.isEmpty {
                Section("Try") {
                    ForEach(["bpm:120-126", "key:8A", "bpm:174"], id: \.self) { example in
                        Button(example) { query = example }.font(Theme.mono(15))
                    }
                }
                .listRowBackground(Color.clear)
            } else {
                ForEach(Array(results.enumerated()), id: \.element.id) { index, track in
                    Button { model.player.play(results, startAt: index) } label: {
                        TrackRow(track: track, isCurrent: model.player.current?.id == track.id)
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Color.clear)
                }
            }
            Section {
                NavigationLink("Search open music catalogs") { DiscoverView() }
            }
            .listRowBackground(Color.clear)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Theme.ground)
        .overlay {
            if !query.isEmpty, results.isEmpty { ContentUnavailableView.search(text: query) }
        }
        .navigationTitle("Search")
        .searchable(text: $query, prompt: "Songs, artists, BPM, key")
    }
}
#endif
