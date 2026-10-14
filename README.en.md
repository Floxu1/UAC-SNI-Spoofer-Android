<div align="center">

<img width="1672" height="941" alt="UAC SNI Spoofer" src="https://github.com/user-attachments/assets/f99d9c99-a01b-43f7-b3f1-f4077d45cf27" />

# UAC SNI Spoofer — Android

**Three tunnel engines in one bilingual Android app: Xray with one-tap auto config, Tor with WebTunnel bridges, and UAC PoW (Psiphon over WARP).**

[![Release](https://img.shields.io/github/v/release/Floxu1/UAC-SNI-Spoofer-Android?display_name=tag&sort=semver&label=version&style=flat-square&color=7c3aed)](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/Floxu1/UAC-SNI-Spoofer-Android/total?style=flat-square)](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases)
![Android](https://img.shields.io/badge/Android-7.0%2B-green?style=flat-square&logo=android&logoColor=white)
![targetSdk](https://img.shields.io/badge/targetSdk-35-blue?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-purple?style=flat-square&logo=kotlin)
[![Stars](https://img.shields.io/github/stars/Floxu1/UAC-SNI-Spoofer-Android?style=flat-square)](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/stargazers)

[فارسی](./README.md) · **English** · [Telegram](https://t.me/UacSniSpoofer) · [Windows version](https://github.com/Floxu1/UAC-SNI-Spoofer-Windows)

</div>

---

## Table of contents

- [Overview](#overview)
- [What's new in 2.0.8](#whats-new-in-208)
- [Engine 1 — Xray](#engine-1--xray)
- [Engine 2 — Tor](#engine-2--tor)
- [Engine 3 — UAC PoW](#engine-3--uac-pow)
- [Speed & everyday features](#speed--everyday-features)
- [Which APK should I download?](#which-apk-should-i-download)
- [Quick start](#quick-start)
- [Requirements](#requirements)
- [Build from source](#build-from-source)
- [FAQ](#faq)
- [Support & contact](#support--contact)
- [Disclaimer](#disclaimer)

## Overview

UAC SNI Spoofer is an open-source Android client built on `VpnService` and a native `TUN` bridge (`hev-socks5-tunnel`). It ships three independent engines. Only one runs at a time, and switching engines while connected disconnects cleanly first.

| | Engine 1 — `Xray` | Engine 2 — `Tor` | Engine 3 — `UAC PoW` |
|---|---|---|---|
| Core | `Xray` (VLESS / VMess / Trojan) | `Tor` + `WebTunnel` bridges | `Aether` (Rust, WARP MASQUE h2) + `Psiphon` |
| Needs configs | No (one-tap Auto) — your own configs work too | No | No |
| Best for | Fastest everyday use | When other routes are blocked | Lines where configs keep dying |

The UI is fully Persian / English (RTL / LTR), built with `Jetpack Compose` + `Material 3`.

## What's new in 2.0.8

- **Rebuilt Xray engine** with one-tap auto config: operator-aware (MCI vs Irancell/Rightel), ECH support and automatic failover.
- **Major speed boost:** Iranian sites direct, smart DNS cache, TCP Fast Open, larger buffers, screen-on prewarm and real-speed ranking after connect.
- **New Xray settings page** with only the useful options, plus a detailed **live route** that shows every connect step and the active config's specs.
- **Tor faster and more stable**, with the fastest nearby-Europe exit.
- **Instant QUIC fallback** (no more stalls) and **lower battery and memory use** across all engines.
- **Redesigned UI** with a new animated engine switcher and new icons.
- **One-time settings reset** when upgrading to 2.0.8; the default engine is now Xray.

## Engine 1 — Xray

Tap **Connect** on **Auto** and the app does the rest:

1. **Operator & DPI detection** — recognises MCI vs Irancell/Rightel and how the line filters.
2. **Config list** — fetches the Patterniha list from GitHub, with a `jsDelivr` mirror as fallback.
3. **ECH keys** — fetched and cached for ECH configs.
4. **Clean-IP race & batch test** — many configs are tested in parallel through the real tunnel, then ranked.
5. **Connect & failover** — the best config connects first; if it fails, the next ones are tried automatically.
6. **Clean-IP rescue** — a last-resort path when every route failed.
7. **Health check** — the connection is watched while idle and switched if it breaks.

Also included: your own `VLESS` / `VMess` / `Trojan` configs (file, text, clipboard, QR, subscription), the **Route Speed Test**, and the **Config Maker**.

**Live route** — open it from Xray settings or from the connect card's details to see every step live: operator, list, ECH, batch test, ranking, connect, failover, DNS & routing, active features, latency, exit country/IP and the full config specs (protocol, network, security, ECH, fingerprint, SNI, ALPN, host/path, fragment, Mux, port).

## Engine 2 — Tor

`Tor` over `WebTunnel` bridges, with the fastest exit chosen from nearby Europe. It has its own settings page and live route view.

## Engine 3 — UAC PoW

No configs needed: the native Rust core `Aether` raises a WARP leg over MASQUE (HTTP/2) and `Psiphon` runs through it. Pick an exit country or leave it on Auto. It has its own settings and live route view.

## Speed & everyday features

- **Iranian sites direct** — Iranian sites skip the VPN and open faster.
- **Smart DNS cache** — sites you visited before open instantly.
- **TCP Fast Open** and **larger buffers** — quicker page loads, smoother video.
- **Screen-on prewarm** — the connection is ready the moment you unlock.
- **Fast QUIC reject** — apps fall back to TCP instantly instead of stalling.
- **Split tunneling** — all apps, bypass selected apps, or VPN only for selected apps.
- **Tunnel or local SOCKS proxy** mode.
- **Quick Settings tile** for one-tap connect / disconnect.
- Live ping, traffic, exit IP and country.

## Which APK should I download?

Download from **[Releases](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases/latest)**:

| File | Use it for |
|---|---|
| `UAC-2.0.8-arm64-v8a.apk` | **Almost every phone** (recommended) |
| `UAC-2.0.8-armeabi-v7a.apk` | Old 32-bit phones |
| `UAC-2.0.8-x86_64.apk` | Emulators on PC (e.g. LDPlayer) |
| `UAC-2.0.8-x86.apk` | Old 32-bit emulators |
| `UAC-2.0.8-universal.apk` | Any device (larger file) |

## Quick start

1. Install the APK that matches your device.
2. Open the app — Engine 1 (Xray) on **Auto** is selected by default.
3. Tap **Connect** and allow the VPN permission.
4. If it doesn't connect, try Engine 2 (Tor) or Engine 3 (PoW) from the switcher at the top.

## Requirements

| Item | Version |
|---|---|
| Android | 7.0+ (API 24) |
| JDK (build) | 17 |
| Android SDK / NDK | 35 / `26.3.11579264` |
| Rust toolchain | for `core/aether` |
| Other VPN apps | must be off while connected |

## Build from source

```powershell
git clone https://github.com/Floxu1/UAC-SNI-Spoofer-Android.git
cd UAC-SNI-Spoofer-Android
.\gradlew.bat :app:assembleRelease
# -> app\build\outputs\apk\release\UAC-<version>-<abi>.apk
```

A release build needs `signing.properties` (see `signing.properties.example`). `preBuild` builds the Rust `libaether.so` for every ABI.

## FAQ

<details><summary><b>Engine 1 doesn't connect.</b></summary>

Leave it on Auto and try again — it re-tests and fails over by itself. If your line blocks everything, try Tor or PoW.
</details>

<details><summary><b>My settings were reset after updating to 2.0.8.</b></summary>

That is the one-time reset for 2.0.8, so the new Xray engine starts clean. It will not happen again.
</details>

<details><summary><b>Can I run two engines at once?</b></summary>

No. Only one engine runs at a time; switching while connected disconnects first.
</details>

## Support & contact

| | |
|---|---|
| 📢 Channel | [@UacSniSpoofer](https://t.me/UacSniSpoofer) |
| 👥 Group | [@UacSniSpooferGroup](https://t.me/UacSniSpooferGroup) |
| 🐛 Bugs | [GitHub Issues](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/issues) |

## Disclaimer

Connection quality depends on your carrier, network and the moment — no route works equally well everywhere. Use this software responsibly and in line with the laws that apply to you. Third-party licenses: [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md).

---

<div align="center">

### ⭐ Happy with the app? Support us with a star on GitHub

[![Star on GitHub](https://img.shields.io/github/stars/Floxu1/UAC-SNI-Spoofer-Android?style=social)](https://github.com/Floxu1/UAC-SNI-Spoofer-Android)

</div>