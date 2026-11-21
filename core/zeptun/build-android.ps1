param(
    [Parameter(Mandatory)][string]$Zig,
    [Parameter(Mandatory)][string]$Ndk
)

$ErrorActionPreference = 'Stop'
$Zig = (Resolve-Path -LiteralPath $Zig).Path
$Ndk = (Resolve-Path -LiteralPath $Ndk).Path
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$source = Join-Path $root 'build/zeptun-upstream'
$revision = '32f5dde141b125ec0aea732b1c55937e7993b189'
$patches = @('android-readiness.patch', 'android-packet-mtu.patch', 'android-packet-backpressure.patch', 'android-tcp-backpressure.patch')
$toolchain = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$targets = [ordered]@{
    'arm64-v8a' = 'aarch64-linux-android'
    'armeabi-v7a' = 'armv7a-linux-androideabi'
    'x86' = 'i686-linux-android'
    'x86_64' = 'x86_64-linux-android'
}

if (!(Test-Path -LiteralPath "$source/.git")) {
    git clone --no-checkout https://github.com/Noisemux/zeptun.git $source
    if ($LASTEXITCODE -ne 0) { throw 'Could not clone Zeptun' }
    git -C $source checkout --detach $revision
    if ($LASTEXITCODE -ne 0) { throw 'Could not check out pinned Zeptun' }
}
if ((git -C $source rev-parse HEAD) -ne $revision) { throw "Expected Zeptun $revision at $source" }
foreach ($name in $patches) {
    $patch = Join-Path $PSScriptRoot $name
    git -C $source apply --check --reverse $patch 2>$null
    if ($LASTEXITCODE -ne 0) {
        git -C $source apply $patch
        if ($LASTEXITCODE -ne 0) { throw "Zeptun Android patch did not apply: $name" }
    }
}

Push-Location $source
try {
    & $Zig build android -Doptimize=small -Denable-route=false -Denable-gso=false
    if ($LASTEXITCODE -ne 0) { throw 'Zeptun Android build failed' }
} finally {
    Pop-Location
}

foreach ($abi in $targets.Keys) {
    $archive = Join-Path $source "zig-out/android/prebuilt/$abi/libzeptun.a"
    $output = Join-Path $root "app/src/main/jniLibs/$abi/libzeptun-jni.so"
    $options = @(
        "--target=$($targets[$abi])24", '-O2', '-Wall', '-Wextra', '-Werror', '-std=c11',
        '-fPIC', '-fvisibility=hidden', '-shared', '-Wl,--no-undefined', '-Wl,--exclude-libs,ALL',
        '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384',
        '-Wl,-soname,libzeptun-jni.so', '-I', "$source/include", "$PSScriptRoot/uac_jni.c", $archive, '-lm', '-o', $output
    )
    & "$toolchain/clang.exe" @options
    if ($LASTEXITCODE -ne 0) { throw "Zeptun JNI link failed for $abi" }
    & "$toolchain/llvm-strip.exe" --strip-unneeded $output
    if ($LASTEXITCODE -ne 0) { throw "Zeptun JNI strip failed for $abi" }
}
