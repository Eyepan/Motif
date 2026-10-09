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

        let analysis = TrackAnalysis(bpm: 72.5, loudnessDb: -14, musicalKey: "8A", waveform: [0, 128, 255], firstDownbeat: 0.25)
        try await store.updateAnalysis(id: track.id, analysis)
        let analyzed = try await store.allTracks().first
        #expect(analyzed?.bpm == 72.5)
        #expect(analyzed?.musicalKey == "8A")
        #expect(analyzed?.waveform == [0, 128, 255])
        #expect(analyzed?.firstDownbeat == 0.25)
        #expect(analyzed?.hasBeatGrid == true)
        #expect(analyzed?.analyzerVersion == TrackAnalyzer.version)
        // Later upserts (tag edits) keep the grid.
        if var edited = analyzed {
            edited.title = "Aria (edit)"
            edited.firstDownbeat = nil
            try await store.upsert(edited)
        }
        #expect(try await store.allTracks().first?.firstDownbeat == 0.25)

        try await store.delete(track)
        #expect(try await store.allTracks().isEmpty)
    }

    @Test func migratesVersionOneDatabase() async throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        var db: OpaquePointer?
        sqlite3_open(dir.appending(path: "library.sqlite").path(percentEncoded: false), &db)
        let v1 = """
        CREATE TABLE tracks (id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT, album TEXT,
            duration_ms INTEGER NOT NULL DEFAULT 0, file_path TEXT NOT NULL UNIQUE, format TEXT NOT NULL,
            sample_rate INTEGER, bit_depth INTEGER, channels INTEGER, source TEXT NOT NULL, source_ref TEXT,
            license_url TEXT, bpm REAL, loudness_db REAL, added_at INTEGER NOT NULL);
        INSERT INTO tracks (id, title, file_path, format, source, added_at)
            VALUES ('\(UUID().uuidString)', 'Old', 'old.wav', 'wav', 'local', 0);
        PRAGMA user_version = 1;
        """
        #expect(sqlite3_exec(db, v1, nil, nil, nil) == SQLITE_OK)
        sqlite3_close(db)

        let store = try LibraryStore(directory: dir)
        let tracks = try await store.allTracks()
        #expect(tracks.map(\.title) == ["Old"])
        #expect(tracks.first?.musicalKey == nil)
    }

    @Test func migratesVersionTwoDatabaseToSharedSchema() async throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let path = dir.appending(path: "library.sqlite").path(percentEncoded: false)
        var db: OpaquePointer?
        sqlite3_open(path, &db)
        let v2 = """
        CREATE TABLE tracks (id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT, album TEXT,
            duration_ms INTEGER NOT NULL DEFAULT 0, file_path TEXT NOT NULL UNIQUE, format TEXT NOT NULL,
            sample_rate INTEGER, bit_depth INTEGER, channels INTEGER, source TEXT NOT NULL, source_ref TEXT,
            license_url TEXT, bpm REAL, loudness_db REAL, musical_key TEXT, waveform BLOB, added_at INTEGER NOT NULL);
        CREATE INDEX tracks_artist_album ON tracks(artist, album);
        CREATE INDEX tracks_added_at ON tracks(added_at DESC);
        INSERT INTO tracks (id, title, file_path, format, source, musical_key, added_at)
            VALUES ('\(UUID().uuidString)', 'Two', 'two.flac', 'flac', 'local', '8A', 0);
        PRAGMA user_version = 2;
        """
        #expect(sqlite3_exec(db, v2, nil, nil, nil) == SQLITE_OK)
        sqlite3_close(db)

        let tracks = try await LibraryStore(directory: dir).allTracks()
        #expect(tracks.map(\.musicalKey) == ["8A"])

        sqlite3_open(path, &db)
        defer { sqlite3_close(db) }
        let shared = try String(contentsOf: Self.sharedSchema, encoding: .utf8)
        #expect(try describe(db) == describe(sql: shared))
    }

    /// A re-upsert (e.g. filling artist from a catalog) must keep the track's
    /// child rows and the columns `Track` doesn't carry.
    @Test func upsertUpdatesInPlace() async throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let store = try LibraryStore(directory: dir)
        var track = Track(title: "x", durationMs: 0, filePath: "x.flac", format: "flac", source: .local)
        try await store.upsert(track)

        var db: OpaquePointer?
        sqlite3_open(dir.appending(path: "library.sqlite").path(percentEncoded: false), &db)
        defer { sqlite3_close(db) }
        let id = track.id.uuidString
        #expect(sqlite3_exec(db, """
        UPDATE tracks SET energy = 0.5 WHERE id = '\(id)';
        INSERT INTO track_genres (track_id, genre_id, provenance) VALUES ('\(id)', 'house', 'tag');
        """, nil, nil, nil) == SQLITE_OK)

        track.artist = "Someone"
        try await store.upsert(track)
        #expect(try await store.allTracks().first?.artist == "Someone")
        #expect(rows(db, "SELECT count(*) FROM track_genres") == ["1"])
        #expect(rows(db, "SELECT energy FROM tracks") == ["0.5"])
    }

    static let sharedSchema = URL(filePath: #filePath)
        .deletingLastPathComponent().appending(path: "../../../../schemas/library.sql").standardized

    /// The embedded schema must define the same tables, columns and indexes as schemas/library.sql.
    @Test func schemaMatchesSharedDefinition() throws {
        let sql = try String(contentsOf: Self.sharedSchema, encoding: .utf8)
        #expect(try describe(sql: sql) == describe(sql: LibraryStore.schema))
        #expect(sql.contains("PRAGMA user_version = \(LibraryStore.schemaVersion);"))
    }

    private func describe(sql: String) throws -> [String] {
        var db: OpaquePointer?
        sqlite3_open(":memory:", &db)
        defer { sqlite3_close(db) }
        #expect(sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK)
        return describe(db)
    }

    /// Every table's columns and every index's columns, in a comparable form.
    private func describe(_ db: OpaquePointer?) -> [String] {
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
