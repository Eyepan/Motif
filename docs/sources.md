# Music sources

Motif downloads only audio the user is allowed to keep, and prefers lossless formats.

| Source | Lossless | Status | Notes |
| --- | --- | --- | --- |
| Local files (Files app / Finder) | FLAC, WAV, ALAC, AIFF | Implemented | Copied into the app container so playback works offline. |
| Bandcamp purchases | FLAC, WAV, ALAC | Via local import | Bandcamp has no public download API. Download the purchase as FLAC, unzip, import. |
| Internet Archive | FLAC, WAV (varies per item) | Implemented | Public search and metadata APIs. Each item carries its own license; Motif stores the license URL with the track. |
| Jamendo | FLAC where offered | Implemented, needs a client id | Creative Commons catalog. Requires a free API client id from developer.jamendo.com, set as `JAMENDO_CLIENT_ID` in the app's Info.plist. |
| Free Music Archive | MP3 mostly | Not planned | Public API was retired; use local import for files downloaded from the site. |
| YouTube Music | No | Not supported | No lossless audio, and downloading from it violates its terms of service. |

## Adding a source

Implement `MusicSource` in `apps/apple/Sources/MotifKit/Import/`. A source searches a catalog and resolves a result to a downloadable file URL plus license metadata. Downloading, metadata extraction and library insertion are shared by `ImportService`, so a source only talks to its catalog.
