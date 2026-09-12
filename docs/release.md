# Release builds

The release build is R8-minified with resource shrinking and is signed with a
local keystore that is never committed.

## One-time keystore
```
mkdir -p ~/.keystores
keytool -genkeypair -v -keystore ~/.keystores/hush-release.jks -alias hush \
  -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12
```
Then add to `local.properties` (untracked):
```
hush.keystore=/home/<you>/.keystores/hush-release.jks
hush.keystorePassword=...
hush.keyAlias=hush
hush.keyPassword=...
```
Without these keys `assembleRelease` still builds, unsigned.

## Build and ship
```
./gradlew assembleRelease
rclone copyto app/build/outputs/apk/release/app-release.apk "gdrive:Hush/APKs/hush-<yyyymmdd-hhmm>.apk"
```
Side-load: allow "install unknown apps" for the file manager, open the APK.
Upgrades in place as long as the same keystore signs every build.
