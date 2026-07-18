#!/bin/bash
set -euo pipefail

LIBSIGNAL_TAG="v0.96.4"
LIBSIGNAL_REPO="https://github.com/signalapp/libsignal"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/ThirdParty/libsignal"
PATCH="$ROOT/patches/libsignal-v0.96.4-kryptos.patch"

if [ ! -d "$DEST/.git" ]; then
  echo "▸ Cloning libsignal $LIBSIGNAL_TAG …"
  git clone --depth 1 --branch "$LIBSIGNAL_TAG" "$LIBSIGNAL_REPO" "$DEST"
  echo "▸ Applying Kryptos patch …"
  ( cd "$DEST" && git apply "$PATCH" )
else
  echo "▸ libsignal already present at $DEST — skipping clone."
fi

build_ios=false
build_android=false
for arg in "$@"; do
  case "$arg" in
    --ios) build_ios=true ;;
    --android) build_android=true ;;
  esac
done

if $build_ios; then
  echo "▸ Building libsignal FFI static libs for iOS (device + simulator) …"
  ( cd "$DEST/swift" && CARGO_BUILD_TARGET=aarch64-apple-ios ./build_ffi.sh -r )
  ( cd "$DEST/swift" && CARGO_BUILD_TARGET=aarch64-apple-ios-sim ./build_ffi.sh -r )
  echo "  → $DEST/target/aarch64-apple-ios{,-sim}/release/libsignal_ffi.a"
fi

if $build_android; then
  echo "▸ Building libsignal JNI shared libs for Android …"
  ( cd "$DEST" && ./java/build_jni.sh android )
  echo "  → $DEST/java/android/src/main/jniLibs/<abi>/libsignal_jni.so"
fi

echo "✓ libsignal ready."
