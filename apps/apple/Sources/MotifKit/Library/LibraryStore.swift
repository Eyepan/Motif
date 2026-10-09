import CryptoKit
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

    static let schemaVersion: Int32 = 3

    static let schema = """
    CREATE TABLE IF NOT EXISTS tracks (
        id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT, album TEXT,
        duration_ms INTEGER NOT NULL DEFAULT 0, file_path TEXT NOT NULL UNIQUE, format TEXT NOT NULL,
        sample_rate INTEGER, bit_depth INTEGER, channels INTEGER, source TEXT NOT NULL, source_ref TEXT,
        license_url TEXT, bpm REAL, loudness_db REAL, musical_key TEXT, waveform BLOB, added_at INTEGER NOT NULL,
        album_artist TEXT, track_no INTEGER, disc_no INTEGER, release_date TEXT, isrc TEXT,
        mb_recording_id TEXT, mb_release_id TEXT, energy REAL, beat_offset_ms INTEGER, analyzer_version INTEGER,
        content_hash TEXT, artwork_hash TEXT
    );
    CREATE INDEX IF NOT EXISTS tracks_artist_album ON tracks(artist, album);
    CREATE INDEX IF NOT EXISTS tracks_added_at ON tracks(added_at DESC);
    \(metadataTables)
    """

    /// Indexes and tables added in v3, shared by `schema` and the v3 migration.
    private static let metadataTables = """
    CREATE INDEX IF NOT EXISTS tracks_album_artist ON tracks(album_artist, album, disc_no, track_no);
    CREATE INDEX IF NOT EXISTS tracks_mb_recording ON tracks(mb_recording_id);
    CREATE INDEX IF NOT EXISTS tracks_content_hash ON tracks(content_hash);
    CREATE TABLE IF NOT EXISTS track_tags (
        track_id TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
        key TEXT NOT NULL, value TEXT NOT NULL, origin TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS track_tags_track ON track_tags(track_id, key);
    CREATE TABLE IF NOT EXISTS track_overrides (
        track_id TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
        field TEXT NOT NULL, value TEXT, PRIMARY KEY (track_id, field)
    );
    CREATE TABLE IF NOT EXISTS artists (
        id TEXT PRIMARY KEY, name TEXT NOT NULL, sort_name TEXT, norm_name TEXT NOT NULL UNIQUE, mbid TEXT UNIQUE
    );
    CREATE TABLE IF NOT EXISTS track_artists (
        track_id TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
        artist_id TEXT NOT NULL REFERENCES artists(id),
        role TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY (track_id, artist_id, role)
    );
    CREATE INDEX IF NOT EXISTS track_artists_artist ON track_artists(artist_id, role);
    CREATE TABLE IF NOT EXISTS track_genres (
        track_id TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
        genre_id TEXT NOT NULL, provenance TEXT NOT NULL, PRIMARY KEY (track_id, genre_id)
    );
    CREATE INDEX IF NOT EXISTS track_genres_genre ON track_genres(genre_id);
    """

    /// Steps from version N-1 to N, applied to databases created before N.
    private static let migrations: [Int32: String] = [
        2: "ALTER TABLE tracks ADD COLUMN musical_key TEXT; ALTER TABLE tracks ADD COLUMN waveform BLOB;",
        // schemas/migrations/3.sql
        3: """
        ALTER TABLE tracks ADD COLUMN album_artist TEXT; ALTER TABLE tracks ADD COLUMN track_no INTEGER;
        ALTER TABLE tracks ADD COLUMN disc_no INTEGER; ALTER TABLE tracks ADD COLUMN release_date TEXT;
        ALTER TABLE tracks ADD COLUMN isrc TEXT; ALTER TABLE tracks ADD COLUMN mb_recording_id TEXT;
        ALTER TABLE tracks ADD COLUMN mb_release_id TEXT; ALTER TABLE tracks ADD COLUMN energy REAL;
        ALTER TABLE tracks ADD COLUMN beat_offset_ms INTEGER; ALTER TABLE tracks ADD COLUMN analyzer_version INTEGER;
        ALTER TABLE tracks ADD COLUMN content_hash TEXT; ALTER TABLE tracks ADD COLUMN artwork_hash TEXT;
        \(metadataTables)
        """,
    ]

    /// Bytes in `Track.waveform`.
    public static let waveformLength = 128

    /// Columns `upsert` writes, in bind order.
    private static let columns = "id, title, artist, album, duration_ms, file_path, format, sample_rate, bit_depth, channels, source, source_ref, license_url, bpm, loudness_db, musical_key, waveform, added_at, album_artist"
    /// Columns read into a `Track`: everything `upsert` writes, plus what other steps fill in
    /// (content hash; beat grid and analyser version from `updateAnalysis`).
    private static let readColumns = columns + ", content_hash, beat_offset_ms, analyzer_version"

    /// Where imported audio files live. Track.filePath is relative to this.
    public nonisolated let mediaDirectory: URL
    /// Album art, one file per track.
    public nonisolated let artwork: ArtworkStore
    /// Site names appended to tags anywhere in the library (see `TagCleaner`), filled by `prepareTagCleaning`.
    public private(set) var siteSuffixes: Set<String> = []
    /// Only touched from actor-isolated code and from deinit, which has exclusive access.
    private nonisolated(unsafe) let db: OpaquePointer

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
        let base = directory ?? FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        let media = base.appending(path: "Media", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: media, withIntermediateDirectories: true)
        mediaDirectory = media
        artwork = try ArtworkStore(directory: base.appending(path: "Artwork", directoryHint: .isDirectory))

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
        try Self.migrate(handle)
    }

    private static func migrate(_ db: OpaquePointer) throws {
        var stmt: OpaquePointer?
        sqlite3_prepare_v2(db, "PRAGMA user_version", -1, &stmt, nil)
        let current = sqlite3_step(stmt) == SQLITE_ROW ? sqlite3_column_int(stmt, 0) : 0
        sqlite3_finalize(stmt)
        guard current < schemaVersion else { return }

        try exec(db, "BEGIN")
        do {
            if current == 0 {
                try exec(db, schema)
            } else {
                for version in (current + 1)...schemaVersion {
                    if let step = migrations[version] { try exec(db, step) }
                }
            }
            try exec(db, "PRAGMA user_version = \(schemaVersion); COMMIT")
        } catch {
            try? exec(db, "ROLLBACK")
            throw error
        }
    }

    deinit { sqlite3_close(db) }

    public func url(for track: Track) -> URL {
        mediaDirectory.appending(path: track.filePath)
    }

    public func allTracks() throws -> [Track] {
        try query("SELECT \(Self.readColumns) FROM tracks ORDER BY added_at DESC")
    }

    public func search(_ text: String) throws -> [Track] {
        let like = "%\(text)%"
        return try query(
            "SELECT \(Self.readColumns) FROM tracks WHERE title LIKE ?1 OR artist LIKE ?1 OR album LIKE ?1 ORDER BY artist, album, title",
            bind: [like]
        )
    }

    /// Inserts or updates in place. Not INSERT OR REPLACE: that deletes the old
    /// row, which would cascade to its tags, credits and genres and null every
    /// column `Track` doesn't carry.
    public func upsert(_ t: Track) throws {
        let placeholders = (1...19).map { "?\($0)" }.joined(separator: ", ")
        let updates = Self.columns.split(separator: ", ").dropFirst().map { "\($0) = excluded.\($0)" }.joined(separator: ", ")
        try run(
            "INSERT INTO tracks (\(Self.columns)) VALUES (\(placeholders)) ON CONFLICT(id) DO UPDATE SET \(updates)",
            bind: [
                t.id.uuidString, t.title, t.artist, t.album, t.durationMs, t.filePath, t.format,
                t.sampleRate, t.bitDepth, t.channels, t.source.rawValue, t.sourceRef,
                t.licenseURL?.absoluteString, t.bpm, t.loudnessDb, t.musicalKey, t.waveform.map { Data($0) },
                Int(t.addedAt.timeIntervalSince1970), t.albumArtist,
            ]
        )
    }

    /// Adds a newly imported track and the raw tags its cleaned values came from, in one transaction.
    public func insert(_ track: Track, raw: RawTags?) throws {
        try Self.exec(db, "BEGIN")
        do {
            try upsert(track)
            if let raw { try insertRawTags(raw, for: track.id) }
            try Self.exec(db, "COMMIT")
        } catch {
            try? Self.exec(db, "ROLLBACK")
            throw error
        }
        if let raw, let suffix = TagCleaner.siteSuffix(raw) { siteSuffixes.insert(suffix) }
    }

    private func insertRawTags(_ raw: RawTags, for id: UUID) throws {
        for entry in raw.entries {
            try run("INSERT INTO track_tags (track_id, key, value, origin) VALUES (?1, ?2, ?3, 'tag')",
                    bind: [id.uuidString, entry.key, entry.value])
        }
    }

    /// Collects site suffixes from every track's raw tags and, when the shared
    /// cleanup rules changed since `previousVersion` (0 = never ran), re-cleans
    /// title, artist, album and album artist from those raw tags. Tracks
    /// imported before raw tags were kept use their current values as raw.
    /// Returns the cleaner version the library is now at.
    @discardableResult
    public func prepareTagCleaning(previousVersion: Int) throws -> Int {
        var raw: [String: [String: String]] = [:]
        for row in try strings("SELECT track_id, key, value FROM track_tags WHERE origin = 'tag'") {
            guard let id = row[0], let key = row[1], let value = row[2] else { continue }
            raw[id, default: [:]][key] = value
        }
        let version = TagCleaner.version
        let stale = previousVersion < version
        let tracks = try stale ? allTracks() : []
        let tags = Dictionary(uniqueKeysWithValues: tracks.map { t in
            (t.id, raw[t.id.uuidString].map(RawTags.init(entries:))
                ?? RawTags(title: t.title, artist: t.artist, album: t.album, albumArtist: t.albumArtist))
        })
        siteSuffixes = Set((raw.values.map(RawTags.init(entries:)) + tags.values).compactMap(TagCleaner.siteSuffix))
        guard stale else { return version }

        try Self.exec(db, "BEGIN")
        do {
            for t in tracks {
                guard let r = tags[t.id] else { continue }
                if raw[t.id.uuidString] == nil { try insertRawTags(r, for: t.id) }
                let albumArtist = TagCleaner.clean(r.albumArtist, suffixes: siteSuffixes)
                try run(
                    "UPDATE tracks SET title = ?1, artist = ?2, album = ?3, album_artist = ?4 WHERE id = ?5",
                    bind: [TagCleaner.clean(r.title, suffixes: siteSuffixes) ?? t.title,
                           TagCleaner.clean(r.artist, suffixes: siteSuffixes) ?? albumArtist,
                           TagCleaner.clean(r.album, suffixes: siteSuffixes), albumArtist, t.id.uuidString]
                )
            }
            try Self.exec(db, "COMMIT")
        } catch {
            try? Self.exec(db, "ROLLBACK")
            throw error
        }
        return version
    }

    /// Removes the row, its audio file and its art.
    public func delete(_ track: Track) throws {
        try run("DELETE FROM tracks WHERE id = ?1", bind: [track.id.uuidString])
        try? FileManager.default.removeItem(at: url(for: track))
        artwork.delete(track.id)
    }

    public func updateAnalysis(id: UUID, _ analysis: TrackAnalysis) throws {
        try run(
            """
            UPDATE tracks SET bpm = ?1, loudness_db = ?2, musical_key = ?3, waveform = ?4,
                beat_offset_ms = ?5, analyzer_version = ?6 WHERE id = ?7
            """,
            bind: [analysis.bpm, analysis.loudnessDb, analysis.musicalKey, Data(analysis.waveform),
                   analysis.firstDownbeat.map { Int(($0 * 1000).rounded()) }, TrackAnalyzer.version, id.uuidString]
        )
    }

    /// The track's content hash, hashing its file and saving the result the first time.
    public func contentHash(for track: Track) throws -> String {
        if let hash = track.contentHash { return hash }
        if let saved = try strings("SELECT content_hash FROM tracks WHERE id = ?1", bind: [track.id.uuidString]).first?.first ?? nil {
            return saved
        }
        let hash = try Self.sha256(of: url(for: track))
        try run("UPDATE tracks SET content_hash = ?1 WHERE id = ?2", bind: [hash, track.id.uuidString])
        return hash
    }

    /// Lowercase hex SHA-256 of a file, read in 1 MiB chunks.
    static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
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
            case let v as Data:
                _ = v.withUnsafeBytes { sqlite3_bind_blob(stmt, idx, $0.baseAddress, Int32($0.count), transient) }
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

    /// Rows of text columns, for queries that aren't tracks.
    private func strings(_ sql: String, bind values: [(any Sendable)?] = []) throws -> [[String?]] {
        let stmt = try prepare(sql, bind: values)
        defer { sqlite3_finalize(stmt) }
        var rows: [[String?]] = []
        while true {
            let rc = sqlite3_step(stmt)
            if rc == SQLITE_DONE { break }
            guard rc == SQLITE_ROW else { throw lastError() }
            rows.append((0..<sqlite3_column_count(stmt)).map { i in
                sqlite3_column_type(stmt, i) == SQLITE_NULL ? nil : String(cString: sqlite3_column_text(stmt, i))
            })
        }
        return rows
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
        func bytes(_ i: Int32) -> [UInt8]? {
            guard sqlite3_column_type(s, i) != SQLITE_NULL, let p = sqlite3_column_blob(s, i) else { return nil }
            return Array(UnsafeRawBufferPointer(start: p, count: Int(sqlite3_column_bytes(s, i))))
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
            musicalKey: text(15),
            waveform: bytes(16),
            addedAt: Date(timeIntervalSince1970: TimeInterval(int(17) ?? 0)),
            albumArtist: text(18),
            contentHash: text(19),
            firstDownbeat: int(20).map { Double($0) / 1000 },
            analyzerVersion: int(21)
        )
    }

    private func lastError() -> StoreError {
        .sqlite(code: sqlite3_errcode(db), message: String(cString: sqlite3_errmsg(db)))
    }
}
