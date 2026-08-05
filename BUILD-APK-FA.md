# ساخت APK واقعی tsgymChat

فایل‌های APK قبلی که دستی بسته‌بندی شده بودند معتبر نبودند. این پروژه باید با Android SDK و Gradle واقعی Build شود.

## روش ۱: Android Studio

1. پوشه پروژه را در Android Studio باز کنید.
2. اجازه دهید Gradle Sync کامل شود.
3. از SDK Manager، Android SDK Platform 35 و Build Tools 35.0.0 را نصب کنید.
4. Gradle JDK را روی JDK 17 قرار دهید.
5. از منوی Build گزینه Build APK(s) را بزنید.
6. خروجی در مسیر زیر است:

`app/build/outputs/apk/debug/app-debug.apk`

## روش ۲: GitHub Actions

1. محتویات همین پوشه را داخل یک Repository خصوصی یا عمومی GitHub قرار دهید.
2. وارد تب Actions شوید.
3. Workflow با نام Build tsgymChat APK را اجرا کنید.
4. پس از سبز شدن Build، پایین صفحه Artifact با نام `tsgymChat-debug-apk` را دانلود کنید.
5. ZIP Artifact را باز کنید؛ فایل `app-debug.apk` داخل آن APK واقعی قابل نصب است.

مشخصات:
- App: tsgymChat
- Debug package: ir.tsgym.chat.debug
- Min Android: 7.0 (API 24)
- Target: Android 15 (API 35)
