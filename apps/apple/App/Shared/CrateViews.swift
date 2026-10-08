import MotifKit
import SwiftUI

/// What the crate name prompt is for.
enum CrateNaming: Identifiable {
    /// A new crate, holding these tracks (maybe none).
    case create([Track])
    case rename(Crate)

    var id: String {
        switch self {
        case .create(let tracks): "new:" + tracks.map(\.id.uuidString).joined(separator: ",")
        case .rename(let crate): "rename:" + crate.id
        }
    }
}

/// Opens a crate in a navigation stack.
struct CrateRoute: Hashable {
    let id: String
}

/// "Add to Crate" for track context menus: every crate, then New Crate.
struct AddToCrateMenu: View {
    @Environment(AppModel.self) private var model
    let tracks: [Track]

    var body: some View {
        Menu {
            ForEach(model.crates) { crate in
                Button(crate.name) { Task { await model.add(tracks, to: crate) } }
            }
            if !model.crates.isEmpty { Divider() }
            Button("New Crate…") { model.crateNaming = .create(tracks) }
        } label: {
            Label("Add to Crate", systemImage: "rectangle.stack.badge.plus")
        }
    }
}

/// The name prompt for creating or renaming a crate. Attach once, at the root.
struct CrateNamePrompt: ViewModifier {
    @Environment(AppModel.self) private var model
    @State private var name = ""

    func body(content: Content) -> some View {
        content
            .alert(title, isPresented: Binding(
                get: { model.crateNaming != nil },
                set: { if !$0 { model.crateNaming = nil } }
            ), presenting: model.crateNaming) { naming in
                TextField("Name", text: $name)
                Button("Cancel", role: .cancel) {}
                Button(isRename ? "Rename" : "Create") { save(naming) }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            } message: { naming in
                if case .create(let tracks) = naming, !tracks.isEmpty {
                    Text(tracks.count == 1 ? "With “\(tracks[0].title)”." : "With \(tracks.count) songs.")
                }
            }
            .onChange(of: model.crateNaming?.id) {
                if case .rename(let crate) = model.crateNaming { name = crate.name } else { name = "" }
            }
    }

    private var isRename: Bool {
        if case .rename = model.crateNaming { true } else { false }
    }

    private var title: String { isRename ? "Rename Crate" : "New Crate" }

    private func save(_ naming: CrateNaming) {
        let name = self.name
        Task {
            switch naming {
            case .create(let tracks): await model.createCrate(named: name, with: tracks)
            case .rename(let crate): await model.renameCrate(crate, to: name)
            }
        }
    }
}

extension View {
    func crateNamePrompt() -> some View { modifier(CrateNamePrompt()) }
}

/// "12 songs · 120–126 BPM", plus how many are on another device.
func crateSummary(_ tracks: [Track], missing: Int) -> String {
    var parts = ["\(tracks.count) \(tracks.count == 1 ? "song" : "songs")"]
    let bpms = tracks.compactMap(\.bpm).map { Int($0.rounded()) }
    if let low = bpms.min(), let high = bpms.max() {
        parts.append(low == high ? "\(low) BPM" : "\(low)–\(high) BPM")
    }
    if missing > 0 { parts.append("\(missing) on another device") }
    return parts.joined(separator: " · ")
}
