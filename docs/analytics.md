# Listening history and the yearly recap

Status: proposal, not implemented. Covers what Motif records and where each signal comes from. The recap itself (stats, story, animations) is a later design.

## Goal

Every December Motif shows a yearly recap with animated story cards, in the spirit of Spotify Wrapped. Each year's edition should feel new, so next year we must be able to compute stats nobody thought of this year, over data we already recorded, without migrating anything.

That leads to one rule: **record what happened, not what we want to show.** We keep an append-only log of raw facts ("track X played from 0:00 for 212 s on headphones, then the user hit next"). Stats like "top artist", "most skipped" or "your 2 a.m. songs" are computed at recap time from the raw log. A stat we invent in 2028 runs just as well over 2026 events.

## Principles

- **Local first, synced to the user's account.** Events are always written to the device first, so tracking works offline. When online, they upload to Motif's own sync service under the user's account, and every device pulls the others' events. No third-party analytics SDK.
- **Append-only.** Events are inserted, never edited (only the local `synced` flag flips). The single exception is user-requested deletion (see Privacy controls).
- **Self-contained.** The log can rebuild the library as it was on any date, so a recap still works for tracks that were since deleted or retagged.
- **Shared, versioned schema.** The log format lives in `schemas/` like the library schema. Apple and Android write identical events; recap code reads either.
- **Never on the audio path.** Events are buffered in memory and written in batches off the main actor. A failed write drops an event, never a buffer.

## Storage

A separate SQLite file, `history.db`, beside `library.db`. Separate because it has a different lifetime: rebuilding or resetting the library must never wipe years of history, and it can be exported or deleted on its own.

```sql
-- schemas/history.sql (proposed)
PRAGMA user_version = 1;

CREATE TABLE IF NOT EXISTS events (
    id          TEXT PRIMARY KEY,       -- UUIDv7: time-ordered, globally unique, safe to merge across devices
    type        TEXT NOT NULL,          -- e.g. 'play', 'track_added'
    v           INTEGER NOT NULL,       -- payload version for this type
    at_ms       INTEGER NOT NULL,       -- unix ms, UTC
    tz_min      INTEGER NOT NULL,       -- local UTC offset in minutes when it happened
    device_id   TEXT NOT NULL,          -- random per install, never a hardware id
    session_id  TEXT,                   -- app foreground session
    track_id    TEXT,                   -- denormalized from payload for indexing
    track_key   TEXT,                   -- cross-device track identity (see Sync)
    synced      INTEGER NOT NULL DEFAULT 0, -- 1 once the server acknowledged it; the only column ever updated
    payload     TEXT NOT NULL           -- JSON, validated by schemas/events/<type>.v<v>.schema.json
);

CREATE INDEX IF NOT EXISTS events_at ON events(at_ms);
CREATE INDEX IF NOT EXISTS events_type_at ON events(type, at_ms);
CREATE INDEX IF NOT EXISTS events_track ON events(track_key, at_ms);
CREATE INDEX IF NOT EXISTS events_unsynced ON events(synced) WHERE synced = 0;

-- The play in progress, checkpointed every ~15 s. Not part of the log:
-- on launch, a leftover row becomes a 'play' event with end_reason 'interrupted'
-- (crash, OS kill, battery). Without it, every crash would lose a listen.
CREATE TABLE IF NOT EXISTS open_play (
    id          TEXT PRIMARY KEY,
    payload     TEXT NOT NULL,
    updated_ms  INTEGER NOT NULL
);
```

`tz_min` matters more than it looks: "you listen most at 11 p.m." needs local time as the user lived it, and people travel.

Size: a heavy listener (4 h/day, ~1,500 plays a month) produces roughly 25,000 events a year at ~400 bytes each, about 10 MB a year before SQLite overhead. No pruning is needed for a decade; if it ever is, old years can be compacted into a yearly summary file.

### Versioning rules

1. Each event type has its own payload version `v` and a JSON schema at `schemas/events/<type>.v<n>.schema.json`.
2. Adding an optional field keeps the version. Removing a field or changing what one means bumps it.
3. Old events are never rewritten. Readers handle every version they have ever seen, forever.
4. Unknown types and unknown fields are ignored, so an older app can read a log a newer app wrote.

## What we track

Every event also carries the envelope above (time, timezone, device, session). "Source" is the code that emits it today, or the feature it waits for.

### Listening

**`play`**: one per track playback, written when it ends. This is the core event; most of the recap comes from it.

