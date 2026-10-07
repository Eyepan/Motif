# Motif for Android

Kotlin + Jetpack Compose, Media3 (ExoPlayer + MediaSessionService) for background playback, and the shared Rust DSP core (`core/dsp`) through a small JNI crate (`dsp-jni`). The library database is created from `schemas/library.sql`, packaged as an asset. No UI code is shared with the Apple app; the look follows the approved design (dark, neon green accent, BPM / key / format in the library, "Mix into next" on Now Playing).

## Get the APK

- **Latest main:** the `android-latest` prerelease on GitHub Releases. Open it on the phone and tap the `.apk`.
- **Any PR or run:** Actions → Android → the run → Artifacts → `motif-android-<run>` (a zip holding the APK).

Builds are release-mode (R8) and signed with the committed development key (`app/motif-dev.keystore`), so each new APK installs over the previous one. Android will ask you to allow installs from your browser or file manager the first time.

## Build locally

```sh
cd apps/android
scripts/build-dsp.sh          # needs rustup + the Android NDK; optional, analysis is off without it
./gradlew assembleRelease     # or installDebug with a device attached
```

## Layout

- `app/src/main/java/app/motif/`
  - `data/`: `Track`, `LibraryStore` (SQLite from the shared schema), `LibraryFilter` (`bpm:` / `key:` search)
  - `importer/`: copy files in untouched, read tags, decode with MediaCodec and analyse with the DSP core
  - `playback/`: `PlaybackEngine` (gapless ExoPlayer; "Mix into next" blends on a second player with tempo match and the DSP core's equal-power curve), `PlaybackService` (MediaSession, notification, headset/Bluetooth)
  - `ui/`: Compose screens: Library, Search, Now Playing, Add Music; Crates and DJ Mix are placeholders for now
- `dsp-jni/`: JNI entry points over `core/dsp`, built per ABI by `scripts/build-dsp.sh`
