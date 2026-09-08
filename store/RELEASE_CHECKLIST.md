# Play internal-testing release checklist

Every line is either **DONE** with the evidence that proves it, or **SAM** with
the exact steps. Nothing is left unclassified: an item nobody owns is an item
that does not happen.

Prepared 2026-09-08 for the first upload of `com.simoscal.app` 0.1.0 (1).

Re-verify any DONE line by running the command in its evidence column. The
environment for all Gradle commands:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME="$HOME/Library/Android/sdk"
cd ~/simoscal-android
```

---

## Build and identity

| # | Item | Owner | Evidence / steps |
| - | ---- | ----- | ---------------- |
| 1 | `applicationId` is the permanent public name `com.simoscal.app` | **DONE** | `aapt2 dump badging engine/build/outputs/apk/release/engine-release.apk` → `package: name='com.simoscal.app'`. Renamed from `com.simoscal.engine` before first upload, while it was still free to change |
| 2 | `targetSdk` meets Play's current minimum | **DONE** | Same badging dump → `targetSdkVersion:'35'`. Play requires 35 for new apps |
| 3 | Whole suite green under the new id | **DONE** | `./gradlew :engine:check -Psimoscal.dir=/Users/sam/SimosTools/Code` → 367 debug + 367 release unit tests, 0 failures |
| 4 | Manifest declares no permissions, release variant included | **DONE** | `cat engine/build/reports/permissions/release.txt` → `unexpected permissions: none`. Enforced by `VerifyNoPermissionsTask`, wired into `check` |
| 5 | Minified release build actually runs | **DONE** | Installed on an arm64 emulator and driven end to end: bin + XDF + switch-patch XDF imported, preflight passed, boost curve edited and applied, build produced a verified `R00.bin` with checksums/readback/blocked-writes/switch-patch gates passing. R8 keeps are intact |
| 6 | `versionCode` 1, `versionName` 0.1.0 | **DONE** | `engine/build.gradle.kts`. Must increase on every later upload and can never repeat |

## Signing

| # | Item | Owner | Evidence / steps |
| - | ---- | ----- | ---------------- |
| 7 | Upload keystore exists, outside the repo | **DONE** | `~/Documents/simoscal-keys/simoscal-upload.jks`, RSA 4096, 10000 days. Passwords in `keystore-credentials.txt` beside it, mode 600 |
| 8 | `keystore.properties` points at it and cannot be committed | **DONE** | `git check-ignore -v keystore.properties` → `.gitignore:18` |
| 9 | Release artifacts are signed by that key | **DONE** | `apksigner verify --print-certs` on the APK → SHA-256 `8c53a7a6…1acc`, identical to `keytool -list -v` on the keystore. `jarsigner -verify` on the AAB → `jar verified` |
| 10 | Back the keystore up somewhere that is not this laptop | **SAM** | Copy `~/Documents/simoscal-keys/` to a password manager or an encrypted backup. With Play App Signing this key is resettable through support, so losing it is survivable — but only while the developer account still exists |

## Store assets

| # | Item | Owner | Evidence / steps |
| - | ---- | ----- | ---------------- |
| 11 | 512×512 app icon | **DONE** | `store/graphics/play-icon-512.png`, 512×512, 17 KB |
| 12 | Launcher icon is the same artwork as the store icon | **DONE** | Both come from `draw_artwork()` in `store/make_store_graphics.py`; `--mipmaps` writes the five density PNGs and the adaptive icon from that one function |
| 13 | Adaptive launcher icon | **DONE** | `engine/src/main/res/mipmap-anydpi-v26/`, foreground inset to the 66/108 safe square on `@color/promo_bg`. Verified rendering on device by temporarily swapping the background colour and watching it change |
| 14 | 1024×500 feature graphic | **DONE** | `store/graphics/play-feature-graphic-1024x500.png`, 23 KB |
| 15 | ≥2 phone screenshots, 9:16 | **DONE** | 4 at 1080×1920 in `store/graphics/screenshots/phone-0*.png`, all under 300 KB |
| 16 | Tablet screenshots, 16:9 | **DONE** | 6 at 1920×1080 in `store/graphics/screenshots/tablet-0*.png`. Serve both the 7-inch and 10-inch slots |
| 17 | Screenshots come from the real app | **DONE** | `store/captures/` holds the raw `adb exec-out screencap` frames off the running minified release build; `store/make_screenshots.py` only crops the status bar and frames them |
| 18 | Listing copy within Play's limits | **DONE** | `python3 store/check_listing.py` → name 8/30, short 72/80, full 2725/4000, release notes 407/500 |

## Console work

| # | Item | Owner | Evidence / steps |
| - | ---- | ----- | ---------------- |
| 19 | Play Console developer account | **SAM** | play.google.com/console, $25 one-time, identity verification. Allow 1–3 days. Nothing below can start until this clears |
| 20 | Create the app | **SAM** | `store/SUBMISSION.md` §1 |
| 21 | App content declarations | **SAM** | `store/SUBMISSION.md` §2 — every question with its answer |
| 22 | Content rating questionnaire | **SAM** | `store/SUBMISSION.md` §3. Expect Everyone / PEGI 3 |
| 23 | Target audience: 18 and over | **SAM** | `store/SUBMISSION.md` §4, and why nothing younger |
| 24 | Data safety form | **SAM** | `docs/play-data-safety.md` — answers plus the evidence behind each |
| 25 | Main store listing | **SAM** | `store/listing.md` for text, `store/graphics/` for images |
| 26 | Privacy policy URL reachable | **DONE** | `curl -I -L https://samryein.github.io/simoscal-android/privacy-policy` → 200. GitHub Pages builds from `main` `/docs` |
| 27 | Confirm the contact mailbox is real | **SAM** | The privacy policy publishes `simoscal.tuning@gmail.com`. Play will also want a contact address, and a Console notice sent to a mailbox nobody reads is a notice missed |
| 28 | Internal testing tester list | **SAM** | `store/SUBMISSION.md` §6.1. Add your own Google account too — a developer account cannot install its own internal build unless it is on the list |
| 29 | Upload the AAB | **SAM** | `engine/build/outputs/bundle/release/engine-release.aab`, 26 MB, built 2026-09-08 |
| 30 | Accept Play App Signing | **SAM** | Offered on first upload. Take it: Google then holds the app signing key and the local keystore becomes the recoverable upload key |
| 31 | Upload the deobfuscation mapping | **SAM** | `engine/build/outputs/mapping/release/mapping.txt`. R8 is on; without this every crash trace is obfuscated |
| 32 | Roll out and send the opt-in link | **SAM** | `store/SUBMISSION.md` §6.7–8. Live in minutes; no review queue on internal testing |

## Decisions worth making before you upload

| # | Item | Owner | Notes |
| - | ---- | ----- | ----- |
| 33 | The screenshots show a real tune | **SAM** | The captures were taken against `Patched_259L_R24.bin`, so the boost panels show this car's actual target curves, and the Changes and Build panels name a real table and a real edit. That data is already public in the `gti-tune` repository, so this changes nothing — but a store listing is a wider audience than a repository, and it is your call. To swap to the stock bin instead, recapture with `Code/bin/5G0906259L__0002.bin`; the Boost and Slots screens are unreachable without a switch-patched bin, so the hero shot would be lost |
| 34 | Production is at least two weeks out | **SAM** | A personal developer account has to run a closed test with 12 testers for 14 days before production. Internal testing is not gated by it, but the clock only starts when the closed test does — worth starting early if production is the goal |
| 35 | `Docs/` name collision | **SAM** | Nothing to do now. Noted because `docs/` is both the design record and the GitHub Pages source; a stray `.md` added there becomes a public web page unless `docs/_config.yml`'s `exclude:` list grows with it |
