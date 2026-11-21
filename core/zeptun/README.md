# Zeptun on Android

UAC uses Zeptun 1.1.3 at commit `32f5dde141b125ec0aea732b1c55937e7993b189`. The [upstream Android guide](https://github.com/Noisemux/zeptun/wiki/Android) describes the mobile preset and C ABI. UAC packages the engine statically linked into `libzeptun-jni.so` for each ABI in `app/src/main/jniLibs`. The [MIT notice](../../third_party/zeptun-LICENSE.txt) covers the engine and the adapted Android JNI bridge.

From the repository root on Windows, run this with official Zig 0.17.1 and Android NDK 26.3.11579264.

```powershell
pwsh -File core/zeptun/build-android.ps1 -Zig C:/tools/zig/zig.exe -Ndk C:/AndroidSdk/ndk/26.3.11579264
```

The script checks out the pinned source in `build/zeptun-upstream`, applies the Android patches, builds the upstream mobile archives and links the JNI libraries for API 24 with 16 KB ELF load alignment. The patches expose worker readiness, use the page allocator on Android to avoid the pre-API-29 ELF TLS dependency, and disable jumbo TCP output on the borrowed Android packet FD. Android's TUN splice reads MTU-sized packet records and has no offload metadata. On packet sockets, the epoll worker defers writes in order when the socket is full, using the existing 128-slot bound. The build disables native route configuration and offload paths. Gradle packages the resulting JNI libraries using its existing ABI splits and Universal APK.

| ABI | SHA-256 |
| --- | --- |
| arm64-v8a | `769c7d83ffba8c1e6dfa5791266d952e51390d768f07bc7f824a26d9a1cd5cf4` |
| armeabi-v7a | `12641d9e4330c696170406c90cf59f817e735cb66e24fdde9cf9ad0102bbfc19` |
| x86 | `a59940bb0cf3c45b9c8725e0dc6d50b53f5d18c00f1dbc13ecb4021d886cd85e` |
| x86_64 | `9d6f213a76ce63eb060fc4291dbd78ec8118fc6d857d7b5f97511b16bca85f86` |

The saved setting is read when an explicit VPN connection starts. Recovery and routing reloads within that session use its original selection. The existing service owns the VPN interface and packet filters, and each engine's Kotlin caller closes its descriptor after its native relay stops. The relay verifies the local SOCKS greeting before starting Zeptun. Zeptun protects its sockets through the running Android VPN service.

| Connection engine | Stable | Zeptun |
| --- | --- | --- |
| Xray | Existing built-in TUN | Private loopback SOCKS with the same routing, DNS rules and statistics |
| Tor | HEV | Tor split-routing SOCKS, with local fake DNS and TCP upstream |
| UAC PoW with Psiphon | HEV | PoW split-routing SOCKS, with local fake DNS and TCP upstream |
| UAC PoW with WARP only | HEV | Aether SOCKS, with local fake DNS and standard SOCKS5 UDP association |

Tor and the PoW Psiphon relay expose SOCKS CONNECT only. Zeptun serves their synthetic DNS locally and rejects other UDP within the tunnel. SOCKS-only mode starts no TUN backend. UAC Direct uses its existing TUN path.