| Field | Meaning | Source |
| --- | --- | --- |
| `track_id` | library track | `PlaybackEngine.load` |
| `started_at_ms` | when audio started | first `resume()` after `load` |
| `listened_ms` | audio actually heard, excluding pauses and skipped-over spans | `currentPosition()` deltas from the ticker |
| `start_pos_ms`, `end_pos_ms` | where it started and stopped in the track | `load`, `next` / `stop` / `segmentFinished` |
| `duration_ms` | track length | `PlaybackEngine.duration` |
| `end_reason` | `completed`, `skipped`, `previous`, `stopped`, `replaced`, `error`, `interrupted` | `segmentFinished` → completed, `next()` → skipped, `previous()`, `stop()`, `play()` with a new queue → replaced, `lastError`, `open_play` recovery |
| `seeks` | count of seeks | `seek(to:)` |
| `pauses`, `paused_ms` | count and total time paused | `pause()` / `resume()` |
| `context` | what started it: `library`, `album`, `artist`, `crate`, `search`, `queue`, `autoplay` (previous one finished), `mix` | caller of `play(_:startAt:)`; needs a parameter added |
| `context_ref` | album / crate / artist id when there is one | same |
| `control` | where the ending command came from: `app`, `lock_screen`, `headset`, `car`, `watch`, `keyboard` | `NowPlayingController` remote command handlers vs in-app calls |
| `route` | output when it started: `speaker`, `wired`, `bluetooth`, `airplay`, `car`, `usb_dac` | `AVAudioSession.currentRoute` (iOS), default output device (macOS) |
| `output_rate`, `bit_perfect` | hardware sample rate, and whether it equalled the file's | `reconnect(for:)` / `AVAudioSession.sampleRate` |
| `mixed_in`, `mixed_out` | true if this play began or ended in a DJ transition | Mix into next / DJ mode |
| `shuffle`, `repeat` | mode at the time | queue settings (not built yet) |

We store `listened_ms` and `end_reason` rather than a "counted as a play" flag. Whether a play counts at 30 s, 50 %, or something else is a recap-time decision we can change later.

**`seek`** is not its own event; the count on `play` is enough for any stat we can think of, and per-seek events would be the largest share of the log.

### Mixing

**`transition`**: one per DJ-style handover between two tracks.

| Field | Source |
| --- | --- |
| `from_track_id`, `to_track_id` | Mix into next / DJ decks |
| `kind`: `gapless`, `beatmatched`, `crossfade`, `cut` | crossfade curve in `core/dsp` (`MotifCrossfadeCurve`) |
| `length_ms` | crossfader movement start to end |
| `from_bpm`, `to_bpm`, `from_key`, `to_key` | analysis at the time |
| `harmonic` | Camelot-compatible keys | computed by `core/dsp` when key detection lands |
| `tempo_shift_pct` | pitch adjustment applied for the beatmatch | DJ engine |
| `manual` | user drove the crossfader vs automatic Mix into next | DJ Mix screen |

**`dj_session`**: written when a stretch of DJ Mix ends (five minutes with no deck playing, or regular playback taking over). `started_at_ms`, `ended_at_ms`, `tracks_loaded`, `transitions`, `cues_used`, `loops_used`, `eq_moves`. Counters, not per-knob events: enough for "you DJ'd 14 hours this year" without logging every fader nudge.

### Library

These make the log self-contained, so a recap can describe a track even after it is deleted.

| Event | Payload | Source |
| --- | --- | --- |
| `track_added` | full track snapshot as in `schemas/track.schema.json` (title, artist, album, format, rate, bit depth, source, license) plus `track_key` | `ImportService.ingest` |
| `track_analyzed` | `bpm`, `key`, `loudness_db`, `analyzer_version` | `LibraryStore.updateAnalysis` |
| `track_updated` | changed fields only | `LibraryStore.upsert` on an existing id (tag edits) |
| `track_removed` | `track_id` | `LibraryStore.delete` |
| `liked` / `unliked` | `track_id` | heart button (not built yet) |
| `crate_changed` | `crate_id`, `name`, `deleted`, `added` / `removed` track keys | Crates (docs/crates.md) |
| `rated` | `track_id`, `stars` | not planned; listed so the name is reserved |

### App

| Event | Payload | Source |
| --- | --- | --- |
| `app_session` | `started_at_ms`, `ended_at_ms`, `platform`, `os_version`, `app_version` | scene phase changes in `MotifApp` |
| `search` | `filters` used (`bpm`, `key`, `format`), `result_count`, whether a result was played. **No query text.** | search field |
| `import_batch` | `source`, `files`, `failed`, `bytes` | `ImportService.importLocal` / `importDownload` |

## What this unlocks

None of these need new tracking. They are here to check the log is rich enough, not as the 2026 edition.

