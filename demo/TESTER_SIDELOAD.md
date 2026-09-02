# Sideload DriftZero on a tester phone

This is a debug APK. Send the file. Do not upload it to Play Store.

## What you need

- An Android phone on Android 10 or newer
- A 64-bit ARM phone (arm64). Almost every phone from 2018 onward qualifies
- The file `DriftZero-testers.apk`

## Send the APK

Use one of these. Send the `.apk` file itself, not a screenshot and not a renamed `.zip`.

1. Google Drive: upload `DriftZero-testers.apk`, share the file, open it on the phone, tap Download, then tap the downloaded file.
2. WhatsApp: attach `DriftZero-testers.apk` as a document. On the phone, tap the file, then tap Open or Install.
3. Email: attach the same file. On the phone, open the attachment from the mail app.

Builder copies of the same file (local, gitignored):

- `results/DriftZero-testers.apk`
- `apps/android/app/build/outputs/apk/debug/` after `assembleDebug`

## Allow install from that app

Android blocks installs that did not come from Play Store until you allow the app that opened the APK (Drive, WhatsApp, Files, Chrome, or Gmail).

1. Tap the APK.
2. If the phone says it is not allowed to install unknown apps from this source, tap Settings.
3. Turn on Allow from this source (some phones say Install unknown apps).
4. Go back and tap Install.
5. If Play Protect offers a scan, you can skip it for this test build.

You only need this switch for the app that delivered the file.

## First launch

1. Open DriftZero from the app list. First run asks for precise location, lists phone sensors, then a 5 s still mount if you start it while stopped.
2. If the phone asks for location, tap Allow. Choose precise location, while using the app. The blue own-vehicle mark needs that permission.
3. If you deny location, the map can still open. The lamp reads No location permission. The blue mark waits.

## What you should see

- A full-bleed street map (OpenFreeMap via MapLibre). Needs network for tiles until an offline package is installed
- A "Where to?" field at the top. Type a place, pick a result, follow the blue route line
- A solid blue you-are-here mark after location is allowed
- A mode lamp: GNSS, Assisted, Dead reckoning, Reacquiring, or Low confidence, with age. Tap it for the status sheet. Long-press holds GNSS when you are under 8 m/s
- Status sheet links: Trips, Offline areas, Settings, About. Long-press the collapsed line for Judge
- Record trips is off until you turn it on in Settings. Export asks first
- NavIC counts live in the sheet. They are chipset visibility, not integrity
- Speed on the collapsed sheet when moving. Distance and ETA only on a route
- No empty `--.- km/h`, NO ROUTE, or DIST/ETA slab when idle

The five-step trip test (search, route, walk, GPS-off hold, GPS back on) is in `PRODUCT.md`.

## Phone requirements

- Android 10 or newer (API 29)
- arm64-v8a. This APK does not include 32-bit or x86 libraries
- No OBD cable, no extra antenna, no vehicle hardware
- This build fetches OpenFreeMap tiles over the network. Airplane mode is for later, after an area package is installed

## If install fails

- App not installed: uninstall any older DriftZero, then install this file again
- Package appears to be invalid: the sender must send the original `.apk`, not a zip or a cloud shortcut
- Parse error or not compatible: the phone is not Android 10+, or it is not arm64

## Rebuild (builders only)

```text
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :android-app:assembleDebug
```

Copy `apps/android/app/build/outputs/apk/debug/*-debug.apk` to `results/DriftZero-testers.apk` if you want a shareable name. Package id is `in.driftzero.app`. Version name is `0.1.0`.
