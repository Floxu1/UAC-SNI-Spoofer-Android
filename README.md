<div align="center">

<img width="1672" height="941" alt="UAC SNI Spoofer" src="https://github.com/user-attachments/assets/f99d9c99-a01b-43f7-b3f1-f4077d45cf27" />

<br/>

# UAC SNI Spoofer Android

**ابزار متن‌باز اندروید برای مدیریت اتصال‌های امن، تست هوشمند مسیر و انتخاب خودکار بهترین کانفیگ بر اساس شرایط واقعی شبکه**

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

<div dir="rtl" align="right">

## ◈ معرفی

**UAC SNI Spoofer** یک ابزار متن‌باز اندروید برای مدیریت اتصال‌های امن، عبور ترافیک و بهینه‌سازی هوشمند مسیر شبکه است.
این برنامه با استفاده از `Android VpnService`، مسیر بومی `TUN`، هسته `Xray`، موتور بومی `Aether` (Rust) و موتور هوشمند `PoW`، کانفیگ‌ها را فقط بر اساس **نتیجه واقعی تست روی همان شبکه** رتبه‌بندی و انتخاب می‌کند.

<div align="right">

- هسته‌های ارتباطی: `Xray` · `hev-socks5-tunnel` · `Psiphon Tunnel` · `Tor` · `WebTunnel` · `Aether`
- پشتیبانی از پروتکل‌های `VLESS` · `VMess` · `Trojan`
- ذخیره جداگانه نتیجه‌ها برای هر کانفیگ × هر اثرانگشت شبکه
- رابط کاربری `Jetpack Compose` + `Material 3`

</div>

> هیچ کانفیگی روی تمام شبکه‌ها بهترین نتیجه را نمی‌دهد. هدف این پروژه این است که به‌صورت خودکار و با تست واقعی، بهترین مسیر ممکن را برای شبکه فعلی شما پیدا کند.

---

## ◈ معماری اتصال
</div>

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
</div>

<div align="center" dir="rtl">

| لایه | توضیح |
|------|-------|
| `VpnService` | تونل سطح سیستم اندروید |
| `TUN Native` | `hev-socks5-tunnel` برای پل بین TUN و SOCKS |
| `PoW Engine` | موتور هوشمند تست، رتبه‌بندی و انتخاب مسیر |
| `Xray Core` | هسته اصلی برای VLESS / VMess / Trojan |
| `Aether (Rust)` | لایه بومی برای عملیات سطح پایین |
| `Psiphon / Tor / WebTunnel` | مسیرهای جایگزین و چندمرحله‌ای |
| `Direct Compat Route` | تست کانفیگ بدون جایگزینی آدرس/ALPN/FinalMask |

</div>

---

## ◈ ویژگی‌های کلیدی

### ⚡ تونل و پروتکل‌ها

<div align="right">

- تونل کامل سیستم اندروید با `VpnService` + `TUN` بومی
- پشتیبانی از `VLESS` · `VMess` · `Trojan` با حفظ فیلدهای اصلی: `SNI` · `Host` · `Path` · `ALPN` · `Fingerprint` · امنیت و انتقال
- دو حالت اتصال: `Tunnel VPN` و `SOCKS Local Proxy`
- پشتیبانی از Android TV (حالت `tv` با معماری `armeabi-v7a`)

</div>

### 🧠 اتصال تطبیقی (Adaptive Connection)

<div align="right">

- ساخت **اثرانگشت شبکه** بر اساس نوع اتصال (WiFi / Mobile)، اپراتور، `ASN` و سرویس‌دهنده
- مجموعه **Edge اصلی و جایگزین** متناسب با اپراتور
- یادگیری از نتایج موفق و استفاده مستقیم در اتصال‌های بعدی
- بازیابی خودکار اتصال هنگام تغییر شبکه یا افت کیفیت
- انتخاب یک **Champion** و یک **Backup** برای هر کانفیگ × هر شبکه
- مکانیزم `Cooldown` برای مسیرهای ناموفق (عدم تست تکراری بی‌مورد)

</div>
<div align="right">

### 🧪 Route Speed Test

موتور تست کامل با ماتریس پارامترها:

<div align="right">

- ترکیب‌های مستقل `Edge × DNS × Fragment × MTU` (صدها مسیر برای هر کانفیگ)
- مراحل رقابت چندمرحله‌ای: غربال اولیه → راستی‌آزمایی → پایداری → استرس → فینال `A-B-B-A`
- اندازه‌گیری با:
  - راه‌اندازی سرد (Cold Start) هسته Xray
  - تست چندمقصدی HTTP
  - پاسخ DNS و Bootstrap
  - حجم دریافتی (Payload) · توان عملیاتی (Throughput)
  - پینگ · نوسان (Jitter) · نرخ موفقیت · درصد اطمینان
