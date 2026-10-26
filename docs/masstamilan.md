# MassTamilan provider

Motif implements an experimental native provider for **`www.masstamilan.dev` only**, in Swift and Kotlin. No documented public API was found. The provider reads the site's public HTML; it does not use `.bid`, `.com.se`, browser clearance cookies, authentication bypass, or DRM circumvention.

## Capabilities

- Song and album search uses the observed `GET /search?keyword=…` form. The first page's song and movie results are shown; other-language external sites, actor pages, and music-director pages are excluded. Pagination is not implemented; results are capped at 50.
- **Browse latest albums** reads the actual homepage's album cards. **Tracks** (Apple) or **Browse tracks** (Android) opens an album's track list with individual downloads. The album's download button imports its available tracks.
- Search cards provide titles, album names, composer credits, and artwork where supplied. Track pages supply singer credits, duration, album, and artwork. Metadata from the downloaded audio is still read and cleaned by the existing importers; catalog fields fill missing tags. Apple now supports per-file artist/album/artwork fallbacks, matching Android's release metadata behavior.
- Audio resolution reads the current page's `a.dlink` links in the recording table, preferring the advertised 320 kbps MP3 over 128 kbps. ZIP/RAR links are excluded. Filenames and CDN signatures are not reconstructed or guessed.
- Previews resolve a fresh link and stream through AVPlayer (Apple) or the existing Previewer/ExoPlayer (Android). Downloads use the existing ImportService/Downloads pipelines, retaining metadata extraction, artwork storage, library insertion, and DSP analysis. Imported files use the normal library playback engines.

## Configuration and device trial

No API key or provider-specific account is required. Select **MassTamilan** in Apple Discover or **Tamil** in the Android catalog picker. Search for a song/movie, or choose **Browse latest albums**. The source's full name appears on results when searching all catalogs.

Test native connectivity on the phone first. If a search shows an HTTP 403/browser-challenge error, the provider cannot access the site from that device/network at that time. Motif reports that error without solving the challenge or replaying another browser's session. Successful browser access, or successful requests on a Mac, does not guarantee phone access. Operator cooperation may be needed for reliable access.

For a successful search, try a preview, download one permitted track, then play it from the library. Check its title, singer, album, artwork, and analysis. Android APKs can be installed with the normal system installer or `adb install -r`; an unsigned IPA must first be signed with the user's own Apple credentials using their chosen sideloading tool. The build does not include an Apple distribution signature.

## Resolution, caching, and failures

Only stable page paths are stored as source references. Metadata lists are cached in memory for five minutes, with at most 16 entries per source instance. Signed downloader and final CDN URLs are never retained in that cache or persisted in the library. Every preview/download rereads the page; the website says its links expire after a day, and the capture indicates that they can be tied to the requesting network address.

HTML requests are restricted to the verified HTTPS host. HTML over 2 MiB or unrecognized markup is rejected. Import/download redirects are restricted to the captured HTTPS media hosts (`www.masstamilan.dev` and `masstamilan.download`), and responses must be `audio/mpeg`. Missing audio, unexpected responses, and denied requests fail visibly. Failed metadata loads are not cached. The provider does not fall back to unrelated websites or a browser session.

## Evidence and validation

The maintainer supplied a browser HAR containing successful homepage and search responses, a song page with an embedded album list, and a downloader redirect ending in an `audio/mpeg` response. Both platforms' fixtures are derived from those actual HTML responses. Scripts and head metadata were removed, signed links were replaced with deterministic fixture values, and audio payloads were excluded. The raw HAR is ignored by Git and is not distributed with the app.

The HTML parsers use the observed `.bots`, `.srg .srl`, `.a-i`, `article.post-content`, schema.org MusicAlbum/MusicRecording metadata, and recording-table download links. SwiftSoup and jsoup parse HTML without executing website scripts. Offline tests cover song/album discovery, metadata, entity decoding, the embedded album list, format selection/fallback, unavailable audio, malformed/challenge responses, foreign URLs, and fresh link resolution versus metadata caching.

Live probes from this Mac using the Swift provider succeeded for search, a standalone album page, fresh MP3 resolution, download, library insertion, metadata extraction, and waveform/loudness analysis. These used ordinary native requests, without browser cookies or challenge bypass. The Kotlin provider also passed opt-in live search, album browsing, and MP3 transfer tests on the Mac JVM. Physical Android/iPhone playback and downloads remain unverified until tested on a connected phone. Earlier curl requests received Cloudflare challenges: access varies by client and must not be assumed from the offline fixtures.

The optional network tests are disabled by default:

```sh
MOTIF_MASSTAMILAN_LIVE=1 MOTIF_MASSTAMILAN_LIVE_MEDIA=1 swift test --package-path apps/apple --filter MassTamilanLiveTests
MOTIF_MASSTAMILAN_LIVE=1 MOTIF_MASSTAMILAN_LIVE_MEDIA=1 apps/android/gradlew -p apps/android testDebugUnitTest --tests app.motif.MassTamilanLiveTest
```

The media flag allows downloading one test track; the temporary audio is deleted afterwards. Native builds require the normal DSP artifacts and platform toolchains. A website change or access challenge can fail these probes; do not turn such failures into a passing availability check.

## Rights and website terms

The repository maintainer has removed Motif's project-level restriction on adding this provider. That authorization supplies no third-party distribution license or permission to download a particular release. Public accessibility is not proof of those rights. Users must have permission to download and retain the music they import. Tracks have no asserted license URL unless an actual license can be verified; this provider does not assign Creative Commons licensing.

The site's [terms of use](https://www.masstamilan.dev/terms), inspected during investigation, require prior written consent for automated collection. Website access permission and rights to the music are separate matters. Repository-maintainer authorization does not supply the website operator's consent; review the applicable permissions before deployment or distribution. No authentication, access-control, or DRM bypass belongs in this integration.
