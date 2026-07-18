# Kryptos 🔐

**Encrypt anywhere. Talk over any channel.**

Kryptos is an end-to-end encryption app for **iOS and Android** that works *through* any messenger
or SMS. You encrypt a message inside Kryptos (or on its keyboard), paste the resulting code into
WhatsApp / Telegram / iMessage / Signal / VK / SMS / anything, and your correspondent decrypts it
with Kryptos on their side. The messenger only ever carries what looks like random noise — the
plaintext never touches it.

No accounts. No phone number. No servers. No telemetry. The app has **no networking code at all**
(the Android build ships without the `INTERNET` permission), and keys are generated on-device and
never leave it.

- 🌍 Website & docs: <https://datakeeper.pages.dev/kryptos>
- 📣 News: Telegram [@KryptosApp](https://t.me/KryptosApp)

## Download

Ready-to-install builds are attached to each [**GitHub Release**](../../releases) and mirrored on
the [website](https://datakeeper.pages.dev/kryptos):

- **Android** — `Kryptos.apk`, self-signed sideload. Enable "install unknown apps", minimum
  Android 8.0.
- **iOS** — `Kryptos.ipa`, **unsigned** — sign it with your own certificate (Feather / AltStore /
  Sideloadly), minimum iOS 17.

SHA-256 checksums are published with every release and on the website. Prefer to build it yourself,
or want it on F-Droid? See [Building](#building).

## What makes Kryptos different

Most secure messengers (Signal, Session, Threema…) are **their own network** — everyone has to be
on the same app for it to work. Classic PGP tools, on the other hand, wrap messages in an obvious
`-----BEGIN PGP MESSAGE-----` block that screams "this person is hiding something." Kryptos is built
around a different idea:

- **It rides on top of the channels you already use.** There is no Kryptos network. You keep using
  WhatsApp, Telegram, iMessage, Discord, email or plain SMS — Kryptos just turns your text into a
  blob before it goes out and back into text on the other end. Only the two of you need Kryptos;
  everyone and everything in between sees ciphertext.

- **The ciphertext looks like pure random noise — no tell-tale markers.** Since version 2.1 there
  are no recognizable prefixes at all. Each message is whitened with AES-256-CTR keyed by HKDF-SHA256
  over the pair's fingerprints plus a fresh random salt, so the output is indistinguishable from
  random, **hides even the message type, and looks completely different every time** — even if you
  send the same text twice. Inside that envelope is the real Signal ciphertext.

- **Real Signal Protocol, not home-made crypto.** Kryptos links Signal's own official
  [`libsignal`](https://github.com/signalapp/libsignal) — **PQXDH** (post-quantum X3DH + Kyber) for
  the initial handshake and the **Double Ratchet** for the conversation: a brand-new key for every
  single message, forward secrecy, and self-healing if a key is ever exposed. No re-implementations,
  no rolled-your-own primitives.

- **Optional length masking.** Turn it on and every ciphertext is padded up to fixed size buckets
  (64 / 128 / 256 …), so its length no longer betrays how long your message was. The format is
  self-describing, so the recipient strips the padding regardless of their own settings.

- **It reads messages right where they are.** On **Android**, Kryptos can recognize its messages in
  any app and lay the decrypted text **live on top of the ciphertext as you scroll the chat** — only
  for your contacts, and never while the app is locked. On **iOS**, a copied message opens already
  decrypted. Messages are found by the *shape* of the token, so stray timestamps, ticks or a sender
  name glued on by the messenger don't break detection.

- **A keyboard that does the crypto in place.** The bundled keyboard (iOS extension / Android IME)
  encrypts the field or decrypts the clipboard **inside any app**, so you never switch back and
  forth.

- **Two ways to hide a message in plain sight (steganography).** Bury an already-encrypted payload
  in the low bits of an ordinary **photo**, or disguise it as an innocent-looking run of **real,
  grammatical words/sentences** (EN + RU) — so even the fact that a message exists can be hidden.

## Three ways to encrypt

| Mode | Key exchange | Best for |
|------|--------------|----------|
| **Signal chat** | one-time key swap (QR or text), then automatic | ongoing private conversations with a contact |
| **Password (Quick)** | none — just a shared passphrase | a quick secret with someone new (PBKDF2-HMAC-SHA256, 210k iterations → AES-256-GCM) |
| **PGP** | exchange public keys | interop with existing OpenPGP setups (sign + encrypt) |

## How it works

1. **Write & encrypt** — type in Kryptos or on its keyboard; one tap turns the text into code.
2. **Send it anywhere** — paste the code into any chat. To everyone else it's just random characters.
3. **Decrypt on screen** — your contact's Kryptos reveals the real text (live overlay on Android, or
   one tap / auto on a copied message on iOS).

## Under the hood

- **Signal Protocol** via official `libsignal` v0.96.4 (PQXDH + Double Ratchet, Kyber prekeys,
  one-time prekeys, rotation) — built from source and linked into each app, round-trip self-verified
  at runtime.
- **Wire format v2** — `salt ‖ AES-256-CTR(HKDF-SHA256(pairKey, salt) → key/IV, header ‖ body)`,
  base64url, no prefix; optional DEFLATE compression and fixed-bucket padding, all negotiated by a
  single header byte.
- **Password mode** — PBKDF2-HMAC-SHA256 (210 000 iterations) → AES-256-GCM, per-message random salt,
  DEFLATE where it helps.
- **Wire-compatible across platforms** — messages, key strings, password blobs, hidden-in-words text
  and stego photos cross-decrypt between iOS and Android, proven by unit tests that decode vectors
  produced by the other platform.
- **Smart offline keyboard** — context-aware suggestions and autocorrect from on-device dictionaries
  (~360k Russian / ~110k English word forms), learned on-device, stored encrypted, disabled in
  password fields. No network, ever.

## Security & privacy

- **Fully offline** — no servers, accounts, phone numbers, analytics or metadata; keys live in the
  iOS **Keychain** / **Android Keystore** (StrongBox-preferred) and never sync or leave the device.
- **App lock** — Face ID / biometrics with an app-switcher privacy cover so snapshots never show your
  chats.
- **Clipboard hygiene** — auto-clear after a delay, clips flagged sensitive to stay out of history.
- **Multiple identities** — create, switch, rename, regenerate or delete separate profiles.
- **Android hardening** — a **panic PIN** that wipes all keys/chats/contacts and destroys the
  Keystore key (crypto-erasure); screenshot blocking (`FLAG_SECURE`); anti-tapjacking and
  task-hijacking (StrandHogg) defences; device-integrity warnings (root / emulator / debugger /
  hooking framework / re-signed APK); backups and device-transfer disabled; R8 shrink + obfuscate.

Report a vulnerability privately — see [SECURITY.md](SECURITY.md).

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

`libsignal` is **not vendored** here (it is AGPL-3.0 and its build artifacts are hundreds of MB). It
is fetched and patched from its real upstream at the exact pinned version by
`scripts/setup-libsignal.sh` — see [BUILDING.md](BUILDING.md).

## Building

Most people should just download from [Releases](../../releases). Building from source is for
contributors, auditors, and packaging (F-Droid builds from source). Full details are in
**[BUILDING.md](BUILDING.md)**; in short:

```bash
scripts/setup-libsignal.sh --ios --android      # fetch + patch + build the Signal library
./build-ipa.sh                                  # iOS: unsigned .ipa
cd android && ./gradlew :app:assembleRelease    # Android: APK
```

## License

Kryptos is licensed under the **GNU Affero General Public License v3.0** — see [LICENSE](LICENSE).
This is required by `libsignal` (AGPL-3.0): any app built on it and distributed to others must make
its source available under the same license. ObjectivePGP is used under its own permissive license.
