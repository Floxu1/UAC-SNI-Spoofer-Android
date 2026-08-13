<div align="center">

# UAC SNI Spoofer Android

<a href="./README.md">فارسی</a> · <a href="./README.en.md">English</a>

</div>

<div dir="rtl" align="right">

<h2>معرفی</h2>

<p><span dir="ltr">UAC SNI Spoofer</span> یک ابزار متن‌باز برای مدیریت اتصال‌های امن در اندروید است. برنامه از مسیر بومی <span dir="ltr">VPN/TUN</span> و هسته <span dir="ltr">Xray</span> استفاده می‌کند و برای اتصال سریع، مدیریت کانفیگ‌ها و بررسی وضعیت واقعی شبکه طراحی شده است.</p>

<p>نسخه فعلی: <strong><span dir="ltr">2.0.0</span></strong></p>

<h2>امکانات</h2>

<ul>
  <li>اتصال مستقیم از طریق <span dir="ltr">Android VpnService</span> و <span dir="ltr">Xray Native TUN</span></li>
  <li>سیستم اتصال تطبیقی برای تشخیص شبکه، آزمایش مسیرهای مختلف و ذخیره بهترین نتیجه برای همان شبکه</li>
  <li>کانفیگ داخلی بهینه‌شده همراه با <span dir="ltr">Edge</span>، <span dir="ltr">DNS</span> و مسیرهای جایگزین</li>
  <li>پشتیبانی از کانفیگ‌های <span dir="ltr">VLESS</span>، <span dir="ltr">VMess</span> و <span dir="ltr">Trojan</span></li>
  <li>واردکردن کانفیگ از متن، کلیپ‌بورد، فایل یا لینک اشتراک</li>
  <li>بخش <span dir="ltr">SNI Config Maker</span> با دو حالت <span dir="ltr">Quick Scan</span> و <span dir="ltr">Deep Adaptive Test</span></li>
  <li>اضافه‌شدن لینک‌های اشتراک جدید به لیست قبلی، بدون پاک‌شدن نتیجه‌ها و بدون ثبت کانفیگ تکراری</li>
  <li>نمایش زنده <span dir="ltr">Candidate</span> در حال آزمایش و جزئیات جمع‌شونده برای <span dir="ltr">HTTP</span>، <span dir="ltr">DNS</span>، <span dir="ltr">Edge</span> و <span dir="ltr">Fragment</span></li>
  <li>تشخیص خودکار کشور خروجی و نمایش پرچم برای کانفیگ‌های سالم</li>
  <li>انتخاب چند کانفیگ در صفحه <span dir="ltr">Configs</span> و کپی‌کردن لینک‌های آن‌ها در کلیپ‌بورد</li>
  <li>نمایش پینگ، کشور خروجی، مصرف ترافیک و گزارش‌های زنده</li>
  <li>انتخاب برنامه‌های داخل یا خارج از تونل با <span dir="ltr">App Bypass</span></li>
  <li>تنظیمات پیشرفته برای <span dir="ltr">Fragment</span>، <span dir="ltr">Finalmask</span>، <span dir="ltr">TUN</span>، مسیریابی و <span dir="ltr">Keepalive</span></li>
  <li>انیمیشن اتصال جدید با حلقه پیشرفت و جلوه <span dir="ltr">Glow</span></li>
  <li>بررسی نسخه‌های جدید از بخش <span dir="ltr">Releases</span> همین مخزن و نصب به‌روزرسانی با تأیید کاربر</li>
</ul>

<h2>نیازمندی‌ها</h2>

<ul>
  <li>اندروید ۷ یا جدیدتر</li>
  <li>دادن مجوز استاندارد <span dir="ltr">VPN</span> هنگام اولین اتصال</li>
  <li>خاموش‌بودن برنامه‌های <span dir="ltr">VPN</span> دیگر هنگام استفاده</li>
</ul>

<h2>نصب</h2>

<ol>
  <li>فایل <span dir="ltr">APK</span> نسخه جدید را از بخش <a href="https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases"><span dir="ltr">Releases</span></a> دریافت کنید.</li>
  <li>برنامه را نصب و اجرا کنید.</li>
  <li>کانفیگ موردنظر را انتخاب کنید و دکمه اتصال را بزنید.</li>
  <li>درخواست مجوز <span dir="ltr">VPN</span> را تأیید کنید.</li>
</ol>

<h2>ساخت از سورس</h2>

<p>برای ساخت پروژه به <span dir="ltr">JDK 17</span> و <span dir="ltr">Android SDK 35</span> نیاز دارید.</p>

<pre dir="ltr" align="left"><code>git clone https://github.com/Floxu1/UAC-SNI-Spoofer-Android.git
cd UAC-SNI-Spoofer-Android
.\gradlew.bat assembleDebug</code></pre>

<p>خروجی نسخه دیباگ در مسیر زیر ساخته می‌شود:</p>

<pre dir="ltr" align="left"><code>app\build\outputs\apk\debug\app-debug.apk</code></pre>

<h2>پشتیبانی و ارتباط</h2>

<ul>
  <li>کانال تلگرام: <a href="https://t.me/UacSniSpoofer"><span dir="ltr">t.me/UacSniSpoofer</span></a></li>
  <li>گروه تلگرام: <a href="https://t.me/UacSniSpooferGroup"><span dir="ltr">t.me/UacSniSpooferGroup</span></a></li>
  <li>گزارش مشکل: <a href="https://github.com/Floxu1/UAC-SNI-Spoofer-Android/issues"><span dir="ltr">GitHub Issues</span></a></li>
</ul>

<h2>نکته</h2>

<p>کیفیت اتصال به وضعیت اپراتور، کانفیگ انتخاب‌شده و شرایط شبکه بستگی دارد. هیچ کانفیگی روی تمام شبکه‌ها عملکرد یکسانی ندارد.</p>

<p>مجوزها و توضیحات وابستگی‌های جانبی در فایل <a href="./THIRD_PARTY_NOTICES.md"><span dir="ltr">THIRD_PARTY_NOTICES.md</span></a> قرار گرفته است.</p>

<h3>اگر این پروژه براتون مفید بود، لطفاً ستاره بدین ⭐</h3>

</div>
