# MassTamilan

## Status

Integration is authorized by the repository maintainer, but is blocked and has not been implemented. Only `masstamilan.dev` is in scope. Do not substitute `.bid`, `.com.se`, or another site using the name, and do not treat their HTML as fixtures for this provider.

On 27 October 2026 (Asia/Kolkata), ordinary HTTPS requests to these public pages returned HTTP 403 with `cf-mitigated: challenge` and a Cloudflare challenge document:

- `https://www.masstamilan.dev/`
- `https://www.masstamilan.dev/search`
- `https://www.masstamilan.dev/ramayana-2026-songs` (linked from the homepage)

The web reader could display extracted homepage and album text. The album text advertises MP3 downloads, artist credits, duration, and links that expire after one day. That extracted text does not expose the search form parameters, actual DOM structure, or a verified audio response. It is insufficient evidence for HTML selectors or a native resolver. No album/search parser, media URL pattern, or download endpoint has been invented.

No provider has been registered in Discover, no source enum/schema has been changed, and no app playback or download behavior has been verified for this host. There is no provider-specific configuration yet. Existing providers remain unchanged.

## Native integration points

The existing provider contracts can support this integration without another provider framework:

- Apple: `MusicSource` in `apps/apple/Sources/MotifKit/Import/MusicSource.swift` searches for `SourceResult` values and resolves them through `downloads(for:)`. `AppModel.sources` registers catalogs; `DiscoverView` searches them and calls `ImportService.importDownload`. `Track.Source` and the shared track schema store provenance. Playback uses the imported library file.
- Android: `MusicSource` in `apps/android/app/src/main/java/app/motif/sources/MusicSource.kt` searches for results, resolves `SourceRelease` files, and supplies an optional preview URL. `MotifApp.sources` registers catalogs; `DiscoverScreen` handles search and previews. `Downloads` feeds files, metadata, and artwork into `Importer`, which retains the existing metadata and analysis pipeline. Playback uses the imported library file; `Previewer` streams catalog previews.
- Internet Archive already represents multi-track releases within these contracts. Album track browsing will need a small extension to the existing source contract and Discover views on both platforms; there is no shared catalog album-browsing hook today.
- Android already carries per-file artist and album fallbacks in `SourceDownload`. Apple's `SourceDownload` currently carries only a per-file title, so parity requires optional artist/album fallbacks before importing albums whose tracks have different singers and missing embedded tags.

## Evidence needed to implement safely

Continue once ordinary public requests can retrieve the real search and album HTML, or representative HTML saved from normal authorized access is available for offline parser development. Saved fixtures alone will not verify live playback or downloads.

Verify the search form, result links, album track lists, artist/artwork fields, and the complete public media resolution flow before writing their parsers. Keep narrowly scoped, representative HTML fixtures with source URL and capture date; remove scripts, advertising, and expiring credentials. Cover malformed pages, empty results, unavailable tracks, HTTP errors, challenge responses, and unsafe URLs in native tests.

Cache bounded catalog metadata for a short time. Resolve expiring links at preview/download time; do not persist them in the library or permanently cache them. Preserve stable page/track references as provenance, and pass downloaded files through the existing importers and analyzers. Report HTTP 403/challenge responses as provider failures; do not solve challenges, replay clearance cookies, or route around access controls.

Verify parsing and resolution on both platforms, then test imported playback and downloads on Android, iOS, and macOS before claiming support. The local environment currently has Xcode/Swift but no Java runtime or Android SDK; Apple's generated `MotifDSP.xcframework` is also absent. No native builds or provider tests have been run for this documentation-only change.

## Rights and website terms

The maintainer's authorization changes Motif's project policy; it supplies no third-party distribution license. Public links are not proof of download or redistribution rights. Leave license metadata unset when no verified license is provided, and do not label this catalog as music users are universally free to keep.

The site's [terms of use](https://www.masstamilan.dev/terms), inspected during this investigation, require prior written consent for automated collection. Website access permission and rights to the music are separate matters. Review the applicable permission before enabling automated access; repository-maintainer authorization does not supply the website operator's consent. No authentication, access-control, or DRM bypass belongs in this integration.
