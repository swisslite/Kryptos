# Security Policy

## Reporting a vulnerability

If you find a security issue in Kryptos, please report it **privately** rather than opening a public
issue. Email **datakeepers@proton.me** with:

- a description of the issue and its impact,
- steps to reproduce (a proof of concept if you have one),
- the affected platform and app version.

You will get an acknowledgement, and fixes for confirmed issues are prioritized. Please give a
reasonable window to ship a fix before any public disclosure.

## Scope

Kryptos is fully offline and has no backend, so there is no server-side attack surface. Relevant
areas include: the cryptographic core, key storage (Keychain / Android Keystore), the keyboard
extensions, on-screen decryption, PGP, and the handling of untrusted input (ciphertext, pasted
data, imported keys).

## Cryptography

Kryptos uses Signal's official [`libsignal`](https://github.com/signalapp/libsignal) for the Signal
Protocol and established libraries for the other modes — no home-rolled cryptographic primitives.
See [BUILDING.md](BUILDING.md) for how `libsignal` is pinned and built.
