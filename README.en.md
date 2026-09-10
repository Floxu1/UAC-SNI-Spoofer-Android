<div align="center">

<img width="1672" height="941" alt="UAC SNI Spoofer" src="https://github.com/user-attachments/assets/f99d9c99-a01b-43f7-b3f1-f4077d45cf27" />

<br/>

# UAC SNI Spoofer Android

**Open-source Android tool for secure connection management, intelligent route testing, and automatic selection of the best config based on real network conditions**

<br/>

[![Release](https://img.shields.io/github/v/release/Floxu1/UAC-SNI-Spoofer-Android?display_name=tag&sort=semver&label=version&style=flat-square&color=7c3aed)](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases/latest)
![Android](https://img.shields.io/badge/Android-7.0%2B-green?style=flat-square&logo=android&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-24-yellowgreen?style=flat-square)
![targetSdk](https://img.shields.io/badge/targetSdk-35-blue?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-purple?style=flat-square&logo=kotlin)
![License](https://img.shields.io/badge/License-Open%20Source-blueviolet?style=flat-square)

<br/>

[فارسی](./README.md) · [English](./README.en.md)

</div>

---

## ◈ Overview

**UAC SNI Spoofer** is an open-source Android tool for managing secure connections, routing traffic, and intelligently optimizing network paths.
Using `Android VpnService`, native `TUN` routing, the `Xray` core, the native `Aether` engine (Rust), and the smart `PoW` engine, it ranks and selects configs based solely on **real test results on the current network**.

- Communication cores: `Xray` · `hev-socks5-tunnel` · `Psiphon Tunnel` · `Tor` · `WebTunnel` · `Aether`
- Protocol support: `VLESS` · `VMess` · `Trojan`
- Separate result storage per config × per network fingerprint
- UI built with `Jetpack Compose` + `Material 3`

> No single config performs best on every network. This project automatically finds the optimal route for your current network through real testing.

---

## ◈ Connection Architecture

<div align="center">

```
┌─────────────────────────────────────────────────────────┐
│                   Android VpnService                    │
├─────────────────────────────────────────────────────────┤
│                   TUN (hev-socks5-tunnel)               │
├─────────────────────────────────────────────────────────┤
│        PoW Adaptive Engine (Kotlin + Rust/Aether)       │
├──────────┬──────────┬──────────┬──────────┬─────────────┤
│  Xray    │ Psiphon  │   Tor    │ WebTunnel│  Direct     │
│ (VLESS/  │ (Go/JNI) │ (libtor) │          │  Compat     │
│ VMess/   │          │          │          │  Route      │
│ Trojan)  │          │          │          │             │
├──────────┴──────────┴──────────┴──────────┴─────────────┤
│           PoW Connection Coordinator / Scoreboard       │
└─────────────────────────────────────────────────────────┘
```

| Layer | Description |
|-------|-------------|
| `VpnService` | System-level tunnel on Android |
| `TUN Native` | `hev-socks5-tunnel` bridge between TUN and SOCKS |
| `PoW Engine` | Smart engine for testing, ranking, and route selection |
| `Xray Core` | Primary core for VLESS / VMess / Trojan |
| `Aether (Rust)` | Native layer for low-level operations |
| `Psiphon / Tor / WebTunnel` | Alternative and multi-hop routes |
| `Direct Compat Route` | Config test without address/ALPN/FinalMask substitution |

</div>

---

## ◈ Key Features

### ⚡ Tunnel & Protocols

- Full system tunnel via `VpnService` + native `TUN`
- Support for `VLESS` · `VMess` · `Trojan` preserving core fields: `SNI` · `Host` · `Path` · `ALPN` · `Fingerprint` · security and transport
- Two connection modes: `Tunnel VPN` and `SOCKS Local Proxy`
- Android TV support (`tv` mode with `armeabi-v7a` architecture)

### 🧠 Adaptive Connection

- Builds a **network fingerprint** based on connection type (WiFi / Mobile), carrier, `ASN`, and provider
- **Primary and fallback Edge sets** tailored per carrier
- Learns from successful results and applies them directly to future connections
- Automatic reconnection on network change or quality drop
- Selects one **Champion** and one **Backup** per config × per network
- `Cooldown` mechanism for failed routes (avoids unnecessary retesting)

### 🧪 Route Speed Test

Full test engine with parameter matrix:

- Independent `Edge × DNS × Fragment × MTU` combinations (hundreds of routes per config)
- Multi-stage competition: Initial Filter → Verification → Stability → Stress → Final `A-B-B-A`
- Measurements include:
  - Cold Start of Xray core
  - Multi-destination HTTP test
  - DNS response and Bootstrap
  - Payload · Throughput
  - Ping · Jitter · Success rate · Confidence score
- Live ranking · Pause/Resume · Manual stage skip
- Final list specific to each config × network fingerprint

### 🛠️ Advanced Controls

- **Fragment** · **FinalMask** · **MTU** · **Mux** · **Keepalive** · **QUIC** · routing controls
- Multiple independent DNS resolvers (DoH + Bootstrap): `Cloudflare` · `Google` · `Quad9` · `AdGuard` · `OpenDNS`
- **Config Maker** with two modes: `Quick Scan` (stops on first healthy result) and `Deep Adaptive Test`
- Merge multiple subscriptions without losing previous results, with automatic deduplication

### 🔀 Routing & Resilience

- Three app routing modes:
  - All apps through VPN
  - `Bypass` for selected apps
  - VPN only for selected apps
- **`Ghost Handover`**: Smooth transition between routes on quality drop or network change, without full disconnection
- **`Adaptive Obfuscation`**: Automatic selection of obfuscation techniques suited to the network
- **`Page Turbo`**: Optimization of frequently used routes
- `Psiphon` transport layer (with independent IPC) and `Tor-Style` routing layer

### 📊 Monitoring & Control

- Live display: Ping · Traffic (Up/Down) · Outbound IP · Country · Connection health status
- Technical reports and logs for debugging
- Quick connect/disconnect from `Android Quick Settings`
- Notification controls
- QR Code support (ZXing) for importing configs

---

## ◈ Project Structure

<div dir="ltr" align="left">

```
UAC-SNI-Spoofer-Android/
├── app/
│   ├── src/main/
│   │   ├── java/com/uacspoofer/mobile/
│   │   │   ├── engine/
│   │   │   │   ├── pow/                   ← PoW Engine
│   │   │   │   │   ├── AetherNative       ← Native libaether binding (Rust)
│   │   │   │   │   ├── PowCoreConfig
│   │   │   │   │   ├── PowEngineStore
│   │   │   │   │   ├── PowConnectionCoordinator
│   │   │   │   │   ├── PowNetworkScoreboard
│   │   │   │   │   ├── PowAdaptiveObfuscation
│   │   │   │   │   ├── PowGhostHandover
│   │   │   │   │   ├── PowPageTurbo
│   │   │   │   │   ├── PowPathProbe
│   │   │   │   │   ├── PowQualityPolicy
│   │   │   │   │   ├── PowTun2Socks / PowTunRelayConfig
│   │   │   │   │   ├── PowPsiphonService / Ipc / Client / Protocols
│   │   │   │   │   ├── PowRegions
│   │   │   │   │   └── PowStatusStore
│   │   │   │   └── tor/                   ← Tor Engine
│   │   │   ├── ai/                        ← Detection & AI
│   │   │   ├── core/                      ← Shared core
│   │   │   ├── vpn/                       ← Android VPN service
│   │   │   ├── profiles/                  ← Config & subscription management
│   │   │   ├── settings/                  ← Settings
│   │   │   ├── ui/                        ← Compose/Material3 UI
│   │   │   ├── logging/                   ← Logging & debug
│   │   │   ├── mci/                       ← Carrier / network detection
│   │   │   └── update/                    ← Updater
│   │   ├── cpp/                           ← Native C++ code (TUN bridge)
│   │   ├── jniLibs/                       ← libxray, libaether, libtor, libgopsi, libhev-socks5-tunnel, ...
│   │   └── assets/
│   └── libs/                              ← Custom AARs (psiphontunnel, libv2ray)
│
├── core/
│   ├── aether/                            ← Native Rust core (PoW low-level)
│   └── quiche/                            ← QUIC/TLS stack
│
├── third_party/
└── scripts/
    ├── isolate_psiphon_aar.py             ← Psiphon AAR isolation
    └── patch_v2ray_seq.py                 ← Xray/V2ray AAR sequence patch
```

</div>

---

## ◈ Core Libraries

| Library | Role |
|---------|------|
| `Xray` | Primary proxy core (VLESS/VMess/Trojan) |
| `hev-socks5-tunnel` | Native TUN → SOCKS bridge |
| `Psiphon Tunnel` | Alternative routes and circumvention |
| `Tor (libtor)` | Tor-style routing layer |
| `Aether (Rust core)` | Native PoW operations |
| `Jetpack Compose + Material3` | User interface |
| `Kotlin Coroutines` | Concurrency |
| `ZXing` | QR Code scanning |
| `Fresco` | Image/WebP loading |

---

## ◈ Requirements

| Item | Version |
|------|---------|
| Android | **7.0** or higher (API 24+) |
| JDK (for build) | **17** |
| Android SDK | **35** |
| NDK | `26.3.11579264` |
| Rust toolchain | For building `core/aether` |
| Python 3 | For pre-build scripts |
| VPN permission | Required on first connection |
| Other VPNs | Must be disabled while in use |

---

## ◈ Installation

1. Download the latest APK from the [Releases](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases) page.

**For 64-bit devices (2017 and newer, recommended):**
`UAC-{version}-arm64-v8a-Android7plus.apk`

**For older 32-bit devices:**
`UAC-{version}-armeabi-v7a-Android7plus.apk`

**For all architectures (larger size):**
`UAC-{version}-universal-Android7plus.apk`

2. Install and launch the app.
3. Import a config from file, text, clipboard, QR code, or subscription link.
4. Tap the connect button and grant VPN permission.

---

## ◈ Build from Source

Make sure `JDK 17`, `Android SDK 35`, and `Android NDK 26.x` are installed. Building the Rust (Aether) component requires `rustup` with Android targets.

<div dir="ltr" align="left">

```powershell
git clone https://github.com/Floxu1/UAC-SNI-Spoofer-Android.git
cd UAC-SNI-Spoofer-Android
.\gradlew.bat assembleDebug
```

Debug output:

```
app\build\outputs\apk\debug\app-debug.apk
```

For release build (requires `signing.properties`):

```powershell
.\gradlew.bat assembleRelease
```

</div>

> The `preBuild` task automatically builds the `libaether.so` Rust library for all ABIs using `core/build-android.ps1`.

---

## ◈ Release APKs

| File | Target |
|------|--------|
| `UAC-{version}-arm64-v8a-Android7plus.apk` | 64-bit devices (recommended, smallest size) |
| `UAC-{version}-armeabi-v7a-Android7plus.apk` | Older 32-bit devices |
| `UAC-{version}-x86_64-Android7plus.apk` | Emulator |
| `UAC-{version}-x86-Android7plus.apk` | x86 Emulator |
| `UAC-{version}-universal-Android7plus.apk` | All architectures (larger size) |
| `app-tv-armeabi-v7a.apk` | Android TV |

---

## ◈ Support & Contact

| | |
|---|---|
| 📢 Telegram Channel | [@UacSniSpoofer](https://t.me/UacSniSpoofer) |
| 👥 Support Group | [@UacSniSpooferGroup](https://t.me/UacSniSpooferGroup) |
| 🐛 Bug Reports | [GitHub Issues](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/issues) |

---

## ◈ Note

> Connection quality heavily depends on carrier conditions, network type, selected config, and real-time factors. No single config or route performs equally well across all networks.

Third-party dependency licenses and notices are available in [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md).

---

<div align="center">

### ⭐ If you find this project useful, please give it a star

</div>
