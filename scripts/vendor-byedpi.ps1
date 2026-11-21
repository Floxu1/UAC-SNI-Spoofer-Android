param([string]$Ndk = "C:/AndroidSdk/ndk/26.3.11579264")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $root "build/byedpi-0.17.3"
$commit = "7efde1b1296eaaa187b70e951894dde17527489c"
$toolchain = Join-Path $Ndk "toolchains/llvm/prebuilt/windows-x86_64/bin"
if (-not (Test-Path -LiteralPath $toolchain)) { throw "Android NDK toolchain missing: $toolchain" }

if (-not (Test-Path -LiteralPath (Join-Path $source ".git"))) {
    git clone --depth 1 --branch v0.17.3 https://github.com/hufrea/byedpi.git $source
    if ($LASTEXITCODE -ne 0) { throw "ByeDPI clone failed" }
}
if ((git -C $source rev-parse HEAD) -ne $commit -or (git -C $source status --porcelain --untracked-files=no)) {
    throw "Expected clean ByeDPI checkout $commit"
}

$targets = [ordered]@{
    "arm64-v8a" = "aarch64-linux-android24"
    "armeabi-v7a" = "armv7a-linux-androideabi24"
    "x86_64" = "x86_64-linux-android24"
    "x86" = "i686-linux-android24"
}
$files = @("packets.c", "main.c", "conev.c", "proxy.c", "desync.c", "mpool.c", "extend.c")
Push-Location -LiteralPath $source
try {
    foreach ($abi in $targets.Keys) {
        $clang = Join-Path $toolchain "$($targets[$abi])-clang.cmd"
        $dest = Join-Path $root "app/src/main/jniLibs/$abi/libbyedpi.so"
        # Keep 16 KiB LOAD alignment without extending RELRO across unmapped 4 KiB pages on older kernels.
        & $clang -D_DEFAULT_SOURCE -std=c99 -Oz -I. -fPIE -pie "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=4096" @files -o $dest
        if ($LASTEXITCODE -ne 0) { throw "ByeDPI build failed for $abi" }
    }
} finally {
    Pop-Location
}
Copy-Item -LiteralPath (Join-Path $source "LICENSE") -Destination (Join-Path $root "app/src/main/assets/byedpi-LICENSE.txt") -Force
