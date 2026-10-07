-- v2 -> v3: release details, MusicBrainz ids, more analysis, raw tags, user
-- overrides, artist credits and genres. See docs/metadata.md.

ALTER TABLE tracks ADD COLUMN album_artist TEXT;
ALTER TABLE tracks ADD COLUMN track_no INTEGER;
ALTER TABLE tracks ADD COLUMN disc_no INTEGER;
ALTER TABLE tracks ADD COLUMN release_date TEXT;
ALTER TABLE tracks ADD COLUMN isrc TEXT;
ALTER TABLE tracks ADD COLUMN mb_recording_id TEXT;
ALTER TABLE tracks ADD COLUMN mb_release_id TEXT;
ALTER TABLE tracks ADD COLUMN energy REAL;
ALTER TABLE tracks ADD COLUMN beat_offset_ms INTEGER;
ALTER TABLE tracks ADD COLUMN analyzer_version INTEGER;
ALTER TABLE tracks ADD COLUMN content_hash TEXT;
ALTER TABLE tracks ADD COLUMN artwork_hash TEXT;

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
