<div align="center">

<img width="1672" height="941" alt="UAC SNI Spoofer" src="https://github.com/user-attachments/assets/f99d9c99-a01b-43f7-b3f1-f4077d45cf27" />

# UAC SNI Spoofer — اندروید

**سه موتور اتصال در یک برنامه‌ی دوزبانه: Xray با کانفیگ خودکار یک‌لمسی، Tor با پل‌های WebTunnel و UAC PoW (سایفون روی وارپ)**

[![Release](https://img.shields.io/github/v/release/Floxu1/UAC-SNI-Spoofer-Android?display_name=tag&sort=semver&label=version&style=flat-square&color=7c3aed)](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/Floxu1/UAC-SNI-Spoofer-Android/total?style=flat-square)](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases)
![Android](https://img.shields.io/badge/Android-7.0%2B-green?style=flat-square&logo=android&logoColor=white)
![targetSdk](https://img.shields.io/badge/targetSdk-35-blue?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-purple?style=flat-square&logo=kotlin)
[![Stars](https://img.shields.io/github/stars/Floxu1/UAC-SNI-Spoofer-Android?style=flat-square)](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/stargazers)

**فارسی** · [English](./README.en.md) · [تلگرام](https://t.me/UacSniSpoofer) · [نسخه ویندوز](https://github.com/Floxu1/UAC-SNI-Spoofer-Windows)

</div>

---

<div dir="rtl">

## فهرست

- [معرفی](#معرفی)
- [تغییرات نسخه 2.0.8](#تغییرات-نسخه-208)
- [موتور ۱ — Xray](#موتور-۱--xray)
- [موتور ۲ — Tor](#موتور-۲--tor)
- [موتور ۳ — UAC PoW](#موتور-۳--uac-pow)
- [سرعت و امکانات روزمره](#سرعت-و-امکانات-روزمره)
- [کدام APK را دانلود کنم؟](#کدام-apk-را-دانلود-کنم)
- [شروع سریع](#شروع-سریع)
- [پیش‌نیازها](#پیشنیازها)
- [ساخت از سورس](#ساخت-از-سورس)
- [پرسش‌های رایج](#پرسشهای-رایج)
- [پشتیبانی و ارتباط](#پشتیبانی-و-ارتباط)
- [سلب مسئولیت](#سلب-مسئولیت)

## معرفی

‏UAC SNI Spoofer یک کلاینت متن‌باز اندروید است که روی <code>VpnService</code> و پل بومی <code>TUN</code> (‏<code>hev-socks5-tunnel</code>) ساخته شده و سه موتور مستقل دارد. همیشه فقط یک موتور اجرا می‌شود و تعویض موتور در حین اتصال، اول اتصال فعلی را قطع می‌کند.

| | موتور ۱ — <code>Xray</code> | موتور ۲ — <code>Tor</code> | موتور ۳ — <code>UAC PoW</code> |
|---|---|---|---|
| هسته | <code>Xray</code> (‏VLESS / VMess / Trojan) | <code>Tor</code> + پل‌های <code>WebTunnel</code> | <code>Aether</code> (‏Rust، ‏WARP MASQUE h2) + <code>Psiphon</code> |
| نیاز به کانفیگ | ندارد (حالت خودکار)؛ کانفیگ خودتان هم کار می‌کند | ندارد | ندارد |
| مناسب برای | سریع‌ترین استفاده‌ی روزمره | وقتی مسیرهای دیگر بسته‌اند | خطوطی که کانفیگ‌ها مدام از کار می‌افتند |

رابط کاربری کاملاً فارسی و انگلیسی (راست‌به‌چپ و چپ‌به‌راست) است و با <code>Jetpack Compose</code> و <code>Material 3</code> ساخته شده.

## تغییرات نسخه 2.0.8

- **بازسازی کامل موتور Xray** با کانفیگ خودکار یک‌لمسی: تشخیص اپراتور (همراه اول یا ایرانسل/رایتل)، پشتیبانی ECH و جایگزینی خودکار.
- **افزایش چشمگیر سرعت:** سایت‌های ایرانی مستقیم، کش هوشمند DNS، ‏TCP Fast Open، بافر بزرگ‌تر، آماده‌سازی با روشن شدن صفحه و رتبه‌بندی با سرعت واقعی بعد از اتصال.
- **صفحه‌ی جدید تنظیمات Xray** فقط با گزینه‌های مفید، به‌همراه **مسیر زنده‌ی** کامل که همه‌ی مراحل اتصال و مشخصات کانفیگ فعال را نشان می‌دهد.
- **‏Tor سریع‌تر و پایدارتر** با سریع‌ترین خروجی از اروپای نزدیک.
- **عبور فوری از QUIC** (بدون گیر کردن) و **مصرف کمتر باتری و حافظه** در همه‌ی موتورها.
- **طراحی تازه‌ی رابط کاربری** با انتخاب‌گر موتور متحرک و آیکون‌های جدید.
- **بازنشانی یک‌باره‌ی تنظیمات** هنگام به‌روزرسانی به 2.0.8؛ موتور پیش‌فرض حالا Xray است.

## موتور ۱ — Xray

روی حالت **خودکار** دکمه‌ی **اتصال** را بزنید؛ بقیه‌اش با برنامه است:

۱. **تشخیص اپراتور و نوع فیلترینگ** (همراه اول یا ایرانسل/رایتل).<br>
۲. **دریافت لیست کانفیگ** Patterniha از GitHub، با آینه‌ی <code>jsDelivr</code> در صورت مسدود بودن.<br>
۳. **کلیدهای ECH** برای کانفیگ‌های ECH دریافت و ذخیره می‌شوند.<br>
۴. **مسابقه‌ی آی‌پی تمیز و تست دسته‌ای**: کانفیگ‌ها هم‌زمان از داخل تونل واقعی تست و رتبه‌بندی می‌شوند.<br>
۵. **اتصال و جایگزینی خودکار**: اول بهترین کانفیگ؛ اگر نشد، بعدی‌ها خودکار امتحان می‌شوند.<br>
۶. **نجات با آی‌پی تمیز**: مسیر آخر وقتی همه‌ی مسیرها شکست بخورند.<br>
۷. **پایش سلامت**: اتصال در بیکاری بررسی و در صورت قطعی عوض می‌شود.

همچنین: کانفیگ‌های خودتان (‏<code>VLESS</code> / <code>VMess</code> / <code>Trojan</code>) از فایل، متن، کلیپ‌بورد، QR یا لینک اشتراک، **تست سرعت مسیر** و **سازنده‌ی کانفیگ**.

**مسیر زنده:** از تنظیمات Xray یا جزئیات کارت اتصال باز می‌شود و همه‌ی مراحل را زنده نشان می‌دهد: اپراتور، لیست، ECH، تست دسته‌ای، رتبه‌بندی، اتصال، جایگزینی، DNS و مسیریابی، قابلیت‌های فعال، تأخیر، کشور و آی‌پی خروجی و مشخصات کامل کانفیگ (پروتکل، شبکه، امنیت، ECH، اثر انگشت، SNI، ‏ALPN، هاست و مسیر، فرگمنت، Mux و پورت).

## موتور ۲ — Tor

‏<code>Tor</code> روی پل‌های <code>WebTunnel</code>، با انتخاب سریع‌ترین خروجی از اروپای نزدیک. صفحه‌ی تنظیمات و مسیر زنده‌ی خودش را دارد.

## موتور ۳ — UAC PoW

بدون نیاز به کانفیگ: هسته‌ی بومی Rust به نام <code>Aether</code> یک مسیر WARP روی MASQUE ‏(HTTP/2) می‌سازد و <code>Psiphon</code> از داخل آن عبور می‌کند. کشور خروجی را انتخاب کنید یا روی «خودکار» بگذارید. تنظیمات و مسیر زنده‌ی خودش را دارد.

## سرعت و امکانات روزمره

- **سایت‌های ایرانی مستقیم**: بدون VPN و سریع‌تر باز می‌شوند.
- **کش هوشمند DNS**: سایت‌هایی که قبلاً رفته‌اید فوری باز می‌شوند.
- **‏TCP Fast Open** و **بافر بزرگ‌تر**: صفحه‌ها زودتر و ویدیوها روان‌تر.
- **آماده‌سازی با روشن شدن صفحه**: اتصال همان لحظه‌ی باز کردن قفل آماده است.
- **رد فوری QUIC**: برنامه‌ها بدون گیر کردن سراغ TCP می‌روند.
- **تونل جداگانه (Split tunneling)**: همه‌ی برنامه‌ها، عبور برنامه‌های انتخابی، یا VPN فقط برای برنامه‌های انتخابی.
- حالت **تونل** یا **پروکسی SOCKS محلی**.
- **کاشی تنظیمات سریع** اندروید برای اتصال و قطع با یک لمس.
- نمایش زنده‌ی پینگ، ترافیک، آی‌پی و کشور خروجی.

## کدام APK را دانلود کنم؟

از صفحه‌ی **[Releases](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases/latest)** دانلود کنید:

| فایل | مناسب برای |
|---|---|
| <code>UAC-2.0.8-arm64-v8a.apk</code> | **تقریباً همه‌ی گوشی‌ها** (پیشنهادی) |
| <code>UAC-2.0.8-armeabi-v7a.apk</code> | گوشی‌های قدیمی ۳۲ بیتی |
| <code>UAC-2.0.8-x86_64.apk</code> | شبیه‌سازهای کامپیوتر (مثل LDPlayer) |
| <code>UAC-2.0.8-x86.apk</code> | شبیه‌سازهای قدیمی ۳۲ بیتی |
| <code>UAC-2.0.8-universal.apk</code> | همه‌ی دستگاه‌ها (حجم بیشتر) |

## شروع سریع

۱. فایل APK مناسب دستگاهتان را نصب کنید.<br>
۲. برنامه را باز کنید؛ موتور ۱ (‏Xray) روی **خودکار** به‌طور پیش‌فرض انتخاب شده.<br>
۳. **اتصال** را بزنید و اجازه‌ی VPN را بدهید.<br>
۴. اگر وصل نشد، از انتخاب‌گر بالای صفحه موتور ۲ (‏Tor) یا ۳ (‏PoW) را امتحان کنید.

## پیش‌نیازها

| مورد | نسخه |
|---|---|
| اندروید | 7.0 به بالا (API 24) |
| ‏JDK (برای ساخت) | 17 |
| ‏Android SDK / NDK | 35 / <code>26.3.11579264</code> |
| ‏Rust | برای ساخت <code>core/aether</code> |
| VPNهای دیگر | هنگام اتصال باید خاموش باشند |

## ساخت از سورس

</div>

```powershell
git clone https://github.com/Floxu1/UAC-SNI-Spoofer-Android.git
cd UAC-SNI-Spoofer-Android
.\gradlew.bat :app:assembleRelease
# -> app\build\outputs\apk\release\UAC-<version>-<abi>.apk
```

<div dir="rtl">

ساخت نسخه‌ی release به فایل <code>signing.properties</code> نیاز دارد (نمونه: <code>signing.properties.example</code>). مرحله‌ی <code>preBuild</code> کتابخانه‌ی Rust ‏<code>libaether.so</code> را برای همه‌ی معماری‌ها می‌سازد.

## پرسش‌های رایج

<details><summary><b>موتور ۱ وصل نمی‌شود.</b></summary>

روی خودکار بگذارید و دوباره امتحان کنید؛ خودش دوباره تست و جایگزین می‌کند. اگر خط شما همه‌چیز را می‌بندد، Tor یا PoW را امتحان کنید.
</details>

<details><summary><b>بعد از به‌روزرسانی به 2.0.8 تنظیماتم پاک شد.</b></summary>

این بازنشانی یک‌باره‌ی نسخه‌ی 2.0.8 است تا موتور جدید Xray تمیز شروع کند. دیگر تکرار نمی‌شود.
</details>

<details><summary><b>می‌توانم دو موتور را هم‌زمان روشن کنم؟</b></summary>

خیر. همیشه فقط یک موتور اجرا می‌شود و تعویض در حین اتصال، اول اتصال را قطع می‌کند.
</details>

## پشتیبانی و ارتباط

| | |
|---|---|
| 📢 کانال | [@UacSniSpoofer](https://t.me/UacSniSpoofer) |
| 👥 گروه | [@UacSniSpooferGroup](https://t.me/UacSniSpooferGroup) |
| 🐛 گزارش مشکل | [GitHub Issues](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/issues) |

## سلب مسئولیت

کیفیت اتصال به اپراتور، نوع شبکه و شرایط لحظه بستگی دارد و هیچ مسیری همه‌جا یکسان کار نمی‌کند. از این نرم‌افزار مسئولانه و مطابق قوانین مربوط به خودتان استفاده کنید. مجوزهای کتابخانه‌های جانبی: [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md).

</div>

---

<div align="center">

### ⭐ از برنامه راضی هستی؟ با ستاره دادن در گیت‌هاب از ما حمایت کن

[![Star on GitHub](https://img.shields.io/github/stars/Floxu1/UAC-SNI-Spoofer-Android?style=social)](https://github.com/Floxu1/UAC-SNI-Spoofer-Android)

</div>