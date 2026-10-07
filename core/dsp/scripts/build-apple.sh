#!/usr/bin/env bash
# Builds MotifDSP.xcframework (iOS device, iOS simulator, macOS) from the Rust core into
# apps/apple/Frameworks, where the MotifKit package links it.
# Run on macOS with Xcode and rustup installed.
set -euo pipefail
cd "$(dirname "$0")/.."

TARGETS=(aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios aarch64-apple-darwin x86_64-apple-darwin)
rustup target add "${TARGETS[@]}"
for t in "${TARGETS[@]}"; do
  cargo build --release --target "$t"
done

OUT=target/apple
DEST=../../apps/apple/Frameworks/MotifDSP.xcframework
rm -rf "$OUT" "$DEST"
mkdir -p "$(dirname "$DEST")"
mkdir -p "$OUT/ios-sim" "$OUT/macos"
lipo -create target/aarch64-apple-ios-sim/release/libmotif_dsp.a target/x86_64-apple-ios/release/libmotif_dsp.a \
  -output "$OUT/ios-sim/libmotif_dsp.a"
lipo -create target/aarch64-apple-darwin/release/libmotif_dsp.a target/x86_64-apple-darwin/release/libmotif_dsp.a \
  -output "$OUT/macos/libmotif_dsp.a"

xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libmotif_dsp.a -headers include \
  -library "$OUT/ios-sim/libmotif_dsp.a" -headers include \
  -library "$OUT/macos/libmotif_dsp.a" -headers include \
  -output "$DEST"
echo "Built apps/apple/Frameworks/MotifDSP.xcframework"
