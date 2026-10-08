-- Motif listening history and change log, shared by every platform. A
-- separate file from the library (history.sqlite) so resetting the library
-- never wipes it. Design: docs/analytics.md; crates: docs/crates.md.
PRAGMA user_version = 1;

-- Append-only. Synced to the user's account as a set union (docs/server.md).
CREATE TABLE IF NOT EXISTS events (
    id          TEXT PRIMARY KEY,       -- UUIDv7, lowercase: time-ordered, globally unique, safe to merge across devices
    type        TEXT NOT NULL,          -- e.g. 'play', 'crate_changed'
    v           INTEGER NOT NULL,       -- payload version for this type
    at_ms       INTEGER NOT NULL,       -- unix ms, UTC
    tz_min      INTEGER NOT NULL,       -- local UTC offset in minutes when it happened
    device_id   TEXT NOT NULL,          -- random per install, never a hardware id
    session_id  TEXT,                   -- app foreground session
    track_id    TEXT,                   -- denormalized from payload for indexing
    track_key   TEXT,                   -- cross-device track identity: SHA-256 of the audio file
    synced      INTEGER NOT NULL DEFAULT 0, -- 1 once the server acknowledged it; the only column ever updated
    payload     TEXT NOT NULL           -- JSON, validated by schemas/events/<type>.v<v>.schema.json
);

CREATE INDEX IF NOT EXISTS events_at ON events(at_ms);
CREATE INDEX IF NOT EXISTS events_type_at ON events(type, at_ms);
CREATE INDEX IF NOT EXISTS events_track ON events(track_key, at_ms);
CREATE INDEX IF NOT EXISTS events_unsynced ON events(synced) WHERE synced = 0;

-- Per-install values: device_id now, the server pull cursor once sync lands.
CREATE TABLE IF NOT EXISTS sync_state (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL
);
