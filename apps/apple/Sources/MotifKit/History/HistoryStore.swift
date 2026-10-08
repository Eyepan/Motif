import Foundation
import SQLite3

/// One row of the history log. `payload` is the JSON object as text.
public struct HistoryEvent: Sendable, Hashable {
    public let id: String
    public let type: String
    public let v: Int
    public let atMs: Int64
    public let tzMin: Int
    public let deviceID: String
    public let trackKey: String?
    public let payload: String

    public init(id: String, type: String, v: Int, atMs: Int64, tzMin: Int, deviceID: String,
                trackKey: String? = nil, payload: String) {
        self.id = id; self.type = type; self.v = v; self.atMs = atMs; self.tzMin = tzMin
        self.deviceID = deviceID; self.trackKey = trackKey; self.payload = payload
    }
}

/// The append-only event log in `history.sqlite`, beside the library database.
/// The schema is `schemas/history.sql`; `HistoryStoreTests` fails if the copy below drifts from it.
/// Events written here are uploaded to the user's account and merged with other
/// devices' events (docs/server.md), so they never carry file paths.
public actor HistoryStore {
    public enum StoreError: Error, CustomStringConvertible {
        case sqlite(code: Int32, message: String)
        public var description: String {
            if case let .sqlite(code, message) = self { return "SQLite \(code): \(message)" }
            return "SQLite error"
        }
    }

    static let schemaVersion: Int32 = 1

    static let schema = """
    CREATE TABLE IF NOT EXISTS events (
        id TEXT PRIMARY KEY, type TEXT NOT NULL, v INTEGER NOT NULL, at_ms INTEGER NOT NULL,
        tz_min INTEGER NOT NULL, device_id TEXT NOT NULL, session_id TEXT, track_id TEXT, track_key TEXT,
        synced INTEGER NOT NULL DEFAULT 0, payload TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS events_at ON events(at_ms);
    CREATE INDEX IF NOT EXISTS events_type_at ON events(type, at_ms);
    CREATE INDEX IF NOT EXISTS events_track ON events(track_key, at_ms);
    CREATE INDEX IF NOT EXISTS events_unsynced ON events(synced) WHERE synced = 0;
    CREATE TABLE IF NOT EXISTS sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    """

    /// Random per install, never a hardware id.
    public nonisolated let deviceID: String
    /// Only touched from actor-isolated code and from deinit, which has exclusive access.
    private nonisolated(unsafe) let db: OpaquePointer
    /// Last timestamp put in an id, so ids from this device keep their order even if the clock steps back.
    private var lastIDMs: Int64 = 0

    public static func makeDefault() throws -> HistoryStore {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        ).appending(path: "Motif", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return try HistoryStore(directory: base)
    }

    /// Pass `nil` for an in-memory database (tests).
    public init(directory: URL?) throws {
        let path = directory.map { $0.appending(path: "history.sqlite").path(percentEncoded: false) } ?? ":memory:"
        var handle: OpaquePointer?
        let rc = sqlite3_open_v2(path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX, nil)
        guard rc == SQLITE_OK, let handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed"
            sqlite3_close(handle)
            throw StoreError.sqlite(code: rc, message: message)
        }
        db = handle
        try Self.exec(handle, "PRAGMA journal_mode = WAL;")
        if Self.userVersion(handle) < Self.schemaVersion {
            try Self.exec(handle, "BEGIN; \(Self.schema) PRAGMA user_version = \(Self.schemaVersion); COMMIT;")
        }
        if let saved = Self.syncValue(handle, "device_id") {
            deviceID = saved
        } else {
            let minted = UUID().uuidString.lowercased()
            try Self.exec(handle, "INSERT INTO sync_state (key, value) VALUES ('device_id', '\(minted)')")
            deviceID = minted
        }
    }

    deinit { sqlite3_close(db) }

    /// Writes a new event from this device and returns it.
    @discardableResult
    public func append(type: String, v: Int, payload: String, trackKey: String? = nil, at date: Date = .now) throws -> HistoryEvent {
        let atMs = Int64((date.timeIntervalSince1970 * 1000).rounded(.down))
        let idMs = max(atMs, lastIDMs + 1)
        lastIDMs = idMs
        let event = HistoryEvent(id: Self.uuidV7(ms: idMs), type: type, v: v, atMs: atMs,
                                 tzMin: TimeZone.current.secondsFromGMT(for: date) / 60,
                                 deviceID: deviceID, trackKey: trackKey, payload: payload)
        try insert([event])
        return event
    }

    /// Adds events, skipping ids already present: pulled events from other devices, or a retried batch.
    public func insert(_ events: [HistoryEvent]) throws {
        try Self.exec(db, "BEGIN")
        do {
            for e in events {
                var stmt: OpaquePointer?
                let sql = """
                INSERT OR IGNORE INTO events (id, type, v, at_ms, tz_min, device_id, track_key, synced, payload)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
                """
                guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { throw lastError() }
                defer { sqlite3_finalize(stmt) }
                let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
                sqlite3_bind_text(stmt, 1, e.id.lowercased(), -1, transient)
                sqlite3_bind_text(stmt, 2, e.type, -1, transient)
                sqlite3_bind_int64(stmt, 3, Int64(e.v))
                sqlite3_bind_int64(stmt, 4, e.atMs)
                sqlite3_bind_int64(stmt, 5, Int64(e.tzMin))
                sqlite3_bind_text(stmt, 6, e.deviceID, -1, transient)
                if let key = e.trackKey { sqlite3_bind_text(stmt, 7, key, -1, transient) } else { sqlite3_bind_null(stmt, 7) }
                // Events from other devices arrive already on the server.
                sqlite3_bind_int64(stmt, 8, e.deviceID == deviceID ? 0 : 1)
                sqlite3_bind_text(stmt, 9, e.payload, -1, transient)
                guard sqlite3_step(stmt) == SQLITE_DONE else { throw lastError() }
            }
            try Self.exec(db, "COMMIT")
        } catch {
            try? Self.exec(db, "ROLLBACK")
            throw error
        }
    }

    /// Every event of one type, in id order (time order across devices).
    public func events(ofType type: String) throws -> [HistoryEvent] {
        var stmt: OpaquePointer?
        let sql = "SELECT id, type, v, at_ms, tz_min, device_id, track_key, payload FROM events WHERE type = ?1 ORDER BY id"
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { throw lastError() }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_text(stmt, 1, type, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
        func text(_ i: Int32) -> String? {
            sqlite3_column_type(stmt, i) == SQLITE_NULL ? nil : String(cString: sqlite3_column_text(stmt, i))
        }
        var rows: [HistoryEvent] = []
        while true {
            let rc = sqlite3_step(stmt)
            if rc == SQLITE_DONE { break }
            guard rc == SQLITE_ROW else { throw lastError() }
            rows.append(HistoryEvent(
                id: text(0) ?? "", type: text(1) ?? "", v: Int(sqlite3_column_int64(stmt, 2)),
                atMs: sqlite3_column_int64(stmt, 3), tzMin: Int(sqlite3_column_int64(stmt, 4)),
                deviceID: text(5) ?? "", trackKey: text(6), payload: text(7) ?? "{}"
            ))
        }
        return rows
    }

    /// Events not yet acknowledged by the server.
    public func unsyncedCount() throws -> Int {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "SELECT count(*) FROM events WHERE synced = 0", -1, &stmt, nil) == SQLITE_OK,
              let stmt else { throw lastError() }
        defer { sqlite3_finalize(stmt) }
        return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int64(stmt, 0)) : 0
    }

    /// A lowercase UUIDv7: 48 bits of unix ms, then version 7, variant 10 and random bits.
    static func uuidV7(ms: Int64) -> String {
        var bytes = (0..<16).map { _ in UInt8.random(in: 0...255) }
        for i in 0..<6 { bytes[i] = UInt8(truncatingIfNeeded: ms >> (8 * (5 - i))) }
        bytes[6] = (bytes[6] & 0x0F) | 0x70
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        let hex = bytes.map { String(format: "%02x", $0) }.joined()
        let cuts = [8, 12, 16, 20]
        var out = ""
        for (i, ch) in hex.enumerated() {
            if cuts.contains(i) { out.append("-") }
            out.append(ch)
        }
        return out
    }

    // MARK: - SQLite plumbing

    private static func userVersion(_ db: OpaquePointer) -> Int32 {
        var stmt: OpaquePointer?
        sqlite3_prepare_v2(db, "PRAGMA user_version", -1, &stmt, nil)
        defer { sqlite3_finalize(stmt) }
        return sqlite3_step(stmt) == SQLITE_ROW ? sqlite3_column_int(stmt, 0) : 0
    }

    private static func syncValue(_ db: OpaquePointer, _ key: String) -> String? {
        var stmt: OpaquePointer?
        sqlite3_prepare_v2(db, "SELECT value FROM sync_state WHERE key = ?1", -1, &stmt, nil)
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_text(stmt, 1, key, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
        guard sqlite3_step(stmt) == SQLITE_ROW, let text = sqlite3_column_text(stmt, 0) else { return nil }
        return String(cString: text)
    }

    private static func exec(_ db: OpaquePointer, _ sql: String) throws {
        var err: UnsafeMutablePointer<CChar>?
        let rc = sqlite3_exec(db, sql, nil, nil, &err)
        if rc != SQLITE_OK {
            let message = err.map { String(cString: $0) } ?? "exec failed"
            sqlite3_free(err)
            throw StoreError.sqlite(code: rc, message: message)
        }
    }

    private func lastError() -> StoreError {
        .sqlite(code: sqlite3_errcode(db), message: String(cString: sqlite3_errmsg(db)))
    }
}
