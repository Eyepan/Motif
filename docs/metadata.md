# Music metadata and classification

Status: agreed with Pan 2026-10-07. Schema v3 and the genre list are in place; the tag reader, MusicBrainz matching and UI are next (see Implementation order). Covers what Motif knows about a track, where each fact comes from, how genre and similar labels are classified, and the schema changes that follow. Companion to [analytics.md](analytics.md), which records events against this metadata.

## Today

`schemas/library.sql` (v2) stores title, artist, album, format facts, source, BPM, Camelot key, loudness and a 128-byte waveform. `ImportService` reads only title, artist and album from `commonMetadata`. There is no genre, album artist, track number, year, artwork, or any record of where a value came from, so a user edit and a file tag look the same.

## Principle: three layers, one precedence rule

Every fact about a track belongs to one of three layers.

1. **Descriptive** (what the release says it is): title, artists, album, album artist, track/disc number, release date, genre, composer, label, ISRC, artwork. Comes from file tags, the source catalog, or the user.
2. **Acoustic** (what the audio measurably is): BPM, beatgrid, key, loudness, waveform, and later energy. Comes only from `core/dsp`, never from tags, because DJ features need numbers we trust. A `TBPM`/`initialkey` tag is kept as a raw tag but never shown as the analyzed value.
3. **Classification** (labels for browsing and recaps): genre family, mood, vocal/instrumental. Derived from layers 1 and 2 by shared rules.

Each stored value carries its **provenance**: `user`, `tag`, `musicbrainz`, `dsp`, or `inferred`. Precedence is fixed: **user > tag > musicbrainz > inferred**. Re-importing or re-analyzing never overwrites a user value, and the UI can say "from file tags" or "you edited this".

Raw tags are kept verbatim in their own table, so a classifier rule written next year can run over tags we read this year without re-reading files (same idea as the event log).

## Where facts come from, per source

| Source | Tags in file | Catalog metadata |
| --- | --- | --- |
| Local files | Vorbis comments (FLAC/OGG), ID3v2 (MP3/AIFF/WAV), MP4 atoms (ALAC/AAC) | none |
| Bandcamp | Usually good title/artist/album/track no./art; genre often missing | page tags are not in the download, so none |
| Internet Archive | Often sparse or missing | item `subject`, `creator`, `date`, `licenseurl` from the metadata API |
| Jamendo | Usually good | `musicinfo`: genre tags, `vocalinstrumental`, `speed`, instrument tags |

Jamendo and Internet Archive API metadata is **not** used for descriptive fields; we keep only `source_ref` and `license_url` from them (see Catalog source below). The table above is just what each source's files usually carry. Reading tags needs more than `commonMetadata`: AVFoundation exposes Vorbis and ID3 frames through their own key spaces on Apple; Android's `MediaMetadataRetriever` is thin, so the Android importer needs a tag reader (TagLib via JNI, or a small Rust reader in core so both platforms parse identically; see Decision 5).

## Catalog source: MusicBrainz only (Pan, 2026-10-07)

One external catalog, so two databases never disagree about the same field. Compared:

| | MusicBrainz | Last.fm | Discogs |
| --- | --- | --- | --- |
| Licence | Core data CC0; tags/genres are "supplementary" data under CC BY-NC-SA | API data, commercial use needs a separate agreement with Last.fm | CC0 data dumps; API images restricted |
| Stable ids | MBIDs for artist, recording, release, release group, work, label, genre | Uses MBIDs where known, otherwise names | Own ids, release-centric |
| Genre | Curated genre list with "subgenre of" links, voted per release/recording | Rich but free-form user tags ("seen live", "favourites") | Fixed genre + "style" pair, very good for electronic |
| Identify a file | AcoustID fingerprint → recording MBID, and Picard-tagged files already carry MBIDs | No fingerprinting | No fingerprinting |
| Offline/self-host | Full database dumps, can mirror | No | Dumps |

**Pick: MusicBrainz.** It is the only one with stable ids for every entity, audio fingerprint lookup (AcoustID), an open licence for core data, and a genre list with a hierarchy we can use as our taxonomy instead of inventing one. Last.fm's tags are the best crowd signal for genre but are free text under a commercial-use restriction; Discogs is great for electronic styles but is release-centric and has no fingerprinting.

How it is used:

- Matching order at import: MBIDs already in the file's tags → AcoustID fingerprint → text search on artist/title/album/duration. A match stores `mb_recording_id`, `mb_release_id` and artist MBIDs; nothing else is overwritten where the file has a value.
- Lookups go through Motif's sync service as a caching proxy, which keeps us within MusicBrainz's 1 request/second per client limit with a proper User-Agent, and means the same recording is fetched once for all users. Offline, import works without it and lookups queue for later.
- The MBID becomes the strongest cross-device identity: analytics `track_key` grouping uses `mb_recording_id` when present, then `content_hash`, then the normalized artist/title/duration fallback.
- Licence caveat: genres come from MusicBrainz tags, which are in the non-commercial supplementary set for dumps. Fine while Motif is non-commercial; if it ever charges, check with MetaBrainz (they offer commercial supporter plans).

## Genre

Tags are free text and messy ("Hip-Hop", "hiphop", "Rap/Hip Hop", "Electronic; House", "Other"). Three things are stored:

