# Third-party notices

## Xray-core

- Version: `v26.7.28`
- Commit: `5ca6f4b7d4dc20a881d4330e498892697627ec0c`
- Purpose: SOCKS inbound and Trojan/WebSocket/TLS/finalmask outbound
- License: MPL-2.0
- Bundled license: `third_party/xray-LICENSE.txt`
- Upstream: https://github.com/XTLS/Xray-core

Bundled Android executable hashes:

```text
arm64-v8a   BA33E8A5518353DB9F5DEC80B3A4133063C3F5F71EB8E64D99DED4FA9DC0F458
armeabi-v7a FB910409903B4AF3A3A673489C27C99398C7D7A3C02C496ECD87698FF50EA735
x86_64      494154A10429A43494D14AC1A78F44870206121D6E8AFBEE6ED94CF3CF68999A
```

## flag-icons / FlagCDN

- Purpose: bundled ISO country flag PNGs shown by SNI Config Maker
- License: MIT
- Bundled license: `third_party/flag-icons-LICENSE.txt`
- Upstream: https://github.com/lipis/flag-icons
- Distribution endpoint: https://flagcdn.com/

## Vazirmatn UI FD

- Purpose: Persian Home interface typography with Farsi digits
- License: SIL Open Font License 1.1
- Bundled license: `third_party/vazirmatn-OFL.txt`
- Upstream: https://github.com/rastikerdar/vazirmatn

## hev-socks5-tunnel

- Purpose: lightweight TUN-to-SOCKS pipe for Tor and UAC PoW device tunnels
- License: MIT
- Bundled license: `third_party/hev-socks5-tunnel-LICENSE.txt`
- Upstream: https://github.com/heiher/hev-socks5-tunnel

## Zeptun

- Version: `1.1.3` at `32f5dde141b125ec0aea732b1c55937e7993b189`
- Purpose: optional native TUN-to-SOCKS processing for Android VPN sessions
- License: MIT
- Bundled license: `third_party/zeptun-LICENSE.txt`
- Build instructions: [Zeptun on Android](core/zeptun/README.md)
- Upstream: https://github.com/Noisemux/zeptun

## UAC PoW · WARP core (vendored)

- Purpose: outer WARP / MASQUE / WireGuard hop for the UAC PoW engine
- License: GNU Affero GPL v3
- Bundled license and trademark notice: `core/aether/LICENSE` and `core/aether/TRADEMARK.md`
- Location: `core/aether/` (plus `core/quiche/` for QUIC)

## UAC Direct Core

- Artifact: `libDirect.so` for Engine 4, with a separate Android JNI bridge
- Source attribution: includes transport primitives adapted from the Aether project
- Modifications: separated the Engine 4 route and Android entry point on 2026-11-21
- License: AGPL-3.0-only, full text in `core/direct/LICENSE`
- Corresponding source: `core/direct/`, shared transport sources in `core/aether/`,
  and the Android build scripts `core/build-android.ps1` and `core/build-android.sh`

## Cloudflare quiche

- Purpose: QUIC / HTTP/3 used by the WARP MASQUE path
- License: BSD-2-Clause
- Location: `core/quiche/`
- Upstream: https://github.com/cloudflare/quiche

## Psiphon tunnel-core

- Purpose: inner circumvention hop for UAC PoW (`psiphontunnel-2.0.39.aar`)
- License: GNU GPL v3
- Bundled AAR: `app/libs/psiphontunnel-2.0.39.aar`
- Upstream: https://github.com/Psiphon-Labs/psiphon-tunnel-core


## Engine 4 (UAC Direct) method

The manual ClientHello fragmentation presets are adapted from [patterniha/Serverless-for-Iran](https://github.com/patterniha/Serverless-for-Iran). The app generates the Xray config in Kotlin.

## ByeDPI

- Version: `v0.17.3` at `7efde1b1296eaaa187b70e951894dde17527489c`
- Purpose: optional loopback SOCKS outbounds for OOB and SNI-aware TLS record fragmentation in UAC Direct
- License: MIT
- Bundled license: `app/src/main/assets/byedpi-LICENSE.txt`
- Build script: `scripts/vendor-byedpi.ps1`
- Upstream: https://github.com/hufrea/byedpi
