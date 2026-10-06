import Foundation
import SQLite3
import Testing
@testable import MotifKit

@Suite struct LibraryStoreTests {
    @Test func roundTripsAndSearches() async throws {
        let store = try LibraryStore(directory: nil)
        let track = Track(title: "Aria", artist: "Bach", album: "Goldberg Variations", durationMs: 180_000,
                          filePath: "a.flac", format: "flac", sampleRate: 96_000, bitDepth: 24, channels: 2,
                          source: .internetArchive, sourceRef: "item/a.flac",
                          licenseURL: URL(string: "https://creativecommons.org/licenses/by/4.0/"))
        try await store.upsert(track)

        let all = try await store.allTracks()
        #expect(all.count == 1)
        #expect(all.first?.title == "Aria")
        #expect(all.first?.bitDepth == 24)
        #expect(all.first?.source == .internetArchive)
        #expect(all.first?.licenseURL == track.licenseURL)
        #expect(try await store.search("goldberg").count == 1)
        #expect(try await store.search("mozart").isEmpty)

        try await store.updateAnalysis(id: track.id, bpm: 72.5, loudnessDb: -14)
        #expect(try await store.allTracks().first?.bpm == 72.5)

        try await store.delete(track)
        #expect(try await store.allTracks().isEmpty)
    }

    /// The embedded schema must define the same columns as schemas/library.sql.
    @Test func schemaMatchesSharedDefinition() throws {
        let shared = URL(filePath: #filePath)
            .deletingLastPathComponent().appending(path: "../../../../schemas/library.sql").standardized
        let sql = try String(contentsOf: shared, encoding: .utf8)
        #expect(try columns(of: sql) == columns(of: LibraryStore.schema))
    }

    private func columns(of sql: String) throws -> [String] {
        var db: OpaquePointer?
        sqlite3_open(":memory:", &db)
        defer { sqlite3_close(db) }
        #expect(sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK)
        var stmt: OpaquePointer?
        sqlite3_prepare_v2(db, "SELECT name, type, \"notnull\" FROM pragma_table_info('tracks')", -1, &stmt, nil)
        defer { sqlite3_finalize(stmt) }
        var out: [String] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            out.append("\(String(cString: sqlite3_column_text(stmt, 0))) \(String(cString: sqlite3_column_text(stmt, 1))) \(sqlite3_column_int(stmt, 2))")
        }
        return out
    }
}

@Suite struct TrackTests {
    @Test func qualityLabel() {
        let t = Track(title: "x", durationMs: 0, filePath: "x", format: "flac", sampleRate: 44_100, bitDepth: 16, source: .local)
        #expect(t.qualityLabel == "FLAC · 16-bit / 44.1 kHz")
        #expect(t.isLossless)
    }

    @Test func formatNames() {
        #expect(ImportService.formatName("wave") == "wav")
        #expect(ImportService.formatName("aif") == "aiff")
        #expect(ImportService.formatName("flac") == "flac")
    }
}
