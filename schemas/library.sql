-- Motif library schema, shared by every platform. Bump user_version on change
-- and add a migration step in each app's store.
PRAGMA user_version = 1;

CREATE TABLE IF NOT EXISTS tracks (
    id              TEXT PRIMARY KEY,          -- UUID
    title           TEXT NOT NULL,
    artist          TEXT,
    album           TEXT,
    duration_ms     INTEGER NOT NULL DEFAULT 0,
    file_path       TEXT NOT NULL UNIQUE,      -- relative to the app's media directory
    format          TEXT NOT NULL,             -- flac | wav | alac | aiff | mp3 | ...
    sample_rate     INTEGER,
    bit_depth       INTEGER,
    channels        INTEGER,
    source          TEXT NOT NULL,             -- local | internet_archive | jamendo | bandcamp
    source_ref      TEXT,                      -- id in the source catalog
    license_url     TEXT,
    bpm             REAL,                      -- filled by core/dsp analysis
    loudness_db     REAL,
    added_at        INTEGER NOT NULL           -- unix seconds
);

CREATE INDEX IF NOT EXISTS tracks_artist_album ON tracks(artist, album);
CREATE INDEX IF NOT EXISTS tracks_added_at ON tracks(added_at DESC);
