# Release builds

Two channels, one key. Side-loaded APKs ship today from `dist/`; the Google
Play listing is planned and its Console steps are parked below until the
business entity and developer account exist. Both builds are R8-minified with
resource shrinking and signed with the same local keystore, which is never
committed.

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
Without these keys `assembleRelease` and `bundleRelease` still build, unsigned.
**Back the keystore up.** With Play App Signing set up as described below, this
key is both the upload key and the app signing key, so side-loaded and Play
installs share a certificate and upgrade over each other in place.

## Side-load build (APK)
R8 is too heavy for a Claude Code session on the shared box; build in a plain
terminal, then hand over:
```
./gradlew :app:assembleRelease
```
1. Verify: `aapt2 dump badging` shows the intended `versionCode`/`versionName`,
   `apksigner verify --verbose` passes, `zipalign -c -P 16 -v 4` passes (16 KB
   page alignment).
2. On-device smoke test on the headless emulator (`CLAUDE.md`): uninstall,
   install, assert the installed `base.apk` md5, launch, play, foreground
   service `isForeground=true`, `hush:playback` wake lock, timer fade, no crash.
3. `dist/hush-<version>.apk` (remove the previous one), README download link,
   commit (docs/APK-only pushes skip the test gate), tag `v<version>`, GitHub
   release with the APK, `rclone copyto dist/hush-<version>.apk
   "gdrive:Hush/APKs/hush-<version>.apk"`.

Side-load: allow "install unknown apps" for the file manager, open the APK.
Upgrades in place as long as the same keystore signs every build.

## Play build (AAB)
Play accepts only Android App Bundles for new apps. Same plain-terminal rule:
```
./gradlew :app:bundleRelease
# -> app/build/outputs/bundle/release/app-release.aab (signed with the same config)
```
Verify it installs the way Play will deliver it — bundletool (release 1.18.x,
`bundletool-all-<v>.jar` from github.com/google/bundletool/releases):
```
java -jar bundletool-all.jar build-apks --bundle=app/build/outputs/bundle/release/app-release.aab \
  --output=/tmp/hush.apks --mode=universal \
  --ks=$HOME/.keystores/hush-release.jks --ks-key-alias=hush
unzip -o /tmp/hush.apks universal.apk -d /tmp && adb install /tmp/universal.apk
```
then the same smoke test as the APK. `versionCode` must rise on every upload
(0.2.0 = 10); Play rejects reuse.

## Store assets
- **Icon (512 × 512) and feature graphic (1024 × 500)** are rendered by the
  screenshot harness — `app/src/screenshotTest/.../StoreAssets.kt`, references
  under `app/src/screenshotTestDebug/reference/dev/tapio/hush/StoreAssetsKt/`.
  They are exact-size PNGs (the previews pin 160 dpi) and regenerate with
  `./gradlew updateDebugScreenshotTest`, so they can never drift from the app.
- **Screenshots** are captured on the emulator and kept on Drive
  (`gdrive:Hush/Store/<version>/`), not in git. Play wants 320–3840 px and an
  aspect ratio no wider than 2:1, so the 20:9 phone is captured at 16:9:
  ```
  adb shell wm size 1080x1920 && adb shell wm density 420   # phone, 411 × 731 dp
  adb shell wm size 1200x2048 && adb shell wm density 320   # 7" tablet, 600 × 1024 dp
  adb shell wm size 2560x1600 && adb shell wm density 320   # 10" tablet, 1280 × 800 dp
  adb exec-out screencap -p > shot.png
  adb shell wm size reset && adb shell wm density reset
  ```
  Swap width and height for the other orientation; the layout follows the
  window (`specs/ui.md`, **Layout**). Set a mix first (a scene chip is one
  tap) and hide the emulator's "3G" status oddities if they bother you.
- **Foreground-service demo video** (the Console asks for one with the
  `mediaPlayback` declaration): `adb shell screenrecord --time-limit 25
  /sdcard/fgs.mp4` while you start playback, pull down the notification shade
  to show the media notification, press Home and come back still playing;
  `adb pull /sdcard/fgs.mp4`. Also on Drive.

## Play Console runbook — parked until the developer account exists
Everything the app itself needs is done; these are Console-side steps.
1. **Create the app**: name Hush, default language English (US), app (not
   game), free. The package `dev.tapio.hush` is fixed at the first upload.
2. **Play App Signing with the existing key** (so side-load and Play installs
   share one certificate): Setup → App signing → *Use a different key* /
   *Export and upload a key from Java keystore*. Download `pepk.jar` and the
   `encryption_public_key.pem` the page offers, then
   ```
   java -jar pepk.jar --keystore=$HOME/.keystores/hush-release.jks --alias=hush \
     --output=hush-pepk.zip --include-cert --rsa-aes-encryption \
     --encryption-key-path=encryption_public_key.pem
   ```
   and upload `hush-pepk.zip`. Register the same keystore's certificate as the
   upload key. Do this **before** the first AAB upload — it cannot be changed
   afterwards without a key-upgrade request.
3. **Foreground service declaration** (App content → Foreground service
   permissions): type *Media playback*; purpose "plays generated sleep sounds
   in the background and with the screen off, controlled from the media
   notification and lock screen"; attach the demo video.
4. **Data safety**: no data collected, no data shared; no third-party SDKs; the
   crash report is on-device and only ever shared by the user through the
   system share sheet (that is not "collection"). Data is not encrypted in
   transit because there is no transit. Users can delete app data by clearing
   the app or uninstalling.
5. **Privacy policy URL**: https://tapio.dev/hush/privacy — publish
   `docs/privacy-policy.md` there first.
6. **Declarations**: Ads — none. App access — all features available without
   credentials. Government app — no. Financial features — none. Health —
   answer the health-apps form honestly: Hush plays sounds and tracks nothing.
   Content rating (IARC questionnaire) — expect *Everyone*. Target audience —
   13 and over or 18 and over; do **not** select under-13 groups (that would
   pull in the Families policy; the companion animal is decoration, not a
   feature for children).
7. **Store listing**: app name ≤ 30 characters (Hush), short description ≤ 80,
   full description ≤ 4000, the icon and feature graphic above, at least two
   phone screenshots plus the 7" and 10" tablet sets (that is what earns the
   "designed for tablets" treatment), category *Health & Fitness* (or
   *Lifestyle*), tags "sleep", "white noise".
8. **Release**: upload the AAB to an *internal testing* track first, install
   from Play on the Pixel, then promote to production. Read the pre-launch
   report: it runs the app on real devices in both orientations.

## Play requirements already met (verified 2026-09-16)
targetSdk 36 (the Aug 2026 bar) · 16 KB page-size alignment (the two AndroidX
`.so` libraries load at 0x4000; `zipalign -P 16` clean) · adaptive icon with a
monochrome layer · edge-to-edge · no `INTERNET` permission · foreground-service
type declared in the manifest · large-screen layout in both orientations, no
orientation lock (`specs/ui.md`, **Layout**) · backup rules
(`specs/persistence.md`, **Backup**) · signed AAB from the same key.
