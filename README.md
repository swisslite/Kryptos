# Kryptos 🔐

**Encrypt anywhere. Talk over any channel.**

Kryptos is an end-to-end encryption app for **iOS and Android** that works *through* any
messenger or SMS. You encrypt a message inside Kryptos (or its keyboard), paste the resulting
blob into WhatsApp / Telegram / iMessage / Signal / SMS / anything, and your correspondent
decrypts it with Kryptos on their side. The plaintext never touches the messenger.

No accounts, no servers, no telemetry. Keys are generated on-device and never leave it. The app
has **no networking code at all** — the Android build ships without the `INTERNET` permission.

- 🌍 Download & docs: <https://datakeeper.pages.dev/kryptos>
- 📣 News: Telegram [@KryptosApp](https://t.me/KryptosApp)

> **Ciphertext compatibility:** the on-wire format changed in 2.1. Both sides must run 2.1 or
> newer to exchange messages.

## How it works

1. **Write & encrypt** — type in Kryptos or on its keyboard; one tap turns the text into code.
2. **Send it anywhere** — paste the code into any chat. To everyone else it looks like random noise.
3. **Decrypt on screen** — your contact's Kryptos reveals the text (on Android it can overlay the
   decryption live in any app; on iOS a copied message opens already decrypted).

## Encryption

The core is the **Signal Protocol**, provided by Signal's own official
[`libsignal`](https://github.com/signalapp/libsignal) — not a re-implementation:

- **PQXDH** — post-quantum initial key agreement (X3DH + Kyber).
- **Double Ratchet** — a fresh key for every message, forward secrecy, and self-healing if a key
  is ever compromised.

Using Signal's actual code is a hard project rule — no home-rolled cryptography. `libsignal` is
compiled from source and linked into each app; its round-trip is self-verified at runtime.

Also included, using vetted primitives / established libraries:

- **Password mode** — a shared passphrase (PBKDF2-HMAC-SHA256 → AES-256-GCM), the simplest way for
  two people to start talking securely.
- **Steganography** — hide an *already-encrypted* payload inside a photo (LSB), or disguise it as
  an innocent run of ordinary words (text stego for chats, EN + RU).
- **PGP/GPG mode** — asymmetric OpenPGP (ObjectivePGP on iOS, PGPainless / Bouncy Castle on Android,
  interoperable).

The two platforms are **wire-compatible**: messages, key strings, password blobs, hidden-in-words
text and stego photos cross-decrypt between iOS and Android, proven by unit tests that decode
vectors produced by the other platform.

## Repository layout

```
.
├── CipherCore/         Swift package: password mode, steganography, wire-token format (unit-tested)
├── Kryptos/            iOS app (SwiftUI): Signal chats, PGP, password, photo stego
├── KryptosKeyboard/    iOS keyboard extension (encrypt/decrypt in any app)
├── Kryptos.xcodeproj/  Xcode project
├── android/            Android app (Kotlin + Jetpack Compose), wire-compatible with iOS
├── ThirdParty/
│   └── ObjectivePGP/   OpenPGP for iOS (prebuilt xcframework, MIT)
├── patches/            the small patch Kryptos applies to libsignal
├── scripts/            setup-libsignal.sh — fetch + patch + build libsignal
├── build-ipa.sh        build the unsigned iOS .ipa
└── BUILDING.md         full build instructions
```

`libsignal` itself is **not vendored** in this repo (it is AGPL-3.0 and its build artifacts are
hundreds of MB). It is fetched and patched from its real upstream at the exact pinned version by
`scripts/setup-libsignal.sh`. See [BUILDING.md](BUILDING.md).

## Building

Full instructions are in **[BUILDING.md](BUILDING.md)**. In short:

```bash
# 1) fetch + patch + build the Signal library for the platforms you want
scripts/setup-libsignal.sh --ios --android

# 2a) iOS  — unsigned .ipa (sign afterwards with your own certificate, e.g. Feather)
./build-ipa.sh

# 2b) Android — APK (unsigned unless you provide android/keystore.properties)
cd android && ./gradlew :app:assembleRelease
```

- **iOS:** minimum iOS 17; native Liquid Glass on iOS 26+. The `.ipa` is unsigned — sign it with
  your own certificate.
- **Android:** minimum Android 8.0 (API 26). Self-signed sideload; enable "install unknown apps".

## Security

Highlights: per-device keys in the Keychain / Android Keystore (StrongBox-preferred), app lock
(biometrics), app-switcher privacy shield, clipboard auto-clear, screenshot blocking, anti-tapjacking
and task-hijacking defences, device-integrity warnings, and a panic PIN (Android) that wipes all
keys and data. Details in [BUILDING.md](BUILDING.md) and the in-app Settings → Security screen.

To report a vulnerability, see [SECURITY.md](SECURITY.md).

## License

Kryptos is licensed under the **GNU Affero General Public License v3.0** — see [LICENSE](LICENSE).

This is required by `libsignal`, which is AGPL-3.0: any app that builds on it and is distributed to
others must make its source available under the same license. ObjectivePGP is used under its own
permissive (BSD-style) license.
