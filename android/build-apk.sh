#!/bin/zsh
set -e
cd "$(dirname "$0")"

./gradlew --no-daemon :app:testReleaseUnitTest :app:assembleRelease

mkdir -p ../dist
cp app/build/outputs/apk/release/app-release.apk ../dist/Kryptos.apk
echo "OK: dist/Kryptos.apk"
