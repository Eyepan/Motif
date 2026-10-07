# Motif

A native-first music player and DJ platform. Each platform gets a fully native UI; algorithms, schemas, protocols and DSP are shared.

## Layout

```
apps/
  apple/      SwiftUI app for iOS + macOS (one multiplatform target) and MotifKit, its Swift package
  android/    Kotlin / Jetpack Compose app (not started)
core/
  dsp/        Portable DSP core in Rust with a C ABI: loudness, tempo, key, beat grids, crossfades, beat-aligned blend planning, deck sync, tag cleanup
server/       Sync service in Rust (axum + Postgres) for accounts and listening history; deploys to Vercel
schemas/      Shared library schema (SQLite) and its migrations, the track JSON schema, the genre list, the sync API contract
tools/        Schema checks and the MusicBrainz genre sync
docs/         Design notes: music sources, metadata, listening history, server and sync
```

## Principles

- Instant interaction and low memory: no web views, no cross-platform UI layer.
- Lossless first: FLAC and WAV play bit-for-bit through AVAudioEngine on Apple platforms.
- Offline-first: the library is a local SQLite database; audio files live in the app container. The server only adds accounts and sync; nothing needs it to work.
- Native media integration: background audio, lock screen / Control Center, remote commands.
- Share `core/` and `schemas/`, never UI.

The UI follows the approved design: dark grounds, neon green accent `#C6F432`, SF Mono for BPM, key and format, Camelot key colours.

## Building

DSP core (any platform):

```sh
cd core/dsp && cargo test
```

Apple (macOS with Xcode 16+ and rustup). The app links the DSP core, so build it first:

```sh
core/dsp/scripts/build-apple.sh   # writes apps/apple/Frameworks/MotifDSP.xcframework
brew install xcodegen
cd apps/apple && xcodegen && open Motif.xcodeproj
swift test                        # MotifKit unit tests
```

Rerun the script after changing anything in `core/dsp`.

Server (any platform with Rust and Postgres): see [server/README.md](server/README.md).

## Music sources

See [docs/sources.md](docs/sources.md). Motif imports only audio you are allowed to download: local files, Bandcamp purchases, and openly licensed catalogs such as the Internet Archive and Jamendo. Streaming services such as YouTube Music are not supported as download sources.
