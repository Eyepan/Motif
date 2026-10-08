import Foundation
import SQLite3
import Testing
@testable import MotifKit

@Suite struct CrateTests {
    static let schemas = URL(filePath: #filePath).deletingLastPathComponent().appending(path: "../../../../schemas").standardized

    /// Every platform must rebuild the same crates from schemas/fixtures/crates.json.
    @Test func foldMatchesSharedFixture() throws {
        let data = try Data(contentsOf: Self.schemas.appending(path: "fixtures/crates.json"))
        let fixture = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let events = try #require(fixture["events"] as? [[String: Any]]).map { e in
            let payload = try JSONSerialization.data(withJSONObject: e["payload"] as? [String: Any] ?? [:])
            return HistoryEvent(id: e["id"] as! String, type: e["type"] as! String, v: e["v"] as! Int,
                                atMs: Int64(e["at_ms"] as! Int), tzMin: e["tz_min"] as! Int,
                                deviceID: e["device_id"] as! String, payload: String(decoding: payload, as: UTF8.self))
        }
        let expected = try #require(fixture["crates"] as? [[String: Any]]).map {
            Crate(id: $0["id"] as! String, name: $0["name"] as! String, trackKeys: $0["tracks"] as! [String])
        }
        #expect(CrateLog.fold(events) == expected)
        #expect(CrateLog.fold(events.reversed()) == expected)
    }

    @Test func largeChangesSplitUnderServerLimit() throws {
        let keys = (0..<450).map { String(repeating: String($0 % 10), count: 64) + "\($0)" }
        let parts = CrateLog.chunked(CrateChange(crateID: "c", name: "Big", added: Array(keys[..<300]), removed: Array(keys[300...])))
        #expect(parts.count == 3)
        #expect(parts[0].name == "Big")
        #expect(parts.dropFirst().allSatisfy { $0.name == nil })
        #expect(parts.flatMap { $0.added ?? [] } == Array(keys[..<300]))
        #expect(parts.flatMap { $0.removed ?? [] } == Array(keys[300...]))
        #expect(parts.allSatisfy { $0.json.utf8.count < 16 * 1024 })
        #expect(CrateLog.chunked(CrateChange(crateID: "c", added: ["a"])).count == 1)
    }

    @Test func payloadOmitsUnsetFields() {
        #expect(CrateChange(crateID: "c", name: "Warm up").json == #"{"crate_id":"c","name":"Warm up"}"#)
        #expect(CrateChange(crateID: "c", deleted: true).json == #"{"crate_id":"c","deleted":true}"#)
    }

    @Test func eventIdsAreOrderedUUIDv7() async throws {
        let history = try HistoryStore(directory: nil)
        let date = Date(timeIntervalSince1970: 1_727_000_000)
        let a = try await history.append(type: "x", v: 1, payload: "{}", at: date)
        let b = try await history.append(type: "x", v: 1, payload: "{}", at: date)
        #expect(a.id < b.id)
        #expect(a.id == a.id.lowercased())
        #expect(UUID(uuidString: a.id) != nil)
        #expect(Array(a.id)[14] == "7")
        #expect(try await history.events(ofType: "x").map(\.id) == [a.id, b.id])
        #expect(try await history.unsyncedCount() == 2)
    }

    @Test func historySchemaMatchesSharedDefinition() throws {
        let sql = try String(contentsOf: Self.schemas.appending(path: "history.sql"), encoding: .utf8)
        #expect(try describe(sql: sql) == describe(sql: HistoryStore.schema))
        #expect(sql.contains("PRAGMA user_version = \(HistoryStore.schemaVersion);"))
    }

    @Test func storeCreatesEditsAndDeletesCrates() async throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let library = try LibraryStore(directory: dir)
        let history = try HistoryStore(directory: dir)
        let crates = CrateStore(history: history, library: library)

        var tracks: [Track] = []
        for name in ["a", "b"] {
            let track = Track(title: name, durationMs: 0, filePath: "\(name).wav", format: "wav", source: .local)
            try Data(name.utf8).write(to: library.mediaDirectory.appending(path: track.filePath))
            try await library.upsert(track)
            tracks.append(track)
        }
        // SHA-256 of "a" and "b".
        let keyA = "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"
        let keyB = "3e23e8160039594a33894f6564e1b1348bbd7a0088d42c4acb73eeaed59c009d"

        let id = try await crates.create(name: "  Warm up ", with: [tracks[0]])
        try await crates.add(tracks, to: id)
        #expect(try await crates.crates() == [Crate(id: id, name: "Warm up", trackKeys: [keyA, keyB])])
        #expect(try await library.allTracks().compactMap(\.contentHash).sorted() == [keyB, keyA].sorted())

        try await crates.rename(id, to: "Opening")
        try await crates.remove(keys: [keyA], from: id)
        #expect(try await crates.crates() == [Crate(id: id, name: "Opening", trackKeys: [keyB])])
        await #expect(throws: CrateStore.CrateError.self) { try await crates.rename(id, to: "  ") }

        try await crates.delete(id)
        #expect(try await crates.crates().isEmpty)

        // A second launch reads the same log and keeps its device id.
        let reopened = try HistoryStore(directory: dir)
        #expect(reopened.deviceID == history.deviceID)
        #expect(try await CrateStore(history: reopened, library: library).crates().isEmpty)
        #expect(try await reopened.events(ofType: CrateLog.eventType).count == 5)
    }

    private func describe(sql: String) throws -> [String] {
        var db: OpaquePointer?
        sqlite3_open(":memory:", &db)
        defer { sqlite3_close(db) }
        #expect(sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK)
        let objects = rows(db, "SELECT type || ' ' || name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name")
        return objects.flatMap { object -> [String] in
            let parts = object.split(separator: " ")
            let pragma = parts[0] == "table"
                ? "SELECT name || ' ' || type || ' ' || \"notnull\" || ' ' || pk FROM pragma_table_info('\(parts[1])')"
                : "SELECT name FROM pragma_index_info('\(parts[1])')"
            return [object] + rows(db, pragma).map { "  " + $0 }
        }
    }

    private func rows(_ db: OpaquePointer?, _ sql: String) -> [String] {
        var stmt: OpaquePointer?
        sqlite3_prepare_v2(db, sql, -1, &stmt, nil)
        defer { sqlite3_finalize(stmt) }
        var out: [String] = []
        while sqlite3_step(stmt) == SQLITE_ROW { out.append(String(cString: sqlite3_column_text(stmt, 0))) }
        return out
    }
}