- رتبه‌بندی زنده · توقف/ادامه · رد شدن دستی مرحله
- لیست نهایی مخصوص همان کانفیگ و همان اثرانگشت شبکه

</div>

### 🛠️ کنترل‌های پیشرفته

<div align="right">
<ul>
  <li>کنترل‌های مسیریابی · <b>QUIC</b> · <b>Keepalive</b> · <b>Mux</b> · <b>MTU</b> · <b>FinalMask</b> · <b>Fragment</b></li>
  <li>چند Resolver DNS مستقل (DoH + Bootstrap): <code>Cloudflare</code> · <code>Google</code> · <code>Quad9</code> · <code>AdGuard</code> · <code>OpenDNS</code></li>
  <li><b>Config Maker</b> با دو حالت: <code>Quick Scan</code> (توقف روی اولین نتیجه سالم) و <code>Deep Adaptive Test</code></li>
  <li>ادغام چند اشتراک بدون پاک شدن نتایج قبلی، با حذف خودکار موارد تکراری</li>
</ul>
</div>

### 🔀 مسیریابی و مقاوم‌سازی

<div align="right">

- سه حالت مسیریابی برنامه‌ها:
  - عبور همه برنامه‌ها از VPN
  - `Bypass` برای برنامه‌های انتخابی
  - VPN فقط برای برنامه‌های انتخابی
- **`Ghost Handover`**: انتقال نرم بین مسیرها هنگام افت کیفیت یا تغییر شبکه، بدون قطع کامل
- **`Adaptive Obfuscation`**: انتخاب خودکار تکنیک‌های مقاوم‌سازی متناسب با شبکه
- **`Page Turbo`**: بهینه‌سازی مسیرهای پرتکرار
- لایه انتقال `Psiphon` (با IPC مستقل) و لایه مسیریابی `Tor-Style`

</div>

### 📊 پایش و کنترل

<div align="right">

- نمایش زنده: پینگ · ترافیک (Up/Down) · IP خروجی · کشور · وضعیت سلامت اتصال
- گزارش‌های فنی و لاگ برای عیب‌یابی
- اتصال/قطع سریع از `Android Quick Settings`
- کنترل‌های اعلان (Notification)
- پشتیبانی از QR Code (ZXing) برای وارد کردن کانفیگ

</div>

---

## ◈ معماری داخلی پوشه‌ها

<div dir="ltr" align="left">

```
UAC-SNI-Spoofer-Android/
├── app/
│   ├── src/main/
│   │   ├── java/com/uacspoofer/mobile/
│   │   │   ├── engine/
│   │   │   │   ├── pow/                   ← موتور PoW
│   │   │   │   │   ├── AetherNative       ← اتصال به libaether بومی (Rust)
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
│   │   │   │   └── tor/                   ← موتور Tor
│   │   │   ├── ai/                        ← تشخیص و هوش مصنوعی
│   │   │   ├── core/                      ← هسته مشترک
│   │   │   ├── vpn/                       ← سرویس VPN اندروید
│   │   │   ├── profiles/                  ← مدیریت کانفیگ‌ها و اشتراک‌ها
│   │   │   ├── settings/                  ← تنظیمات
│   │   │   ├── ui/                        ← رابط Compose/Material3
│   │   │   ├── logging/                   ← لاگ و دیباگ
│   │   │   ├── mci/                       ← اپراتور / تشخیص شبکه
│   │   │   └── update/                    ← آپدیت
│   │   ├── cpp/                           ← کد بومی C++ (TUN bridge)
│   │   ├── jniLibs/                       ← libxray, libaether, libtor, libgopsi, libhev-socks5-tunnel, ...
│   │   └── assets/
│   └── libs/                              ← AAR های شخصی‌سازی‌شده (psiphontunnel, libv2ray)
│
├── core/
│   ├── aether/                            ← هسته بومی Rust (PoW low-level)
│   └── quiche/                            ← QUIC/TLS stack
│
├── third_party/
└── scripts/
    ├── isolate_psiphon_aar.py             ← جداسازی Psiphon AAR
    └── patch_v2ray_seq.py                 ← پچ ترتیب Xray/V2ray AAR
```

</div>

---

## ◈ کتابخانه‌های اصلی استفاده‌شده

