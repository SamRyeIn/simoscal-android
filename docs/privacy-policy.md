---
layout: default
title: Privacy policy
---

# Privacy policy — simoscal Android app

**Last updated:** 2026-08-25

## Summary

The simoscal Android app does not collect or automatically transmit any data. It
declares **no Android permissions at all**, including no internet permission, so
it cannot send anything to a server. Every file you open stays on your device,
in storage private to the app, until you delete it or uninstall.

You can deliberately share two generated files through Android's system share
sheet: a verified calibration you built, or a context bundle you asked the app
to create for use with an assistant such as Claude. The app sends either file
only to the destination you choose.

This is enforced by the build, not just promised here. A Gradle task
(`verifyReleaseNoPermissions`) reads the app's *merged* manifest and fails the
build if any permission appears — including one contributed by a library.

## What the app does with your files

You choose calibration files (`.bin`), definition files (`.xdf`), datalogs
(`.csv`), and recommendation files (`.json`) through Android's system file
picker. The picker grants the app access to exactly the files you selected and
nothing else; no storage permission is involved.

Those files are copied into the app's private storage on your device
(`filesDir/imports/`), where no other app can read them. When you build an
edited calibration, the result is written to a separate private staging
directory (`filesDir/staging/`).

The app also stores a small amount of state so a session survives being closed:
a pointer to your current session, kept in the app's private preferences.

## What leaves your device

Nothing, unless you deliberately send it.

The only way anything leaves the app is the Android share sheet, which you
invoke yourself. You can use it to hand a built calibration to another app — for
example SimosTools, for flashing — or to send a generated context bundle to an
assistant.

A context bundle contains decoded physical table values from the open session,
its edit journal, notes you typed for the assistant, findings from any datalogs
you selected, and provenance hashes. It does **not** contain the bytes of the
source calibration or definition files. Read the share-sheet destination before
sending it: the bundle can still contain detailed information about your
calibration and logs.

Each share grants read access to that one generated file, to the app you picked,
for that share only. Your imported source files, XDFs, datalogs, and
recommendations are not shareable through this route at all; the file provider
is scoped to the staging directory alone.

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
deletes all of it. Because the app has no server and performs no upload, there is
no server-side copy to request the deletion of. A file you deliberately shared
is controlled by the destination app you chose.

## Children

The app is a tool for modifying motor-vehicle engine calibrations and is not
directed to children. It collects no data from anyone, including children.

## Changes

Material changes to this policy will be reflected here with a new "last updated"
date. Because the no-collection claim is enforced by a build gate, a change to
it would require a change to the app's source, which is public.

## Who publishes this app

The app is published by the **simoscal project**, an individual open-source
project. It is not a company, has no employees, and sells nothing.

No physical address appears in this policy, and none is needed for it: nothing
is collected, so there is no data controller to serve notice on and no records
request to route. What is required is a contact route that works, and that is
below.

## Contact

Questions about this policy, or about the app: <simoscal.tuning@gmail.com>

Public issue tracker (no account details required to read, a GitHub account to
post): <https://github.com/SamRyeIn/simoscal-android/issues>

Source code: <https://github.com/SamRyeIn/simoscal-android> and
<https://github.com/SamRyeIn/simoscal>
