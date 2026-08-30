$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$jni = Join-Path $root "app\src\main\jniLibs"
$torVersion = "0.4.9.11"
$webtunnelVersion = "v0.0.6"
$work = Join-Path $root "build\tor-runtime"
New-Item -ItemType Directory -Force -Path $work | Out-Null

$aar = Join-Path $work "tor-android-$torVersion.aar"
$url = "https://repo1.maven.org/maven2/info/guardianproject/tor-android/$torVersion/tor-android-$torVersion.aar"
Write-Host "Downloading $url"
& curl.exe -L --fail -A "Mozilla/5.0" -o $aar $url
$extract = Join-Path $work "aar-unpacked"
if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($aar, $extract)

$abis = @("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
foreach ($abi in $abis) {
    $from = Join-Path $extract "jni\$abi\libtor.so"
    $toDir = Join-Path $jni $abi
    New-Item -ItemType Directory -Force -Path $toDir | Out-Null
    Copy-Item $from (Join-Path $toDir "libtor.so") -Force
}

$goWork = Join-Path $root "third_party\webtunnel-client"
if (-not (Test-Path (Join-Path $goWork "go.mod"))) {
    throw "Patched webtunnel client is missing at $goWork"
}

$sdkDir = "C:\AndroidSdk"
$localProps = Join-Path $root "local.properties"
if (Test-Path $localProps) {
    $sdkLine = Select-String -Path $localProps -Pattern '^sdk.dir=' | Select-Object -First 1
    if ($sdkLine) {
        $sdkDir = $sdkLine.Line.Substring('sdk.dir='.Length).Replace("\\", "\").Replace("\:", ":")
    }
}
$ndkRoot = Get-ChildItem (Join-Path $sdkDir "ndk") -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1 -ExpandProperty FullName
$ndkPrebuilt = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin"
if (-not (Test-Path $ndkPrebuilt)) {
    throw "Android NDK clang not found under $sdkDir\ndk"
}

function Build-WebTunnel($abi, $goarch, $goarm, $clang) {
    Push-Location $goWork
    $env:GOOS = "android"
    $env:GOARCH = $goarch
    if ($goarm) { $env:GOARM = $goarm } else { Remove-Item Env:GOARM -ErrorAction SilentlyContinue }
    $out = Join-Path $jni "$abi\libwebtunnel.so"
    $clangPath = Join-Path $ndkPrebuilt $clang
    $env:CGO_ENABLED = "1"
    $env:CC = $clangPath
    Write-Host "Building libwebtunnel.so $abi (android/$goarch) cc=$clang"
    & go build -trimpath -ldflags="-s -w" -buildmode=pie -o $out .
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw "webtunnel build failed for $abi" }
    Pop-Location
}

Build-WebTunnel "arm64-v8a" "arm64" $null "aarch64-linux-android24-clang.cmd"
Build-WebTunnel "x86_64" "amd64" $null "x86_64-linux-android24-clang.cmd"
Build-WebTunnel "armeabi-v7a" "arm" "7" "armv7a-linux-androideabi24-clang.cmd"
Build-WebTunnel "x86" "386" $null "i686-linux-android24-clang.cmd"
Remove-Item Env:CGO_ENABLED, Env:GOOS, Env:GOARCH, Env:GOARM, Env:CC -ErrorAction SilentlyContinue
Write-Host "Tor / WebTunnel runtime vendored into $jni"
