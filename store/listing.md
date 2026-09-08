# Play Store listing copy

Paste these into **Play Console → Grow → Store presence → Main store listing**.
Character limits are Play's; the counts below are measured, not estimated — run
`python3 store/check_listing.py` to re-measure after any edit. A field that is
over its limit is rejected at save time, and Play counts the *rendered* text, so
the counter includes every bullet glyph and newline.

Nothing here is filled in on Sam's behalf where Play wants a personal detail:
the contact email and phone number are typed into the Console, never committed,
because this repository is public.

---

## App name — limit 30

```
simoscal
```

## Short description — limit 80

```
Edit a Simos18 calibration in physical units. Checksum-verified bin out.
```

## Full description — limit 4000

```
simoscal edits a Simos18 ECU calibration file on your device, in the units the engine actually works in — psi, mg/stroke, degrees, lambda — rather than in raw hex.

It is a file editor. It never talks to your car.

WHAT IT DOES

• Opens a .bin together with its XDF definition, and refuses to go further unless it recognises the calibration and both embedded checksums verify.

• Boost curve editor — drag the target curve for each of the five map slots on a switch-patched bin, with the other slots drawn behind it for reference and an optional datalog overlaid on top.

• Calibration editors for limiters, pedal and torque request, lambda enrichment, and the per-slot feature switches.

• Every table in the definition, plotted and editable in physical units, searchable by ID or description.

• A running journal of every edit, carrying the reason you typed for it.

• Build writes a new .bin with the checksums corrected, then verifies its own work: it re-reads every changed table off the saved file and audits the file byte by byte against what the journal says should have moved. An undeclared change fails the build instead of passing quietly.

• Analyze reads SimosTools datalogs and runs a fixed battery of checks — knock, boost tracking, lambda, fuel pressure, turbo heat — with the evidence plot behind every finding.

• Export a context bundle to talk a session over with an assistant outside the app, then bring one recommendations file back. Every item is replayed through the app's own guards before you are asked about it.

WHAT IT DOES NOT DO

• It does not flash. You take the built .bin to a flashing tool yourself.

• It does not talk to the car. No OBD, no Bluetooth, no USB.

• It does not talk to the internet. The app declares no Android permissions at all — not even INTERNET — so it cannot send anything anywhere. Files you open are copied into storage private to the app and stay there until you delete them or uninstall.

WHAT YOU NEED

A Simos18 calibration file and the XDF definition that matches it. The app ships neither, and cannot obtain them for you. Boost and map-slot editing additionally need a switch-patch XDF. Built and tested against the SC8S50 file structure; anything else is refused at the door rather than half-supported.

SAFETY

Software verification is not mechanical safety. These checks prove the file changed only where you said it should and that its checksums are correct. They cannot tell you whether a calibration is safe for your engine — only logging on the car can. A wrong value can damage an engine or leave a vehicle unable to start. You are responsible for what you build and for what you flash.

Not affiliated with Volkswagen, Audi, Continental, or TunerPro.
```

## Release notes / "What's new" — limit 500

```
First build on internal testing.

Opens a Simos18 bin with its XDF, checks the calibration and both checksums before it lets you edit, and gives you the boost curve, the limiters, pedal and torque request, lambda, the per-slot switches, and every other table in physical units. Build writes a checksum-corrected bin and audits it byte by byte against the edit journal.

Offline, no permissions, no flashing.
```

---

## Console fields that are not free text

| Field                    | Value                                                          |
| ------------------------ | -------------------------------------------------------------- |
| App or game              | App                                                             |
| Free or paid             | Free                                                            |
| Category                 | Auto & Vehicles                                                 |
| Tags                     | Vehicle utilities; Tools (choose from Play's fixed list)         |
| Email address            | Sam's, typed into the Console — deliberately not committed here |
| Website                  | https://github.com/SamRyeIn/simoscal-android                    |
| Privacy policy URL       | https://samryein.github.io/simoscal-android/privacy-policy      |
| External marketing       | Leave off — nothing to advertise on an internal track           |

`Auto & Vehicles` over `Tools` because the app is meaningless outside a specific
car context; the Play category shapes which listings it is compared against, and
being the odd file editor in Tools helps nobody.
