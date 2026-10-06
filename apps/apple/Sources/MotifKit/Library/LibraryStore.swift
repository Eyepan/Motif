import Foundation
import SQLite3

/// Offline library backed by SQLite. The schema is `schemas/library.sql`;
/// `LibraryStoreTests` fails if the copy below drifts from it.
public actor LibraryStore {
    public enum StoreError: Error, CustomStringConvertible {
        case sqlite(code: Int32, message: String)
        public var description: String {
            if case let .sqlite(code, message) = self { return "SQLite \(code): \(message)" }
            return "SQLite error"
        }
    }

    static let schema = """
    CREATE TABLE IF NOT EXISTS tracks (
        id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT, album TEXT,
        duration_ms INTEGER NOT NULL DEFAULT 0, file_path TEXT NOT NULL UNIQUE, format TEXT NOT NULL,
        sample_rate INTEGER, bit_depth INTEGER, channels INTEGER, source TEXT NOT NULL, source_ref TEXT,
        license_url TEXT, bpm REAL, loudness_db REAL, added_at INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS tracks_artist_album ON tracks(artist, album);
    CREATE INDEX IF NOT EXISTS tracks_added_at ON tracks(added_at DESC);
    PRAGMA user_version = 1;
    """

    private static let columns = "id, title, artist, album, duration_ms, file_path, format, sample_rate, bit_depth, channels, source, source_ref, license_url, bpm, loudness_db, added_at"

    /// Where imported audio files live. Track.filePath is relative to this.
    public nonisolated let mediaDirectory: URL
    private let db: OpaquePointer

    /// The default store in Application Support. Excluded from nothing: audio
    /// and the database are user content and belong in backups.
    public static func makeDefault() throws -> LibraryStore {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        ).appending(path: "Motif", directoryHint: .isDirectory)
        return try LibraryStore(directory: base)
    }

    /// Pass `nil` for an in-memory database (tests).
    public init(directory: URL?) throws {
        let media = (directory ?? FileManager.default.temporaryDirectory.appending(path: UUID().uuidString))
            .appending(path: "Media", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: media, withIntermediateDirectories: true)
        mediaDirectory = media

        let path = directory.map { $0.appending(path: "library.sqlite").path(percentEncoded: false) } ?? ":memory:"
        var handle: OpaquePointer?
        let rc = sqlite3_open_v2(path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX, nil)
        guard rc == SQLITE_OK, let handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed"
            sqlite3_close(handle)
            throw StoreError.sqlite(code: rc, message: message)
        }
        db = handle
        try Self.exec(handle, "PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON;")
        try Self.exec(handle, Self.schema)
    }

    deinit { sqlite3_close(db) }

    public func url(for track: Track) -> URL {
        mediaDirectory.appending(path: track.filePath)
    }

    public func allTracks() throws -> [Track] {
        try query("SELECT \(Self.columns) FROM tracks ORDER BY added_at DESC")
    }

    public func search(_ text: String) throws -> [Track] {
        let like = "%\(text)%"
        return try query(
            "SELECT \(Self.columns) FROM tracks WHERE title LIKE ?1 OR artist LIKE ?1 OR album LIKE ?1 ORDER BY artist, album, title",
            bind: [like]
        )
    }

    public func upsert(_ t: Track) throws {
        let placeholders = (1...16).map { "?\($0)" }.joined(separator: ", ")
        try run(
            "INSERT OR REPLACE INTO tracks (\(Self.columns)) VALUES (\(placeholders))",
            bind: [
                t.id.uuidString, t.title, t.artist, t.album, t.durationMs, t.filePath, t.format,
                t.sampleRate, t.bitDepth, t.channels, t.source.rawValue, t.sourceRef,
                t.licenseURL?.absoluteString, t.bpm, t.loudnessDb, Int(t.addedAt.timeIntervalSince1970),
            ]
        )
    }

    /// Removes the row and its audio file.
    public func delete(_ track: Track) throws {
        try run("DELETE FROM tracks WHERE id = ?1", bind: [track.id.uuidString])
        try? FileManager.default.removeItem(at: url(for: track))
    }

    public func updateAnalysis(id: UUID, bpm: Double?, loudnessDb: Double?) throws {
        try run("UPDATE tracks SET bpm = ?1, loudness_db = ?2 WHERE id = ?3", bind: [bpm, loudnessDb, id.uuidString])
    }

    // MARK: - SQLite plumbing

    private static func exec(_ db: OpaquePointer, _ sql: String) throws {
        var err: UnsafeMutablePointer<CChar>?
        let rc = sqlite3_exec(db, sql, nil, nil, &err)
        if rc != SQLITE_OK {
            let message = err.map { String(cString: $0) } ?? "exec failed"
            sqlite3_free(err)
            throw StoreError.sqlite(code: rc, message: message)
        }
    }

    private func prepare(_ sql: String, bind values: [(any Sendable)?]) throws -> OpaquePointer {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { throw lastError() }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (i, value) in values.enumerated() {
            let idx = Int32(i + 1)
            switch value {
            case nil: sqlite3_bind_null(stmt, idx)
            case let v as String: sqlite3_bind_text(stmt, idx, v, -1, transient)
            case let v as Int: sqlite3_bind_int64(stmt, idx, Int64(v))
            case let v as Double: sqlite3_bind_double(stmt, idx, v)
            default:
                sqlite3_finalize(stmt)
                preconditionFailure("Unsupported bind type \(type(of: value))")
            }
        }
        return stmt
    }

    private func run(_ sql: String, bind values: [(any Sendable)?] = []) throws {
        let stmt = try prepare(sql, bind: values)
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_DONE else { throw lastError() }
    }

    private func query(_ sql: String, bind values: [(any Sendable)?] = []) throws -> [Track] {
        let stmt = try prepare(sql, bind: values)
        defer { sqlite3_finalize(stmt) }
        var rows: [Track] = []
        while true {
            let rc = sqlite3_step(stmt)
            if rc == SQLITE_DONE { break }
            guard rc == SQLITE_ROW else { throw lastError() }
            rows.append(Self.track(from: stmt))
        }
        return rows
    }

    private static func track(from s: OpaquePointer) -> Track {
        func text(_ i: Int32) -> String? {
            sqlite3_column_type(s, i) == SQLITE_NULL ? nil : String(cString: sqlite3_column_text(s, i))
        }
        func int(_ i: Int32) -> Int? {
            sqlite3_column_type(s, i) == SQLITE_NULL ? nil : Int(sqlite3_column_int64(s, i))
        }
        func double(_ i: Int32) -> Double? {
            sqlite3_column_type(s, i) == SQLITE_NULL ? nil : sqlite3_column_double(s, i)
        }
        return Track(
            id: UUID(uuidString: text(0) ?? "") ?? UUID(),
            title: text(1) ?? "",
            artist: text(2),
            album: text(3),
            durationMs: int(4) ?? 0,
            filePath: text(5) ?? "",
            format: text(6) ?? "",
            sampleRate: int(7),
            bitDepth: int(8),
            channels: int(9),
            source: Track.Source(rawValue: text(10) ?? "") ?? .local,
            sourceRef: text(11),
            licenseURL: text(12).flatMap(URL.init(string:)),
            bpm: double(13),
            loudnessDb: double(14),
            addedAt: Date(timeIntervalSince1970: TimeInterval(int(15) ?? 0))
        )
    }

    private func lastError() -> StoreError {
        .sqlite(code: sqlite3_errcode(db), message: String(cString: sqlite3_errmsg(db)))
    }
}
