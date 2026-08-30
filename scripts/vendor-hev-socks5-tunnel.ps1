$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$jni = Join-Path $root "app\src\main\jniLibs"
$work = Join-Path $root "build\hev-socks5-ndk"
$src = Join-Path $work "jni"
$ndkBuild = "C:\AndroidSdk\ndk\26.3.11579264\ndk-build.cmd"
if (-not (Test-Path $ndkBuild)) {
    $sdkDir = "C:\AndroidSdk"
    $localProps = Join-Path $root "local.properties"
    if (Test-Path $localProps) {
        $sdkLine = Select-String -Path $localProps -Pattern '^sdk.dir=' | Select-Object -First 1
        if ($sdkLine) {
            $sdkDir = $sdkLine.Line.Substring("sdk.dir=".Length).Replace("\\", "\").Replace("\:", ":")
        }
    }
    $ndkRoot = Get-ChildItem (Join-Path $sdkDir "ndk") -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty FullName
    $ndkBuild = Join-Path $ndkRoot "ndk-build.cmd"
}
if (-not (Test-Path $ndkBuild)) {
    throw "ndk-build.cmd not found"
}

New-Item -ItemType Directory -Force -Path $work | Out-Null
if (-not (Test-Path (Join-Path $src ".git"))) {
    if (Test-Path $src) { Remove-Item $src -Recurse -Force }
    Write-Host "Cloning hev-socks5-tunnel"
    & git clone --recursive https://github.com/heiher/hev-socks5-tunnel.git $src
    if ($LASTEXITCODE -ne 0) { throw "git clone failed" }
}

function Resolve-GitlinkFiles([string]$dir) {
    Get-ChildItem -Recurse -File $dir | ForEach-Object {
        if ($_.Length -gt 240) { return }
        $text = (Get-Content $_.FullName -Raw -ErrorAction SilentlyContinue)
        if (-not $text) { return }
        $targetRel = $text.Trim()
        if ($targetRel -notmatch '^\.\./') { return }
        $target = [IO.Path]::GetFullPath((Join-Path $_.DirectoryName ($targetRel -replace '/', '\')))
        if (-not (Test-Path $target)) {
            throw "missing gitlink target $($_.FullName) -> $target"
        }
        Copy-Item $target $_.FullName -Force
    }
}

Write-Host "Resolving git symlink placeholders for Windows"
Resolve-GitlinkFiles $src

$appMk = Join-Path $src "Application.mk"
@(
    "APP_OPTIM := release"
    "APP_PLATFORM := android-24"
    "APP_ABI := armeabi-v7a arm64-v8a x86 x86_64"
    "APP_CFLAGS := -O3 -DPKGNAME=com/uacspoofer/mobile/engine/tor -DCLSNAME=HevSocks5Tunnel"
    "APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true"
    "NDK_TOOLCHAIN_VERSION := clang"
) | Set-Content -Path $appMk

Write-Host "ndk-build hev-socks5-tunnel"
Push-Location $work
& $ndkBuild
$buildCode = $LASTEXITCODE
Pop-Location
if ($buildCode -ne 0) { throw "ndk-build failed" }

$abis = @("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
foreach ($abi in $abis) {
    $from = Join-Path $work "libs\$abi\libhev-socks5-tunnel.so"
    if (-not (Test-Path $from)) {
        $from = Join-Path $work "obj\local\$abi\libhev-socks5-tunnel.so"
    }
    if (-not (Test-Path $from)) { throw "missing $abi libhev-socks5-tunnel.so" }
    $toDir = Join-Path $jni $abi
    New-Item -ItemType Directory -Force -Path $toDir | Out-Null
    Copy-Item $from (Join-Path $toDir "libhev-socks5-tunnel.so") -Force
    Write-Host "vendored $abi"
}
Write-Host "hev-socks5-tunnel vendored into $jni"
