tsgymChat Android hotfix v3.3.1

Replace these files in the existing GitHub repository:
- app/src/main/java/ir/tsgym/chat/MainActivity.java
- app/build.gradle

Fixes:
- opens the known-good app_login URL directly
- removes custom request headers
- removes the false blank-page detector during redirects
- enables compatible storage/cookie settings
- versionCode 8 / versionName 3.3.1
