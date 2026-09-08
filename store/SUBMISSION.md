# Play Console submission packet — internal testing

Every Console field, with the answer to give and why it is the right one. Written
for the **internal testing** track: up to 100 testers invited by email address, no
content-review wait, but the store listing, content rating, and all of App
content still have to be completed before Play will let a release out.

Data safety has its own file and is not duplicated here: **`docs/play-data-safety.md`**.
Listing copy has its own file: **`store/listing.md`**.

Nothing in this repository holds a password, a keystore, or a personal contact
detail — the repository is public. Where a field needs one, this file says which
and leaves it to be typed into the Console.

---

## 0. Before opening the Console

| Thing                        | State                                                                    |
| ---------------------------- | ------------------------------------------------------------------------- |
| Play Console developer account | **Sam's to do.** $25 one-time, identity verification, 1–3 days to clear |
| Upload keystore              | Done — `~/Documents/simoscal-keys/simoscal-upload.jks`, with its passwords in `keystore-credentials.txt` beside it. Back that directory up off this laptop |
| Signed AAB                   | Done — `engine/build/outputs/bundle/release/engine-release.aab`          |
| Privacy policy URL           | Live — https://samryein.github.io/simoscal-android/privacy-policy         |

A personal (non-organisation) developer account created after November 2023 must
run a **closed** test with 12 testers for 14 days before it can publish to
production. That requirement does **not** gate internal testing, which is why
internal is the right first track — but it means production is at least two weeks
past the day the closed test starts, and that clock is worth starting early.

---

## 1. Create the app

| Field              | Value                                                     |
| ------------------ | ---------------------------------------------------------- |
| App name           | `simoscal`                                                 |
| Default language   | English (United States) — en-US                            |
| App or game        | App                                                        |
| Free or paid       | Free (cannot be changed to paid later)                     |
| Declarations       | Tick both: Developer Programme Policies, US export laws    |

## 2. App content declarations

| Declaration               | Answer                                                                                                                                        |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Privacy policy            | https://samryein.github.io/simoscal-android/privacy-policy                                                                                     |
| App access                | **All functionality is available without special access.** No login, no account, no region lock, no paywall. See the reviewer note below         |
| Ads                       | **No**, the app contains no ads. No ad SDK is declared and there is no network permission to serve one over                                     |
| Content rating            | Complete the IARC questionnaire — answers in §3                                                                                                 |
| Target audience and content | Age group **18 and over** only. See §4                                                                                                       |
| News app                  | **No**                                                                                                                                          |
| COVID-19 contact tracing  | **No**                                                                                                                                          |
| Data safety               | Per `docs/play-data-safety.md`: no data collected, no data shared                                                                               |
| Government apps           | **No** — not developed by or on behalf of a government                                                                                          |
| Financial features        | **None of these** — no payments, lending, crypto, or investment features                                                                        |
| Health apps               | **No** — not a health app                                                                                                                       |
| Advertising ID            | **No**, the app does not use an advertising ID. Nothing in the dependency set reads one, and `check` fails if any permission appears             |

### The reviewer note App access wants

The app opens files the tester supplies; it ships none, and it cannot fetch any.
That is not restricted access in Play's sense — no credential gates it — but a
reviewer who opens the app with no files sees only the import screen. Put this in
the **Instructions** box so that is expected rather than reported as broken:

> The app edits a Simos18 ECU calibration file that you provide. It ships no
> calibration data and cannot download any, so with no file on the device the
> import screen is as far as it goes — that is correct behaviour, not a failure.
> To exercise it you need a Simos18 `.bin` and a matching TunerPro `.xdf`
> definition, opened through the two file pickers on the first screen. The app
> never connects to a vehicle and never connects to the network; it declares no
> Android permissions at all.

## 3. Content rating questionnaire (IARC)

Category: **Utility, Productivity, Communication or Other**.

| Question                                                         | Answer |
| ---------------------------------------------------------------- | ------ |
| Violence, sexuality, language, controlled substances, gambling    | No, to all |
| Does the app share the user's location with other users?          | No     |
| Does the app allow users to interact or exchange content?         | No     |
| Does the app allow purchase of digital goods?                     | No     |
| Does the app contain, or is it a store for, unmoderated content?  | No     |
| Does the app share personal information with third parties?       | No     |

Expected outcome: **Everyone / PEGI 3 / USK 0**. The subject matter is vehicle
tuning, which the questionnaire does not rate — nothing in it is depicted.

## 4. Target audience

Select **18 and over** and nothing younger. Not because the content is adult, but
because it is the honest answer for a tool that modifies engine calibrations, and
because selecting any under-18 band pulls the app into the Families policy
programme with its own ad, content, and design requirements — a compliance
surface this app has no reason to enter.

Follow-up question "could the app unintentionally appeal to children?" — **No**.

## 5. Main store listing

Copy is in `store/listing.md`. Graphics, all generated by
`store/make_store_graphics.py` and `store/make_screenshots.py`:

| Asset                     | File                                            | Spec        |
| ------------------------- | ----------------------------------------------- | ----------- |
| App icon                  | `store/graphics/play-icon-512.png`              | 512×512 PNG |
| Feature graphic           | `store/graphics/play-feature-graphic-1024x500.png` | 1024×500 PNG |
| Phone screenshots (4)     | `store/graphics/screenshots/phone-0*.png`       | 1080×1920, 9:16 |
| 7-inch tablet screenshots | `store/graphics/screenshots/tablet-0*.png`      | 1920×1080, 16:9 |
| 10-inch tablet screenshots | same six files                                 | 1920×1080, 16:9 |

Play asks for phone screenshots always, and for tablet screenshots separately per
size class. The same six tablet images serve both tablet slots: they were taken
at 1920×1200 on an emulator configured like the Galaxy Tab A9+ this app is built
for, which is in both size classes' range.

## 6. Internal testing release

1. **Testing → Internal testing → Testers**: create an email list and add every
   tester's Google account address. Sam's own account included — a developer
   account cannot install its own internal test build without being on the list.
2. **Create new release**.
3. Upload `engine/build/outputs/bundle/release/engine-release.aab`.
4. Accept **Play App Signing** when offered. Google then holds the app signing
   key and the keystore in `~/Documents/simoscal-keys/` becomes the *upload* key
   only — which is the recoverable kind. Do not decline this.
5. Upload the deobfuscation file so crash traces are readable:
   `engine/build/outputs/mapping/release/mapping.txt`. R8 is on, so without it
   every stack trace comes back obfuscated.
6. Release name: `0.1.0 (1)`. Release notes: from `store/listing.md`.
7. Save → Review release → **Start rollout to Internal testing**.
8. Copy the opt-in URL from the Testers tab and send it to the testers. Each one
   opts in through that link once before Play will show them the app.

Internal testing releases go live in minutes, not days. There is no content
review queue on this track.

## 7. After the first upload

- `versionCode` **must increase on every upload and can never repeat**, even for
  a build that was rejected or replaced. It is `1` now, in
  `engine/build.gradle.kts`. Bump it before every subsequent upload.
- `applicationId` is frozen from the moment this uploads. It is `com.simoscal.app`
  (renamed from `com.simoscal.engine` on 2026-09-08, while renaming was still
  free). It cannot be changed afterwards without publishing a different app.
- The AAB is 26 MB, most of it the Python runtime and NumPy. Play's limit is
  200 MB for the base module, so there is a lot of headroom.
