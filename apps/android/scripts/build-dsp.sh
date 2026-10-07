#!/usr/bin/env bash
# Builds the DSP core's JNI library (dsp-jni) for the app's ABIs into
# app/src/main/jniLibs, where Gradle packages it. Needs rustup and the Android
# NDK (ANDROID_NDK_HOME, or the newest NDK under ANDROID_HOME/ndk).
# Without it the app still builds and runs; import just skips analysis.
set -euo pipefail
cd "$(dirname "$0")/.."

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" ]]; then
  NDK="$(ls -d "${ANDROID_HOME:?set ANDROID_NDK_HOME or ANDROID_HOME}"/ndk/* | sort -V | tail -1)"
fi
HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
API=26  # minSdk

# ABI  Rust target  clang prefix
ABIS=(
  "arm64-v8a aarch64-linux-android aarch64-linux-android"
  "x86_64 x86_64-linux-android x86_64-linux-android"
)

for entry in "${ABIS[@]}"; do
  read -r abi target clang <<<"$entry"
  rustup target add "$target"
  env_target="$(echo "$target" | tr '[:lower:]-' '[:upper:]_')"
  export "CARGO_TARGET_${env_target}_LINKER=$BIN/${clang}${API}-clang"
  # 16 KB page alignment, required on Android 15+ devices with 16 KB pages.
  export "CARGO_TARGET_${env_target}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384"
  cargo build --manifest-path dsp-jni/Cargo.toml --release --target "$target"
  mkdir -p "app/src/main/jniLibs/$abi"
  cp "dsp-jni/target/$target/release/libmotif_dsp_jni.so" "app/src/main/jniLibs/$abi/"
done
echo "Built app/src/main/jniLibs/{arm64-v8a,x86_64}/libmotif_dsp_jni.so"
