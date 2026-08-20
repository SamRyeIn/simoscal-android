# Play Console — Data safety answers

The Data safety form is a declaration Google holds you to, and a wrong answer is
treated as a policy violation rather than a mistake. Every answer below is
followed by the thing in this repo that makes it true, so it can be re-checked
before each submission instead of copied forward on trust.

**Verified against commit `cf324b7`, 2026-08-20.**

## The form

| Question                                                     | Answer | Why that is true                                                                                     |
|--------------------------------------------------------------|--------|------------------------------------------------------------------------------------------------------|
| Does your app collect or share any of the required user data types? | **No** | No network permission exists, so transmission is impossible. See the evidence section below.          |
| Is all of the user data collected by your app encrypted in transit? | n/a    | Not asked once collection is "No" — there is no transit.                                              |
| Do you provide a way for users to request that their data is deleted? | n/a    | Not asked once collection is "No". Uninstalling removes everything; there is no server-side copy.     |
| Does your app collect data from children?                     | **No** | Nothing is collected from anyone.                                                                     |
| Data types: location, personal info, financial, health, messages, photos, files, contacts, calendar, app activity, web browsing, app info and performance, device or other IDs | **None selected** | None are read, and none could be transmitted if they were. |

### The one that needs care

"Files and docs" is the answer people get wrong on an app like this, because the
app plainly *opens files you choose*. Play's definition of **collection** is
transmission off the device. Data that is only read and stored locally, never
sent anywhere, is not collected and must not be declared as collected — a
false positive here is as wrong as a false negative.

If a future version ever gains network access — sync, backup, a crash reporter,
a "share your tune" feature — this answer changes to **Yes / Files and docs**,
and the privacy policy changes with it. That is the trigger to revisit this file.

## Evidence, so this can be re-verified rather than trusted

1. **No permissions, enforced by the build.** `engine/build.gradle.kts` defines
   `VerifyNoPermissionsTask`, which parses the *merged* manifest and fails the
   build if any `uses-permission` appears — a library-contributed one included.
   It is wired into `check`, and it runs on the release variant, not only debug:

   ```
   $ cat engine/build/reports/permissions/release.txt
   merged manifest: AndroidManifest.xml
   unexpected permissions: none
   allowed: com.simoscal.engine.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
   ```

   The single allowed entry is defined by the app for itself by AndroidX Core to
   guard a non-exported broadcast receiver. It is signature-level, scoped to this
   application id, and grants no access to network, storage, location, or the
   vehicle. It is allow-listed by exact name so a real permission cannot ride
   in beside it.

2. **No network, analytics, or telemetry code.** Nothing in
   `engine/src/main/java/` references HTTP, sockets, OkHttp, Retrofit, Firebase,
   Crashlytics, analytics, or telemetry, and no such dependency is declared.
   Without `android.permission.INTERNET` the platform would refuse the call
   regardless.

3. **Imported files are app-private.** `ImportStore` writes under
   `context.filesDir` (`imports/`, `staging/`) — private per-app storage no other
   app can read.

4. **Sharing is user-initiated and narrowly scoped.**
   `engine/src/main/res/xml/file_paths.xml` exposes **only** `staging/` through
   the FileProvider. The `imports/` directory holding your source bin and XDFs is
   deliberately not exposed, so nothing you imported can be handed to another app
   this way. The provider is `android:exported="false"` and works by per-share
   URI grant.

5. **No system backup.** `android:allowBackup="false"` in the manifest, so
   Android does not copy app data off the device.

6. **No account or identifier.** The app has no login, no user concept, and
   generates no device or advertising ID.

## Store listing items this does not cover

- A hosted **privacy policy URL** is mandatory. `docs/privacy-policy.md` is the
  text; it still needs a public URL and has two placeholders to fill in first.
- Data safety is not the only declaration in the Console. The content rating
  questionnaire, target audience, ads declaration (none), and the government-apps
  and financial-features questions are separate and are not addressed here.