| کتابخانه | نقش |
|----------|-----|
| `Xray` | هسته اصلی پروکسی (VLESS/VMess/Trojan) |
| `hev-socks5-tunnel` | پل بومی TUN → SOCKS |
| `Psiphon Tunnel` | مسیرهای جایگزین و مقاوم‌سازی |
| `Tor (libtor)` | لایه مسیریابی Tor-style |
| `Aether (Rust core)` | عملیات بومی PoW |
| `Jetpack Compose + Material3` | رابط کاربری |
| `Kotlin Coroutines` | همزمانی |
| `ZXing` | خواندن QR Code |
| `Fresco` | بارگذاری تصاویر/WebP |

---

## ◈ نیازمندی‌ها

| مورد | نسخه |
|------|------|
| اندروید | **7.0** یا بالاتر (API 24+) |
| JDK (برای ساخت) | **17** |
| Android SDK | **35** |
| NDK | `26.3.11579264` |
| Rust toolchain | برای بیلد `core/aether` |
| Python 3 | برای اسکریپت‌های pre-build |
| مجوز VPN | الزامی در اولین اتصال |
| سایر VPNها | هنگام استفاده باید غیرفعال باشند |

---

## ◈ نصب

<div align="right">

۱. آخرین APK را از صفحه [Releases](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases) دریافت کنید.

**برای گوشی‌های ۶۴بیتی (۲۰۱۷ به بعد، پیشنهاد اصلی):**
`UAC-{version}-arm64-v8a-Android7plus.apk`

**برای گوشی‌های قدیمی ۳۲بیتی:**
`UAC-{version}-armeabi-v7a-Android7plus.apk`

**برای همه معماری‌ها (حجم بیشتر):**
`UAC-{version}-universal-Android7plus.apk`

۲. برنامه را نصب و اجرا کنید.
۳. کانفیگ را از فایل، متن، کلیپ‌بورد، QR یا لینک اشتراک وارد کنید.
۴. دکمه اتصال را بزنید و مجوز VPN را تأیید کنید.

</div>

---

## ◈ ساخت از سورس

<div align="right">

ابتدا مطمئن شوید `JDK 17`، `Android SDK 35` و `Android NDK 26.x` نصب هستند. برای ساخت بخش Rust (Aether) به `rustup` با target های اندروید نیاز دارید.

</div>

<div dir="ltr" align="left">

```powershell
git clone https://github.com/Floxu1/UAC-SNI-Spoofer-Android.git
cd UAC-SNI-Spoofer-Android
.\gradlew.bat assembleDebug
```

خروجی دیباگ:

```
app\build\outputs\apk\debug\app-debug.apk
```

برای نسخه انتشار (نیازمند `signing.properties`):

```powershell
.\gradlew.bat assembleRelease
```

</div>

> تسک `preBuild` به‌صورت خودکار کتابخانه Rust `libaether.so` را برای همه ABIها با استفاده از `core/build-android.ps1` بیلد می‌کند.

---

## ◈ APK های خروجی (Release)

| فایل | کاربرد |
|------|--------|
| `UAC-{version}-arm64-v8a-Android7plus.apk` | گوشی‌های ۶۴بیتی (پیشنهاد اصلی، سبک‌ترین) |
| `UAC-{version}-armeabi-v7a-Android7plus.apk` | گوشی‌های ۳۲بیتی قدیمی |
| `UAC-{version}-x86_64-Android7plus.apk` | امولاتور |
| `UAC-{version}-x86-Android7plus.apk` | امولاتور x86 |
| `UAC-{version}-universal-Android7plus.apk` | همه معماری‌ها (حجم بیشتر) |
| `app-tv-armeabi-v7a.apk` | Android TV |

---

## ◈ پشتیبانی و ارتباط

| | |
|---|---|
| 📢 کانال تلگرام | [@UacSniSpoofer](https://t.me/UacSniSpoofer) |
| 👥 گروه پشتیبانی | [@UacSniSpooferGroup](https://t.me/UacSniSpooferGroup) |
| 🐛 گزارش باگ | [GitHub Issues](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/issues) |

---

## ◈ نکته

> کیفیت اتصال به شدت به وضعیت اپراتور، نوع شبکه، کانفیگ انتخابی و شرایط لحظه‌ای بستگی دارد. هیچ کانفیگ یا مسیری روی تمام شبکه‌ها عملکرد یکسانی ندارد.

مجوزها و توضیحات وابستگی‌های شخص ثالث در فایل [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md) قرار دارد.

---

<div align="center">

### ⭐ اگر این پروژه برای شما مفید بود، لطفاً ستاره بدهید

</div>

</div>
