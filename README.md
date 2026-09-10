<div align="center">

<img src="https://github.com/user-attachments/assets/f99d9c99-a01b-43f7-b3f1-f4077d45cf27" alt="UAC SNI Spoofer" width="880" />

<br/>
<br/>

# UAC SNI Spoofer · Android

**کلاینت چندموتوره اندروید برای عبور از محدودیت شبکه**

<sub>`Xray` · `UAC PoW (WARP/MASQUE)` · `Psiphon` · `Tor` · موتور تطبیقی انتخاب مسیر</sub>

<br/>

[![Release](https://img.shields.io/github/v/release/Floxu1/UAC-SNI-Spoofer-Android?display_name=tag&sort=semver&label=release&style=for-the-badge&color=7C3AED&labelColor=1e1b2e)](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases/latest)
[![Stars](https://img.shields.io/github/stars/Floxu1/UAC-SNI-Spoofer-Android?style=for-the-badge&color=eab308&labelColor=1e1b2e)](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/stargazers)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white&labelColor=1e1b2e)](#-نصب)
[![Telegram](https://img.shields.io/badge/Telegram-UacSniSpoofer-229ED9?style=for-the-badge&logo=telegram&logoColor=white&labelColor=1e1b2e)](https://t.me/UacSniSpoofer)

<br/>

**‹** [**دانلود آخرین نسخه**](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases/latest) **›** &nbsp;•&nbsp; **‹** [**English**](./README.en.md) **›** &nbsp;•&nbsp; **‹** [**کانال تلگرام**](https://t.me/UacSniSpoofer) **›**

</div>

<br/>

---

<div dir="rtl" align="right">

## ◈ معرفی

**UAC SNI Spoofer** یک کلاینت VPN متن‌باز برای اندروید است که به‌جای تکیه بر یک پروتکل، **چند موتور عبور مستقل** را در یک برنامه جمع کرده و به‌صورت خودکار بهترین مسیر را برای شبکه شما پیدا می‌کند.

هسته برنامه با **Kotlin + Jetpack Compose** نوشته شده و لایه شبکه آن ترکیبی از **Rust**، **C** و **Go** است که از طریق `JNI` به اپلیکیشن متصل می‌شود.

<div align="right">

| | |
|:--|:--|
| **رابط کاربری** | Kotlin · Jetpack Compose · Material 3 |
| **هسته بومی** | Rust (`aether`) · C (`badvpn`, `hev-socks5-tunnel`) · Go (`Xray`, `Psiphon`) |
| **حداقل اندروید** | 7.0 — `minSdk 24` |
| **معماری‌ها** | `arm64-v8a` · `armeabi-v7a` · `x86_64` · `x86` |

</div>

---

## ◈ موتورهای عبور

برنامه چهار موتور مستقل دارد که از بخش تنظیمات قابل انتخاب هستند:

<div align="right">

| موتور | توضیح | پیاده‌سازی |
|:--|:--|:--|
| **Xray** | اجرای کانفیگ‌های شخصی شما با کنترل کامل روی SNI، Fragment و FinalMask | `Xray-core v26.7.28` |
| **UAC PoW** | موتور اختصاصی مبتنی بر WARP — تونل `MASQUE` / `WireGuard` روی `QUIC` | هسته Rust به‌نام `aether` |
| **Psiphon** | لایه عبور داخلی به‌عنوان hop دوم یا مسیر جایگزین | `psiphontunnel 2.0.39` |
| **Tor** | مسیریابی چندلایه به‌همراه پل‌های `WebTunnel` | `TorDaemon` + `hev-socks5-tunnel` |

</div>

> موتور `PoW` می‌تواند Psiphon را به‌عنوان hop داخلی و WARP را به‌عنوان hop خارجی ترکیب کند.

---

## ◈ معماری

```
┌──────────────────────────────────────────────────────────┐
│              Jetpack Compose UI  ·  Kotlin               │
│        profiles · settings · logging · update · ai       │
├──────────────────────────────────────────────────────────┤
│                     VpnController                        │
│               ConnectionStateMachine                     │
├──────────────────────────────────────────────────────────┤
│                   Engine Selector                        │
│      Xray  │  UAC PoW  │  Psiphon  │  Tor                │
├──────────────────────────────────────────────────────────┤
│              PowConnectionCoordinator                    │
│   PathProbe · NetworkScoreboard · GhostHandover          │
│   AdaptiveObfuscation · PageTurbo · QualityPolicy        │
├──────────────────────────────────────────────────────────┤
│                   Native Layer (JNI)                     │
│   libaether.so     ← Rust : QUIC/MASQUE/WireGuard        │
│   libxray.so       ← Go   : VLESS/VMess/Trojan           │
│   libgopsi.so      ← Go   : Psiphon tunnel-core          │
│   libtor.so        ← C    : Tor + WebTunnel              │
│   libhev-socks5-tunnel.so / badvpn ← TUN ⇄ SOCKS         │
├──────────────────────────────────────────────────────────┤
│                  Android VpnService (TUN)                │
└──────────────────────────────────────────────────────────┘
```

---

## ◈ امکانات

### پشتیبانی از کانفیگ

<div align="right">

- اجرای `VLESS` ، `VMess` و `Trojan` با حفظ کامل پارامترهای اصلی
- کنترل دستی روی `SNI` ، `Host` ، `Path` ، `ALPN` و `Fingerprint`
- واردکردن از متن، کلیپ‌بورد، فایل، لینک اشتراک و اسکن `QR`
- ادغام چند اشتراک بدون پاک‌شدن نتایج قبلی و حذف خودکار موارد تکراری

</div>

### انتخاب هوشمند مسیر

<div align="right">

- ساخت **اثرانگشت اختصاصی برای هر شبکه** بر پایه نوع اتصال، اپراتور، `ASN` و سرویس‌دهنده
- بررسی کامل ترکیب‌های `Edge × DNS × Fragment × MTU` و آزمایش صدها مسیر مستقل
- رقابت چندمرحله‌ای: غربال اولیه ← آزمون پایداری ← تست فشار ← فینال `A-B-B-A`
- سنجش با راه‌اندازی سرد Xray، تست چندمقصدی `HTTP`، پاسخ `DNS`، حجم دریافتی، سرعت، نوسان و درصد اطمینان
- ذخیره یک **برنده** و یک **مسیر پشتیبان** برای همان کانفیگ و همان شبکه
- توقف و ادامه تست بدون از دست رفتن نتیجه و امکان رد کردن دستی مراحل

</div>

### پایداری اتصال

<div align="right">

- بازیابی خودکار هنگام تغییر شبکه یا افت کیفیت با کمک برنده ذخیره‌شده — `Ghost Handover`
- تغییر پویای روش مقاوم‌سازی بر اساس رفتار شبکه — `Adaptive Obfuscation`
- زمان استراحت برای مسیرهای ناموفق و بازگشت تدریجی آن‌ها — `Quality Policy`
- کاهش زمان بازکردن صفحات پرتکرار — `Page Turbo`

</div>

### شبکه و مسیریابی

<div align="right">

- سه حالت مسیریابی برنامه‌ها: **همه از VPN** / **دورزدن انتخابی** / **فقط برنامه‌های انتخابی**
- دو حالت اتصال: تونل سراسری `TUN` یا پروکسی محلی `SOCKS`
- چند `DNS Resolver` مستقل شامل `Cloudflare` ، `Google` ، `Quad9` ، `AdGuard` و `OpenDNS` روی `DoH`
- کنترل‌های پیشرفته: `Fragment` ، `FinalMask` ، `MTU` ، `Mux` ، `Keepalive` و `QUIC`

</div>

### پایش و ابزار

<div align="right">

- نمایش زنده پینگ، ترافیک، کشور، آدرس خروجی و سلامت اتصال
- گزارش فنی زنده برای عیب‌یابی
- `Config Maker` با دو روش `Quick Scan` و `Deep Adaptive Test`
- اتصال و قطع سریع از `Quick Settings` اندروید و کنترل‌های اعلان
- بررسی خودکار به‌روزرسانی از داخل برنامه

</div>

---

## ◈ نصب

<div align="right">

**۱.** آخرین نسخه را از بخش [**Releases**](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases/latest) دریافت کنید:

| فایل | مناسب برای |
|:--|:--|
| `UAC-x.x.x-arm64-v8a-Android7plus.apk` | **پیشنهاد اصلی** — گوشی‌های ۶۴ بیتی (۲۰۱۷ به بعد) |
| `UAC-x.x.x-armeabi-v7a-Android7plus.apk` | گوشی‌های ۳۲ بیتی قدیمی |
| `UAC-x.x.x-universal-Android7plus.apk` | همه معماری‌ها — حجم بیشتر |
| `UAC-x.x.x-x86_64 / x86` | فقط شبیه‌ساز |

**۲.** برنامه را نصب و اجرا کنید.
**۳.** کانفیگ خود را وارد کرده یا موتور دلخواه را انتخاب کنید.
**۴.** دکمه اتصال را بزنید و درخواست مجوز VPN را تأیید کنید.

</div>

> پیش از استفاده، سایر برنامه‌های VPN را ببندید.

---

## ◈ ساخت از سورس

### پیش‌نیازها

<div align="right">

| ابزار | نسخه |
|:--|:--|
| JDK | `17` |
| Android SDK | `35` + Build Tools `35.0.0` |
| Android NDK | `26.3.11579264` |
| CMake | `3.22.1` |
| Rust | `stable` با تارگت‌های Android — فقط برای بیلد هسته PoW |
| Python | `3.x` — برای اسکریپت‌های آماده‌سازی AAR |

</div>

### بیلد اپلیکیشن

```bash
git clone https://github.com/Floxu1/UAC-SNI-Spoofer-Android.git
cd UAC-SNI-Spoofer-Android

# Windows
.\gradlew.bat assembleDebug

# Linux / macOS
./gradlew assembleDebug
```

خروجی در مسیر زیر ساخته می‌شود:

```
app/build/outputs/apk/debug/app-debug.apk
```

### بیلد هسته Rust

اگر فایل `libaether.so` از قبل در `app/src/main/jniLibs/` موجود باشد، Gradle این مرحله را رد می‌کند. برای بیلد دستی:

```bash
# Windows
powershell -ExecutionPolicy Bypass -File core/build-android.ps1 -Abi arm64-v8a

# Linux / macOS
./core/build-android.sh arm64-v8a
```

---

## ◈ ساختار پروژه

```
UAC-SNI-Spoofer-Android/
│
├── app/
│   ├── libs/                          کتابخانه‌های AAR
│   │   ├── libv2ray-native-tun.aar
│   │   └── psiphontunnel-2.0.39.aar
│   │
│   └── src/main/
│       ├── assets/                    پل‌های Tor · geoip · server entries
│       ├── cpp/
│       │   ├── aether_jni.cpp         پل JNI به هسته Rust
│       │   └── badvpn/                tun2socks
│       │
│       └── java/com/uacspoofer/mobile/
│           ├── ai/                    انتخاب مسیر بر اساس دامنه
│           ├── core/                  VpnController · ConnectionStateMachine
│           ├── engine/
│           │   ├── pow/               موتور UAC PoW
│           │   │   ├── PowConnectionCoordinator.kt
│           │   │   ├── PowPathProbe.kt
│           │   │   ├── PowNetworkScoreboard.kt
│           │   │   ├── PowGhostHandover.kt
│           │   │   ├── PowAdaptiveObfuscation.kt
│           │   │   ├── PowQualityPolicy.kt
│           │   │   ├── PowPsiphon*.kt
│           │   │   └── PowTun2Socks.kt
│           │   └── tor/               TorDaemon · TorControlClient
│           │
│           ├── mci · profiles · settings · logging · ui · update
│
├── core/
│   ├── aether/src/                    هسته Rust
│   │   ├── quic.rs · masque.rs · masque_h2.rs
│   │   ├── wireguard.rs · wg_prober.rs
│   │   ├── netstack.rs · tun.rs
│   │   ├── socks.rs · socks_upstream.rs
│   │   ├── dns.rs · tls.rs · fragment.rs · noize.rs
│   │   ├── prober.rs · routing.rs · zerotrust.rs
│   │   └── ffi.rs                     مرز FFI با اندروید
│   │
│   └── quiche/                        QUIC / HTTP-3
│
├── scripts/                           آماده‌سازی AAR و vendoring
└── third_party/                       مجوزهای وابستگی‌ها
```

---

## ◈ وابستگی‌ها

<div align="right">

| پروژه | نقش | مجوز |
|:--|:--|:--|
| [Xray-core](https://github.com/XTLS/Xray-core) | موتور اصلی پروتکل‌ها | `MPL-2.0` |
| [Cloudflare quiche](https://github.com/cloudflare/quiche) | لایه QUIC / HTTP-3 | `BSD-2-Clause` |
| [Psiphon tunnel-core](https://github.com/Psiphon-Labs/psiphon-tunnel-core) | hop عبور داخلی | `GPL-3.0` |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | پل TUN به SOCKS | `MIT` |
| [flag-icons](https://github.com/lipis/flag-icons) | پرچم کشورها | `MIT` |
| [Vazirmatn](https://github.com/rastikerdar/vazirmatn) | فونت فارسی رابط کاربری | `OFL-1.1` |

</div>

جزئیات کامل در [**THIRD_PARTY_NOTICES.md**](./THIRD_PARTY_NOTICES.md) آمده است.

---

## ◈ پشتیبانی

<div align="right">

| | |
|:--|:--|
| **کانال تلگرام** | [t.me/UacSniSpoofer](https://t.me/UacSniSpoofer) |
| **گروه گفتگو** | [t.me/UacSniSpooferGroup](https://t.me/UacSniSpooferGroup) |
| **گزارش مشکل** | [GitHub Issues](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/issues) |

</div>

---

## ◈ نکته

> کیفیت اتصال به وضعیت اپراتور، کانفیگ انتخاب‌شده و شرایط لحظه‌ای شبکه بستگی دارد.
> هیچ مسیر یا کانفیگی روی تمام شبکه‌ها عملکرد یکسانی ندارد؛ به همین دلیل موتور تطبیقی برای هر شبکه جداگانه تصمیم می‌گیرد.

این پروژه صرفاً برای دسترسی آزاد به اطلاعات و اهداف آموزشی منتشر شده است.

</div>

<br/>

<div align="center">

### اگر این پروژه برایتان مفید بود، به آن ستاره بدهید ⭐

<sub>ساخته شده با ❤️ توسط <a href="https://github.com/Floxu1">Floxu1</a></sub>

</div>
