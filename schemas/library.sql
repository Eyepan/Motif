-- Motif library schema, shared by every platform. Bump user_version on change,
-- add schemas/migrations/<version>.sql, and run it from each app's store.
-- Metadata design: docs/metadata.md.
PRAGMA user_version = 3;

-- Display values, already resolved by precedence: user override > file tag >
-- MusicBrainz > inferred. Raw inputs live in track_tags and track_overrides.
CREATE TABLE IF NOT EXISTS tracks (
    id              TEXT PRIMARY KEY,          -- UUID
    title           TEXT NOT NULL,
    artist          TEXT,                      -- credit string as tagged, e.g. 'A feat. B'; see track_artists
    album           TEXT,
    duration_ms     INTEGER NOT NULL DEFAULT 0,
    file_path       TEXT NOT NULL UNIQUE,      -- relative to the app's media directory
    format          TEXT NOT NULL,             -- flac | wav | alac | aiff | mp3 | ...
    sample_rate     INTEGER,
    bit_depth       INTEGER,
    channels        INTEGER,
    source          TEXT NOT NULL,             -- local | internet_archive | jamendo | audius | bandcamp
    source_ref      TEXT,                      -- id in the source catalog
    license_url     TEXT,
    bpm             REAL,                      -- filled by core/dsp analysis
    loudness_db     REAL,
    musical_key     TEXT,                      -- Camelot notation, e.g. 8A (added in v2)
    waveform        BLOB,                      -- 128 loudness bytes, 0-255 (added in v2)
    added_at        INTEGER NOT NULL,          -- unix seconds
    album_artist    TEXT,                      -- added in v3 from here down
    track_no        INTEGER,
    disc_no         INTEGER,
    release_date    TEXT,                      -- ISO 8601, may be just YYYY or YYYY-MM
    isrc            TEXT,
    mb_recording_id TEXT,                      -- MusicBrainz, the only external catalog
    mb_release_id   TEXT,
    energy          REAL,                      -- 0..1, core/dsp
    beat_offset_ms  INTEGER,                   -- first downbeat, core/dsp
    analyzer_version INTEGER,                  -- core/dsp version that produced bpm, key, energy
    content_hash    TEXT,                      -- SHA-256 of the file, used by the history log's track_key
    artwork_hash    TEXT                       -- SHA-256 of the image in Media/artwork/<hash>
);

CREATE INDEX IF NOT EXISTS tracks_artist_album ON tracks(artist, album);
CREATE INDEX IF NOT EXISTS tracks_added_at ON tracks(added_at DESC);
CREATE INDEX IF NOT EXISTS tracks_album_artist ON tracks(album_artist, album, disc_no, track_no);
CREATE INDEX IF NOT EXISTS tracks_mb_recording ON tracks(mb_recording_id);
CREATE INDEX IF NOT EXISTS tracks_content_hash ON tracks(content_hash);

-- Every tag read from the file, and every MusicBrainz field fetched, verbatim.
-- Never interpreted in place, so new classification rules can rerun over it.
CREATE TABLE IF NOT EXISTS track_tags (
    track_id        TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
    key             TEXT NOT NULL,             -- lowercased field name, e.g. genre, tbpm, musicbrainz_trackid
    value           TEXT NOT NULL,
    origin          TEXT NOT NULL              -- tag | musicbrainz
);
CREATE INDEX IF NOT EXISTS track_tags_track ON track_tags(track_id, key);

-- User edits. They win over everything and are never written back to files.
CREATE TABLE IF NOT EXISTS track_overrides (
    track_id        TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
    field           TEXT NOT NULL,             -- tracks column name, or artists / genres
    value           TEXT,                      -- NULL clears the field
    PRIMARY KEY (track_id, field)
);

CREATE TABLE IF NOT EXISTS artists (
    id              TEXT PRIMARY KEY,          -- UUID
    name            TEXT NOT NULL,
    sort_name       TEXT,
    norm_name       TEXT NOT NULL UNIQUE,      -- shared normalization from core, used to merge credits
    mbid            TEXT UNIQUE
);

-- Credited artists per track. Plays count toward every primary and featured artist.
CREATE TABLE IF NOT EXISTS track_artists (
    track_id        TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
    artist_id       TEXT NOT NULL REFERENCES artists(id),
    role            TEXT NOT NULL,             -- primary | featured | remixer | composer | album_artist
    position        INTEGER NOT NULL,          -- order within the role
    PRIMARY KEY (track_id, artist_id, role)
);
CREATE INDEX IF NOT EXISTS track_artists_artist ON track_artists(artist_id, role);

CREATE TABLE IF NOT EXISTS track_genres (
    track_id        TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
    genre_id        TEXT NOT NULL,             -- id in schemas/genres.json, e.g. deep-house
    provenance      TEXT NOT NULL,             -- user | tag | musicbrainz | inferred
    PRIMARY KEY (track_id, genre_id)
);
CREATE INDEX IF NOT EXISTS track_genres_genre ON track_genres(genre_id);
