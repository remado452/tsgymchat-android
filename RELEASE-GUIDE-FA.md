# راهنمای ساخت نسخه رسمی TsGym-Chat

این پروژه نسخه رسمی Android است:

- نام روی گوشی: `TsGym-Chat`
- نام فایل خروجی: `TsgymChat.apk`
- شناسه رسمی: `ir.tsgym.chat`
- نسخه فعلی: `5.0.0` (`versionCode 50000`)
- خروجی: Release امضاشده، بدون پسوند Debug

## نکته حیاتی SSL

این دو فایل همان تنظیمی هستند که خطای Certum/Android را رفع کرده‌اند و نباید حذف شوند:

- `app/src/main/res/xml/network_security_config.xml`
- `app/src/main/res/raw/certum_android_ca.pem`

اعتبارسنجی SSL غیرفعال نشده و `handler.cancel()` برای خطای واقعی SSL حفظ شده است.

## ۱) جایگزینی سورس GitHub

محتوای ZIP سورس رسمی را در ریشه Repository فعلی قرار دهید و فایل‌های هم‌نام را Replace کنید. فایل کلید خصوصی را داخل Repository نگذارید.

## ۲) تعریف Secrets

در GitHub وارد مسیر زیر شوید:

`Settings → Secrets and variables → Actions → New repository secret`

چهار Secret زیر را بسازید:

1. `TSGYMCHAT_RELEASE_KEYSTORE_BASE64`
2. `TSGYMCHAT_RELEASE_STORE_PASSWORD`
3. `TSGYMCHAT_RELEASE_KEY_ALIAS`
4. `TSGYMCHAT_RELEASE_KEY_PASSWORD`

مقادیر دقیق داخل بسته محرمانه Signing و فایل `PRIVATE-CREDENTIALS.txt` قرار دارند.

## ۳) ساخت APK رسمی

به مسیر زیر بروید:

`Actions → Build official TsGym-Chat APK → Run workflow`

بعد از سبز شدن Build، Artifact زیر را دانلود کنید:

`TsGym-Chat-release`

داخل Artifact سه فایل وجود دارد:

- `TsgymChat.apk`
- `latest.json`
- `SHA256.txt`

## ۴) فعال‌کردن بروزرسانی

دو فایل زیر را از Artifact در هاست جایگزین کنید:

- `/public_html/panel/tsgymchat_app/updates/TsgymChat.apk`
- `/public_html/panel/tsgymchat_app/updates/latest.json`

تا قبل از قرارگرفتن APK رسمی، فایل `latest.json` اولیه روی سرور غیرفعال است و کاربران مسدود نمی‌شوند.

## ۵) نسخه‌های بعدی

برای نسخه بعد فقط در `app/build.gradle` این دو مقدار را افزایش دهید؛ برای مثال:

```gradle
versionCode 50001
versionName '5.0.1'
```

سپس Workflow را دوباره اجرا کنید و `TsgymChat.apk` و `latest.json` جدید را روی هاست جایگزین کنید. Workflow نسخه، اندازه و SHA-256 را خودکار وارد JSON می‌کند.

## آپدیت اجباری و اختیاری

خروجی Workflow به‌صورت پیش‌فرض اجباری است:

```json
"mandatory": true
```

برای آپدیت اختیاری، قبل از آپلود `latest.json` مقدار `mandatory` را `false` کنید و `minSupportedVersionCode` را برابر یا کمتر از نسخه قدیمی قابل‌قبول بگذارید.

Android برای نصب APK دانلودشده، صفحه رسمی Installer را باز می‌کند و تأیید نهایی کاربر لازم است. اپ فایل را قبل از نصب از نظر دامنه HTTPS، حجم، SHA-256، نام Package، نسخه و امضای دیجیتال بررسی می‌کند.

## نسخه آزمایشی قبلی

نسخه Debug قبلی شناسه `ir.tsgym.chat.debug` داشت، اما نسخه رسمی شناسه `ir.tsgym.chat` دارد؛ بنابراین اولین نسخه رسمی ممکن است کنار نسخه آزمایشی نصب شود. پس از اطمینان از عملکرد نسخه رسمی، نسخه قدیمی را حذف کنید. از نسخه رسمی 5.0.0 به بعد، بروزرسانی‌ها روی همان برنامه نصب می‌شوند؛ به شرط حفظ دائمی کلید Release.
