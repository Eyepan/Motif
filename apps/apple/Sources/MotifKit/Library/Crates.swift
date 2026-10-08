import Foundation

/// A user's set of tracks for a gig or a mood. Crates live only in the history
/// log as `crate_changed` events, so they sync like every other event and each
/// device rebuilds the same crates from the merged log (docs/crates.md).
public struct Crate: Identifiable, Hashable, Sendable {
    public let id: String
    public var name: String
    /// Track keys (`Track.contentHash`) in the order they were added. A key can
    /// name a track that isn't on this device.
    public var trackKeys: [String]

    public init(id: String, name: String, trackKeys: [String]) {
        self.id = id; self.name = name; self.trackKeys = trackKeys
    }
}

/// The payload of one `crate_changed` v1 event (`schemas/events/crate_changed.v1.schema.json`).
public struct CrateChange: Codable, Hashable, Sendable {
    public var crateID: String
    public var name: String?
    public var deleted: Bool?
    public var added: [String]?
    public var removed: [String]?

    enum CodingKeys: String, CodingKey {
        case crateID = "crate_id", name, deleted, added, removed
    }

    public init(crateID: String, name: String? = nil, deleted: Bool = false, added: [String] = [], removed: [String] = []) {
        self.crateID = crateID
        self.name = name
        self.deleted = deleted ? true : nil
        self.added = added.isEmpty ? nil : added
        self.removed = removed.isEmpty ? nil : removed
    }

    public var json: String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return (try? encoder.encode(self)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }
}

public enum CrateLog {
    public static let eventType = "crate_changed"
    public static let version = 1
    /// Keeps each payload under the server's 16 KiB limit (64-character keys).
    public static let maxKeysPerEvent = 200

    /// A crate id, minted lowercase like event ids.
    public static func newCrateID() -> String { UUID().uuidString.lowercased() }

    /// Splits a change whose track lists exceed `maxKeysPerEvent` into several, in order.
    /// The name and deletion ride on the first one.
    public static func chunked(_ change: CrateChange) -> [CrateChange] {
        let added = change.added ?? [], removed = change.removed ?? []
        guard added.count + removed.count > maxKeysPerEvent else { return [change] }
        var out: [CrateChange] = []
        var pending = CrateChange(crateID: change.crateID, name: change.name, deleted: change.deleted == true)
        var count = 0
        for (key, isAdded) in added.map({ ($0, true) }) + removed.map({ ($0, false) }) {
            if count == maxKeysPerEvent {
                out.append(pending)
                pending = CrateChange(crateID: change.crateID)
                count = 0
            }
            if isAdded { pending.added = (pending.added ?? []) + [key] } else { pending.removed = (pending.removed ?? []) + [key] }
            count += 1
        }
        out.append(pending)
        return out
    }

    /// Rebuilds crates by replaying `crate_changed` v1 events in id order, so every
    /// device gets the same result whatever order the events arrived in. The latest
    /// event wins: a name creates or renames, `deleted` empties and hides the crate,
    /// and a later name brings it back empty. Membership changes are kept even for a
    /// crate whose creation sorts later (another device's clock was behind).
    /// Other types, other versions and payloads without a crate id are skipped.
    /// Crates come back in the order they first appear in the log.
    public static func fold(_ events: [HistoryEvent]) -> [Crate] {
        struct State {
            var name: String?
            var deleted = false
            var keys: [String] = []
            var members: Set<String> = []
        }
        let decoder = JSONDecoder()
        var seen = Set<String>()
        var order: [String] = []
        var state: [String: State] = [:]
        for event in events.sorted(by: { $0.id.lowercased() < $1.id.lowercased() }) {
            guard event.type == eventType, event.v == version, seen.insert(event.id.lowercased()).inserted,
                  let change = try? decoder.decode(CrateChange.self, from: Data(event.payload.utf8)),
                  !change.crateID.isEmpty
            else { continue }
            let id = change.crateID.lowercased()
            if state[id] == nil { order.append(id) }
            var crate = state[id] ?? State()
            if let name = change.name?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty {
                if crate.deleted { crate.keys = []; crate.members = [] }
                crate.name = name
                crate.deleted = false
            }
            if change.deleted == true {
                crate.deleted = true
                crate.keys = []
                crate.members = []
            }
            for key in change.added ?? [] where crate.members.insert(key).inserted {
                crate.keys.append(key)
            }
            let removed = Set(change.removed ?? [])
            if !removed.isEmpty {
                crate.keys.removeAll { removed.contains($0) }
                crate.members.subtract(removed)
            }
            state[id] = crate
        }
        return order.compactMap { id in
            guard let crate = state[id], let name = crate.name, !crate.deleted else { return nil }
            return Crate(id: id, name: name, trackKeys: crate.keys)
        }
    }
}

/// Crate edits. Each one appends `crate_changed` events to the history log;
/// `crates()` folds the log back into crates.
public struct CrateStore: Sendable {
    public let history: HistoryStore
    public let library: LibraryStore

    public init(history: HistoryStore, library: LibraryStore) {
        self.history = history
        self.library = library
    }

    public func crates() async throws -> [Crate] {
        CrateLog.fold(try await history.events(ofType: CrateLog.eventType))
    }

    /// Creates a crate, optionally with tracks already in it, and returns its id.
    @discardableResult
    public func create(name: String, with tracks: [Track] = []) async throws -> String {
        let name = try Self.validName(name)
        let keys = try await keys(for: tracks)
        let id = CrateLog.newCrateID()
        try await record(CrateChange(crateID: id, name: name, added: keys))
        return id
    }

    public func rename(_ id: String, to name: String) async throws {
        let name = try Self.validName(name)
        try await record(CrateChange(crateID: id, name: name))
    }

    public func delete(_ id: String) async throws {
        try await record(CrateChange(crateID: id, deleted: true))
    }

    public func add(_ tracks: [Track], to id: String) async throws {
        let keys = try await keys(for: tracks)
        guard !keys.isEmpty else { return }
        try await record(CrateChange(crateID: id, added: keys))
    }

    public func remove(keys: [String], from id: String) async throws {
        guard !keys.isEmpty else { return }
        try await record(CrateChange(crateID: id, removed: keys))
    }

    public enum CrateError: Error, LocalizedError {
        case emptyName
        public var errorDescription: String? { "A crate needs a name." }
    }

    static func validName(_ name: String) throws -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw CrateError.emptyName }
        return String(trimmed.prefix(200))
    }

    private func keys(for tracks: [Track]) async throws -> [String] {
        var keys: [String] = []
        var seen = Set<String>()
        for track in tracks {
            let key = try await library.contentHash(for: track)
            if seen.insert(key).inserted { keys.append(key) }
        }
        return keys
    }

    private func record(_ change: CrateChange) async throws {
        for part in CrateLog.chunked(change) {
            try await history.append(type: CrateLog.eventType, v: CrateLog.version, payload: part.json)
        }
    }
}
