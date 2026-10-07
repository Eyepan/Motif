# Music sources

Motif downloads only audio the user is allowed to keep, and prefers lossless formats.

| Source | Lossless | Status | Notes |
| --- | --- | --- | --- |
| Local files (Files app / Finder) | FLAC, WAV, ALAC, AIFF | Implemented | Copied into the app container so playback works offline. |
| Bandcamp purchases | FLAC, WAV, ALAC | Via local import | Bandcamp has no public download API. Download the purchase as FLAC, unzip, import. |
| Internet Archive | FLAC, WAV (varies per item) | Implemented (Apple, Android) | Public search and metadata APIs. Each item carries its own license; Motif stores the license URL with the track. |
| Jamendo | FLAC where offered | Implemented (Apple, Android), needs a client id | Creative Commons catalog. Requires a free API client id from developer.jamendo.com: `JAMENDO_CLIENT_ID` in the Apple app's Info.plist; on Android the `JAMENDO_CLIENT_ID` build environment variable (a CI secret), or pasted into Discover. |
| Free Music Archive | MP3 mostly | Not possible | No public API since the 2019 move to Tribe of Noise, so Motif can't search or download it without scraping the site. |
| YouTube Music | No | Not supported | No lossless audio, and downloading from it violates its terms of service. |

## Discover

Discover searches a catalog, previews a result by streaming it, and downloads it with one tap straight into the library. On Android, downloaded files go through the same import as local files (bit-perfect copy, tags, on-device analysis); the catalog's title, artist and album fill any tag the file lacks, and the catalog's cover becomes the track's art. Downloads run in the app process two at a time and show in Add Music as well as on the result.

## Buying inside Motif

Stores that sell lossless downloads only open their catalogs to partners under contract; none offers a self-serve API.

- **Qobuz**: the REST API is partner-only (apply at api@qobuz.com for an app id and secret, under the Qobuz API Terms of Use). Partners can sign a user into their Qobuz account, search, and download the user's purchases, which is the realistic first step: buy on qobuz.com, and Motif downloads the FLAC files from the user's purchase history. Buying inside the app would need Qobuz to agree to it as well.
- **7digital** (owned by Songtradr since 2023): sells its catalog to businesses through a licensed API. It needs a commercial agreement, per-territory label licensing, and Motif acting as the merchant (payments, tax, sales reporting).
- **Bandcamp**: no public API for fans' purchases; download as FLAC on the site and import.
- **App stores**: buying digital music inside an app distributed through Google Play or the App Store has to use their billing (15 to 30%), unless the purchase happens on the store's own website.

## Adding a source

Implement `MusicSource` in `apps/apple/Sources/MotifKit/Import/` and `apps/android/app/src/main/java/app/motif/sources/`. A source searches a catalog and resolves a result to a downloadable file URL plus license metadata. Downloading, metadata extraction and library insertion are shared by `ImportService`, so a source only talks to its catalog.