- **Volume**: minutes listened, plays, distinct tracks, artists and albums; day with the most listening; longest unbroken session.
- **Favourites**: top tracks, artists and albums by `listened_ms` (not play count, so long tracks aren't punished); the song you played on repeat in a single day.
- **Habits**: listening clock by local hour; weekday vs weekend; streak of consecutive days; first and last song of the year; "most skipped" and "always finished".
- **Discovery**: first-ever play date per track and artist; new artists this year; rediscoveries (a track back after six or more months away); what you imported vs what you actually played.
- **Sound**: your average BPM by month (the year as a tempo curve); Camelot key wheel of your listening; loudness profile; share of listening that was hi-res lossless or bit-perfect, which no streaming recap can claim.
- **Places and gear**: headphones vs speaker vs car; Mac vs iPhone.
- **Mixing**: DJ Mix listening counts toward minutes and top tracks like any other play, and also gets its own section: hours DJ'd, transitions made, most harmonic blend, the pair of tracks you mixed together most, biggest tempo jump you pulled off.
- **Sources**: share of listening from Bandcamp purchases, Internet Archive and Jamendo; most-played openly licensed track (with its license link).

## Recap pipeline (sketch)

1. A **recap engine** reads `history.db` and produces a recap document: a list of cards, each with a kind and its numbers. It lives in shared code (a new `core/recap` Rust crate next to `core/dsp`, reading SQLite directly), so Apple and Android compute identical numbers.
2. Each year is an **edition**: a list of card definitions, each a pure function over events. New editions add new cards; old cards keep working on old data. The recap document is versioned in `schemas/recap/` like everything else.
3. Each platform renders the document natively: SwiftUI on Apple, Compose on Android. Animations are per-platform and per-edition, never a shared UI layer.

Because the engine only reads raw events, we can also offer "your recap so far" at any time, and regenerate past years with new editions.

## Sync and collection

History is collected to the user's Motif account, not only kept on the device. The device log stays the source of truth while offline; the server holds the merged log across all of a user's devices.

- **Service**: a small Motif-run service (Rust + Postgres, in `server/`, design in docs/server.md) with two endpoints. `POST /v1/events` takes a batch of events and is idempotent on `id`, so retries never duplicate. `GET /v1/events?after=<cursor>` returns events in server arrival order, so late uploads from a device that was offline are not skipped. The server stores the same envelope and payload, append-only.
- **Client**: a background uploader sends unsynced rows in batches (on launch, on network change, every few minutes while playing, and via `BGAppRefreshTask` on iOS / WorkManager on Android). Pulled events are inserted locally, so each device ends up with the full log and recaps work offline.
- **Accounts**: a username and password (docs/server.md). Without an account, everything still works locally; signing in later uploads the whole backlog.
- **Merging** is a set union: UUIDv7 ids are unique across devices and no event is ever edited, so there are no conflicts.
- **Track identity across devices**: `track_id` is per install, so the same song on Mac and iPhone has two ids. Each track gets a `track_key` at import: a SHA-256 of the audio file's contents, which matches whenever the same file is imported on two devices. Recaps group plays by `track_key`, with a fallback match on normalized artist, title and duration for re-encoded copies. This needs a `content_hash` column added to the library schema.
- **Transport**: TLS only, and the server encrypts at rest. Events carry no file paths or search text.

## Privacy controls

- **Pause history** is per app session: while paused, plays are not written at all. The next launch goes back to full tracking.
- Settings → Listening history: view recent events, **export** (JSON Lines, one event per line, same envelope), **delete** a date range or everything. Deletion removes rows on the device and on the server; it is the one place append-only is broken, on purpose.
- Search text, file paths and anything identifying the user beyond the account are never recorded. `device_id` is random per install.

## Gaps in today's code

- The library has no `key` or `genre`. Key detection belongs in `core/dsp`; genre can come from file tags at import.
- `play(_:startAt:)` doesn't know what started playback. It needs a `context` argument from each view.
- `NowPlayingController` doesn't tell the engine a command came from the lock screen or headset; handlers need to pass that through.
- Tracks have no content hash yet, so `track_key` needs a `content_hash` column and a hash step in `ImportService.ingest`.
- No queue modes or likes yet. Their events are listed so the names and shapes are settled before the features exist. Crates are built and write `crate_changed` (docs/crates.md).

## Decisions (Pan, 2026-10-07)

1. DJ Mix listening counts toward yearly minutes and top tracks, and also has its own recap section.
2. Pause history lasts for the current session; reopening the app resumes full tracking.
3. History is collected to the user's account and synced between devices, not kept only on the device.

## Implementation order

1. `schemas/history.sql` and `schemas/events/*.v1.schema.json` for `play`, `track_added`, `track_analyzed`, `track_updated`, `track_removed`, `app_session`.
2. `HistoryStore` in MotifKit (batched writer, `open_play` recovery, export, delete) and hooks in `PlaybackEngine`, `ImportService` and `LibraryStore`.
3. Sync service (`server/`, Rust) with accounts, the two event endpoints and delete-range, plus the client uploader and puller.
4. Backfill: on first launch with history, write a `track_added` for every existing track, dated by its `added_at`.
5. Transition and DJ events when the mixing engine lands.
6. `core/recap` and the first edition, in time for December.
