<div align="center">

# UAC SNI Spoofer Android

[فارسی](./README.md) · [English](./README.en.md)

</div>

## Overview

UAC SNI Spoofer is an open-source Android tool for managing secure connections. It uses Android's native VPN/TUN path with the Xray core and provides a focused interface for connecting, managing configurations, and inspecting real network status.

Current version: **1.0.7**

## Features

- Native Android `VpnService` with Xray Native TUN
- A tuned built-in configuration with primary and fallback routes
- VLESS, VMess, and Trojan configuration support
- Import from text, clipboard, local files, and subscription URLs
- SNI Config Maker with real latency testing and sorting
- Live latency, exit country, traffic statistics, and logs
- Per-app tunnel selection through App Bypass
- Advanced Fragment, Finalmask, TUN, routing, and keepalive controls
- Update checks through this repository's Releases section, followed by user-approved installation

## Requirements

- Android 7.0 or newer
- Standard Android VPN permission
- Other VPN apps must be disconnected while UAC SNI Spoofer is active

## Installation

1. Download the latest APK from [Releases](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases).
2. Install and open the app.
3. Select a configuration and tap Connect.
4. Approve Android's VPN request.

## Build from source

JDK 17 and Android SDK 35 are required.

```powershell
git clone https://github.com/Floxu1/UAC-SNI-Spoofer-Android.git
cd UAC-SNI-Spoofer-Android
.\gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Support

- Telegram channel: [t.me/UacSniSpoofer](https://t.me/UacSniSpoofer)
- Telegram group: [t.me/UacSniSpooferGroup](https://t.me/UacSniSpooferGroup)
- Bug reports: [GitHub Issues](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/issues)

Connection quality depends on the carrier, selected configuration, and current network conditions. No single configuration performs identically on every network.

Third-party dependency notices are available in [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md).

If this project helps you, please give it a star ⭐
