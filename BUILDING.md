# Building Kryptos

Kryptos builds two apps from this repository: an **iOS** app (SwiftUI, built on macOS with Xcode)
and an **Android** app (Kotlin + Jetpack Compose). Both link Signal's `libsignal`, which is fetched
and built from source rather than vendored here.

## 1. Prerequisites

Common:

- **Git**
- **Rust** via [rustup](https://rustup.rs) — needed to build `libsignal` from source.
- **CMake**, the **Protocol Buffers compiler** (`protoc`) and **Clang** with `libclang`. `libsignal`
  compiles BoringSSL and generates protobuf code, so without these the build stops with raw compiler
  errors rather than a helpful message.
  - Debian / Ubuntu: `apt-get install cmake build-essential clang libclang-dev protobuf-compiler libprotobuf-dev`
  - macOS: the Xcode command-line tools, plus `brew install cmake protobuf`

For iOS:

- **macOS** with **Xcode** (command-line tools included). Deployment target iOS 17.
- Rust iOS targets: `rustup target add aarch64-apple-ios aarch64-apple-ios-sim`

For Android:

- **JDK 17+** and the **Android SDK**, plus **NDK `26.1.10909125`** (referenced by the Gradle build).
- The Gradle **wrapper** is included — use `./gradlew`, no system Gradle needed.
- Rust Android targets: `rustup target add aarch64-linux-android armv7-linux-androideabi`

## 2. Fetch and build libsignal

`libsignal` is AGPL-3.0 and is not committed to this repo. Fetch the exact pinned version
(**v0.96.4**), apply the small Kryptos patch, and build the native artifacts:

```bash
scripts/setup-libsignal.sh --ios --android
```

This clones `https://github.com/signalapp/libsignal` at `v0.96.4` into `ThirdParty/libsignal/`,
applies [`patches/libsignal-v0.96.4-kryptos.patch`](patches/libsignal-v0.96.4-kryptos.patch), and
produces:

- iOS: `ThirdParty/libsignal/target/aarch64-apple-ios{,-sim}/release/libsignal_ffi.a`
- Android: `ThirdParty/libsignal/java/android/src/main/jniLibs/<abi>/libsignal_jni.so`

The patch is two changes: an Android compile-compatibility cast in `ChatConnection.java`, and
removing the `swift-docc-plugin` dependency so the Swift package resolves offline. Nothing about the
cryptography is changed.

You can run the script without flags to only fetch + patch, then build later.

## 3. Build the iOS app

```bash
./build-ipa.sh
```

Runs the CipherCore unit tests, builds `Kryptos` for device (arm64, Release, **unsigned**), and
writes `dist/Kryptos.ipa`. To install it: with a certificate of your own (a `.p12` plus a
provisioning profile) open the file in **Feather**, **ESign** or **Scarlet**; without one, use
**AltStore** or **SideStore**, which sign with your ordinary Apple ID. Native Liquid Glass on
iOS 26+, a close visual fallback below.

Just the crypto engine:

```bash
cd CipherCore && swift test
```

## 4. Build the Android app

```bash
cd android
./gradlew :app:testReleaseUnitTest :app:assembleRelease
```

Output: `android/app/build/outputs/apk/release/app-release.apk`.

### Signing (optional)

Without a keystore the release APK is built **unsigned** (fine for source review, and F-Droid signs
with its own key). To produce a signed sideload APK:

1. Create a keystore (once):
   ```bash
   keytool -genkeypair -v -keystore android/kryptos.keystore \
     -alias kryptos -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Copy `android/keystore.properties.template` to `android/keystore.properties` and fill in your
   passwords. Both `keystore.properties` and `*.keystore` are gitignored — never commit them.

## 5. Reproducing the published builds

The binaries distributed at <https://datakeeper.pages.dev/kryptos> are built exactly this way:
`libsignal` at `v0.96.4` + the patch above, `versionName` **2.3** (`versionCode` 8). SHA-256
checksums of the current release are shown on that page.

## Android security hardening (reference)

- App lock (biometrics / device PIN) with auto-lock grace and an opaque app-switcher cover.
- Panic password that wipes all keys, chats, contacts and settings (destroys the Keystore key first,
  so an interrupted wipe leaves nothing decryptable).
- Screenshot blocking (`FLAG_SECURE`) for the app and the keyboard window.
- Clipboard auto-clear; clips flagged sensitive to stay out of clipboard history.
- Device-integrity warnings (root / emulator / debugger / hooking framework / re-signed APK).
- Anti-tapjacking (`filterTouchesWhenObscured`, `setHideOverlayWindows` on 12+) and task-hijacking
  defence (empty `taskAffinity`).
- Keystore hardening: StrongBox-preferred, `setUnlockedDeviceRequired`.
- No data extraction (`allowBackup=false`, empty `dataExtractionRules`), ARM MTE where supported,
  R8 shrink/obfuscate for release.
