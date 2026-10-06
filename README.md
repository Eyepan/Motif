# Motif

A native-first music player and DJ platform. Each platform gets a fully native UI; algorithms, schemas, protocols and DSP are shared.

## Layout

```
apps/
  apple/      SwiftUI app for iOS + macOS (one multiplatform target) and MotifKit, its Swift package
  android/    Kotlin / Jetpack Compose app (not started)
core/
  dsp/        Portable DSP core in Rust with a C ABI: loudness, tempo estimation, crossfade curves
schemas/      Shared library schema (SQLite) and the track JSON schema used for sync/import
docs/         Design notes, including where music comes from
```

## Principles

- Instant interaction and low memory: no web views, no cross-platform UI layer.
- Lossless first: FLAC and WAV play bit-for-bit through AVAudioEngine on Apple platforms.
- Offline-first: the library is a local SQLite database; audio files live in the app container.
- Native media integration: background audio, lock screen / Control Center, remote commands.
- Share `core/` and `schemas/`, never UI.

## Building

Apple (macOS with Xcode 16+):

```sh
brew install xcodegen
cd apps/apple && xcodegen && open Motif.xcodeproj
swift test            # MotifKit unit tests
```

DSP core:

```sh
cd core/dsp && cargo test
./scripts/build-apple.sh   # builds MotifDSP.xcframework for iOS, simulator and macOS
```

## Music sources

See [docs/sources.md](docs/sources.md). Motif imports only audio you are allowed to download: local files, Bandcamp purchases, and openly licensed catalogs such as the Internet Archive and Jamendo. Streaming services such as YouTube Music are not supported as download sources.
