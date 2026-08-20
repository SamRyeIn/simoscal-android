# Privacy policy — simoscal Android app

**Last updated:** 2026-08-20

> **Before publishing:** two placeholders below must be filled in — the
> developer name and a contact address. They are deliberately left blank rather
> than guessed, because a privacy policy is a public commitment made in a real
> person's name, and Play requires the contact route in it to actually work.
> Publish this at a stable public URL (GitHub Pages off this repo is enough) and
> give that URL to the Play Console.

## Summary

The simoscal Android app does not collect, transmit, or share any data. It
cannot: the app declares **no Android permissions at all**, including no
internet permission, so it has no ability to send anything anywhere. Every file
you open stays on your device, in storage private to the app, until you delete
it or uninstall.

This is enforced by the build, not just promised here. A Gradle task
(`verifyReleaseNoPermissions`) reads the app's *merged* manifest and fails the
build if any permission appears — including one contributed by a library.

## What the app does with your files

You choose a calibration file (`.bin`) and one or more definition files
(`.xdf`) through Android's system file picker. The picker grants the app access
to exactly the files you selected and nothing else; no storage permission is
involved.

Those files are copied into the app's private storage on your device
(`filesDir/imports/`), where no other app can read them. When you build an
edited calibration, the result is written to a separate private staging
directory (`filesDir/staging/`).

The app also stores a small amount of state so a session survives being closed:
a pointer to your current session, kept in the app's private preferences.

## What leaves your device

Nothing, unless you deliberately send it.

The only way anything leaves the app is the Android share sheet, which you
invoke yourself to hand a built calibration file to another app — for example
SimosTools, for flashing. That share grants read access to that one file, to the
app you picked, for that share only. Your imported source files are not
shareable through this route at all; the file provider is scoped to the staging
directory alone.

Where a file goes after you share it is governed by whatever app you shared it
with, not by this app.

## What the app does not do

- No analytics, telemetry, crash reporting, or advertising. There is no such
  code and no such dependency in the app.
- No network access of any kind. There is no internet permission.
- No account, login, or user identifier. The app has no concept of a user.
- No access to location, contacts, camera, microphone, Bluetooth, or USB.
- No connection to a vehicle. This app does not communicate with an ECU and
  cannot flash one.
- No cloud backup of app data. Android backup is disabled
  (`android:allowBackup="false"`), so your imported calibration files are not
  copied off the device by the system.

## Retention and deletion

Files stay in the app's private storage until you remove them in the app, clear
the app's storage in Android Settings, or uninstall the app. Uninstalling
deletes all of it. Because nothing is ever transmitted, there is no server-side
copy to request the deletion of.

## Children

The app is a tool for modifying motor-vehicle engine calibrations and is not
directed to children. It collects no data from anyone, including children.

## Changes

Material changes to this policy will be reflected here with a new "last updated"
date. Because the no-collection claim is enforced by a build gate, a change to
it would require a change to the app's source, which is public.

## Contact

Developer: _[NAME — fill in before publishing]_

Questions about this policy: _[CONTACT EMAIL — fill in before publishing]_

Source code: <https://github.com/SamRyeIn/simoscal-android> and
<https://github.com/SamRyeIn/simoscal>
