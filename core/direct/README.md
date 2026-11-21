# UAC Direct Core

The Android Engine 4 transport builds as `libDirect.so`. Its JNI bridge is in
`android/jni.cpp`. The transport keeps Engine 4's existing identities and
scan history under `files/direct-wireguard/`.

`src/route.rs` owns the HTTP/2 carrier selection, WireGuard session,
range scan, latency ranking, data verification, and local SOCKS entry point.
The build links shared Rust transport primitives from `../aether` into
`libDirect.so`. The separate JNI bridge links to `libDirect.so` at runtime.

From the repository root:

```powershell
./core/build-android.ps1 -Abi arm64-v8a -Core Direct
```

```sh
./core/build-android.sh -Abi arm64-v8a -Core Direct
```

Use the same commands with `armeabi-v7a` and `x86_64` for the other supported
Android ABIs. Gradle packages the Rust library with the JNI bridge.

The core is licensed under [AGPL-3.0-only](LICENSE). See the
[third-party notices](../../THIRD_PARTY_NOTICES.md) for the source attribution.
