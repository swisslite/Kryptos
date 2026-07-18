#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "▸ Running crypto unit tests…"
( cd CipherCore && swift test )

echo "▸ Building Kryptos for device (arm64, Release, unsigned)…"
rm -rf build dist/Payload
rm -f dist/Kryptos.ipa
mkdir -p dist
xcodebuild -project Kryptos.xcodeproj -scheme Kryptos \
  -sdk iphoneos -configuration Release \
  -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" CODE_SIGN_ENTITLEMENTS="" \
  build

APP="build/Build/Products/Release-iphoneos/Kryptos.app"

echo "▸ Stripping symbol tables…"
xcrun strip -rSTx "$APP/Kryptos"
xcrun strip -rSTx "$APP/PlugIns/KryptosKeyboard.appex/KryptosKeyboard"

echo "▸ Packaging IPA…"
mkdir -p dist/Payload
cp -R "$APP" dist/Payload/
( cd dist && zip -qr Kryptos.ipa Payload && rm -rf Payload )

echo "✓ Done → $ROOT/dist/Kryptos.ipa"
echo "  Sign in Feather/AltStore/Sideloadly with a certificate whose profile grants App Groups"
echo "  (most do). The app auto-detects the granted group — no manual group name needed."
ls -lh dist/Kryptos.ipa