- **Raw genre strings** exactly as tagged, split on `;`, `/`, `,` and null separators, many per track.
- **Motif genre**: a MusicBrainz genre, stored by a stable Motif slug id (`deep-house`) so a MusicBrainz rename never orphans rows; the MBID sits beside it. `schemas/genres.json` is a versioned snapshot of the MusicBrainz genre list and its subgenre links, plus our alias table for messy tag strings ("hiphop", "Rap/Hip Hop") and a rollup of each genre to one of about 16 families (Electronic, Hip-Hop, Rock, Pop, Jazz, Classical, Folk, Soul/R&B, Metal, Ambient, World, Reggae, Latin, Country, Soundtrack, Experimental) for browse and recap cards. Genres come from file tags first, then from the matched MusicBrainz recording or release. Unmapped strings stay visible as raw tags and are listed for adding later.
- **User genres**, which win over both.

Browse, search (`genre:house`) and recaps use the Motif genre, so "your top genre" counts "Deep House" and "deep-house" as one, and rolls up to Electronic for a family card. The mapping lives in shared code, so Apple and Android classify identically.

## Artists

"A feat. B", "A & B", "A x B" are one string in tags but two artists to a listener. Store artist **credits**, not just a string: `track_artists(track_id, artist_id, role, position)` with roles `primary`, `featured`, `remixer`, `composer`, plus `album_artist`. The display string is kept as tagged. Splitting uses the multi-value tag when present (Vorbis `ARTIST` repeated, `ARTISTS`), else conservative separator rules (`feat.`, `ft.`, `featuring`; `&` only when both halves are known artists elsewhere in the library, so "Simon & Garfunkel" stays one).

This matters for the recap: plays credit every primary and featured artist, so a feature counts toward both.

## Acoustic additions

Cheap, deterministic, no ML, all in `core/dsp` at import:

- **Beatgrid** (first downbeat offset + BPM), already listed on the Import screen.
- **Energy** 0–1 from RMS, onset density and spectral brightness: powers "low/medium/high energy" filters, DJ suggestions and an "energy of your year" curve.
- **Intro/outro length** from the waveform: lets Mix into next choose where to start a blend.

Every analysis stores `analyzer_version`, so a better key detector can re-run on the whole library and the history log knows which version produced a value.

## Optional, later: classifying from audio

For untagged files (common from Internet Archive), a small on-device model could predict genre family, mood and vocal/instrumental from audio. Costs: a 5–20 MB model, extra import time and memory, and accuracy that is fine for families but poor for subgenres. Results would be stored with provenance `inferred` and the lowest precedence. Never cloud-based.

MusicBrainz lookup itself is covered under Catalog source; whether it runs by default is Decision 3.

## Schema (library v3)

Implemented in [`schemas/library.sql`](../schemas/library.sql), with the upgrade step in [`schemas/migrations/3.sql`](../schemas/migrations/3.sql) and the exchange format in [`schemas/track.schema.json`](../schemas/track.schema.json). `tracks` holds resolved display values; `track_tags` keeps every raw tag and MusicBrainz field verbatim; `track_overrides` holds user edits; `artists` and `track_artists` hold credits with roles; `track_genres` links tracks to genres in [`schemas/genres.json`](../schemas/genres.json) with provenance.

`tools/check_migrations.py` and `tools/check_genres.py` run in CI. `tools/sync_genres.py` fills MusicBrainz ids into `genres.json` and reports seed names MusicBrainz doesn't know; it needs network access to musicbrainz.org.

## Shared code

`core/dsp/src/meta.rs` owns everything both platforms must agree on. Android calls it through `dsp-jni`; Apple will use the C ABI. Today it strips site names appended to tags (a suffix like " - SiteName" shared by two or more of a track's fields, or a domain) and splits artist credits on commas, semicolons, "feat."/"ft."/"featuring", and "&" when inside a comma list or when both names already appear alone. Planned:

- text normalization (case, Unicode NFKC, diacritics, "The " prefix, feat. stripping), used for `norm_name`, search and the analytics fallback match on artist/title/duration;
- artist credit splitting;
- raw genre → Motif genre mapping from `schemas/genres.json`;
- optionally, tag parsing itself (Decision 5).

Each of these carries a version number, recorded with the values it produced.

## Consistency with the analytics design

- `track_added` snapshots include the new fields, credited artists and genres, so a recap can say "your top genre" for deleted tracks too.
- `track_updated` is written for user overrides (changed fields only), and for re-classification when the genre mapping version changes.
- `track_analyzed` gains `energy`, `beat_offset_ms` and keeps `analyzer_version`.
- Recap "top artist" counts via credits; "top genre" uses the Motif genre as of the play, read from the log, so later edits don't rewrite past years. (Alternative: classify at recap time with today's mapping. Proposed: as of the play, since that is what the user was listening to then.)
- The `content_hash` column the analytics proposal needs lands in this same v3 migration.
- Search event `filters` gains `genre` and `energy`.

## Decisions (Pan, 2026-10-07)

0. **Catalog source**: MusicBrainz only, to prevent collisions between catalogs.
1. **Genre model**: raw tags mapped to MusicBrainz genres with a family rollup; the user can override.
2. **Audio-based classification for untagged files**: on-device, opt-in.
3. **MusicBrainz lookup at import**: through Motif's caching proxy.
4. **User edits**: stored in the library only; files are never rewritten.
5. **Tag reading**: one shared Rust tag reader in core, used by both apps.
6. **Artist credits**: split into credited artists with roles.

## Implementation order

1. `schemas/genres.json` v1 and library v3 migration on both apps, plus `track.schema.json`.
2. `core/meta`: normalization, artist splitting, genre mapping, with tests over a fixture of real-world messy tags.
3. Tag reader (per Decision 5), then MusicBrainz matching (MBIDs in tags, AcoustID, text search) and the caching proxy in the sync service.
4. Library UI: genre column/filter, album artist grouping, artwork, edit sheet.
5. `core/dsp`: beatgrid, energy, intro/outro; re-analysis by `analyzer_version`.
6. History events updated per the section above.
