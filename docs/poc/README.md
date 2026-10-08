# Builder POC: phone editor + Watch Face Push

A phone app edits a watch face (free colours and numbers, movable complication slots, ring complications), builds and
signs it on the phone, and sends it to the watch. A watch app installs it with Watch Face Push. Wear OS 6 only
(Galaxy Watch SM-L315F is the target). Design: `docs/superpowers/specs/2026-10-08-builder-app-design.md`.

Both apps share the application id `com.seyedjafari.analogplus`. The face they push is
`com.seyedjafari.analogplus.watchfacepush.analog`. The existing face (`com.seyedjafari.analogwatchface`) is untouched.

## Install on the real devices

```bash
./gradlew :phone:assembleDebug :watch:assembleDebug     # first build compiles the Rust library, about 1 minute
adb devices                                              # phone by USB or wireless debugging
adb -s <phone-serial> install -r phone/build/outputs/apk/debug/phone-debug.apk
adb connect <watch-ip>:<port>                            # watch: Developer options > Wireless debugging > Pair
adb -s <watch-serial> install -r watch/build/outputs/apk/debug/watch-debug.apk
```

- The watch drops off wireless debugging often. If `adb` says offline, reconnect and run the command again.
- Phone: Android 9 or newer. Watch: Wear OS 6 (API 36), because Watch Face Push does not exist before that.
- The watch app asks for two permissions on first start: push watch faces and set the pushed face as active. Allow both.
  (If it does not ask, `adb -s <watch-serial> shell pm grant com.seyedjafari.analogplus com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE`.)
- Phone and watch must be paired through the Galaxy Wearable / Wear OS app. The phone sends the face over the Wear Data Layer.

## Using it

**Phone, editor.** Tabs: Colours, Dial, Hands, Background, Complications, Rings, Templates, Share. Colours are typed as
`#AARRGGBB` (for example `#FF1E6BFF`); a wrong value shows a message and disables **Send to watch**. The preview updates
as you type. Complications: choose 4 or 6 slots, tap a slot to edit it, drag it to move it (slots cannot overlap each
other or the hand centre). At most 8 complication slots in total; each ring segment counts as one. Rings: pick a layout
(full, halves, thirds, quarters). Templates: start from a built-in design or save your own. Share: copy a link
(`analogplus://design?d=...`) or load one from a link.

**Send.** Press **Send to watch**. The phone builds and signs the face, takes the next version number and sends it.

**Watch, first time.** Open the Analog Plus app on the watch. When it shows "Face: version N" press **Activate face**.
Activation works once per install of the watch app. After that the face is only updated in place.

**Complications.** Pushed faces carry their default providers (battery on the right, steps at the bottom, Samsung weather
on top when present). To change a provider, long-press the face and tap *Customize* on the watch, or use Galaxy Wearable
on the phone. Ring segments start empty: pick a provider for each in the same editor.

## Known limits

- **Data Layer not verified on real devices.** It needs the phone and watch paired and the same application id on both.
  Only the emulator relay below has been run.
- **One activation per install.** `setWatchFaceAsActive` works once. If the face stops being active, pick Analog Plus
  in the watch face picker. The app never removes the face (`removeWatchFace` is never called).
- **Version counter.** Every send uses a higher version number, kept on the phone. The watch rejects a number that is not
  higher ("version not higher").
- **No bundled default face.** The watch is empty until the phone has pushed once.
- **Signing key.** It is generated on the first send and lives in the phone app's private storage. It is excluded from
  backup. **If you uninstall the phone app (or clear its data) the key is lost, a new key is generated, and the watch
  rejects every update because it is signed with a different key. Recovery means removing the installed face package
  from the watch by hand (not tried in the POC; `adb shell pm uninstall com.seyedjafari.analogplus.watchfacepush.analog`
  on the watch is the first thing to try; the app never removes a face itself). The Share tab has "Reset signing key"
  for a damaged key; it only helps after the old face is gone from the watch.**
  Do not uninstall the phone app during the test.
- Photo backgrounds are resized and compressed to fit Bluetooth (350 KB cap); the build fails with a message if it
  cannot. Share links do not carry the photo.
- The ring and slot complication data cannot be shown on an emulator without providers; the e2e run only checks that the
  layout is drawn.

## Checklist for the real-device test

1. Pair phone and watch, install both apps (above), allow the watch permissions.
2. Phone: open Analog Plus, leave the default design, press **Send to watch**. Wait for the success message with the version.
3. Watch: open Analog Plus, check "Face: version N", press **Activate face**. The face appears and looks like the Classic face.
4. Phone: Colours tab, set the accent to `#FF1E6BFF`, press **Send to watch** again.
5. Watch: the face turns blue in place. No activation, no second face in the picker, the status shows the new version.
6. Phone: Complications tab, drag one slot to another position, send. Check the slot moved on the watch.
7. Phone: Rings tab, choose quarters, send. Check four arcs around the dial and the slot cap indicator.
8. Long-press the face, *Customize*, and pick a provider for a ring segment; check the arc fills.
9. Write down anything that fails (message on the phone, status on the watch).

## Emulator end-to-end run

The two emulators are not paired, so the script replaces the Data Layer: the phone debug build builds the face from an
`adb` broadcast, the script copies the APK to the watch and the watch debug build applies it. The signing, packing,
validation, push, update-in-place and activation code is the real code.

```bash
scripts/poc_e2e.sh                  # a few minutes: both emulators, 3 designs, assertions, screenshots
scripts/poc_e2e.sh --with-tests     # also unit tests and device tests of every module (slow)
```

Needs the phone AVD `1_Pixel_8_API_33` and the watch AVD `GalaxyWatch7_API36` (started headless when not running, and
stopped again only if the script started them), Python 3 with Pillow (`pip3 install -r requirements.txt`), and the Rust
setup in `docs/poc/pack-native.md`. Screenshots go to `docs/screenshots/poc/`. The script fails when a face does not
apply, the centre disc is not the design's accent colour, the version does not rise, or more than one face is installed.
`POC_SETTLE_SECONDS` (default 25) is the wait before each screenshot; complications need about 20 s after the first
activation.

Device tests by hand: `./gradlew testDebugUnitTest`, then
`scripts/with_phone_emulator_lock.sh ./gradlew :facegen:connectedDebugAndroidTest :packjni:connectedDebugAndroidTest :phone:connectedDebugAndroidTest`
(phone emulator, port 5562) and `ANDROID_SERIAL=emulator-5560 ./gradlew :watch:connectedDebugAndroidTest` (watch emulator).
