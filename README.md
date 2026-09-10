<div align="center">

<img width="1672" height="941" alt="UAC SNI Spoofer" src="https://github.com/user-attachments/assets/f99d9c99-a01b-43f7-b3f1-f4077d45cf27" />

<br/>

# UAC SNI Spoofer Android

**ابزار متن‌باز پیشرفته برای مدیریت اتصال، عبور ترافیک و بهینه‌سازی مسیر شبکه در اندروید**

<br/>

[![Release](https://img.shields.io/github/v/release/Floxu1/UAC-SNI-Spoofer-Android?display_name=tag&sort=semver&label=version&style=flat-square&color=7c3aed)](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases/latest)
![Android](https://img.shields.io/badge/Android-7.0%2B-green?style=flat-square&logo=android&logoColor=white)
![License](https://img.shields.io/badge/License-Open%20Source-blue?style=flat-square)
![Core](https://img.shields.io/badge/Core-Xray-orange?style=flat-square)

<br/>

[فارسی](./README.md) · [English](./README.en.md)

</div>

---

<div dir="rtl" align="right">

## ◈ معرفی

**UAC SNI Spoofer** یک ابزار متن‌باز پیشرفته برای مدیریت اتصال‌های امن، عبور ترافیک و بهینه‌سازی مسیر شبکه در اندروید است.

برنامه از یک معماری چندلایه استفاده می‌کند:

<div align="right">

- تونل کامل سیستم — `Android VpnService`
- مسیر بومی — `TUN Native Route`
- هسته اصلی — `Xray Core`
- موتور جدید بهینه‌سازی مسیر — `PoW (Path Optimization Wrapper)`

</div>

> هدف اصلی پروژه ایجاد یک سیستم هوشمند برای انتخاب مسیر، مقاوم‌سازی اتصال، مدیریت تغییرات شبکه و کاهش اختلال‌های ناشی از شرایط مختلف اپراتورها است.

---

## ◈ معماری اتصال

| لایه | نقش |
|------|------|
| `VPN Layer` | استفاده از `Android VpnService` برای ایجاد تونل کامل سیستم |
| `Xray Core` | پشتیبانی از پروتکل‌های مدرن و مدیریت انتقال‌های پیچیده |
| `PoW Engine` | بررسی، انتخاب و مدیریت هوشمند مسیرهای اتصال |
| `Aether Native Layer` | مدیریت عملیات سطح پایین و ارتباط سریع بین لایه‌های اندروید و موتور شبکه |
| `Tun Relay / SOCKS Bridge` | مدیریت مسیرهای `TUN → SOCKS → Transport` برای انعطاف بیشتر |

---

## ◈ امکانات

**تونل و اتصال**

<div align="right">

- تونل کامل اندروید با `VpnService` و مسیر بومی `TUN`
- پشتیبانی از موتور `Xray` برای مدیریت کانفیگ‌های `VLESS` ،`VMess` و `Trojan`
- پشتیبانی از `SOCKS Local Proxy` در کنار حالت `Tunnel VPN`

</div>

**هوش مصنوعی مسیر — PoW Adaptive Network Engine**

<div align="right">

- تشخیص خودکار شرایط شبکه
- امتیازدهی و رتبه‌بندی مسیرها
- ذخیره‌سازی نتایج موفق برای هر کانفیگ و هر شبکه
- انتخاب خودکار بهترین مسیر

</div>

**معیارهای رتبه‌بندی — Network Scoreboard**

<div align="right">

- تأخیر — `Latency`
- پایداری بسته‌ها — `Packet Stability`
- نرخ موفقیت — `Success Rate`
- اثرانگشت شبکه — `Network Fingerprint`
- اپراتور و مسیر — `Carrier / ASN`

</div>

**قابلیت‌های پیشرفته**

<div align="right">

- مدیریت هوشمند تکنیک‌های مقاوم‌سازی اتصال برای شرایط مختلف شبکه — `Adaptive Obfuscation`
- انتقال نرم بین مسیرها هنگام تغییر شبکه یا افت کیفیت، بدون قطع کامل اتصال — `Ghost Handover`
- بهینه‌سازی مسیرهای پرتکرار و کاهش زمان برقراری اتصال — `PoW Page Turbo`
- معماری ارتباطی جداگانه برای مدیریت سرویس‌ها، IPC و مسیرهای جایگزین — `Psiphon Compatible Transport Layer`
- مدیریت مسیرهای چندمرحله‌ای و لایه‌ای برای افزایش انعطاف شبکه — `Tor Style Routing Layer`
- تست و رتبه‌بندی مسیرهای مختلف: `Edge × DNS × Fragment × MTU`

</div>

**مدیریت برنامه‌ها**

<div align="right">

- برای همه برنامه‌ها — `Global VPN`
- `Bypass` انتخابی برای برنامه‌های مشخص
- `VPN` فقط برای برنامه‌های انتخابی

</div>

**وارد کردن کانفیگ**

<div align="right">

- `Text` · `Clipboard` · `File` · `Subscription URL`

</div>

**نمایش زنده**

<div align="right">

- `Ping` · `Traffic` · `Exit IP` · `Country` · `Connection Health`
- کنترل سریع از `Android Quick Settings`

</div>

---

## ◈ موتور PoW

موتور `PoW` به عنوان یک لایه مستقل برای هوشمندسازی اتصال طراحی شده است.
به جای استفاده از یک مسیر ثابت، مسیرهای مختلف را بررسی کرده و بر اساس شرایط واقعی شبکه تصمیم می‌گیرد.

```
تشخیص تغییر شبکه  →  امتیازدهی مسیرها  →  ذخیره برنده و مسیر پشتیبان
        ↓
Recovery خودکار  ←  Adaptive Routing  ←  مدیریت اتصال‌های طولانی‌مدت
```

---

## ◈ نیازمندی‌ها

| مورد | مقدار |
|------|-------|
| Android | 7.0 یا بالاتر |
| JDK (ساخت) | 17 |
| Android SDK | 35 |
| مجوز VPN | هنگام اولین اتصال |

---

## ◈ ساخت از سورس

```bash
git clone https://github.com/Floxu1/UAC-SNI-Spoofer-Android.git
cd UAC-SNI-Spoofer-Android
.\gradlew.bat assembleDebug
```

خروجی:

```
app\build\outputs\apk\debug\app-debug.apk
```

> بخش‌های Native شامل موتورهای سطح پایین در مسیر `core/aether` قرار دارند.

---

## ◈ ساختار پروژه

```
app/
 └── engine/
      └── pow/
          ├── AetherNative
          ├── PowCoreConfig
          ├── PowEngineStore
          ├── PowAdaptiveObfuscation
          ├── PowGhostHandover
          ├── PowNetworkScoreboard
          ├── PowTun2Socks
          └── PowPsiphonService

core/
 └── aether/

scripts/
 ├── isolate_psiphon_aar.py
 └── patch_v2ray_seq.py
```

---

## ◈ پشتیبانی

| | |
|---|---|
| 📢 Telegram | [@UacSniSpoofer](https://t.me/UacSniSpoofer) |
| 👥 Group | [@UacSniSpooferGroup](https://t.me/UacSniSpooferGroup) |
| 🐛 Issues | [GitHub Issues](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/issues) |

---

## ◈ توضیحات

> عملکرد اتصال به عوامل مختلفی مانند اپراتور، کیفیت شبکه، کانفیگ انتخابی و شرایط مسیر بستگی دارد.
> هیچ مسیر یا کانفیگی برای تمام شبکه‌ها بهترین نتیجه را تضمین نمی‌کند.

مجوزها و وابستگی‌های خارجی در فایل [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md) قرار دارند.

---

<div align="center">

اگر پروژه برای شما مفید بود، لطفاً ⭐ بدهید

</div>

</div>
