# Crates

Status: built on Apple (iOS and macOS) and Android. Crates are written to the history log on each device; they reach other devices once the apps upload and pull that log (docs/server.md).

A crate is a named set of tracks a DJ pulls from for a gig or a mood. Unlike a playlist it has no fixed running order: the crate view sorts by when a track was added, by BPM or by key.

## Where crates live

Crates have no table of their own. Every edit is a `crate_changed` event in the history log (`schemas/history.sql`, `history.sqlite` beside the library), and each device rebuilds its crates by replaying those events. This is decision 3 in docs/server.md: library edits, playlists and crates sync through the same log as listening history, so the server needs no crate endpoints and an offline device never loses an edit.

Payload (`schemas/events/crate_changed.v1.schema.json`), carrying only what changed:

| Field | Meaning |
| --- | --- |
| `crate_id` | UUID minted (lowercase) by the device that made the crate |
| `name` | creates the crate, renames it, or brings a deleted one back empty |
| `deleted` | `true` deletes the crate and empties it |
| `added`, `removed` | track keys appended to or taken out of the crate |

A change that touches more than 200 tracks is split into several events, so each payload stays under the server's 16 KiB limit.

## Track identity

Track ids are per install, so a crate made on the Mac can't name the iPhone's copy of a song by id. Crates hold **track keys** instead: the lowercase hex SHA-256 of the audio file, the `track_key` from docs/analytics.md. It is stored in the library's `content_hash` column and computed the first time a track goes into a crate. The same file imported on two devices has the same key, so the crate finds it on both. A key whose file isn't on this device stays in the crate and is counted as "on another device".

## Rebuilding crates

Both apps run the same fold, checked against the shared fixture `schemas/fixtures/crates.json` by `CrateTests` (Swift) and `CratesTest` (Kotlin):

1. Sort `crate_changed` v1 events by id. Ids are UUIDv7, so this is time order across devices, and the result doesn't depend on the order events arrived in. Duplicate ids count once.
2. Skip other types, other payload versions, and payloads without a `crate_id`.
3. For each event, in this order: a non-blank `name` (trimmed) names the crate and, if it was deleted, brings it back empty; `deleted` hides and empties it; `added` keys not already in the crate are appended; `removed` keys are taken out.
4. A crate shows when it has a name and isn't deleted, in the order crates first appear in the log.

The latest event wins for each crate's name and for each track's membership. Membership edits that sort before the crate's creation (another device's clock was behind) are kept, not dropped.

Each device's event ids keep increasing even if its clock steps back, so a device's own edits always replay in the order they were made.

## Not built yet

- **Upload and pull.** The apps don't sign in or sync the history log yet. Crate events are written with `synced = 0` and go up with the first upload; pulled events are inserted with `insert` and the crates re-fold.
- **Drag and drop** of songs onto a crate in the Mac sidebar. Today it's the Add to Crate menu.
- **Crate context on plays** (`context: "crate"`, `context_ref` in docs/analytics.md), once `play` events are written.
